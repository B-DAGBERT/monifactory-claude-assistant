"""Questions, fiches et échanges vocaux de l’application."""

import json
import threading
import time
import traceback

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.game.context import describe_state, describe_storage
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
from monifactory_assistant.settings import HOTKEY_TALK, INFO_FILE, SAMPLE_RATE, SENTENCE_END


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

    def context(self, shot, with_storage=False):
        parts = []
        if self.objective_paused:
            parts.append(
                "L'objectif est EN PAUSE à la demande du joueur. Ne le relance pas, ne pousse pas "
                "à progresser et ne le remplace pas sans demande explicite de reprise. "
                "Tu peux toujours répondre aux autres questions."
            )
        if self.game:
            parts.append(describe_state(self.game))
        if with_storage:
            parts.append(describe_storage(self.storage))
            parts.append(describe_machines(self.storage))
            parts.append(describe_veins(self.veins, self.game))
            world = describe_world(self.world)
            if world:
                parts.append(world)
        if self.objectives:
            marks = {"fait": "✔ ", "en_cours": "▶ ", "a_faire": ""}
            parts.append(
                f"Plan (objectif principal) : {self.plan_title or 'sans titre'} ; ses objectifs dans "
                "l'ordre (✔ fait, ▶ en cours) : "
                + " ; ".join(
                    f"{i + 1}. {marks.get(o['etat'], '')}{o['titre']}"
                    for i, o in enumerate(self.objectives)
                )
            )
        if with_storage:
            journal = self.describe_journal(25)
            if journal:
                parts.append(journal)
        if self.plan:
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
        if self.places:
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

            self.speaker.stop()
            recipes = self.recipe_index.hint(
                text
            )  # les recettes des objets cités : Codex n'a pas à les chercher
            prompt = (
                f"{self.context(shot, with_storage=True)}\n\n{ACTIONS_HELP}\n\n"
                + (recipes + "\n\n" if recipes else "")
                + f"Ma question : {text}"
            )
            on_tool = lambda name, data: self.events.put(("status", tool_status(name, data)))  # noqa: E731

            # Never replay a submitted turn: a timeout must not duplicate an action.
            def on_preview(partial):
                self.events.put(("live_text", readable(visible_text(partial))))

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
        self.message, self.message_until = text, 0
        self.refresh()

    def on_answer(self, question, answer):
        self.busy = False
        self.connection_problem = ""
        log(f"Q : {question}\nR : {answer}")
        answer, actions = split_actions(answer)
        answer = readable(answer)
        self.add_history("claude", answer)
        self.status = ""
        self.message, self.message_until = answer, time.time() + 180
        self.refresh(force=True)
        if not self.game:
            self.show_overlay(f"Toi : {question}\n\n{answer}")
        self.run_actions(actions)

    def run_actions(self, actions):
        for action in actions:
            handler = getattr(self, "action_" + str(action.get("type")), None)
            if handler is None:
                log(f"Action inconnue : {action}")
                continue
            try:
                handler(action)
                log(f"Action : {json.dumps(action, ensure_ascii=False)}")
            except Exception:
                log(f"Action ratée : {action}\n" + traceback.format_exc())
        self.refresh(force=True)
