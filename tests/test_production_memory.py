"""Read archived projects without resuming, migrating or writing them."""

import tempfile
import unittest
from pathlib import Path

from monifactory_assistant.game.world_scope import WorldScope, scope_folder
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.production.memory import ProjectNotes, ReviewedNote, read_projects


class ProjectMemoryTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.game = self.root / "kubejs" / "assistant"
        self.first = WorldScope("m1_" + "a" * 64, "a" * 32, "minecraft:overworld")
        self.other = WorldScope("m1_" + "b" * 64, "b" * 32, "minecraft:overworld")

    def write(self, scope, name, root=None):
        folder = (
            scope_folder(root or self.root, scope.world, create=True)
            if scope
            else root or self.root
        )
        path = folder / "plan_ia.json"
        atomic_json(
            path,
            {
                "plan": {
                    "objectif": name,
                    "etapes": [{"texte": "Installer la machine"}, {"texte": "Préparer le circuit"}],
                },
                "plan_principal": {
                    "titre": name,
                    "objectifs": [{"etat": "fait"}, {"etat": "a_faire"}],
                },
                "objectif_en_pause": True,
                "version": 12,
                "etapes_faites": [0, 1],
                "etapes_decochees": [1],
            },
        )
        return path

    def test_reads_a_paused_legacy_project_without_modifying_bytes_or_version(self):
        path = self.write(None, "Cuivre")
        previous = path.read_bytes(), path.stat().st_mtime_ns
        (first,) = read_projects(self.root, self.game, None)
        (second,) = read_projects(self.root, self.game, None)
        self.assertEqual(first, second)
        self.assertEqual((path.read_bytes(), path.stat().st_mtime_ns), previous)
        self.assertIsNone(first.ref.world)
        self.assertTrue(first.active)
        self.assertTrue(first.paused)
        self.assertEqual((first.revision, first.completed, first.total), (12, 1, 2))
        self.assertTrue(first.steps[0].completed)
        self.assertFalse(first.steps[1].completed)
        self.assertTrue(first.steps[1].unchecked)
        self.assertFalse(path.with_suffix(".json.avant-projets").exists())

    def test_same_project_identifier_is_separate_across_worlds_and_installations(self):
        self.write(None, "Archives")
        self.write(self.first, "Premier monde")
        self.assertEqual(read_projects(self.root, self.game, self.other), ())
        self.write(self.other, "Second monde")
        other_installation = self.root / "windows-fixture"
        other_installation.mkdir()
        self.write(self.first, "Installation Windows", other_installation)
        (first,) = read_projects(self.root, self.game, self.first)
        (second,) = read_projects(self.root, self.game, self.other)
        (third,) = read_projects(other_installation, self.game, self.first)
        self.assertEqual(first.ref.project, second.ref.project)
        self.assertEqual(first.ref.world, third.ref.world)
        self.assertEqual(len({first.ref, second.ref, third.ref}), 3)
        self.assertEqual(
            [first.objective, second.objective, third.objective],
            ["Premier monde", "Second monde", "Installation Windows"],
        )

    def test_reads_existing_backup_after_incomplete_write_without_repairing_source(self):
        path = self.write(self.first, "Cuivre")
        self.write(self.first, "Étain")
        path.write_text("{", encoding="utf-8")
        (summary,) = read_projects(self.root, self.game, self.first)
        self.assertEqual(summary.name, "Cuivre")
        self.assertEqual(summary.source, "plan_ia.json.bak")
        self.assertEqual(path.read_text(encoding="utf-8"), "{")

    def test_notes_keep_history_conflicts_and_scope(self):
        self.write(self.first, "Cuivre")
        self.write(self.other, "Autre projet")
        (first,) = read_projects(self.root, self.game, self.first)
        (other,) = read_projects(self.root, self.game, self.other)
        initial = ReviewedNote(
            "old", first.ref, "Circuit", "Réglage 1", "user_confirmed", "journal:1", 10
        )
        correction = ReviewedNote(
            "new", first.ref, "Circuit", "Réglage 3", "user_confirmed", "journal:2", 12, "old"
        )
        alternative = ReviewedNote(
            "alt", first.ref, "Circuit", "Réglage 2", "observed", "capture:1", 11
        )
        foreign = ReviewedNote(
            "new", other.ref, "Circuit", "Réglage 4", "observed", "capture:2", 20
        )
        unrelated = ReviewedNote(
            "room", first.ref, "Coffre", "Cuivre rangé au nord", "observed", "capture:3", 19
        )
        view = ProjectNotes(first.ref, [foreign, unrelated, correction, initial, alternative])
        self.assertEqual(view.search("circuit"), (correction, alternative))
        self.assertEqual(view.conflicts, ("Circuit",))
        self.assertEqual(view.history, (initial, alternative, correction, unrelated))
        self.assertEqual(view.search("", limit=1), (unrelated,))
        with self.assertRaises(ValueError):
            ProjectNotes(first.ref, [correction, foreign])
        with self.assertRaises(ValueError):
            ReviewedNote("bad", first.ref, "Circuit", "Réglage 4", "model", "reply:1", 20)
