"""Exercise the real module wiring without creating a window or making AI requests."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

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
for module in ("game.bridge", "game.storage", "game.recipes", "game.world", "game.veins", "game.context", "game.objectives", "application.projects", "application.game", "providers.responses", "providers.session", "ui.views"):
    importlib.import_module("monifactory_assistant." + module)
assert "monifactory_assistant.app" not in sys.modules
assert "monifactory_assistant.providers.codex" not in sys.modules
assert "monifactory_assistant.providers.claude" not in sys.modules
"""
        result = subprocess.run(
            [sys.executable, "-c", script], cwd=ROOT, capture_output=True, text=True, timeout=15
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_real_app_restores_paused_project_after_switch_and_restart(self):
        from monifactory_assistant.app import App
        from monifactory_assistant.game.storage import Storage

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan_ia.json"
            path.write_text(
                json.dumps(
                    {
                        "objectif_en_pause": True,
                        "plan": {"objectif": "LV", "recettes": [], "cibles": [], "etapes": []},
                        "version": 42,
                        "etapes_faites": [0],
                        "obtenus": {"minecraft:iron_ingot": 3},
                    }
                )
            )
            with patch("monifactory_assistant.application.projects.PLAN_FILE", path):
                app = App.__new__(App)
                app.load_plan()
                before = app.project_snapshot()
                app.busy, app.game, app.storage = False, {"inventaire": {}}, Storage([])
                app.notify, app.refresh, app.start_objective = Mock(), Mock(), Mock()
                app.handle_project_request(
                    {
                        "action": "creer",
                        "id": "farm",
                        "nom": "Ferme",
                        "mode": "objectif",
                        "origine": "principal",
                        "token": "new",
                    }
                )
                self.assertTrue(app.project_feedback["ok"])
                app.handle_project_request(
                    {"action": "choisir", "id": "principal", "origine": "farm", "token": "back"}
                )
                self.assertTrue(app.project_feedback["ok"])
                self.assertTrue(app.objective_paused)
                for field in ("plan", "obtenus", "etapes_faites", "objectif_en_pause"):
                    self.assertEqual(app.project_snapshot()[field], before[field], field)
                restored = App.__new__(App)
                restored.load_plan()
                self.assertEqual(restored.project_snapshot(), app.project_snapshot())
                app.start_objective.assert_called_once_with("Ferme", new_plan=False)

    def test_controller_composition_does_not_hide_any_method(self):
        from monifactory_assistant.app import App

        names = {}
        for controller in App.__bases__:
            for name, member in vars(controller).items():
                if callable(member) or isinstance(member, property):
                    self.assertNotIn(name, names, (name, names.get(name), controller.__name__))
                    names[name] = controller.__name__
        for name in ("set_objective_paused", "handle_mod_request", "work_question", "check_game"):
            self.assertTrue(callable(getattr(App, name)))
