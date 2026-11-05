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

    def test_placed_machines_are_not_added_to_the_emi_target(self):
        # Deux chaudières posées, aucune sur lui : EMI n'en demande qu'une, pas trois
        target = {"item": "gtceu:solid_boiler", "quantite": 1, "deja": 2, "deja_inventaire": 0}
        result = objective_tree({"cibles": [target]})
        self.assertEqual(result["cibles"], [{"item": "gtceu:solid_boiler", "quantite": 1}])
        # Ancien plan sauvegardé sans deja_inventaire : calcul inchangé
        legacy = objective_tree(
            {"cibles": [{"item": "gtceu:solid_boiler", "quantite": 1, "deja": 2}]}
        )
        self.assertEqual(legacy["cibles"], [{"item": "gtceu:solid_boiler", "quantite": 3}])

    def test_reopening_exports_only_unfinished_targets_and_preserves_inventory_credit(self):
        plan = {"cibles": [{"item": "machine", "quantite": 4}, {"item": "cable", "quantite": 16}]}
        progress = {
            "buts": {"machine": 4, "cable": 16},
            "comptes": {"machine": 2, "cable": 16},
            "atteints": {"cable"},
        }
        result = objective_tree(plan, progress=progress, inventory={"machine": 1})
        self.assertEqual(result["cibles"], [{"item": "machine", "quantite": 3}])
        self.assertEqual(result["credits"], {"machine": 1})
        result = objective_tree(plan, progress=progress, inventory={})
        self.assertEqual(result["cibles"], [{"item": "machine", "quantite": 2}])
        self.assertEqual(result["credits"], {"machine": 2})
        progress["comptes"]["machine"] = 4
        self.assertIsNone(objective_tree(plan, progress=progress, inventory={}))

    def test_machine_lot_states_partial_receipts_and_cancel(self):
        self._run_java_contract("LotFabrication")

    def test_manual_machine_batch_receipts_do_not_duplicate_stock(self):
        self._run_java_contract("ReceptionManuelle")

    def test_manual_lot_has_no_machine_location_and_cannot_break_machine_panel(self):
        self._run_java_contract("LotMachine")

    def test_chest_withdrawal_allocation(self):
        self._run_java_contract("PlanRetrait")

    def test_collected_ingredients_never_relight(self):
        self._run_java_contract("MemoireCollecte")

    def test_complete_tree_ingredients_quantities_and_branches(self):
        self._run_java_contract("ArbreCollecte")

    def test_update_notice_dismissal_persists_for_this_revision(self):
        self._run_java_contract("AvisMiseAJour")

    def test_collected_components_prune_raw_materials(self):
        self._run_java_contract("PlanArbre")

    def test_objective_pause_stops_emi_once_without_implicit_resume(self):
        self._run_java_contract("EtatPauseEmi")

    def test_final_craft_quantities_and_multiple_targets(self):
        self._run_java_contract("SuiviFabrication")

    def test_only_explicitly_active_emi_craft_allows_collection(self):
        self._run_java_contract("ArbreEmi")

    def test_machine_steps_prune_owned_components_and_scale_batches(self):
        self._run_java_contract("PlanMachines")

    def test_machine_send_waits_without_replaying_actions(self):
        self._run_java_contract("SequenceEnvoi")

    def test_pending_outputs_received_once_across_machine_and_manual_lots(self):
        self._run_java_contract("ReceptionLots")

    def test_machine_banner_avoids_native_title_and_ignores_hidden_widgets(self):
        self._run_java_contract("EmpriseModulaire")

    def test_virtual_recipe_contract(self):
        self._run_java_contract("RecetteObjectifEmi")

    def test_chest_panel_never_covers_the_chest(self):
        self._run_java_contract("DispositionCoffre")

    def _run_java_contract(self, name):
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
            fixtures = []
            if name == "ArbreEmi":
                for package, cls, body in (
                    (
                        "dev.emi.emi.bom",
                        "BoM",
                        "public static Object tree; public static boolean craftingMode;",
                    ),
                    (
                        "dev.emi.emi.screen",
                        "EmiScreenManager",
                        "public static boolean disabled; public static boolean isDisabled() { return disabled; }",
                    ),
                ):
                    source = Path(directory) / f"{cls}.java"
                    source.write_text(f"package {package}; public class {cls} {{ {body} }}")
                    fixtures.append(str(source))
            if name == "LotMachine":
                for package, cls, body in (
                    (
                        "net.minecraft.core",
                        "BlockPos",
                        'public class BlockPos {public int x,y,z;public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public BlockPos immutable(){return new BlockPos(x,y,z);}public boolean equals(Object o){return o instanceof BlockPos p&&x==p.x&&y==p.y&&z==p.z;}public int hashCode(){return java.util.Objects.hash(x,y,z);}public String toShortString(){return x+", "+y+", "+z;}}',
                    ),
                    ("dev.emi.emi.api.recipe", "EmiRecipe", "public interface EmiRecipe {}"),
                    ("dev.emi.emi.api.stack", "EmiStack", "public record EmiStack(String id) {}"),
                ):
                    source = Path(directory) / f"{cls}.java"
                    source.write_text(f"package {package}; {body}")
                    fixtures.append(str(source))
            package_path = "compat/emi/" if name == "LotMachine" else ""
            subprocess.run(
                [
                    javac,
                    "-encoding",
                    "UTF-8",
                    "-d",
                    directory,
                    *fixtures,
                    str(ROOT / f"mod/src/main/java/fr/assistantia/{package_path}{name}.java"),
                    str(ROOT / f"tests/java/fr/assistantia/{package_path}{name}Test.java"),
                    *(
                        [str(ROOT / "mod/src/main/java/fr/assistantia/ReceptionSortie.java")]
                        if name == "LotFabrication"
                        else []
                    ),
                    *(
                        [str(ROOT / "mod/src/main/java/fr/assistantia/DispositionCoffre.java")]
                        if name == "EmpriseModulaire"
                        else []
                    ),
                    *(
                        [str(ROOT / "mod/src/main/java/fr/assistantia/PlanRetrait.java")]
                        if name
                        in {
                            "SuiviFabrication",
                            "PlanMachines",
                            "LotFabrication",
                            "ReceptionManuelle",
                            "LotMachine",
                            "ReceptionLots",
                        }
                        else []
                    ),
                    *(
                        [str(ROOT / "mod/src/main/java/fr/assistantia/LotFabrication.java")]
                        if name in {"ReceptionManuelle", "LotMachine", "ReceptionLots"}
                        else []
                    ),
                    *(
                        [str(ROOT / "mod/src/main/java/fr/assistantia/ReceptionManuelle.java")]
                        if name == "ReceptionLots"
                        else []
                    ),
                    *[
                        str(ROOT / f"mod/src/main/java/fr/assistantia/{dependency}.java")
                        for dependency in (
                            "PlanRetrait",
                            "MemoireCollecte",
                            "ArbreCollecte",
                            "PlanArbre",
                        )
                        if name in {"PlanRetrait", "MemoireCollecte", "ArbreCollecte", "PlanArbre"}
                        and dependency != name
                    ],
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                [
                    java,
                    "-ea",
                    "-cp",
                    directory,
                    f"fr.assistantia.{package_path.replace('/', '.')}{name}Test",
                ],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
