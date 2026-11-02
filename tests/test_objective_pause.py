"""Exercise the production objective methods without Tk, audio, Minecraft or an AI account.

Only UI/process initialization is omitted: method bodies and the inventory evaluator
are compiled directly from assistant_mc.py, including the real JSON request handler.
"""
import ast
import json
import math
import sys
import re
import tempfile
import time
import unittest
from collections import defaultdict
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[1]
METHODS = {
    "load_plan", "save_plan", "current_objective", "set_objective_paused", "auto_next_objective",
    "update_objective_progress", "progress", "have", "target_totals", "track_targets",
    "on_next_objective", "start_objective", "action_effacer_objectif", "action_effacer_plan",
    "action_pause_objectif", "action_reprendre_objectif", "handle_mod_request", "set_step",
    "coach", "flush_coach", "check_stuck", "start_briefing", "on_coach_message",
}


def load_logic(path):
    source = ast.parse((ROOT / "assistant_mc.py").read_text())
    functions = [n for n in source.body if isinstance(n, ast.FunctionDef)
                 and n.name in {"objective_entry", "evaluate", "has_tool", "build_mod_view", "item_names",
                                "step_count", "step_text", "needed_machines", "craft_suggestions", "block_name"}]
    app = next(n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "App")
    app.body = [n for n in app.body if isinstance(n, ast.FunctionDef) and n.name in METHODS]
    namespace = dict(json=json, time=time, math=math, defaultdict=defaultdict, PLAN_FILE=path,
                     USEFUL=1, COACH_LEVELS={"normal": 1}, COACH="normal", HOTKEY_PLAN="f7",
                     STUCK_MINUTES=15, TOOL_TYPES=[], sys=sys, re=re, HERE=ROOT, SETTINGS={})
    exec(compile(ast.Module(body=functions + [app], type_ignores=[]), "assistant_mc.py", "exec"), namespace)
    return namespace["App"]


class ObjectivePauseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "plan_ia.json"
        self.App = load_logic(self.path)
        self.app = self.App.__new__(self.App)
        a = self.app
        a.load_plan()
        a.plan = {"objectif": "Deux pioches", "recettes": [],
                  "cibles": [{"item": "minecraft:stone_pickaxe", "nom": "Pioche", "quantite": 2}],
                  "etapes": [{"texte": "Fabriquer deux pioches", "item": "minecraft:stone_pickaxe", "quantite": 2}]}
        a.plan_version = 42
        a.objectives = [{"titre": "Deux pioches", "demande": "Pioches", "etat": "en_cours"},
                        {"titre": "Miner", "demande": "Miner", "etat": "a_faire"}]
        a.busy = a.coach_busy = False
        a.last_progress = a.last_moved = 0
        a.plan_done_announced = False
        a.game = {"inventaire": {}}
        a.storage = SimpleNamespace(totals=lambda: {}, placed=lambda: {})
        a.last_totals = a.target_totals()
        a.obtained = dict(a.last_totals)
        a.settings = {"enchainer": True, "coach": True}
        a.root = SimpleNamespace(after=Mock())
        a.notify, a.refresh, a.journal, a.add_history = Mock(), Mock(), Mock(), Mock()
        a.coach_queue, a.coach_times = [], {}
        a.recorder, a.popup = SimpleNamespace(stream=None), None

    def test_pause_survives_restart_and_preserves_manual_progress(self):
        a = self.app
        a.done_steps, a.undone_steps = {0}, {1}
        a.obtained = {"minecraft:stone_pickaxe": 1}
        a.set_objective_paused(True)
        restored = self.App.__new__(self.App)
        restored.load_plan()
        self.assertTrue(restored.objective_paused)
        for key in ("plan", "objectives", "plan_version", "done_steps", "undone_steps", "obtained"):
            self.assertEqual(getattr(restored, key), getattr(a, key), key)

    def test_old_save_defaults_to_active(self):
        self.app.save_plan()
        saved = json.loads(self.path.read_text())
        del saved["objectif_en_pause"]
        self.path.write_text(json.dumps(saved))
        self.app.load_plan()
        self.assertFalse(self.app.objective_paused)

    def test_duplicate_and_stale_menu_requests_are_safe(self):
        a = self.app
        request = {"type": "action", "action": "pause_objectif", "version": "42"}
        a.handle_mod_request(request)
        a.handle_mod_request(request)
        self.assertTrue(a.objective_paused)
        self.assertEqual(a.objective_epoch, 1)
        a.notify.assert_called_once()
        a.handle_mod_request({**request, "action": "reprendre_objectif", "version": "41"})
        self.assertTrue(a.objective_paused)
        a.handle_mod_request({**request, "action": "reprendre_objectif"})
        a.handle_mod_request({**request, "action": "reprendre_objectif"})
        self.assertFalse(a.objective_paused)
        self.assertEqual(a.objective_epoch, 2)

    def test_paused_crafts_are_counted_without_completion_or_auto_next(self):
        a = self.app
        a.set_objective_paused(True)
        a.game["inventaire"] = {"minecraft:stone_pickaxe": 2}
        a.update_objective_progress()
        a.game["inventaire"] = {}  # crafted then consumed during pause
        a.update_objective_progress()
        self.assertEqual(a.obtained["minecraft:stone_pickaxe"], 2)
        self.assertEqual(a.done_steps, set())
        self.assertEqual(a.objectives[0]["etat"], "en_cours")
        a.journal.assert_not_called()
        a.root.after.assert_not_called()
        a.set_objective_paused(False)
        a.update_objective_progress()
        self.assertEqual(a.done_steps, {0})
        self.assertEqual(a.objectives[0]["etat"], "fait")
        a.root.after.assert_called_once()
        a.update_objective_progress()
        a.root.after.assert_called_once()

    def test_already_scheduled_auto_next_cancelled_even_after_resume(self):
        a = self.app
        a.game["inventaire"] = {"minecraft:stone_pickaxe": 2}
        a.update_objective_progress()
        callback = a.root.after.call_args.args[1]
        a.set_objective_paused(True)
        a.on_next_objective = Mock()
        callback()
        a.on_next_objective.assert_not_called()
        a.set_objective_paused(False)
        callback()
        a.on_next_objective.assert_not_called()
        a.update_objective_progress()
        a.root.after.call_args.args[1]()
        a.on_next_objective.assert_called_once_with(auto=True)

    def test_pause_blocks_new_objective_and_auto_requests(self):
        a = self.app
        a.set_objective_paused(True)
        a.notify.reset_mock()
        a.on_next_objective(auto=True)
        a.start_objective("Un autre objectif", auto=True)
        self.assertFalse(a.busy)
        a.notify.assert_not_called()
        a.start_objective("Un autre objectif")
        a.notify.assert_called_once()
        self.assertEqual(a.plan["objectif"], "Deux pioches")

    def test_pause_during_generation_is_rejected_without_changing_state(self):
        self.app.busy = True
        self.app.set_objective_paused(True)
        self.assertFalse(self.app.objective_paused)
        self.app.notify.assert_called_once()

    def test_delete_clears_pause_and_invalidates_old_timer(self):
        a = self.app
        a.set_objective_paused(True)
        epoch = a.objective_epoch
        a.action_effacer_objectif({})
        self.assertFalse(a.objective_paused)
        self.assertIsNone(a.plan)
        a.on_next_objective = Mock()
        a.auto_next_objective(epoch)
        a.on_next_objective.assert_not_called()
        self.assertFalse(json.loads(self.path.read_text())["objectif_en_pause"])

    def test_pause_keeps_safety_alerts_but_drops_progress_and_briefing(self):
        a = self.app
        a.coach_queue = [("Ancienne étape", True, "progression"),
                         ("Reprise", True, "briefing"), ("Danger", True, "coach")]
        a.set_objective_paused(True)
        self.assertEqual(a.coach_queue, [("Danger", True, "coach")])
        a.flush_coach()
        a.add_history.assert_called_once_with("claude", "Danger")
        a.on_coach_message("Réponse retardée", "progression")
        a.on_coach_message("Briefing retardé", "briefing")
        a.add_history.assert_called_once()
        a.check_stuck({}, time.time())
        a.start_briefing()
        self.assertFalse(a.coach_busy)

    def test_manual_step_validation_is_kept_during_pause(self):
        a = self.app
        a.set_objective_paused(True)
        a.set_step(1, True)
        a.update_objective_progress()
        self.assertEqual(a.done_steps, {0})
        a.root.after.assert_not_called()
        self.assertTrue(a.objective_paused)

    def test_mod_view_exposes_pause_but_preserves_detailed_steps(self):
        a = self.app
        a.game = None
        a.storage.containers = []
        a.world = SimpleNamespace(active=False, machines=lambda: [], players=[])
        a.bridge = SimpleNamespace(script_outdated=lambda _: False)
        a.visible, a.status, a.craftable = True, "", set()
        a.search, a.info, a.guide = None, None, None
        a.message, a.message_until, a.history = "", 0, []
        a.recipe_index, a.veins = None, []
        a.settings.update(machines=False, surbrillance=True, faisceaux=True, remplir=True)
        build = a.progress.__globals__["build_mod_view"]
        active = build(a)
        self.assertTrue(active["hud"]["prochains"])
        self.assertTrue(active["besoins"])
        a.set_objective_paused(True)
        paused = build(a)
        self.assertTrue(paused["ecran"]["objectif_en_pause"])
        self.assertEqual(paused["ecran"]["objectif_version"], "42")
        self.assertEqual(paused["ecran"]["plan"]["objectifs"][0]["etat"], "en_pause")
        self.assertEqual(paused["ecran"]["etapes"], active["ecran"]["etapes"])
        self.assertEqual(paused["hud"]["prochains"], [])
        self.assertEqual(paused["besoins"], [])
        self.assertEqual(paused["fabrications"], [])
        self.assertIn("En pause", paused["hud"]["objectif"])
        json.dumps(paused)  # payload remains serializable for the mod
        a.set_objective_paused(False)
        self.assertEqual(build(a)["ecran"]["plan"]["objectifs"][0]["etat"], "en_cours")

    def test_no_objective_cannot_be_paused(self):
        self.app.plan = None
        self.app.action_pause_objectif({})
        self.assertFalse(self.app.objective_paused)


if __name__ == "__main__":
    unittest.main()
