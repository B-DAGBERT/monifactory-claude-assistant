"""Exercise the real module wiring without creating a window or making AI requests."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[1]


class ApplicationModulesTests(unittest.TestCase):
    def test_game_modules_work_without_desktop_or_audio_dependencies(self):
        script = """
import builtins, importlib, sys
original = builtins.__import__
blocked = {"tkinter", "mss", "PIL", "keyboard", "sounddevice", "numpy", "torch", "faster_whisper", "edge_tts"}
def guarded(name, *args, **kwargs):
    if name.split(".")[0] in blocked:
        raise AssertionError("Unexpected desktop/audio import: " + name)
    return original(name, *args, **kwargs)
builtins.__import__ = guarded
for module in ("game.bridge", "game.storage", "game.recipes", "game.world", "game.veins", "game.context", "application.game", "providers.responses", "providers.session", "ui.views"):
    importlib.import_module("monifactory_assistant." + module)
assert "monifactory_assistant.app" not in sys.modules
assert "monifactory_assistant.providers.codex" not in sys.modules
assert "monifactory_assistant.providers.claude" not in sys.modules
"""
        result = subprocess.run(
            [sys.executable, "-c", script], cwd=ROOT, capture_output=True, text=True, timeout=15
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_real_app_has_no_objective_api_and_never_prepares_one_on_connection(self):
        from monifactory_assistant.app import App

        for name in (
            "load_plan",
            "save_plan",
            "handle_project_request",
            "set_objective_paused",
            "auto_next_objective",
            "on_next_objective",
            "start_objective",
            "on_plan",
        ):
            self.assertFalse(hasattr(App, name), name)
        with tempfile.TemporaryDirectory() as directory:
            old = Path(directory) / "plan_ia.json"
            old.write_text(json.dumps({"plan": {"objectif": "LV"}, "objectif_en_pause": True}))
            before = old.read_bytes()
            app = App.__new__(App)
            app.session_open, app.journal_file = False, Path(directory) / "journal.jsonl"
            app.root = Mock()
            app.on_game_connected()
            # Connecting no longer schedules an AI request (it spent the subscription).
            app.root.after.assert_not_called()
            self.assertTrue(app.session_open)
            self.assertEqual(old.read_bytes(), before)

    def test_old_mod_project_and_objective_requests_are_refused_without_effect(self):
        from monifactory_assistant.app import App

        app = App.__new__(App)
        app.bridge = Mock(isolated=False)
        app.game = {"dimension": "minecraft:overworld"}
        app.busy, app.question_ack, app.mod_request_n = False, {}, None
        app.notify, app.refresh, app.set_setting, app.start_question = (
            Mock(),
            Mock(),
            Mock(),
            Mock(),
        )
        app.on_hide, app.on_voice, app.show_info = Mock(), Mock(), Mock()
        for request in (
            {"type": "projet", "action": "supprimer", "id": "principal", "n": 1},
            {"type": "action", "action": "objectif_suivant", "projet": "principal", "n": 2},
            {"type": "action", "action": "pause_objectif", "version": "42", "n": 3},
            {"type": "action", "action": "refaire_objectif", "n": 4},
            {"type": "action", "action": "etape", "index": 0, "n": 5},
        ):
            with self.subTest(request=request):
                # Explicit refusal: the old button must not look « pris en compte ».
                result = App.dispatch_queued_request(app, dict(request))
                self.assertEqual(result["etat"], "refusee")
                self.assertIn("Fonction retirée", result["message"])
                self.assertIn("relance Minecraft", result["message"])
                App.handle_mod_request(app, dict(request))
        for request in (
            {"type": "inconnu", "n": 7},
            {"type": "action", "action": "teleporter", "n": 8},
            {"n": 9},
        ):
            with self.subTest(request=request):
                result = App.dispatch_queued_request(app, dict(request))
                self.assertEqual(result["etat"], "refusee")
                self.assertIn("Demande inconnue", result["message"])
        for mock in (app.notify, app.set_setting, app.start_question, app.on_hide, app.on_voice):
            mock.assert_not_called()
        # Supported requests keep their normal receipt.
        result = App.dispatch_queued_request(app, {"type": "action", "action": "voix", "n": 10})
        self.assertEqual(result["etat"], "traitee")
        app.on_voice.assert_called_once()
        # The question protocol of the same requests keeps working.
        App.handle_mod_request(app, {"type": "question", "texte": "Recette ?", "n": 6})
        app.start_question.assert_called_once_with("Recette ?", None)

    def test_controller_composition_does_not_hide_any_method(self):
        from monifactory_assistant.app import App

        names = {}
        for controller in App.__bases__:
            for name, member in vars(controller).items():
                if callable(member) or isinstance(member, property):
                    self.assertNotIn(name, names, (name, names.get(name), controller.__name__))
                    names[name] = controller.__name__
        for name in ("have", "handle_mod_request", "work_question", "check_game"):
            self.assertTrue(callable(getattr(App, name)))
