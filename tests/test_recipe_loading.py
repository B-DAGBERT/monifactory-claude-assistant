"""Publish one complete recipe catalog from the current on-disk revision."""

import os
import queue
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.game import GameController
from monifactory_assistant.game.recipes import RecipeIndex


class RecipeLoadingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)
        self.marker = self.path / "recettes_info.json"
        self.marker.write_text('{"v":2}', encoding="utf-8")
        self.catalog = self.path / "recettes_0.json"
        self.catalog.write_text(
            "minecraft:crafting | minecraft:iron_ingot => gtceu:plate | gtceu:plate\n",
            encoding="utf-8",
        )
        self.game = GameController()
        self.game.bridge = SimpleNamespace(dir=self.path)
        self.game.craftable = {"minecraft:stick"}
        self.game.recipe_index = RecipeIndex()
        self.game.craftable_read = None
        self.game.recipe_loading = False
        self.game.logged_errors = set()
        self.game.events = queue.Queue()
        self.workers = []

        def thread(*, target, args=(), daemon):
            self.assertTrue(daemon)
            return Mock(start=lambda: self.workers.append(lambda: target(*args)))

        patcher = patch("monifactory_assistant.application.game.threading.Thread", thread)
        patcher.start()
        self.addCleanup(patcher.stop)

    def publish(self):
        self.workers.pop(0)()
        event, *args = self.game.events.get_nowait()
        self.assertEqual(event, "recipes_loaded")
        self.game.on_recipes_loaded(*args)

    def test_retries_same_revision_after_io_failure_and_preserves_previous_indexes(self):
        previous = self.game.recipe_index
        self.game.check_craftable()
        with (
            patch(
                "monifactory_assistant.application.game.RecipeIndex",
                side_effect=PermissionError("file locked"),
            ),
            patch("monifactory_assistant.application.game.log") as log,
        ):
            self.publish()
            log.assert_called_once()
        self.assertIs(self.game.recipe_index, previous)
        self.assertEqual(self.game.craftable, {"minecraft:stick"})
        self.assertIsNone(self.game.craftable_read)

        self.game.check_craftable()
        self.publish()
        self.assertIn("gtceu:plate", self.game.recipe_index.by_output)
        self.assertEqual(self.game.craftable, {"gtceu:plate"})
        self.game.check_craftable()
        self.assertFalse(self.workers)

    def test_one_worker_and_no_publication_if_marker_changed_before_acceptance(self):
        self.game.check_craftable()
        self.game.check_craftable()
        self.assertEqual(len(self.workers), 1)
        self.workers.pop(0)()
        event, *args = self.game.events.get_nowait()
        original = self.marker.stat()
        next_marker = self.marker.with_suffix(".new")
        next_marker.write_text('{"v":2}', encoding="utf-8")
        os.utime(next_marker, ns=(original.st_atime_ns, original.st_mtime_ns))
        next_marker.replace(self.marker)
        self.catalog.write_text(
            "minecraft:crafting | minecraft:iron_ingot => gtceu:rod | gtceu:rod\n",
            encoding="utf-8",
        )
        self.assertEqual(event, "recipes_loaded")
        self.game.on_recipes_loaded(*args)
        self.assertEqual(self.game.craftable, {"minecraft:stick"})

        self.game.check_craftable()
        self.publish()
        self.assertEqual(self.game.craftable, {"gtceu:rod"})
        self.assertNotIn("gtceu:plate", self.game.recipe_index.by_output)

    def test_new_native_machine_catalog_invalidates_the_existing_recipe_revision(self):
        self.game.check_craftable()
        self.publish()
        (self.path / "machines_recettes.txt").write_text(
            "gtceu:bender | Bender | 1 recette | gtceu:lv_bender\n", encoding="utf-8"
        )
        self.game.check_craftable()
        self.publish()
        self.assertEqual(self.game.recipe_index.machines["gtceu:bender"], ["gtceu:lv_bender"])
