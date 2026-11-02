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

ROOT = Path(__file__).resolve().parents[1]


class FilonsTests(unittest.TestCase):
    def setUp(self):
        tree = ast.parse((ROOT / "assistant_mc.py").read_text())
        nodes = [n for n in tree.body if
                 isinstance(n, ast.FunctionDef) and n.name in {"ore_material", "find_veins", "describe_veins"}
                 or isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "STONE_PREFIXES" for t in n.targets)]
        namespace = {"math": math}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "assistant_mc.py", "exec"), namespace)
        self.find = namespace["find_veins"]
        self.describe = namespace["describe_veins"]
        self.block = {"bloc": "minecraft:iron_ore", "x": 1, "y": 20, "z": 3,
                      "dimension": "minecraft:overworld", "t": 100, "mine": False}

    def test_scanner_export_overrides_old_looked_at_blocks(self):
        files = {"filons_mod.json": {"v": 1, "monde": "world-a", "blocs": [{**self.block, "mine": True}]},
                 "filons.json": {"blocs": [self.block]}}
        veins = self.find(SimpleNamespace(read=files.get))
        self.assertEqual((veins[0]["vus"], veins[0]["mines"]), (0, 1))

    def test_empty_new_world_does_not_reuse_old_world(self):
        files = {"filons_mod.json": {"v": 1, "monde": "world-b", "blocs": []},
                 "filons.json": {"blocs": [self.block]}}
        self.assertEqual(self.find(SimpleNamespace(read=files.get)), [])

    def test_scanner_anchors_preserved_and_guidance_targets_remaining_block(self):
        anchor = {"x": 0, "y": 20, "z": 0}
        blocks = [{**self.block, "ancre": anchor, "mine": True},
                  {**self.block, "ancre": anchor, "x": 2},
                  {**self.block, "ancre": {**anchor, "x": 10}, "x": 10}]
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
        g = {"id": "gt:a", "nom": "Iron vein", "definition": "gtceu:iron_vein",
             "dimension": "minecraft:overworld", "x": 10, "y": 20, "z": 30,
             "composition": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"], "epuise": False}
        data = {"v": 1, "monde": "a", "source": "gregtech", "gisements": [g], "blocs": []}
        veins = self.find(SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None))
        self.assertEqual(len(veins), 1)
        self.assertEqual(veins[0]["gisement"], "Iron vein")
        self.assertTrue(veins[0]["partiel"])
        self.assertFalse(veins[0]["epuise"])
        description = self.describe(veins, None)
        self.assertIn("quantité totale restante inconnue", description)
        self.assertNotIn("blocs encore là", description)
        self.assertEqual((veins[0]["vus"], veins[0]["x"]), (0, 10))
        data["blocs"] = [{**self.block, "filon": "gt:a"}]
        veins = self.find(SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None))
        self.assertEqual(veins[0]["vus"], 1)
        data["gisements"] = []
        self.assertEqual(self.find(SimpleNamespace(read=lambda name: data if name == "filons_mod.json" else None)), [])

    def test_java_scanner_model_and_safe_destination(self):
        java_home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (java_home / "bin/javac").exists():
            java_home = Path.home() / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
        javac = str(java_home / "bin/javac") if (java_home / "bin/javac").exists() else shutil.which("javac")
        java = str(java_home / "bin/java") if (java_home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 required for the Java scanner tests")
        with tempfile.TemporaryDirectory() as directory:
            sources = [ROOT / "mod/src/main/java/fr/assistantia/FilonMemoire.java",
                       ROOT / "tests/java/fr/assistantia/FilonMemoireTest.java"]
            subprocess.run([javac, "-encoding", "UTF-8", "-d", directory, *map(str, sources)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([java, "-ea", "-cp", directory, "fr.assistantia.FilonMemoireTest"],
                                    check=True, capture_output=True, text=True, timeout=30)
            self.assertIn("14 scénarios filons validés", result.stdout)


if __name__ == "__main__":
    unittest.main()
