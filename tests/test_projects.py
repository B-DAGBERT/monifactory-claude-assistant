"""Migration, independent progress and idempotent project requests, using real App methods."""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import test_objective_pause

from monifactory_assistant.game.projects import ProjectStore


class ProjectStorageTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "plan_ia.json"

    def test_legacy_backup_and_multiple_projects_survive_restart(self):
        legacy = {
            "plan": {"objectif": "Préparer le LV"},
            "objectif_en_pause": True,
            "etapes_faites": [0, 2],
            "obtenus": {"minecraft:iron_ingot": 3},
            "version": 42,
        }
        original = json.dumps(legacy).encode()
        self.path.write_bytes(original)
        store = ProjectStore(self.path)
        self.assertEqual(store.current()["nom"], "Préparer le LV")
        store.save(store.snapshot())
        self.assertEqual(self.path.with_name("plan_ia.json.avant-projets").read_bytes(), original)
        self.assertTrue(store.create("ferme", "Une ferme"))
        second = {"plan": {"objectif": "Planter"}, "etapes_faites": [1], "objectif_en_pause": False}
        store.save(second)
        restarted = ProjectStore(self.path)
        self.assertEqual(restarted.active, "ferme")
        self.assertEqual(restarted.snapshot(), second)
        restarted.select("principal")
        self.assertEqual(restarted.snapshot(), legacy)
        restarted.save(restarted.snapshot())
        self.assertEqual(self.path.with_name("plan_ia.json.avant-projets").read_bytes(), original)
        self.assertEqual(ProjectStore(self.path).active, "principal")

    def test_failed_atomic_replace_preserves_last_good_file(self):
        store = ProjectStore(self.path)
        store.save({"version": 7})
        before = self.path.read_bytes()
        with patch.object(Path, "replace", side_effect=OSError("Disque indisponible")):
            with self.assertRaises(OSError):
                store.save({"version": 8})
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(ProjectStore(self.path).snapshot(), {"version": 7})

    def test_corrupt_catalog_is_not_overwritten(self):
        for data in (
            [],
            {"projets": []},
            {"projet_actif": "absent", "projets": [{"id": "a", "nom": "A", "etat": {}}]},
        ):
            original = json.dumps(data).encode()
            self.path.write_bytes(original)
            with self.assertRaises(ValueError):
                ProjectStore(self.path)
            self.assertEqual(self.path.read_bytes(), original)


class ProjectRequestTests(unittest.TestCase):
    def setUp(self):
        test_objective_pause.ObjectivePauseTests.setUp(self)
        self.app.start_objective = Mock()
        self.app.save_plan()

    def request(
        self,
        action="creer",
        ident="ferme",
        name="Une ferme",
        token="request-1",
        origin=None,
        mode="plan",
    ):
        request = {
            "type": "projet",
            "action": action,
            "id": ident,
            "nom": name,
            "mode": mode,
            "token": token,
            "origine": self.app.projects.active if origin is None else origin,
        }
        self.app.handle_mod_request(request)
        return request

    def test_creation_and_retries_preserve_paused_project_and_generate_once(self):
        a = self.app
        a.set_objective_paused(True)
        a.done_steps, a.undone_steps = {0}, {1}
        a.obtained = {"minecraft:stone_pickaxe": 1}
        previous = copy.deepcopy(a.project_snapshot())
        request = self.request()
        self.assertEqual(a.projects.active, "ferme")
        self.assertFalse(a.objective_paused)
        self.assertIsNone(a.plan)
        self.assertEqual(a.projects.projects[0]["etat"], previous)
        a.start_objective.assert_called_once_with("Une ferme", new_plan=True)
        a.handle_mod_request(request)
        # A later duplicate, even with a different token, still cannot create another project.
        self.request(action="renommer", name="Ma ferme", token="rename")
        a.handle_mod_request({**request, "token": "other-token"})
        self.assertEqual(len(a.projects.projects), 2)
        a.start_objective.assert_called_once()
        a.projects.select("principal")
        self.assertEqual(a.projects.snapshot(), previous)

    def test_switch_restores_independent_steps_and_pause_without_ai(self):
        a = self.app
        a.set_objective_paused(True)
        a.done_steps, a.undone_steps = {0}, {1}
        a.obtained = {"minecraft:stone_pickaxe": 1}
        previous = copy.deepcopy(a.project_snapshot())
        self.request()
        a.plan = {"objectif": "Planter", "etapes": ["Planter"], "cibles": []}
        a.done_steps = {2}
        a.save_plan()
        a.start_objective.reset_mock()
        self.request("choisir", "principal", token="choose-original")
        self.assertTrue(a.project_feedback["ok"])
        for key in (
            "plan",
            "etapes_faites",
            "etapes_decochees",
            "obtenus",
            "plan_principal",
            "objectif_en_pause",
        ):
            self.assertEqual(a.project_snapshot()[key], previous[key], key)
        self.assertTrue(a.objective_paused)
        a.start_objective.assert_not_called()
        self.request("choisir", "ferme", token="choose-farm")
        self.assertEqual(a.plan["objectif"], "Planter")
        self.assertEqual(a.done_steps, {2})
        self.assertFalse(a.objective_paused)
        restored = self.App.__new__(self.App)
        restored.load_plan()
        self.assertEqual(restored.projects.active, "ferme")
        self.assertEqual(restored.project_snapshot(), a.project_snapshot())
        self.assertTrue(restored.projects.projects[0]["etat"]["objectif_en_pause"])

    def test_objective_only_creation(self):
        self.request(mode="objectif")
        self.app.start_objective.assert_called_once_with("Une ferme", new_plan=False)

    def test_stale_busy_invalid_and_disconnected_requests_do_not_change_project(self):
        a = self.app
        before = copy.deepcopy(a.project_snapshot())
        for condition in ("busy", "disconnected", "stale", "unknown-id"):
            a.busy = condition == "busy"
            a.game = None if condition == "disconnected" else {"inventaire": {}}
            self.request(
                action="choisir" if condition == "unknown-id" else "creer",
                token=condition,
                origin="wrong-project" if condition == "stale" else "principal",
            )
            self.assertFalse(a.project_feedback["ok"], condition)
            self.assertEqual(a.projects.active, "principal")
            self.assertEqual(a.project_snapshot(), before)
        a.start_objective.assert_not_called()
        self.assertEqual(len(a.projects.projects), 1)

    def test_late_objective_actions_do_not_change_the_new_project(self):
        self.request()
        self.app.plan = {"objectif": "Ferme", "etapes": ["Planter"]}
        self.app.on_next_objective = Mock()
        for action in ("objectif_suivant", "pause_objectif", "etape"):
            self.app.handle_mod_request(
                {"type": "action", "action": action, "projet": "principal", "index": 0}
            )
        self.assertFalse(self.app.objective_paused)
        self.assertEqual(self.app.done_steps, set())
        self.app.on_next_objective.assert_not_called()

    def test_scheduled_actions_cancelled_by_project_change(self):
        callback = Mock()
        self.app.schedule_project_action(callback)
        scheduled = self.app.root.after.call_args.args[1]
        self.request()
        scheduled()
        callback.assert_not_called()
        self.app.schedule_project_action(callback)
        self.app.root.after.call_args.args[1]()
        callback.assert_called_once()

    def test_session_auto_start_cannot_apply_to_another_project(self):
        a = self.app
        a.plan = None
        a.session_open = False
        a.journal_tail = Mock(return_value=[])
        a.on_game_connected()
        scheduled = a.root.after.call_args.args[1]
        self.request()
        a.on_next_objective = Mock()
        scheduled()
        a.on_next_objective.assert_not_called()

    def test_failure_after_selection_rolls_back_memory_and_disk(self):
        a = self.app
        a.set_objective_paused(True)
        before = copy.deepcopy(a.project_snapshot())
        original = a.save_plan
        calls = 0

        def save():
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("Disque indisponible")
            original()

        a.save_plan = save
        self.request()
        self.assertFalse(a.project_feedback["ok"])
        self.assertEqual(a.projects.active, "principal")
        self.assertEqual(a.project_snapshot(), before)
        self.assertEqual(len(a.projects.projects), 1)
        self.assertEqual(ProjectStore(self.path).snapshot(), before)
        a.start_objective.assert_not_called()

    def test_rename_only_changes_name_and_keeps_pause_and_plan(self):
        a = self.app
        a.set_objective_paused(True)
        before = copy.deepcopy(a.project_snapshot())
        self.request("renommer", "principal", name="Mon LV")
        self.assertEqual(a.projects.current()["nom"], "Mon LV")
        self.assertEqual(a.project_snapshot(), before)
        self.assertEqual(ProjectStore(self.path).current()["nom"], "Mon LV")
        a.start_objective.assert_not_called()
