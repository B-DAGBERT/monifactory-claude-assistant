"""Publish one complete recipe catalog from the current on-disk revision."""

import json
import os
import queue
import shutil
import subprocess
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
        self.marker.write_text('{"version":2,"parties":1,"nombre":1}', encoding="utf-8")
        self.catalog = self.path / "recettes_0.json"
        self.catalog.write_text(
            json.dumps(
                {
                    "recettes": [
                        "minecraft:crafting | minecraft:iron_ingot => gtceu:plate | gtceu:plate"
                    ]
                }
            ),
            encoding="utf-8",
        )
        self.game = GameController()
        self.game.bridge = SimpleNamespace(dir=self.path)
        self.game.recipe_index = RecipeIndex()
        self.game.recipe_revision = None
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

    def test_retries_same_revision_after_io_failure_and_preserves_previous_index(self):
        previous = self.game.recipe_index
        self.game.check_recipes()
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
        self.assertIsNone(self.game.recipe_revision)

        self.game.check_recipes()
        self.publish()
        self.assertIn("gtceu:plate", self.game.recipe_index.by_output)
        self.game.check_recipes()
        self.assertFalse(self.workers)

    def test_one_worker_and_no_publication_if_marker_changed_before_acceptance(self):
        previous = self.game.recipe_index
        self.game.check_recipes()
        self.game.check_recipes()
        self.assertEqual(len(self.workers), 1)
        self.workers.pop(0)()
        event, *args = self.game.events.get_nowait()
        original = self.marker.stat()
        next_marker = self.marker.with_suffix(".new")
        next_marker.write_text(self.marker.read_text(encoding="utf-8"), encoding="utf-8")
        os.utime(next_marker, ns=(original.st_atime_ns, original.st_mtime_ns))
        next_marker.replace(self.marker)
        self.catalog.write_text(
            json.dumps(
                {"recettes": ["minecraft:crafting | minecraft:iron_ingot => gtceu:rod | gtceu:rod"]}
            ),
            encoding="utf-8",
        )
        self.assertEqual(event, "recipes_loaded")
        self.game.on_recipes_loaded(*args)
        self.assertIs(self.game.recipe_index, previous)

        self.game.check_recipes()
        self.publish()
        self.assertIn("gtceu:rod", self.game.recipe_index.by_output)
        self.assertNotIn("gtceu:plate", self.game.recipe_index.by_output)

    def test_new_native_machine_catalog_invalidates_the_existing_recipe_revision(self):
        self.game.check_recipes()
        self.publish()
        self.assertEqual(self.game.recipe_index.about("gtceu:lv_bender"), "")
        (self.path / "machines_recettes.txt").write_text(
            "gtceu:bender | Bender | 1 recette | gtceu:lv_bender\n", encoding="utf-8"
        )
        self.game.check_recipes()
        self.publish()
        self.assertIn(
            "« Bender » (gtceu:bender, 1 recettes)", self.game.recipe_index.about("gtceu:lv_bender")
        )

    def test_recipe_file_is_read_once_per_reload(self):
        read_text = Path.read_text
        reads = []

        def track_read(path, *args, **kwargs):
            reads.append(path)
            return read_text(path, *args, **kwargs)

        self.game.check_recipes()
        with patch.object(Path, "read_text", track_read):
            self.publish()
        self.assertEqual(reads.count(self.catalog), 1)

    def test_secondary_and_probabilistic_outputs_use_only_the_published_group(self):
        line = "gtceu:centrifuge | gtceu:ore => 2x gtceu:chance_dust (50%) + gtceu:secondary + gtceu:secondary + 100x gtceu:water | gtceu:separate"
        (self.path / "recettes_a_0.json").write_text(
            json.dumps({"generation": "published", "recettes": [line]}), encoding="utf-8"
        )
        (self.path / "recettes_a_1.json").write_text(
            json.dumps({"generation": "older", "recettes": ["x | x => gtceu:obsolete"]}),
            encoding="utf-8",
        )
        (self.path / "recettes_b_0.json").write_text(
            json.dumps({"generation": "running", "recettes": ["x | x => gtceu:unpublished"]}),
            encoding="utf-8",
        )
        self.marker.write_text(
            '{"version":3,"groupe":"a","generation":"published","parties":1,"nombre":1}',
            encoding="utf-8",
        )
        index = RecipeIndex(self.path)
        self.assertEqual(
            set(index.by_output), {"gtceu:chance_dust", "gtceu:secondary", "gtceu:water"}
        )
        self.assertEqual(index.by_output["gtceu:secondary"], [line])
        self.assertIn("2x gtceu:chance_dust (50%)", index.about("gtceu:chance_dust"))

    def test_unfinished_or_mixed_generation_leaves_previous_index_untouched(self):
        self.game.check_recipes()
        self.publish()
        previous = self.game.recipe_index
        self.marker.write_text(
            '{"version":3,"groupe":"b","generation":"new","parties":2,"nombre":2}',
            encoding="utf-8",
        )
        part = self.path / "recettes_b_0.json"
        part.write_text(
            '{"generation":"new","recettes":["x | x => gtceu:new_item"]}', encoding="utf-8"
        )
        other = self.path / "recettes_b_1.json"
        for second_part in (None, '{"generation":"old","recettes":["x | x => gtceu:old_item"]}'):
            if second_part:
                other.write_text(second_part, encoding="utf-8")
            self.game.check_recipes()
            with patch("monifactory_assistant.application.game.log"):
                self.publish()
            self.assertIs(self.game.recipe_index, previous)
            self.assertTrue(self.catalog.exists())  # toujours le catalogue lu
        other.write_text(
            '{"generation":"new","recettes":["x | x => gtceu:new_secondary"]}', encoding="utf-8"
        )
        self.game.check_recipes()
        with patch("monifactory_assistant.application.game.log") as log:
            self.publish()
        self.assertEqual(
            set(self.game.recipe_index.by_output), {"gtceu:new_item", "gtceu:new_secondary"}
        )
        log.assert_called_once_with("Ancien catalogue de recettes supprimé : recettes_0.json")
        self.assertFalse(self.catalog.exists())
        self.assertTrue(part.exists() and other.exists())

    def test_published_group_removes_only_legacy_unprefixed_parts(self):
        legacy = ["recettes_0.json", "recettes_1.json", "recettes_12.json"]
        others = [
            "recettes_a_1.json",
            "recettes_b_0.json",
            "recettes_0.json.bak",
            "recettes_1a.json",
            "recettes_x_0.json",
            "machines_recettes.txt",
        ]
        for name in legacy[1:] + others:
            (self.path / name).write_text("{}", encoding="utf-8")
        # Un dossier de ce nom : sa suppression échoue sans interrompre les autres.
        (self.path / "recettes_7.json").mkdir()
        (self.path / "recettes_a_0.json").write_text(
            json.dumps({"generation": "g", "recettes": ["x | x => gtceu:published"]}),
            encoding="utf-8",
        )
        self.marker.write_text(
            '{"version":3,"groupe":"a","generation":"g","parties":1,"nombre":1}', encoding="utf-8"
        )
        # Un index refusé (manifeste remplacé pendant la lecture) ne supprime rien.
        self.game.check_recipes()
        self.workers.pop(0)()
        _event, *args = self.game.events.get_nowait()
        stat = self.marker.stat()
        os.utime(self.marker, ns=(stat.st_atime_ns, stat.st_mtime_ns + 10**9))
        self.game.on_recipes_loaded(*args)
        self.assertTrue(all((self.path / name).exists() for name in legacy))

        self.game.check_recipes()
        with patch("monifactory_assistant.application.game.log") as log:
            self.publish()
        self.assertEqual(set(self.game.recipe_index.by_output), {"gtceu:published"})
        self.assertEqual(self.game.recipe_index.pattern, "recettes_a_*.json")
        log.assert_called_once_with(
            "Ancien catalogue de recettes supprimé : "
            "recettes_0.json, recettes_1.json, recettes_12.json"
        )
        self.assertEqual(
            {path.name for path in self.path.iterdir()},
            {*others, "recettes_a_0.json", "recettes_info.json", "recettes_7.json"},
        )

    def test_legacy_manifest_keeps_its_unprefixed_parts(self):
        group = self.path / "recettes_a_0.json"
        group.write_text(
            json.dumps({"generation": "g", "recettes": ["x | x => gtceu:other"]}), encoding="utf-8"
        )
        for manifest in (
            '{"parties":1,"nombre":1}',
            '{"version":2,"groupe":"a","parties":1,"nombre":1}',  # seule la version 3 a un groupe
        ):
            with self.subTest(manifest=manifest):
                self.marker.write_text(manifest, encoding="utf-8")
                self.game.check_recipes()
                with patch("monifactory_assistant.application.game.log") as log:
                    self.publish()
                log.assert_not_called()
                index = self.game.recipe_index
                self.assertEqual(set(index.by_output), {"gtceu:plate"})
                self.assertEqual(index.pattern, "recettes_*.json")
                self.assertEqual(index.remove_legacy_parts(self.path), [])
                self.assertTrue(self.catalog.exists())
                self.assertTrue(group.exists())
        self.assertEqual(RecipeIndex().remove_legacy_parts(self.path), [])
        self.assertTrue(self.catalog.exists())

    def test_kubejs_writes_inactive_group_before_publishing_manifest(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js required")
        source = (Path(__file__).resolve().parents[1] / "jeu/assistant_ia.js").read_text()
        functions = source[
            source.index("function lancerExportRecettes(") : source.index(
                "function verifierRecettes("
            )
        ]
        program = (
            """
        var DOSSIER='', VERSION_RECETTES=3, RECETTES_PAR_TICK=1, RECETTES_PAR_FICHIER=1;
        var exportRecettes=null, etatRecettes={}, gestionRecettes, EmiReload=null, Client={level:{}};
        var recettes={size:()=>2,get:i=>i};
        var EmiApi={getRecipeManager:()=>({getRecipes:()=>recettes})};
        function ArrayList(r){return r;}
        function premiereValeur(){return null;}
        function str(v){return String(v);}
        function decrireRecette(r){return 'recipe-'+r;}
        var files={'recettes_info.json':{groupe:'a'},'recettes_a_0.json':{recettes:['old']}};
        var JsonIO={read:key=>files[key] ? {get:k=>files[key][k]} : null,
                    write:(key,value)=>files[key]=JSON.parse(JSON.stringify(value))};
        """
            + functions
            + """
        lancerExportRecettes();
        avancerExportRecettes();
        if (files['recettes_info.json'].groupe !== 'a' || !files['recettes_b_0.json'])
            throw Error('generation published before it was complete');
        avancerExportRecettes();
        var manifest=files['recettes_info.json'];
        if (manifest.groupe !== 'b' || manifest.parties !== 2 || manifest.nombre !== 2 ||
            files['recettes_b_0.json'].generation !== manifest.generation ||
            files['recettes_b_1.json'].generation !== manifest.generation ||
            files['recettes_a_0.json'].recettes[0] !== 'old')
            throw Error('inconsistent generation');
        """
        )
        run = subprocess.run([node, "-e", program], capture_output=True, text=True, timeout=15)
        self.assertEqual(run.returncode, 0, run.stderr)
