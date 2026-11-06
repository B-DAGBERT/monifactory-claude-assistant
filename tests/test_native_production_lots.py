"""Exercise the real native lot owner and its read-only projection without a Minecraft instance."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_machine_inputs import STUBS as MACHINE_STUBS

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = (
    "initial",
    "partial_cancel",
    "complete_move",
    "extension",
    "stock_changed",
    "recipe_reload",
    "scope_tree",
    "scope_project",
    "scope_connection",
    "scope_recipes",
    "extension_scope",
    "unattributed",
    "read_only",
    "available_label",
    "fluids_variants",
    "duplicate_outputs",
    "bounded",
    "pending_receipt",
    "clear",
    "inventory_variant",
    "foreign_native_scope",
    "foreign_context",
)


class NativeProductionLotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        if not cls.java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "2.10/*/gson-2.10.jar"
            ),
            None,
        )
        if gson is None:
            raise unittest.SkipTest("Gson 2.10 en cache requis")
        cls.work = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.work.cleanup)
        directory = Path(cls.work.name)
        cls.classpath = os.pathsep.join((str(directory), str(gson)))
        stubs = dict(MACHINE_STUBS)
        stubs["dev.emi.emi.api.recipe.EmiRecipeManager"] = (
            "public class EmiRecipeManager {public EmiRecipe recipe;public EmiRecipe getRecipe(String id){return recipe;}}"
        )
        stubs["dev.emi.emi.api.recipe.EmiRecipe"] = stubs[
            "dev.emi.emi.api.recipe.EmiRecipe"
        ].replace(
            "public class EmiRecipe {",
            'public class EmiRecipe {public String getId(){return "test:recipe";}',
        )
        stubs["net.minecraft.core.BlockPos"] = stubs["net.minecraft.core.BlockPos"].replace(
            "public BlockPos immutable()",
            "public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}public BlockPos immutable()",
        )
        # The actual resource encoder is independently replayed in test_native_production_catalog.
        stubs["fr.assistantia.compat.emi.RecetteProduction"] = """public class RecetteProduction {
 public static com.google.gson.JsonObject resource(dev.emi.emi.api.stack.EmiStack s){
  var r=new com.google.gson.JsonObject();boolean item=!s.getItemStack().isEmpty();
  r.addProperty("kind",item?"item":"fluid");r.addProperty("id",s.getId().toString());
  r.addProperty("unit",item?"item":"mB");
  r.addProperty("variant",item&&s.getItemStack().tag!=0?"{tag:"+s.getItemStack().tag+"}":"");return r;
 }}"""
        sources = []
        for name, body in stubs.items():
            path = directory / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
            sources.append(str(path))
        sources.extend(
            str(ROOT / "mod/src/main/java/fr/assistantia" / f"{name}.java")
            for name in (
                "PlanRetrait",
                "PlanMachines",
                "LotFabrication",
                "ReceptionManuelle",
                "ReceptionSortie",
                "ReceptionLots",
                "compat/emi/ComparaisonEmi",
                "compat/emi/ConfigurationCircuit",
                "compat/emi/LotMachine",
                "compat/emi/SuiviLotsEmi",
                "compat/emi/ProjectionLotsEmi",
            )
        )
        sources.append(
            str(ROOT / "tests/java/fr/assistantia/compat/emi/ProjectionLotsEmiTest.java")
        )
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", str(directory), *sources],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def test_lifecycle_quantities_scope_and_no_mutation(self):
        for scenario in SCENARIOS:
            with self.subTest(scenario=scenario):
                result = subprocess.run(
                    [
                        self.java,
                        "-ea",
                        "-cp",
                        self.classpath,
                        "fr.assistantia.compat.emi.ProjectionLotsEmiTest",
                        scenario,
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("PASS " + scenario, result.stdout)
