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

                def ask(prompt, on_text, _on_tool, on_preview):
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

        def ask(_prompt, on_text, _on_tool, on_preview):
            app.production.context_valid.return_value = False
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
