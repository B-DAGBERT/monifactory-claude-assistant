"""Questions, fiches et échanges vocaux de l’application."""

import json
import threading
import time
import traceback

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.game.context import (
    QuestionContext,
    describe_state,
    describe_storage,
    question_context,
)
from monifactory_assistant.game.objectives import item_names, machine_label, needed_machines
from monifactory_assistant.game.veins import describe_veins
from monifactory_assistant.game.world import describe_machines, describe_world
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.platform.hooks import keyboard
from monifactory_assistant.prompts import ACTIONS_HELP, INFO_REQUEST
from monifactory_assistant.providers.responses import (
    parse_info,
    readable,
    split_actions,
    visible_text,
)
from monifactory_assistant.providers.session import ask_claude, tool_status
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import (
    HOTKEY_TALK,
    INFO_FILE,
    SAMPLE_RATE,
    SENTENCE_END,
    SETTINGS,
)


class ConversationController:
    """Questions, fiches et commandes vocales de la discussion."""

    def prepare_conversation(self):
        try:
            self.conversation.prepare()
            log("Discussion prête pour le mod (aucune question envoyée).")
            self.events.put(("connection_ready",))
        except Exception as error:
            log("Discussion non disponible : " + str(error))
            self.events.put(("connection_problem", str(error)))

    def on_connection_problem(self, message):
        self.connection_problem = message
        if not self.busy:
            self.notify(message, 30)

    def on_connection_ready(self):
        self.connection_problem = ""
        self.refresh()

    def add_history(self, who, text):
        self.history = (self.history + [{"de": who, "texte": text}])[-60:]

    def on_history(self, who, text):
        self.add_history(who, text)

    def show_info(self, request):
        """Fiche d'un objet : tout de suite si Codex l'a déjà rédigée, sinon il la rédige (une fois pour toutes,
        ou de nouveau quand le joueur demande « Refaire »)."""
        item = str(request["item"])
        if request.get("refaire"):
            self.infos.pop(item, None)
        self.info_request = request
        self.info = {"item": item, "nom": str(request.get("nom") or item)}
        if item in self.infos:
            self.info.update(self.infos[item], etat="pret")
        else:
            self.info["etat"] = "en_cours"
            if item not in self.info_pending:
                self.info_pending.add(item)
                threading.Thread(
                    target=self.work_info, args=(item, self.info["nom"]), daemon=True
                ).start()
        self.refresh(force=True)

    def work_info(self, item, name):
        try:
            known = self.recipe_index.about(
                item
            )  # sa fabrication et, pour une machine, ce qu'elle fait
            prompt = INFO_REQUEST.format(nom=name, item=item) + (
                f"\n\nDéjà trouvé dans le pack :\n{known}" if known else ""
            )
            answer = ask_claude(
                prompt,
                self.bridge.dir,
                ai_provider.model("question"),
                effort=ai_provider.effort("question", "low"),
            )
            self.events.put(("info_ready", item, parse_info(answer), None))
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("info_ready", item, None, str(e)[:120]))

    def on_info_ready(self, item, card, error):
        self.info_pending.discard(item)
        if card:
            self.infos[item] = card
            atomic_json(INFO_FILE, self.infos)
        if self.info and self.info["item"] == item:
            if card:
                self.info.update(card, etat="pret")
            else:
                self.info.update(etat="erreur", erreur=error)
            self.refresh(force=True)

    def talk_key_down(self, _event):  # appelé par le hook clavier : rester très court
        if self.talk_key_held or keyboard.is_pressed("ctrl") or keyboard.is_pressed("shift"):
            return
        self.talk_key_held = True
        self.events.put(("talk_start",))

    def talk_key_up(self, _event):
        if self.talk_key_held:
            self.talk_key_held = False
            self.events.put(("talk_end",))

    def on_talk_start(self):
        self.talk_cancelled = self.busy or self.popup is not None
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return
        if self.talk_cancelled:
            return
        self.speaker.stop()
        self.shot = self.safe_screenshot()
        self.recorder.start()
        if self.recorder.stream is None:
            self.talk_cancelled = True
            self.notify(
                f"Micro indisponible : Maj+{HOTKEY_TALK.upper()} pour écrire ta question.", 8
            )
            return
        self.set_status(f"● Je t'écoute… relâche {HOTKEY_TALK.upper()} pour envoyer")

    def on_talk_end(self):
        if self.talk_cancelled:
            return
        audio = self.recorder.stop()
        self.set_status("")
        if audio is None or len(audio) < SAMPLE_RATE * 0.5:
            self.notify(f"Maintiens {HOTKEY_TALK.upper()} pendant que tu parles.", 4)
            return
        self.start_question(None, audio)

    def context(self, shot, with_storage=False, *, question=None):
        selection = (
            question_context(question, self.recipe_index)
            if with_storage and question is not None
            else QuestionContext()
        )
        parts = []
        bridge = getattr(self, "bridge", None)
        if getattr(bridge, "isolated", False) is True:
            parts.append(bridge.context_hint())
        if self.objective_paused:
            parts.append(
                "L'objectif est EN PAUSE à la demande du joueur. Ne le relance pas, ne pousse pas "
                "à progresser et ne le remplace pas sans demande explicite de reprise. "
                "Tu peux toujours répondre aux autres questions."
            )
        if self.game:
            parts.append(describe_state(self.game, items=selection.items))
        if with_storage:
            if selection.profile != "reglage":
                parts.append(describe_storage(self.storage, items=selection.items))
                prefix = "Machines ciblées. " if selection.machines is not None else ""
                parts.append(
                    prefix + describe_machines(self.storage, machine_ids=selection.machines)
                )
                parts.append(describe_veins(self.veins, self.game))
            world = describe_world(self.world, machine_ids=selection.machines)
            if world:
                parts.append(world)
            if selection.focused:
                parts.append(
                    "Contexte ciblé pour cette question ; les autres stocks et événements ne sont pas détaillés. "
                    "Si une information manque, consulte les fichiers locaux en lecture seule "
                    "(stockage.txt, recettes_*.json, machines_recettes.txt, inventaire_joueur.json). "
                    "Ne conclus pas qu'un objet absent de cet extrait n'existe pas. "
                    "Ajoute « contexte complet » dans une question pour joindre le contexte étendu à ce tour."
                )
        if self.objectives and selection.profile != "reglage":
            marks = {"fait": "✔ ", "en_cours": "▶ ", "a_faire": ""}
            parts.append(
                f"Plan (objectif principal) : {self.plan_title or 'sans titre'} ; ses objectifs dans "
                "l'ordre (✔ fait, ▶ en cours) : "
                + " ; ".join(
                    f"{i + 1}. {marks.get(o['etat'], '')}{o['titre']}"
                    for i, o in enumerate(self.objectives)
                )
            )
        if with_storage and not selection.focused:
            journal = self.describe_journal(25)
            if journal:
                parts.append(journal)
        if selection.profile == "reglage":
            parts.append(
                f"Plan conservé : {self.plan_title or 'sans titre'} ; objectif : "
                + (self.plan["objectif"] if self.plan else "aucun")
            )
        if self.plan and selection.profile != "reglage":
            progress = self.progress()
            names = item_names(self.plan)
            current = next(
                (
                    s["texte"]
                    for s, done in zip(self.plan["etapes"], progress["etapes"])
                    if not done
                ),
                None,
            )
            state = "terminé" if progress["termine"] else f"étape en cours : {current or 'aucune'}"
            missing = ", ".join(f"{n}x {names.get(i, i)}" for i, n in progress["manque"].items())
            steps = " ; ".join(
                f"{i + 1}. {'✔ ' if done else ''}{s['texte']}"
                for i, (s, done) in enumerate(zip(self.plan["etapes"], progress["etapes"]))
            )
            parts.append(
                f"Objectif en cours : {self.plan['objectif']} ({state}) ; ses étapes : {steps} ; "
                f"il me manque : {missing or 'rien'}"
            )
            machines = needed_machines(
                self.plan, progress, self.recipe_index, self.have(), self.storage.placed()
            )
            if machines:
                parts.append(
                    "Machines dont l'objectif a besoin : "
                    + " ; ".join(
                        f"{machine_label(m['a'] or m['machines'][0])} pour {', '.join(m['pour'])} : "
                        + ("je l'ai" if m["a"] else "JE NE L'AI PAS, il faut la fabriquer d'abord")
                        for m in machines
                    )
                )
        if self.places and selection.profile != "reglage":
            parts.append(
                "Lieux mémorisés : "
                + " ; ".join(
                    f"{name} ({p['x']} {p['y']} {p['z']}, {p['dimension']})"
                    for name, p in self.places.items()
                )
            )
        if self.guide:
            parts.append(f"Guidage en cours vers : {self.guide['label']}")
        removed = [
            f"{int(c['x'])} {int(c['y'])} {int(c['z'])} (il y a {int((time.time() - t) / 60)} min)"
            for t, c, broken in self.removed_chests
            if broken
        ]
        if removed:
            parts.append(
                "Coffres retirés automatiquement de la mémoire (cassés) : " + ", ".join(removed)
            )
        if shot:
            parts.append(
                f"Capture de mon écran : {shot} (lis-la avec view_image SEULEMENT si ma question porte sur ce que "
                "je vois : « ça », « ce bloc », « cet écran »… ; sinon ne l'ouvre pas, ça ralentit)"
            )
        return "\n".join(parts)

    def start_question(self, text, audio):
        self.question_scope = getattr(getattr(self, "bridge", None), "scope", None)
        self.busy = True
        self.set_status(
            "Je transcris ta question…"
            if audio is not None
            else f"{ai_provider.label()} réfléchit…"
        )
        threading.Thread(
            target=self.work_question, args=(text, audio, self.shot), daemon=True
        ).start()

    def work_question(self, text, audio, shot):
        bridge = getattr(self, "bridge", None)
        scope = getattr(self, "question_scope", None)
        scoped = getattr(bridge, "isolated", False) is True
        timing = {"début": time.time()}  # pour le journal : où passe le temps
        try:
            if audio is not None:
                text = self.transcriber.transcribe(
                    audio, sorted(set(item_names(self.plan).values())) if self.plan else ()
                )
                timing["transcrite"] = time.time()
                if not text:
                    raise RuntimeError(
                        "Je n'ai rien entendu : parle plus près du micro en tenant F8."
                    )
            self.events.put(("status", f"« {text} » : {ai_provider.label()} réfléchit…"))
            self.events.put(("history", "toi", text))
            spoken = [0]

            def on_text(partial):
                timing.setdefault("premier mot", time.time())
                partial = readable(
                    visible_text(partial)
                )  # la ligne d'actions n'est ni affichée ni lue
                self.events.put(("live_text", partial))
                if len(partial) < spoken[0]:  # nouveau message de Codex
                    spoken[0] = 0
                cut = spoken[0]
                for match in SENTENCE_END.finditer(partial, spoken[0]):
                    cut = match.end()
                if cut > spoken[0] and self.speak:
                    self.speaker.add(partial[spoken[0] : cut])
                spoken[0] = cut

            if scoped and (
                bridge.scope != scope or scope is not None and not bridge.scope_valid(scope)
            ):
                raise ValueError("Le monde a changé pendant la demande.")
            self.speaker.stop()
            recipes = self.recipe_index.hint(
                text
            )  # les recettes des objets cités : Codex n'a pas à les chercher
            prompt = (
                f"{self.context(shot, with_storage=True, question=text)}\n\n{ACTIONS_HELP}\n\n"
                + (recipes + "\n\n" if recipes else "")
                + f"Ma question : {text}"
            )
            on_tool = lambda name, data: self.events.put(("status", tool_status(name, data)))  # noqa: E731

            # Never replay a submitted turn: a timeout must not duplicate an action.
            def on_preview(partial):
                self.events.put(("live_text", readable(visible_text(partial))))

            if scoped and (
                bridge.scope != scope or scope is not None and not bridge.scope_valid(scope)
            ):
                raise ValueError("Le monde a changé pendant la préparation de la demande.")
            answer = self.conversation.ask(prompt, on_text, on_tool, on_preview)
            shown = readable(split_actions(answer)[0])
            if self.speak and spoken[0] < len(shown):
                self.speaker.add(shown[spoken[0] :])
            self.events.put(("answer", text, answer))
            timing["fin"] = time.time()
            log(
                "Temps : "
                + ", ".join(
                    f"{step} {moment - timing['début']:.1f} s"
                    for step, moment in timing.items()
                    if step != "début"
                )
            )
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("error", str(e)))

    def on_status(self, text):
        self.set_status(text)

    def on_live_text(self, text):
        bridge = getattr(self, "bridge", None)
        if getattr(bridge, "isolated", False) is True:
            scope = getattr(self, "question_scope", None)
            if bridge.scope != scope or scope is not None and not bridge.scope_valid(scope):
                return
        self.message, self.message_until = text, 0
        self.refresh()

    def on_answer(self, question, answer):
        self.busy = False
        self.connection_problem = ""
        log(f"Q : {question}\nR : {answer}")
        answer, actions = split_actions(answer)
        answer = readable(answer)
        bridge = getattr(self, "bridge", None)
        scope = getattr(self, "question_scope", None)
        scoped = getattr(bridge, "isolated", False) is True
        self.status = ""
        if scoped and (
            bridge.scope != scope or scope is not None and not bridge.scope_valid(scope)
        ):
            self.add_history("claude", "Réponse de la connexion précédente · " + answer)
            self.refresh(force=True)
            return
        self.add_history("claude", answer)
        self.message, self.message_until = answer, time.time() + 180
        self.refresh(force=True)
        if not self.game:
            self.show_overlay(f"Toi : {question}\n\n{answer}")
        if not scoped or bridge.scope_valid(scope):
            self.run_actions(actions)

    def run_actions(self, actions):
        # This entry point is exclusively model-sourced. Native menu commands keep their
        # separate handlers; no field supplied by a model constitutes user consent.
        allowed = {
            "surligner",
            "effacer_surbrillance",
            "guider",
            "arreter_guidage",
            "pause_objectif",
            "voix",
            "affichage",
            "reglage",
            "memoriser_lieu",
            "ajouter_objectif",
        }
        rejected = not isinstance(actions, list)
        prepared, new_places = [], set()
        previous_message = getattr(self, "message", "")
        for action in actions if isinstance(actions, list) else []:
            kind = action.get("type") if isinstance(action, dict) else None
            if not isinstance(kind, str) or kind not in allowed:
                rejected = True
                log(f"Action IA refusée : {action}")
                continue
            payload = dict(action)
            if kind == "memoriser_lieu":
                name = action.get("nom")
                places = getattr(self, "places", None)
                if (
                    not isinstance(name, str)
                    or not name.strip()
                    or len(name) > 100
                    or any(ord(c) < 32 or ord(c) == 127 for c in name)
                    or not isinstance(places, dict)
                    or any(not isinstance(key, str) for key in places)
                    or name.strip().casefold() in {key.strip().casefold() for key in places}
                    or name.strip().casefold() in new_places
                ):
                    rejected = True
                    continue
                payload = {"type": kind, "nom": name.strip()}
                new_places.add(name.strip().casefold())
            elif kind == "ajouter_objectif":
                title = action.get("titre") or action.get("demande")
                if not isinstance(title, str) or not title.strip():
                    rejected = True
                    continue
                # An addition may not forge an active/completed objective through `etat`.
                payload = {"type": kind, "titre": title.strip(), "etat": "a_faire"}
                if isinstance(action.get("demande"), str):
                    payload["demande"] = action["demande"]
                position = action.get("position")
                if isinstance(position, int) and not isinstance(position, bool):
                    payload["position"] = position
            elif kind in ("reglage", "voix", "affichage"):
                key = (
                    action.get("cle")
                    if kind == "reglage"
                    else "voix"
                    if kind == "voix"
                    else "panneau"
                )
                field = "valeur" if kind == "reglage" else "active" if kind == "voix" else "visible"
                value = action.get(field, True)
                if (
                    not isinstance(key, str)
                    or key not in SETTINGS
                    or not isinstance(value, bool)
                    or key == "enchainer"
                    and value
                ):
                    # Enabling auto-next could replace a completed plan indirectly.
                    rejected = True
                    continue
            handler = getattr(self, "action_" + kind, None)
            if not callable(handler):
                rejected = True
                continue
            prepared.append((handler, payload))
        # Validate the whole response before effects: an otherwise harmless addition must
        # not mutate the project when it accompanies an unauthorized destructive action.
        if not rejected:
            for handler, payload in prepared:
                try:
                    handler(payload)
                    log(f"Action : {json.dumps(payload, ensure_ascii=False)}")
                except Exception:
                    log(f"Action ratée : {payload}\n" + traceback.format_exc())
        if rejected:
            notice = (
                "Certaines actions proposées n’ont pas été exécutées. La discussion ne peut pas "
                "effacer, remplacer ou réinitialiser tes données. Les commandes du projet restent "
                "disponibles dans le menu en jeu."
            )
            self.add_history("claude", notice)
            self.message = (previous_message + "\n\n" if previous_message else "") + notice
            self.message_until = time.time() + 180
        self.refresh(force=True)
