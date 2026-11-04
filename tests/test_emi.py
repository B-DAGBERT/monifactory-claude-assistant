"""Objective-to-EMI snapshot and virtual recipe contract, without Minecraft or an AI call."""

import copy
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from monifactory_assistant.game.emi import objective_tree

ROOT = Path(__file__).resolve().parents[1]


class EmiTests(unittest.TestCase):
    def test_all_targets_quantities_and_intermediate_recipes_without_mutating_plan(self):
        plan = {
            "objectif": "Une machine et ses câbles",
            "cibles": [
                {"item": "gtceu:lv_wiremill", "quantite": 2, "deja": 1},
                {"item": "gtceu:copper_single_wire", "quantite": 130},
            ],
            "recettes": [
                {"item": "gtceu:lv_wiremill", "id": "gtceu:shaped/lv_wiremill"},
                {"item": "gtceu:lv_motor", "id": "gtceu:shaped/motor_lv"},
                {"item": "gtceu:copper_single_wire", "id": None},
            ],
        }
        before = copy.deepcopy(plan)
        result = objective_tree(plan, "usine", 42)
        self.assertEqual([x["quantite"] for x in result["cibles"]], [3, 130])
        self.assertEqual(result["recettes"][1]["id"], "gtceu:shaped/motor_lv")
        self.assertEqual(result["recettes"][2]["id"], "")
        self.assertEqual((result["projet"], result["version"]), ("usine", "42"))
        self.assertEqual(plan, before)
        self.assertNotEqual(result, objective_tree(plan, "autre", 42))

    def test_empty_objective_has_no_emi_action(self):
        self.assertIsNone(objective_tree(None))
        self.assertIsNone(objective_tree({"cibles": []}))

    def test_target_without_ai_recipe_is_still_available_to_emi(self):
        result = objective_tree({"cibles": [{"item": "minecraft:torch", "quantite": 64}]})
        self.assertEqual(result["cibles"], [{"item": "minecraft:torch", "quantite": 64}])
        self.assertEqual(result["recettes"], [])

    def test_duplicate_targets_are_combined(self):
        result = objective_tree(
            {
                "cibles": [
                    {"item": "minecraft:torch", "quantite": 4},
                    {"item": "minecraft:torch", "quantite": 8},
                ]
            }
        )
        self.assertEqual(result["cibles"], [{"item": "minecraft:torch", "quantite": 12}])

    def test_virtual_recipe_contract(self):
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
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(
                [
                    javac,
                    "-encoding",
                    "UTF-8",
                    "-d",
                    directory,
                    str(ROOT / "mod/src/main/java/fr/assistantia/RecetteObjectifEmi.java"),
                    str(ROOT / "tests/java/fr/assistantia/RecetteObjectifEmiTest.java"),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.RecetteObjectifEmiTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
