"""A requested plan change keeps the full previous project until one commit succeeds."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.application.projects import ProjectsController


class ProjectClearTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "plan_ia.json"
        state = {
            "objectif_en_pause": True,
            "version": 41,
            "plan": {"objectif": "Passer en LV", "cibles": [], "etapes": []},
            "etapes_faites": [0],
            "obtenus": {"gtceu:steel_ingot": 4},
            "plan_principal": {
                "titre": "Métallurgie",
                "pourquoi": "Progression",
                "objectifs": [
                    {"titre": "Four", "etat": "fait"},
                    {"titre": "Passer en LV", "etat": "en_cours"},
                ],
            },
        }
        self.path.write_text(json.dumps(state), encoding="utf-8")
        self.controller = ProjectsController()
        self.controller.load_plan(self.path)
        self.controller.save_plan()

    def test_whole_plan_uses_one_write_and_retains_exact_prior_backup(self):
        before = self.path.read_bytes()
        epoch = self.controller.objective_epoch
        self.controller.action_effacer_plan({})
        saved = json.loads(self.path.read_bytes())
        self.assertIsNone(saved["plan"])
        self.assertFalse(saved["objectif_en_pause"])
        self.assertEqual(saved["plan_principal"]["objectifs"], [])
        self.assertEqual(self.controller.objective_epoch, epoch + 1)
        self.assertEqual(self.path.with_suffix(".json.bak").read_bytes(), before)

    def test_objective_only_keeps_completed_plan_entries(self):
        before = self.path.read_bytes()
        self.controller.action_effacer_objectif({})
        self.assertIsNone(self.controller.plan)
        self.assertEqual(self.controller.plan_title, "Métallurgie")
        self.assertEqual([item["etat"] for item in self.controller.objectives], ["fait", "a_faire"])
        self.assertEqual(self.path.with_suffix(".json.bak").read_bytes(), before)

    def test_write_failure_does_not_change_memory_or_original(self):
        before = self.path.read_bytes()
        state = self.controller.project_snapshot()
        epoch = self.controller.objective_epoch
        for command in (
            self.controller.action_effacer_plan,
            self.controller.action_effacer_objectif,
        ):
            with (
                self.subTest(command=command.__name__),
                patch(
                    "monifactory_assistant.game.projects.atomic_json",
                    side_effect=OSError("file locked"),
                ),
                self.assertRaises(OSError),
            ):
                command({})
            self.assertEqual(self.controller.project_snapshot(), state)
            self.assertEqual(self.controller.objective_epoch, epoch)
            self.assertEqual(self.path.read_bytes(), before)
