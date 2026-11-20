"""Both providers explain the same local proof without acquiring authority to act."""

import json
import queue
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.prompts import ACTIONS_HELP


class Fixture(ConversationController):
    def __init__(self):
        self.proof = {"n": 21, "generation": 2}
        self.production = SimpleNamespace(
            context=Mock(
                return_value=("8 plaques, 2 lots, couverture des stocks inconnue.", self.proof)
            ),
            context_valid=Mock(return_value=True),
            context_bound=Mock(return_value=True),
            delivery_current=Mock(return_value=True),
            delivery_verdict=Mock(return_value=True),
        )
        self.conversation = SimpleNamespace(ask=Mock())
        self.bridge = SimpleNamespace(scope=None, isolated=False)
        self.context = Mock(return_value="Contexte du joueur · objectif en pause")
        self.recipe_index = SimpleNamespace(hint=Mock(return_value="Recettes historiques"))
        self.events = queue.Queue()
        self.speaker = SimpleNamespace(stop=Mock(), add=Mock())
        self.history = []
        self.game = {"dimension": "minecraft:overworld"}
        self.speak = True
        self.refresh = Mock()
        self.notify = Mock()
        self.run_actions = Mock()

    def results(self, kind):
        return [event for event in self.events.queue if event[0] == kind]


class ProductionConversationTests(unittest.TestCase):
    @patch("monifactory_assistant.application.conversation.log")
    def test_v2_explanation_uses_one_turn_and_ignores_all_model_actions(self, _log):
        proposed = [{"type": "effacer_plan"}, {"type": "reglage", "cle": "voix", "valeur": False}]
        for label, payload in (("Codex", proposed), ("Claude", {"actions": proposed})):
            with (
                self.subTest(provider=label),
                patch(
                    "monifactory_assistant.application.conversation.ai_provider.label",
                    return_value=label,
                ),
            ):
                app = Fixture()
                response = (
                    "Le calcul prévoit huit plaques.<actions>" + json.dumps(payload) + "</actions>"
                )

                def ask(prompt, on_text, _on_tool, on_preview, **_options):
                    self.assertIn("8 plaques, 2 lots, couverture des stocks inconnue", prompt)
                    self.assertIn("objectif en pause", prompt)
                    self.assertNotIn(ACTIONS_HELP, prompt)
                    self.assertNotIn("Recettes historiques", prompt)
                    on_text("Le calcul prévoit huit plaques.")
                    on_preview("Résultat du calcul")
                    return response

                app.conversation.ask.side_effect = ask
                app.work_question("Explique le plan de production.", None, None)
                app.conversation.ask.assert_called_once()
                app.recipe_index.hint.assert_not_called()
                self.assertEqual(len(app.results("answer")), 1)
                self.assertTrue(all(row[-1] == app.proof for row in app.results("live_text")))
                app.on_answer(*app.results("answer")[0][1:])
                app.run_actions.assert_not_called()
                self.assertIn("huit plaques", app.history[-1]["texte"])
                self.assertNotIn("effacer_plan", app.history[-1]["texte"])

    @patch("monifactory_assistant.application.conversation.log")
    def test_a_late_v2_explanation_has_no_preview_voice_or_effect(self, _log):
        app = Fixture()

        def ask(_prompt, on_text, _on_tool, on_preview, **_options):
            # The world changed after sending: neither the binding nor the delivery proof holds.
            app.production.context_bound.return_value = False
            app.production.delivery_current.return_value = False
            app.production.delivery_verdict.return_value = False
            on_text("Ancien monde.")
            on_preview("Ancien aperçu.")
            return "Ancienne réponse."

        app.conversation.ask.side_effect = ask
        app.work_question("Explique ce diagnostic.", None, None)
        self.assertFalse(app.results("live_text"))
        app.speaker.add.assert_not_called()
        app.on_live_text("Aperçu déjà en file.", app.proof)
        app.on_answer(*app.results("answer")[0][1:])
        self.assertFalse(app.history)
        app.notify.assert_called_once()
        app.run_actions.assert_not_called()

    @patch("monifactory_assistant.application.conversation.log")
    def test_previews_check_the_binding_and_tk_reuses_the_worker_verdict(self, _log):
        app = Fixture()

        def ask(_prompt, on_text, _on_tool, on_preview, **_options):
            on_preview("Le calcul")
            on_text("Le calcul prévoit huit plaques.")
            return "Le calcul prévoit huit plaques."

        app.conversation.ask.side_effect = ask
        app.work_question("Explique le plan de production.", None, None)
        # Only the check before sending and the worker's delivery check reach the client.
        app.production.context_valid.assert_called_once_with(app.proof)
        app.production.delivery_verdict.assert_called_once_with(app.proof)
        self.assertTrue(app.production.context_bound.called)
        self.assertTrue(app.results("live_text"))
        ((_kind, question, answer, proof, verdict),) = app.results("answer")
        self.assertEqual((proof, verdict), (app.proof, True))
        for _kind, text, preview_proof in app.results("live_text"):
            app.on_live_text(text, preview_proof)
        app.on_answer(question, answer, proof, verdict)
        app.production.context_valid.assert_called_once()
        app.production.delivery_verdict.assert_called_once()
        self.assertEqual(app.history[-1]["texte"], "Le calcul prévoit huit plaques.")
        app.notify.assert_not_called()

    def test_tk_delivery_combines_the_worker_verdict_and_the_current_binding(self):
        stale = "Le calcul de production n’est plus courant. Relance-le dans Minecraft."
        unverified = "Le calcul de production n’a pas pu être revérifié. Repose ta question."
        for verdict, bound, message in (
            (True, True, None),
            (False, True, stale),
            (True, False, stale),
            (None, True, unverified),
            (None, False, stale),
        ):
            with self.subTest(verdict=verdict, bound=bound):
                app = Fixture()
                app.production.context_bound.return_value = bound
                app.production.delivery_current.return_value = bound
                app.on_answer(
                    "Explique le plan de production.", "Huit plaques.", app.proof, verdict
                )
                if message is None:
                    app.notify.assert_not_called()
                    self.assertEqual(app.history[-1]["texte"], "Huit plaques.")
                else:
                    app.notify.assert_called_once_with(message, 8)
                    self.assertFalse(app.history)
                if verdict is True:
                    app.production.delivery_current.assert_called_once_with(app.proof)
                else:
                    app.production.delivery_current.assert_not_called()
                if verdict is None:
                    app.production.context_bound.assert_called_once_with(app.proof)
                else:
                    app.production.context_bound.assert_not_called()
                app.production.context_valid.assert_not_called()
                app.production.delivery_verdict.assert_not_called()
                app.run_actions.assert_not_called()

    @patch("monifactory_assistant.application.conversation.log")
    def test_ordinary_question_keeps_existing_prompt_and_action_guard(self, _log):
        app = Fixture()
        app.production.context.return_value = None
        app.conversation.ask.return_value = "Une recette."
        app.work_question("Comment fabriquer cette pioche ?", None, None)
        prompt = app.conversation.ask.call_args.args[0]
        self.assertEqual(
            prompt,
            f"Contexte du joueur · objectif en pause\n\n{ACTIONS_HELP}\n\n"
            "Recettes historiques\n\nMa question : Comment fabriquer cette pioche ?",
        )
        app.on_answer(*app.results("answer")[0][1:])
        app.run_actions.assert_called_once()
        app.production.context_valid.assert_not_called()
        app.production.context_bound.assert_not_called()
        app.production.delivery_verdict.assert_not_called()

    @patch("monifactory_assistant.application.conversation.log")
    def test_late_ordinary_answer_is_neither_previewed_nor_spoken(self, _log):
        app = Fixture()
        app.question_scope = "session-a"
        app.bridge.isolated = True
        app.bridge.scope = app.question_scope
        app.bridge.scope_valid = Mock(return_value=True)
        app.production.context.return_value = None

        def ask(_prompt, on_text, _on_tool, on_preview, **_options):
            app.bridge.scope = "session-b"
            on_preview("Ancienne réponse")
            on_text("Ancienne réponse.")
            return "Ancienne réponse."

        app.conversation.ask.side_effect = ask
        app.work_question("Une question ordinaire", None, None)
        self.assertFalse(app.results("live_text"))
        self.assertFalse(app.results("voice_text"))
        app.on_answer(*app.results("answer")[0][1:])
        app.speaker.add.assert_not_called()
        app.run_actions.assert_not_called()
        self.assertIn("connexion précédente", app.history[-1]["texte"])

    @patch("monifactory_assistant.application.conversation.log")
    def test_voice_event_revalidates_connection_when_tk_delivers_it(self, _log):
        app = Fixture()
        app.question_scope = "session-a"
        app.bridge.isolated = True
        app.bridge.scope = app.question_scope
        app.bridge.scope_valid = Mock(return_value=True)
        app.production.context.return_value = None
        app.conversation.ask.return_value = "Réponse en file."
        app.work_question("Une question ordinaire", None, None)
        ((_kind, words, scope),) = app.results("voice_text")
        app.on_voice_text(words, scope)
        app.speaker.add.assert_called_once_with(words)
        app.speaker.add.reset_mock()
        app.bridge.scope = "session-b"
        app.on_voice_text(words, scope)
        app.speaker.add.assert_not_called()

    @patch("monifactory_assistant.application.conversation.log")
    def test_unverifiable_v2_never_falls_back_to_an_action_enabled_turn(self, _log):
        app = Fixture()
        app.production.context.side_effect = ValueError("Preuve native indisponible")
        app.work_question("Explique mon plan de production V2", None, None)
        app.conversation.ask.assert_not_called()
        app.run_actions.assert_not_called()
        self.assertEqual(app.results("error"), [("error", "Preuve native indisponible")])
