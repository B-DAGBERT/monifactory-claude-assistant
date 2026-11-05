"""Policy regression: GT tool wear, explicit EMI comparison and virtual circuits."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "net.minecraft.world.item.ItemStack": """
public record ItemStack(Object getItem) {public boolean isEmpty(){return getItem==null;}}
""",
    "dev.emi.emi.api.stack.Comparison": """
public class Comparison {public static final Comparison DEFAULT_COMPARISON=new Comparison();
 public static final Comparison NBT=new Comparison(); public static Comparison compareNbt(){return NBT;}}
""",
    "dev.emi.emi.api.stack.EmiIngredient": """
public interface EmiIngredient {java.util.List<EmiStack> getEmiStacks();}
""",
    "dev.emi.emi.api.stack.EmiStack": """
public class EmiStack implements EmiIngredient {
 public final String id;public final Object item; public final int damage;public long amount;
 public Comparison comparison=Comparison.DEFAULT_COMPARISON;
 public EmiStack(String i,Object t,int d,long n){id=i;item=t;damage=d;amount=n;}
 public EmiStack copy(){var s=new EmiStack(id,item,damage,amount);s.comparison=comparison;return s;}
 public EmiStack comparison(java.util.function.Function<Comparison,Comparison> f){comparison=f.apply(comparison);return this;}
 public net.minecraft.world.item.ItemStack getItemStack(){return new net.minecraft.world.item.ItemStack(item);}
 public String getId(){return id;} public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}
 public boolean isEqual(EmiStack s){return item==s.item&&(comparison!=Comparison.NBT&&s.comparison!=Comparison.NBT||damage==s.damage);}}
""",
    "dev.emi.emi.api.recipe.EmiRecipe": "public interface EmiRecipe {}",
    "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe": "public class GTEmiRecipe implements dev.emi.emi.api.recipe.EmiRecipe {}",
    "com.gregtechceu.gtceu.api.item.IGTTool": "public interface IGTTool {}",
}
HARNESS = """
package fr.assistantia;
import fr.assistantia.compat.emi.ComparaisonEmi;
import dev.emi.emi.api.stack.*;
public class EmiComparisonTest {
 static final class Tool implements com.gregtechceu.gtceu.api.item.IGTTool {}
 public static void main(String[] args) {
  var hammer=new Tool();var otherMaterial=new Tool();
  var worn=new EmiStack("gtceu:steel_hammer",hammer,311,1);
  var fresh=new EmiStack("gtceu:steel_hammer",hammer,0,1);
  var got=ComparaisonEmi.pourInventaire(worn);
  assert got!=worn&&got.item==hammer&&got.damage==311&&got.amount==1;
  assert got.comparison==Comparison.DEFAULT_COMPARISON&&got.isEqual(fresh):"wear does not require another hammer";
  assert !got.isEqual(new EmiStack("gtceu:iron_hammer",otherMaterial,0,1)):"material is not interchangeable";
  worn.comparison=Comparison.NBT;
  assert ComparaisonEmi.pourInventaire(worn).comparison==Comparison.NBT:"explicit EMI constraint preserved";
  assert !ComparaisonEmi.pourInventaire(worn).isEqual(fresh);
  var customItem=new Object();
  var custom=new EmiStack("other:tool",customItem,2,4);
  var strict=ComparaisonEmi.pourInventaire(custom);
  assert strict.comparison==Comparison.NBT&&strict.amount==4;
  assert custom.comparison==Comparison.DEFAULT_COMPARISON:"source stack unchanged";
  assert !strict.isEqual(new EmiStack("other:tool",customItem,1,4)):"other material-in-NBT items stay strict";
  var gt=new com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe();
  var circuit=new EmiStack("gtceu:programmed_circuit",new Object(),99,1);
  assert ComparaisonEmi.circuitVirtuel(gt,circuit,true);
  assert !ComparaisonEmi.circuitVirtuel(null,circuit,true):"root circuit is an actual target";
  assert !ComparaisonEmi.circuitVirtuel(gt,circuit,false):"consumed circuit remains an item";
  assert !ComparaisonEmi.circuitVirtuel(new dev.emi.emi.api.recipe.EmiRecipe(){},circuit,true):"other recipe catalysts preserved";
  assert !ComparaisonEmi.circuitVirtuel(gt,()->java.util.List.of(),true);
  assert !ComparaisonEmi.circuitVirtuel(gt,()->java.util.List.of(circuit,fresh),true):"mixed alternative is not purely virtual";
 }
}
"""


class EmiComparisonTests(unittest.TestCase):
    def test_worn_gt_tools_explicit_nbt_and_contextual_virtual_circuits(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory() as directory:
            files = []
            for name, body in STUBS.items():
                path = Path(directory) / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}")
                files.append(str(path))
            harness = Path(directory) / "EmiComparisonTest.java"
            harness.write_text(HARNESS)
            files += [
                str(harness),
                str(ROOT / "mod/src/main/java/fr/assistantia/compat/emi/ComparaisonEmi.java"),
            ]
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *files],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.EmiComparisonTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
