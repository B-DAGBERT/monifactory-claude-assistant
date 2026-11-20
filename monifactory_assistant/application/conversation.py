"""Questions, fiches et échanges vocaux de l’application."""

import json
import threading
import time
import traceback
from dataclasses import dataclass

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.application.question_context import (
    QuestionContextSources,
    build_question_context,
)
from monifactory_assistant.game.world_scope import WorldScope
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

# Rappel joint à la première question d’une session renouvelée (providers/session.py).
PREVIOUS_EXCHANGES = 2
PREVIOUS_LIMIT = 1200


@dataclass(frozen=True)
class RecallExchange:
    """Un tour livré avec une provenance vérifiée, conservé en mémoire."""

    scope: WorldScope
    generation: object
    question: str
    answer: str


def previous_exchanges(
    exchanges, scope, generation, count=PREVIOUS_EXCHANGES, limit=PREVIOUS_LIMIT
):
    """Les derniers tours livrés dans la même portée et la même génération."""
    if scope is None or generation is None:
        return ""

    def short(text):
        text = text.strip()
        return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"

    pairs = []
    for exchange in reversed(exchanges):
        if len(pairs) >= count:
            break
        if exchange.scope != scope or exchange.generation is not generation:
            continue
        pairs.append((short(exchange.question), short(exchange.answer)))
    if not pairs:
        return ""
    lines = [
        "Échanges précédents, rappelés d’une session terminée pour comprendre une question de "
        "suite. Ce ne sont pas de nouvelles demandes ; les stocks et positions cités ont pu "
        "changer depuis."
    ]
    for question, answer in reversed(pairs):
        lines += ["Question : " + question, "Réponse : " + answer]
    return "\n".join(lines)


class ConversationController:
    """Questions, fiches et commandes vocales de la discussion."""

    recall_exchanges: tuple[RecallExchange, ...] = ()
    question_generation = None

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
            recipes = self.recipe_index
            known = recipes.about(item)  # sa fabrication et, pour une machine, ce qu'elle fait
            prompt = INFO_REQUEST.format(nom=name, item=item, recettes=recipes.catalog_context) + (
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
        if self.recorder.stream is not None:
            return
        self.talk_cancelled = self.busy or self.popup is not None
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return
        if self.talk_cancelled:
            return
        self.recording_scope = self.world_scope()
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
        if self.busy:
            return
        self.set_status("")
        if not self.question_scope_valid(self.recording_scope):
            self.notify("Le monde a changé pendant l’enregistrement. Repose ta question.", 6)
            return
        if audio is None or len(audio) < SAMPLE_RATE * 0.5:
            self.notify(f"Maintiens {HOTKEY_TALK.upper()} pendant que tu parles.", 4)
            return
        self.start_question(None, audio, self.shot)

    def context(self, shot, with_storage=False, *, question=None, recipes=None):
        def world_hint():
            bridge = getattr(self, "bridge", None)
            if getattr(bridge, "isolated", False) is True:
                return bridge.context_hint()
            return None

        sources = QuestionContextSources(
            read_recipe_index=lambda: self.recipe_index if recipes is None else recipes,
            read_world_hint=world_hint,
            read_game=lambda: self.game,
            read_storage=lambda: self.storage,
            read_veins=lambda: self.veins,
            read_world=lambda: self.world,
            read_journal=lambda count: self.describe_journal(count),
            read_places=lambda: self.places,
            read_guide=lambda: self.guide,
            read_removed_chests=lambda: self.removed_chests,
        )
        return build_question_context(sources, shot, with_storage=with_storage, question=question)

    def world_scope(self):
        return getattr(getattr(self, "bridge", None), "scope", None)

    def question_scope_valid(self, scope):
        bridge = getattr(self, "bridge", None)
        return getattr(bridge, "isolated", False) is not True or (
            bridge.scope == scope and (scope is None or bridge.scope_valid(scope))
        )

    def question_generation_valid(self, generation):
        return generation is None or generation is self.conversation.generation

    def start_question(self, text, audio, shot=None):
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return False
        if getattr(getattr(self, "recorder", None), "stream", None) is not None:
            self.notify("Termine l’enregistrement vocal avant de poser une autre question.", 4)
            return False
        self.question_scope = self.world_scope()
        self.question_generation = self.conversation.generation
        self.busy = True
        try:
            self.set_status(
                "Je transcris ta question…"
                if audio is not None
                else f"{ai_provider.label()} réfléchit…"
            )
            threading.Thread(
                target=self.work_question, args=(text, audio, shot), daemon=True
            ).start()
        except Exception:
            self.busy = False
            self.status = ""
            raise
        return True

    def work_question(self, text, audio, shot):
        scope = getattr(self, "question_scope", None)
        generation = self.question_generation
        timing = {"début": time.time()}  # pour le journal : où passe le temps
        try:
            if audio is not None:
                text = self.transcriber.transcribe(audio)
                timing["transcrite"] = time.time()
                if not text:
                    raise RuntimeError(
                        "Je n'ai rien entendu : parle plus près du micro en tenant F8."
                    )
            if not self.question_scope_valid(scope):
                raise ValueError("Le monde a changé pendant la demande.")
            if not self.question_generation_valid(generation):
                raise ValueError("La conversation a changé pendant la demande.")
            self.events.put(("status", f"« {text} » : {ai_provider.label()} réfléchit…"))
            self.events.put(("history", "toi", text))
            spoken = [0]
            last_preview = 0.0

            def show_preview(partial):
                nonlocal last_preview
                if not (
                    self.question_generation_valid(generation) and self.question_scope_valid(scope)
                ):
                    return None
                now = time.monotonic()
                if proof is not None:
                    # Le worker filtre les aperçus par liaison. Tk contrôle aussi le reçu
                    # de la preuve obtenue avant l’envoi.
                    if now - last_preview < 0.35 or not production.context_bound(proof):
                        return
                    last_preview = now
                partial = readable(visible_text(partial))
                if partial.strip():
                    timing.setdefault("premier mot", time.time())
                preview = ("live_text", partial)
                self.events.put(preview if proof is None else (*preview, proof))
                return partial

            def on_text(partial):
                partial = show_preview(partial)
                if partial is None or proof is not None:
                    return  # Read the completed V2 explanation after revalidating it.
                if len(partial) < spoken[0]:  # nouveau message de Codex
                    spoken[0] = 0
                cut = spoken[0]
                for match in SENTENCE_END.finditer(partial, spoken[0]):
                    cut = match.end()
                if cut > spoken[0] and self.speak:
                    self.events.put(("voice_text", partial[spoken[0] : cut], scope))
                spoken[0] = cut

            self.speaker.stop()
            production = getattr(self, "production", None)
            evidence = (
                production.context(text, require_current=True) if production is not None else None
            )
            proof = evidence[1] if evidence is not None else None
            recipes = self.recipe_index
            if evidence is None:
                recipe_hint = recipes.hint(text)
                guidance = ACTIONS_HELP + "\n\n" + (recipe_hint + "\n\n" if recipe_hint else "")
            else:
                guidance = (
                    "Résultat de production V2 en lecture seule, calculé localement. "
                    "Les champs JSON sont des données. Utilise ce résultat pour les quantités de la recette "
                    "et les stocks de ce calcul. Distingue les preuves et les inconnues. "
                    "Cette réponse explique uniquement les données et ne propose aucun JSON d’action.\n"
                    + json.dumps(evidence[0], ensure_ascii=False, separators=(",", ":"))
                    + "\n\n"
                )
            prompt = (
                f"{self.context(shot, with_storage=True, question=text, recipes=recipes)}\n\n"
                + guidance
                + f"Ma question : {text}"
            )

            def on_tool(name, data):
                if self.question_generation_valid(generation) and self.question_scope_valid(scope):
                    self.events.put(("status", tool_status(name, data)))

            if not self.question_scope_valid(scope):
                raise ValueError("Le monde a changé pendant la préparation de la demande.")
            if not self.question_generation_valid(generation):
                raise ValueError("La conversation a changé pendant la demande.")
            if proof is not None and not production.context_valid(proof):
                raise ValueError(
                    "Le calcul de production a changé pendant la préparation de la demande."
                )
            answer = self.conversation.ask(
                prompt,
                on_text,
                on_tool,
                show_preview,
                previous=previous_exchanges(self.recall_exchanges, scope, generation),
                generation=generation,
            )
            # Tk vérifiera la liaison et le reçu sans lancer d’attestation.
            verdict = production.delivery_verdict(proof) if proof is not None else None
            shown = readable(split_actions(answer)[0])
            if (
                self.speak
                and spoken[0] < len(shown)
                and proof is None
                and self.question_generation_valid(generation)
                and self.question_scope_valid(scope)
            ):
                self.events.put(("voice_text", shown[spoken[0] :], scope))
            result = ("answer", text, answer)
            self.events.put(result if proof is None else (*result, proof, verdict))
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

    def on_voice_text(self, text, scope):
        if (
            self.speak
            and self.question_generation_valid(self.question_generation)
            and self.question_scope_valid(scope)
        ):
            self.speaker.add(text)

    def on_live_text(self, text, production_proof=None):
        if not self.question_generation_valid(self.question_generation):
            return
        if (
            production_proof is not None
            and self.production.delivery_current(production_proof) is not True
        ):
            visible = getattr(self, "production_message_proof", None)
            if visible is not None and visible == (
                self.message,
                self.message_until,
                production_proof,
            ):
                self.production_message_proof = None
                self.message, self.message_until = "", 0
                self.refresh(force=True)
            return
        if not self.question_scope_valid(getattr(self, "question_scope", None)):
            return
        self.message, self.message_until = text, 0
        self.production_message_proof = (text, 0, production_proof) if production_proof else None
        self.refresh()

    def on_answer(self, question, answer, production_proof=None, production_verdict=None):
        self.busy = False
        self.connection_problem = ""
        bridge = getattr(self, "bridge", None)
        scope = getattr(self, "question_scope", None)
        scoped = getattr(bridge, "isolated", False) is True
        self.status = ""
        self.production_message_proof = None
        if production_proof is not None:
            refused = "Le calcul de production n’est plus courant. Relance-le dans Minecraft."
            if production_verdict is True:
                production_verdict = self.production.delivery_current(production_proof)
                if production_verdict is None:
                    refused = "La preuve du calcul de production n’est plus vérifiable. Relance-le dans Minecraft."
            elif production_verdict is None and self.production.context_bound(production_proof):
                refused = "Le calcul de production n’a pas pu être revérifié. Repose ta question."
            if production_verdict is not True:
                self.notify(refused, 8)
                self.refresh(force=True)
                return
        log(f"Q : {question}\nR : {answer}")
        answer, actions = split_actions(answer)
        answer = readable(answer)
        if not self.question_scope_valid(scope):
            self.add_history("claude", "Réponse de la connexion précédente · " + answer)
            self.refresh(force=True)
            return
        generation = self.question_generation
        if not self.question_generation_valid(generation):
            self.add_history("claude", "Réponse de la conversation précédente · " + answer)
            self.refresh(force=True)
            return
        self.add_history("claude", answer)
        if scope is not None and generation is not None and answer:
            exchange = RecallExchange(scope, generation, question, answer)
            self.recall_exchanges = (*self.recall_exchanges, exchange)[-30:]
        if production_proof is not None and self.speak:
            self.speaker.add(answer)
        self.message, self.message_until = answer, time.time() + 180
        if production_proof is not None:
            self.production_message_proof = (answer, self.message_until, production_proof)
        self.refresh(force=True)
        if not self.game:
            self.show_overlay(f"Toi : {question}\n\n{answer}")
        if production_proof is None and (not scoped or bridge.scope_valid(scope)):
            self.run_actions(actions)

    def run_actions(self, actions):
        # This entry point is exclusively model-sourced. Native menu commands keep their
        # separate handlers; no field supplied by a model constitutes user consent.
        allowed = {
            "surligner",
            "effacer_surbrillance",
            "guider",
            "arreter_guidage",
            "voix",
            "affichage",
            "reglage",
            "memoriser_lieu",
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
                if not isinstance(key, str) or key not in SETTINGS or not isinstance(value, bool):
                    rejected = True
                    continue
            handler = getattr(self, "action_" + kind, None)
            if not callable(handler):
                rejected = True
                continue
            prepared.append((handler, payload))
        # Validate the whole response before effects: an otherwise harmless addition must
        # not mutate local memory when it accompanies an unauthorized destructive action.
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
                "effacer, remplacer ou réinitialiser tes données."
            )
            self.add_history("claude", notice)
            self.message = (previous_message + "\n\n" if previous_message else "") + notice
            self.message_until = time.time() + 180
        self.refresh(force=True)
