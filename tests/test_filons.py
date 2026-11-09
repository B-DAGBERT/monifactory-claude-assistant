"""Headless scanner model + bridge regression tests, no Minecraft or AI process needed."""

import ast
import math
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from tests.app_source import application_tree

ROOT = Path(__file__).resolve().parents[1]


class FilonsTests(unittest.TestCase):
    def setUp(self):
        tree = application_tree()
        nodes = [
            n
            for n in tree.body
            if isinstance(n, ast.FunctionDef)
            and n.name
            in {
                "ore_material",
                "find_veins",
                "describe_veins",
                "_summary_veins",
                "vein_files_stamp",
            }
            or isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "STONE_PREFIXES" for t in n.targets)
        ]
        namespace = {"math": math}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "assistant_mc.py", "exec"), namespace)
        self.find = namespace["find_veins"]
        self.describe = namespace["describe_veins"]
        self.stamp = namespace["vein_files_stamp"]
        self.block = {
            "bloc": "minecraft:iron_ore",
            "x": 1,
            "y": 20,
            "z": 3,
            "dimension": "minecraft:overworld",
            "t": 100,
            "mine": False,
        }

    def test_scanner_export_overrides_old_looked_at_blocks(self):
        files = {
            "filons_mod.json": {
                "v": 1,
                "monde": "world-a",
                "blocs": [{**self.block, "mine": True}],
            },
            "filons.json": {"blocs": [self.block]},
        }
        veins = self.find(SimpleNamespace(read=files.get))
        self.assertEqual((veins[0]["vus"], veins[0]["mines"]), (0, 1))

    def test_empty_new_world_does_not_reuse_old_world(self):
        files = {
            "filons_mod.json": {"v": 1, "monde": "world-b", "blocs": []},
            "filons.json": {"blocs": [self.block]},
        }
        self.assertEqual(self.find(SimpleNamespace(read=files.get)), [])

    def test_scanner_anchors_preserved_and_guidance_targets_remaining_block(self):
        anchor = {"x": 0, "y": 20, "z": 0}
        blocks = [
            {**self.block, "ancre": anchor, "mine": True},
            {**self.block, "ancre": anchor, "x": 2},
            {**self.block, "ancre": {**anchor, "x": 10}, "x": 10},
        ]
        files = {"filons_mod.json": {"v": 1, "monde": "a", "blocs": blocks}}
        veins = self.find(SimpleNamespace(read=files.get))
        self.assertEqual(len(veins), 2)
        self.assertEqual((veins[0]["x"], veins[0]["vus"], veins[0]["mines"]), (2, 1, 1))

    def test_old_mod_keeps_legacy_veins(self):
        files = {"filons.json": {"blocs": [self.block]}}
        self.assertEqual(self.find(SimpleNamespace(read=files.get))[0]["vus"], 1)

    def test_invalid_export_falls_back_without_crashing(self):
        files = {"filons_mod.json": [], "filons.json": {"blocs": [self.block]}}
        self.assertEqual(len(self.find(SimpleNamespace(read=files.get))), 1)

    def test_gregtech_metadata_survives_empty_scan_and_preserves_deposits(self):
        g = {
            "id": "gt:a",
            "nom": "Iron vein",
            "definition": "gtceu:iron_vein",
            "dimension": "minecraft:overworld",
            "x": 10,
            "y": 20,
            "z": 30,
            "composition": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"],
            "epuise": False,
        }
        data = {"v": 1, "monde": "a", "source": "gregtech", "gisements": [g], "blocs": []}
        veins = self.find(
            SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None)
        )
        self.assertEqual(len(veins), 1)
        self.assertEqual(veins[0]["gisement"], "Iron vein")
        self.assertTrue(veins[0]["partiel"])
        self.assertFalse(veins[0]["epuise"])
        description = self.describe(veins, None)
        self.assertIn("quantité totale restante inconnue", description)
        self.assertNotIn("blocs encore là", description)
        self.assertEqual((veins[0]["vus"], veins[0]["x"]), (0, 10))
        data["blocs"] = [{**self.block, "filon": "gt:a"}]
        veins = self.find(
            SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None)
        )
        self.assertEqual(veins[0]["vus"], 1)
        data["gisements"] = []
        self.assertEqual(
            self.find(
                SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None)
            ),
            [],
        )

    def test_manual_exhaustion_is_visible_to_assistant_without_claiming_gregtech_reported_it(self):
        deposit = {
            "id": "gt:a",
            "nom": "Iron vein",
            "definition": "gtceu:iron_vein",
            "dimension": "minecraft:overworld",
            "x": 10,
            "y": 20,
            "z": 30,
            "composition": ["minecraft:iron_ore"],
            "epuise": False,
            "epuise_manuel": True,
        }
        data = {"v": 1, "monde": "a", "source": "gregtech", "gisements": [deposit], "blocs": []}
        bridge = SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None)
        veins = self.find(bridge)
        self.assertTrue(veins[0]["epuise"])
        self.assertTrue(veins[0]["epuise_manuel"])
        self.assertIn("marqué épuisé par le joueur", self.describe(veins, None))
        self.assertNotIn("épuisé selon GregTech", self.describe(veins, None))
        deposit["epuise_manuel"] = False
        self.assertFalse(self.find(bridge)[0]["epuise"])
        deposit["epuise"] = True
        self.assertIn("épuisé selon GregTech", self.describe(self.find(bridge), None))

    def summary(self, **vein):
        deposit = {
            "id": "gt|a",
            "nom": "Redstone Vein",
            "definition": "gtceu:redstone_vein_ow",
            "dimension": "minecraft:overworld",
            "x": 10,
            "y": -20,
            "z": 30,
            "epuise": False,
            "epuise_manuel": False,
            "composition": [
                "gtceu:redstone_ore",
                "gtceu:deepslate_redstone_ore",
                "gtceu:deepslate_ruby_ore",
            ],
            "restants": 100,
            "mines": 1400,
            "trouves": 1500,
            "scan": "complet",
            "presque_epuise": True,
            "verification": 1234,
            "minerais": {
                "gtceu:redstone_ore": [40, 600],
                "gtceu:deepslate_redstone_ore": [60, 800],
            },
            **vein,
        }
        return {
            "v": 2,
            "monde": "a" * 24,
            "source": "gregtech",
            "resume": True,
            "t": 99,
            "gisements": [deposit],
        }

    def test_compact_summary_counts_per_material_without_block_positions(self):
        data = self.summary()
        veins = self.find(SimpleNamespace(read=lambda n: data if n == "filons_mod.json" else None))
        by_material = {v["minerai"]: v for v in veins}
        self.assertEqual(set(by_material), {"redstone", "ruby"})
        redstone = by_material["redstone"]
        self.assertEqual(
            (redstone["vus"], redstone["mines"], redstone["trouves"]), (100, 1400, 1500)
        )
        self.assertEqual((by_material["ruby"]["vus"], by_material["ruby"]["mines"]), (0, 0))
        self.assertTrue(redstone["complet"] and redstone["presque_epuise"])
        self.assertFalse(redstone["partiel"])
        self.assertEqual(redstone["t"], 1234)
        description = self.describe(veins, None)
        self.assertIn("100 blocs restants sur 1500 trouvés", description)
        self.assertIn("presque épuisé", description)

    def test_planet_stone_variants_are_one_material(self):
        data = self.summary(
            composition=[
                "gtceu:moon_asbestos_ore",
                "gtceu:glacio_asbestos_ore",
                "gtceu:asbestos_ore",
            ],
            minerais={"gtceu:moon_asbestos_ore": [3, 1], "gtceu:asbestos_ore": [2, 0]},
        )
        veins = self.find(SimpleNamespace(read=lambda n: data if n == "filons_mod.json" else None))
        self.assertEqual(
            [(v["minerai"], v["vus"], v["mines"]) for v in veins], [("asbestos", 5, 1)]
        )

    def test_compact_summary_partial_scan_and_manual_exhaustion(self):
        data = self.summary(scan="partiel", presque_epuise=False, epuise_manuel=True)
        bridge = SimpleNamespace(read=lambda n: data if n == "filons_mod.json" else None)
        veins = self.find(bridge)
        self.assertTrue(all(v["partiel"] and v["epuise"] for v in veins))
        description = self.describe(veins, None)
        self.assertIn("marqué épuisé par le joueur", description)
        self.assertIn("limites du gisement non vérifiées", description)
        data["gisements"][0]["minerais"] = {"gtceu:redstone_ore": "abîmé"}
        self.assertEqual(self.find(bridge)[0]["vus"], 0)

    def test_newer_summary_version_keeps_counts_and_ignores_stale_kubejs_file(self):
        files = {
            "filons_mod.json": {**self.summary(), "v": 3},
            "filons.json": {"blocs": [self.block]},
        }
        veins = self.find(SimpleNamespace(read=files.get))
        self.assertEqual({v["minerai"] for v in veins}, {"redstone", "ruby"})
        self.assertEqual(max(v["vus"] for v in veins), 100)

    def test_vein_files_stamp_changes_only_with_files_or_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            bridge = SimpleNamespace(data_path=lambda name: folder / name, scope="A")
            empty = self.stamp(bridge)
            (folder / "filons_mod.json").write_text("{}")
            written = self.stamp(bridge)
            self.assertNotEqual(empty, written)
            self.assertEqual(written, self.stamp(bridge))
            bridge.scope = "B"
            self.assertNotEqual(written, self.stamp(bridge))
        self.assertIsNone(self.stamp(SimpleNamespace(read=lambda name: None)))

    def test_same_world_scope_after_a_loss_rereads_the_vein_summary(self):
        """A freeze or F3+T loses then restores the same scope: veins must come back unchanged."""
        from unittest import mock

        import monifactory_assistant.application.game as game
        from monifactory_assistant.game.world_scope import WorldScope

        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "filons_mod.json").write_text("{}")
            (folder / "filons.json").write_text("{}")
            reads = []
            scope = WorldScope("m1_" + "a" * 64, "1" * 32, "minecraft:overworld")
            bridge = SimpleNamespace(
                isolated=True,
                scope=scope,
                data_path=lambda name, create=False: folder / name,
                scope_valid=lambda s: True,
                write_text=lambda *args: None,
            )
            storage = SimpleNamespace(
                containers=[], raw=[], machines=[], export_key=lambda: 1, as_text=lambda: ""
            )

            class World:
                def __init__(self, *args, **kwargs):
                    self.errors = []

                def invalidate_live(self):
                    pass

                def poll(self, *args):
                    return False

            class Controller(game.GameController):
                def check_quests(self):
                    pass

                def check_new_machines(self, storage):
                    pass

                def journal(self, *args):
                    pass

                def coach(self, *args, **kwargs):
                    pass

            def find(bridge):
                reads.append(1)
                return [{"minerai": "redstone", "x": 0, "y": 0, "z": 0}]

            monitor = SimpleNamespace(refresh=lambda bridge, world: storage)
            with (
                mock.patch.object(game, "find_veins", find),
                mock.patch.object(game, "scope_folder", lambda *a, **k: folder),
                mock.patch.object(game, "World", World),
                mock.patch.object(game, "Storage", lambda containers: storage),
                mock.patch.object(game, "StorageMonitor", lambda: monitor),
            ):
                app = Controller.__new__(Controller)
                app.bridge, app.world, app.veins, app.veins_read = bridge, World(), [], 0
                app.guide, app.known_veins, app.logged_errors = None, None, set()
                app.storage, app.storage_monitor = storage, monitor
                app.storage_export_key, app.storage_text = 1, ""
                state = {"dimension": "minecraft:overworld", "x": 0, "z": 0}
                app.apply_world_scope(scope)
                app.check_game(state)
                self.assertEqual((len(app.veins), len(reads)), (1, 1))
                app.apply_world_scope(None)  # frozen identity, F3+T or a short dimension trip
                app.apply_world_scope(scope)  # the very same world, session and dimension
                app.check_game(state)
                self.assertEqual(len(app.veins), 1, "veins must be read again for the same scope")
                self.assertEqual(len(reads), 2)
                app.veins_read = 0
                app.check_game(state)
                self.assertEqual(len(reads), 2, "an unchanged file is still not parsed twice")

    def test_java_scanner_model_and_safe_destination(self):
        java_home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (java_home / "bin/javac").exists():
            java_home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = (
            str(java_home / "bin/javac")
            if (java_home / "bin/javac").exists()
            else shutil.which("javac")
        )
        java = (
            str(java_home / "bin/java")
            if (java_home / "bin/java").exists()
            else shutil.which("java")
        )
        if not javac or not java:
            self.skipTest("JDK 17 required for the Java scanner tests")
        gson = next((Path.home() / ".gradle/caches").glob("**/gson-2.10.1.jar"), None)
        if gson is None:
            self.skipTest("Gson required for the vein save format")
        with tempfile.TemporaryDirectory() as directory:
            names = [
                "TableLong",
                "VolumeFilon",
                "FilonMemoire",
                "ScanFilon",
                "VerificationFilons",
                "InstantaneFilons",
                "FormatFilons",
                "CouleursFilons",
                "DispositionFilons",
            ]
            sources = [ROOT / f"mod/src/main/java/fr/assistantia/{name}.java" for name in names]
            sources.append(ROOT / "tests/java/fr/assistantia/FilonMemoireTest.java")
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", directory]
                + list(map(str, sources)),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [
                    java,
                    "-ea",
                    "-cp",
                    os.pathsep.join([directory, str(gson)]),
                    "fr.assistantia.FilonMemoireTest",
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=60,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("21 scenarios filons valides", result.stdout)


if __name__ == "__main__":
    unittest.main()
