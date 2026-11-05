"""Native EMI tree inventory adapter: counts, close/reopen, refresh and transfer isolation."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_backpack_inventory import STUBS as BACKPACK_STUBS

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    **BACKPACK_STUBS,
    "net.minecraft.world.entity.player.Player": """
public class Player {
 public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=new net.minecraft.world.inventory.AbstractContainerMenu();
 public java.util.List<net.minecraft.world.item.ItemStack> items=new java.util.ArrayList<>(),extra=new java.util.ArrayList<>();
}
""",
    "net.minecraft.world.inventory.AbstractContainerMenu": """
public class AbstractContainerMenu {public java.util.List<Slot> slots=new java.util.ArrayList<>();}
""",
    "net.minecraft.world.inventory.Slot": "public class Slot {public Object container;public Slot(Object c){container=c;}}",
    "net.minecraft.world.inventory.CraftingContainer": """
public class CraftingContainer {private final java.util.List<net.minecraft.world.item.ItemStack> items;
 public CraftingContainer(java.util.List<net.minecraft.world.item.ItemStack> l){items=l;}
 public int getContainerSize(){return items.size();}public net.minecraft.world.item.ItemStack getItem(int i){return items.get(i);}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft I=new Minecraft();public static Minecraft getInstance(){return I;}
 public net.minecraft.world.entity.player.Player player;public Object level,screen,connection=new Object();
 public Object getConnection(){return connection;}}
""",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.event.TickEvent": """
public class TickEvent {public enum Phase {START,END} public static class ClientTickEvent {
 public Phase phase;public ClientTickEvent(Phase p){phase=p;}}}
""",
    "fr.assistantia.InventaireJoueur": """
public class InventaireJoueur {public static boolean sync=true;public static java.util.List<net.minecraft.world.item.ItemStack> piles=java.util.List.of();
 public static java.util.List<net.minecraft.world.item.ItemStack> sacs;
 public static boolean disponible(){return sync;}public static java.util.List<net.minecraft.world.item.ItemStack> sacs(){return sacs==null?piles:sacs;}public static java.util.List<net.minecraft.world.item.ItemStack> piles(){return piles;}}
""",
    "fr.assistantia.compat.emi.CoffresArbreEmi": """
public class CoffresArbreEmi {public static Object arbre=new Object();public static Object arbreActif(){return arbre;}}
""",
    "dev.emi.emi.api.stack.EmiIngredient": "public interface EmiIngredient {java.util.List<EmiStack> getEmiStacks();}",
    "fr.assistantia.compat.emi.ComparaisonEmi": """
public class ComparaisonEmi {public static dev.emi.emi.api.stack.EmiStack pourInventaire(dev.emi.emi.api.stack.EmiStack s){return s.copy();}
 public static boolean meme(dev.emi.emi.api.stack.EmiStack a,dev.emi.emi.api.stack.EmiStack b){return a.equals(b);}}
""",
    "fr.assistantia.compat.emi.MachinesEmi": """
public class MachinesEmi {public static java.util.List<fr.assistantia.PlanRetrait.Stock<dev.emi.emi.api.stack.EmiStack>> reservations=java.util.List.of();
 public static java.util.List<fr.assistantia.PlanRetrait.Stock<dev.emi.emi.api.stack.EmiStack>> reservationsArbre(){return CoffresArbreEmi.arbreActif()==null?java.util.List.of():reservations;}}
""",
    "dev.emi.emi.api.stack.Comparison": """
public class Comparison {public static final Comparison DEFAULT_COMPARISON=new Comparison();private static final Comparison NBT=new Comparison();
 public static Comparison compareNbt(){return NBT;}}
""",
    "dev.emi.emi.api.stack.EmiStack": """
import net.minecraft.world.item.ItemStack;
public class EmiStack implements EmiIngredient {private final ItemStack item;private long n;
 public EmiStack(ItemStack s){item=s.copy();n=s.getCount();}public static EmiStack of(ItemStack s){return new EmiStack(s);}
 public EmiStack copy(){return new EmiStack(item).setAmount(n);}public long getAmount(){return n;}
 public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}public net.minecraft.nbt.CompoundTag getNbt(){return item.getTag();}
 public EmiStack setAmount(long value){n=value;return this;}
 public EmiStack comparison(java.util.function.Function<Comparison,Comparison> c){c.apply(Comparison.DEFAULT_COMPARISON);return this;}
 public net.minecraft.resources.ResourceLocation getId(){return new net.minecraft.resources.ResourceLocation(item.id);}
 public ItemStack getItemStack(){var s=item.copy();s.setCount((int)n);return s;}
 public boolean equals(Object o){return o instanceof EmiStack s&&item.id.equals(s.item.id)&&java.util.Objects.equals(item.getTag(),s.item.getTag());}
 public int hashCode(){return java.util.Objects.hash(item.id,item.getTag());}}
""",
    "dev.emi.emi.api.recipe.EmiPlayerInventory": """
import dev.emi.emi.api.stack.EmiStack;
public class EmiPlayerInventory {public java.util.Map<EmiStack,EmiStack> inventory=new java.util.HashMap<>();
 public static net.minecraft.world.item.ItemStack cursor=net.minecraft.world.item.ItemStack.EMPTY;
 public EmiPlayerInventory(java.util.List<EmiStack> items){for(var s:items)add(s.copy());if(!cursor.isEmpty())add(EmiStack.of(cursor));}
 private void add(EmiStack s){inventory.merge(s,s,(a,b)->a.setAmount(a.getAmount()+b.getAmount()));}
 public static EmiPlayerInventory of(net.minecraft.world.entity.player.Player p){return new EmiPlayerInventory(p.items.stream().map(EmiStack::of).toList());}}
""",
    "dev.emi.emi.runtime.EmiFavorites": """
public class EmiFavorites {public static int updates;public static dev.emi.emi.api.recipe.EmiPlayerInventory last;
 public static void updateSynthetic(dev.emi.emi.api.recipe.EmiPlayerInventory i){updates++;last=i;}}
""",
    "dev.emi.emi.config.SidebarType": "public enum SidebarType {CRAFTABLES}",
    "dev.emi.emi.screen.EmiScreenManager": "public class EmiScreenManager {public static void repopulatePanels(dev.emi.emi.config.SidebarType t){}}",
    "dev.emi.emi.screen.BoMScreen": "public class BoMScreen {public int updates;public void recalculateTree(){updates++;}}",
}


class EmiBackpackTreeTests(unittest.TestCase):
    def test_closed_bag_native_tree_counts_and_refresh_without_fill_changes(self):
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
            files = []
            for name, text in STUBS.items():
                package, cls = name.rsplit(".", 1)
                path = Path(directory) / name.replace(".", "/")
                path = path.with_suffix(".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {package};\n{text}")
                files.append(str(path))
            files.extend(
                str(ROOT / name)
                for name in (
                    "mod/src/main/java/fr/assistantia/PlanRetrait.java",
                    "mod/src/main/java/fr/assistantia/PlanMachines.java",
                    "mod/src/main/java/fr/assistantia/compat/emi/PlisFabricationEmi.java",
                    "mod/src/main/java/fr/assistantia/compat/emi/InventaireArbreEmi.java",
                    "tests/java/fr/assistantia/compat/emi/PlisFabricationEmiTest.java",
                    "tests/java/fr/assistantia/InventaireArbreEmiTest.java",
                )
            )
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *files],
                text=True,
                capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.InventaireArbreEmiTest"],
                text=True,
                capture_output=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.compat.emi.PlisFabricationEmiTest"],
                text=True,
                capture_output=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
