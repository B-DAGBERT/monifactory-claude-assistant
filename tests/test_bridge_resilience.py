"""Characterize restart/reply failures without Minecraft or an AI process."""

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.game import GameController
from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.game.requests import CommandQueue
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.providers.responses import split_actions


class LegacyRequestRecoveryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.now = 1000.0
        self.action = Mock()
        logger = patch("monifactory_assistant.application.interface.log", Mock())
        logger.start()
        self.addCleanup(logger.stop)
        clock = patch("monifactory_assistant.application.interface.time.time", lambda: self.now)
        clock.start()
        self.addCleanup(clock.stop)

    def app(self):
        app = InterfaceController()
        app.bridge = SimpleNamespace(dir=self.folder, isolated=False)
        app.request_queue = CommandQueue(self.folder, clock=lambda: self.now)
        app.projects = SimpleNamespace(active="current")
        app.game = {"dimension": "minecraft:overworld"}
        app.root = SimpleNamespace(after=Mock())
        app.mod_request_n = app.mod_request_time = None
        app.project_reply_n = None
        app.question_ack = {}
        app.project_feedback = {}
        app.busy = False
        app.refresh, app.notify, app.start_question = Mock(), Mock(), Mock()
        app.on_voice = self.action
        return app

    def write(self, **changes):
        request = {"n": 1_000_000, "type": "action", "action": "voix", **changes}
        (self.folder / "demande_mod.json").write_text(json.dumps(request), encoding="utf-8")
        return request

    def test_legacy_action_is_not_replayed_after_a_quick_restart(self):
        self.write()
        self.app().check_mod_requests()
        self.now += 2
        self.app().check_mod_requests()
        self.action.assert_called_once()

    def test_question_ack_survives_restart_without_resubmitting(self):
        self.write(type="question", texte="Question de test")
        first = self.app()
        first.check_mod_requests()
        first.start_question.assert_called_once_with("Question de test", None)
        restarted = self.app()
        restarted.check_mod_requests()
        restarted.start_question.assert_not_called()
        self.assertEqual(restarted.question_ack, {"n": 1_000_000, "etat": "acceptee"})

    def test_retry_uses_one_identity_across_legacy_and_durable_channels(self):
        request = self.write(requete="same-client-request")
        self.app().check_mod_requests()
        inbox = self.folder / "demandes"
        inbox.mkdir(exist_ok=True)
        (inbox / "retry.json").write_text(json.dumps(request), encoding="utf-8")
        self.app().check_mod_requests()
        self.action.assert_called_once()
        self.assertFalse(list(inbox.glob("*.json")))

    def test_receipt_corruption_blocks_the_legacy_fallback_too(self):
        self.write()
        (self.folder / "reponses_demandes.json").write_text("{broken", encoding="utf-8")
        app = self.app()
        app.check_mod_requests()
        self.action.assert_not_called()
        self.assertIn("illisible", app.request_queue.error)

    def test_reservation_failure_never_dispatches_legacy_action(self):
        self.write()
        with patch("monifactory_assistant.game.requests.atomic_json", side_effect=OSError("full")):
            self.app().check_mod_requests()
        self.action.assert_not_called()

    def test_lost_confirmation_does_not_replay_legacy_action(self):
        self.write()
        first = self.app()
        save = first.request_queue._save
        calls = 0

        def fail_confirmation():
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("confirmation interrupted")
            save()

        with patch.object(first.request_queue, "_save", side_effect=fail_confirmation):
            first.check_mod_requests()
        self.app().check_mod_requests()
        self.action.assert_called_once()

    def test_crash_after_reservation_leaves_legacy_action_uncertain(self):
        self.write()
        first = self.app()
        first.dispatch_queued_request = Mock(side_effect=SystemExit("process stopped"))
        with self.assertRaises(SystemExit):
            first.check_mod_requests()
        restarted = self.app()
        restarted.check_mod_requests()
        self.action.assert_not_called()
        self.assertEqual(restarted.request_queue.last["etat"], "interrompue")

    def test_busy_legacy_question_is_not_submitted_when_busy_clears(self):
        self.write(type="question", texte="Question de test")
        first = self.app()
        first.busy = True
        first.check_mod_requests()
        restarted = self.app()
        restarted.check_mod_requests()
        first.start_question.assert_not_called()
        restarted.start_question.assert_not_called()
        self.assertEqual(restarted.question_ack, {"n": 1_000_000, "etat": "refusee"})

    def test_legacy_fallback_keeps_world_and_project_checks(self):
        self.write(projet="previous-project")
        app = self.app()
        app.check_mod_requests()
        self.action.assert_not_called()
        self.assertEqual(app.request_queue.last["etat"], "refusee")
        self.write(n=1_000_001, dimension_envoi="minecraft:the_nether")
        self.app().check_mod_requests()
        self.action.assert_not_called()
        self.write(n=1_000_002, session_monde="previous-session")
        isolated = self.app()
        isolated.bridge.isolated = True
        isolated.bridge.scope = object()
        isolated.bridge.scope_valid = Mock(return_value=True)
        isolated.bridge.accepts = Mock(return_value=False)
        isolated.check_mod_requests()
        self.action.assert_not_called()

    def test_expired_legacy_request_is_not_executed(self):
        self.write(n=900_000)
        self.app().check_mod_requests()
        self.action.assert_not_called()

    def test_first_project_refusal_after_scope_reset_does_not_block_later_requests(self):
        app = self.app()
        inbox = self.folder / "demandes"
        inbox.mkdir()
        old = {
            "requete": "old-project",
            "n": 1_000_000,
            "type": "projet",
            "projet": "previous-project",
            "token": "old-token",
        }
        valid = {"requete": "later", "n": 1_000_001, "type": "action", "action": "voix"}
        (inbox / "1.json").write_text(json.dumps(old), encoding="utf-8")
        (inbox / "2.json").write_text(json.dumps(valid), encoding="utf-8")
        self.assertTrue(
            app.request_queue.poll(app.dispatch_queued_request, app.restore_request_reply)
        )
        self.action.assert_called_once()
        self.assertFalse(app.project_feedback["ok"])
        self.assertEqual(app.project_feedback["token"], "old-token")
        self.assertFalse(list(inbox.glob("*.json")))


class StructuredActionsTests(unittest.TestCase):
    def test_scalar_action_json_keeps_the_answer_without_actions(self):
        for value in ("null", "true", "42", '"invalid"'):
            with self.subTest(value=value):
                self.assertEqual(
                    split_actions("Réponse utile.<actions>" + value + "</actions>"),
                    ("Réponse utile.", []),
                )

    def test_object_list_and_malformed_json_preserve_the_existing_contract(self):
        action = {"type": "voix", "active": False}
        for value in (action, [None, action, 12]):
            self.assertEqual(
                split_actions("Réponse.<actions>" + json.dumps(value) + "</actions>"),
                ("Réponse.", [action]),
            )
        with patch("monifactory_assistant.providers.responses.log"):
            self.assertEqual(split_actions("Réponse.<actions>{broken"), ("Réponse.", []))


class ForgetChestDimensionTests(unittest.TestCase):
    def setUp(self):
        self.app = GameController()
        self.overworld, self.nether = "minecraft:overworld", "minecraft:the_nether"
        self.rows = [
            {
                "cle": dimension + "|1|2|3",
                "dimension": dimension,
                "x": 1,
                "y": 2,
                "z": 3,
                "bloc": "minecraft:chest",
            }
            for dimension in (self.nether, self.overworld)
        ]
        self.app.storage = Storage(self.rows)
        self.app.game = {"dimension": self.overworld, "x": 0, "y": 2, "z": 3}
        self.app.forgotten_keys = set()
        self.app.send_command, self.app.notify = Mock(), Mock()

    def test_coordinates_use_explicit_or_current_dimension(self):
        for dimension in (None, self.overworld, self.nether):
            with self.subTest(dimension=dimension):
                self.app.send_command.reset_mock()
                self.app.action_oublier_coffre({"x": 1, "y": 2, "z": 3, "dimension": dimension})
                self.app.send_command.assert_called_once_with(
                    "oublier_coffres", cles=[(dimension or self.overworld) + "|1|2|3"]
                )

    def test_unknown_dimension_never_guesses_from_coordinates(self):
        self.app.game = None
        self.app.action_oublier_coffre({"x": 1, "y": 2, "z": 3})
        self.app.send_command.assert_not_called()
        self.app.action_oublier_coffre({"x": 1, "y": 2, "z": 3, "dimension": self.overworld})
        self.app.send_command.assert_called_once_with(
            "oublier_coffres", cles=[self.overworld + "|1|2|3"]
        )

    def test_nearby_and_double_chest_keep_the_existing_selection(self):
        other_half = {
            **self.rows[1],
            "cle": self.overworld + "|2|2|3",
            "x": 2,
            "nom": "Large Chest",
        }
        self.rows.append(other_half)
        self.app.action_oublier_coffre({"proche": True})
        self.app.send_command.assert_called_once_with(
            "oublier_coffres", cles=[self.overworld + "|1|2|3", self.overworld + "|2|2|3"]
        )


if __name__ == "__main__":
    unittest.main()
