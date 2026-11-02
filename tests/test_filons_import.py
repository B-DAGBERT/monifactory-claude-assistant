import json
import os
import tempfile
import time
import unittest
from pathlib import Path

from filons_import import prepare_import


class ImportFilonsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.instance = Path(self.tmp.name)
        self.folder = self.instance / "kubejs/assistant"
        self.folder.mkdir(parents=True)
        self.world = "a" * 24
        self.active = self.folder / "filons_mod.json"
        self.active.write_text(json.dumps({"v": 1, "monde": self.world, "blocs": []}))
        self.source = self.folder / "filons.json"
        self.block = {"dimension": "minecraft:overworld", "bloc": "minecraft:iron_ore",
                      "x": 1, "y": 20, "z": 3, "t": 100, "mine": False}
        self.source.write_text(json.dumps({"blocs": [self.block]}))

    def test_import_bound_to_current_world_preserves_sources(self):
        old, active = self.source.read_bytes(), self.active.read_bytes()
        result = prepare_import(self.instance)
        queued = json.loads(Path(result["fichier"]).read_text())
        self.assertEqual(queued["monde"], self.world)
        self.assertEqual(queued["blocs"], [self.block])
        self.assertEqual(result["restants_historiques"], 1)
        self.assertEqual(self.source.read_bytes(), old)
        self.assertEqual(self.active.read_bytes(), active)

    def test_repeated_import_merges_without_duplicates_or_losing_pending_data(self):
        first = prepare_import(self.instance)
        self.source.write_text(json.dumps({"blocs": [{**self.block, "x": 2}, {**self.block, "t": 200, "mine": True}]}))
        result = prepare_import(self.instance)
        self.assertEqual(result["blocs"], 2)
        self.assertEqual(result["restants_historiques"], 1)
        self.assertEqual(prepare_import(self.instance), result)
        self.assertEqual(first["fichier"], result["fichier"])

    def test_world_switch_creates_separate_request(self):
        first = prepare_import(self.instance)
        self.active.write_text(json.dumps({"v": 1, "monde": "b" * 24}))
        second = prepare_import(self.instance)
        self.assertNotEqual(first["fichier"], second["fichier"])
        self.assertTrue(Path(first["fichier"]).exists())

    def test_stale_world_is_rejected(self):
        os.utime(self.active, (time.time() - 500, time.time() - 500))
        with self.assertRaises(RuntimeError):
            prepare_import(self.instance)
        self.assertFalse((self.folder / "filons/imports").exists())

    def test_invalid_coordinates_do_not_replace_pending_request(self):
        result = prepare_import(self.instance)
        path = Path(result["fichier"])
        before = path.read_bytes()
        self.source.write_text(json.dumps({"blocs": [{**self.block, "x": float("nan")}] }))
        with self.assertRaises(ValueError):
            prepare_import(self.instance)
        self.assertEqual(path.read_bytes(), before)

    def test_legacy_blocks_cannot_be_imported_as_gregtech_deposits(self):
        self.active.write_text(json.dumps({"v": 1, "monde": self.world, "source": "gregtech", "gisements": []}))
        with self.assertRaisesRegex(RuntimeError, "GregTech"):
            prepare_import(self.instance)
        self.assertFalse((self.folder / "filons/imports").exists())

    def test_dry_run_does_not_write(self):
        result = prepare_import(self.instance, dry_run=True)
        self.assertEqual(result["blocs"], 1)
        self.assertFalse(Path(result["fichier"]).exists())
