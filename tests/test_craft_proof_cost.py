"""Count exact item comparisons in the production craft proof collector, independently of FPS."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
 public static long comparisons;public static final ItemStack EMPTY=new ItemStack("",0);
 public final String id;public int count,tag;public ItemStack(String id,int count){this.id=id;this.count=count;}
 public ItemStack(String id){this(id,1);}public String getItem(){return id;}
 public boolean isEmpty(){return count<=0;}public int getCount(){return count;}
 public ItemStack copy(){var s=new ItemStack(id,count);s.tag=tag;return s;}
 public static boolean isSameItemSameTags(ItemStack a,ItemStack b){comparisons++;return a.id.equals(b.id)&&a.tag==b.tag;}}
""",
    "net.minecraft.core.BlockPos": "public record BlockPos(int x,int y,int z) {public BlockPos immutable(){return this;}}",
    "net.minecraft.world.inventory.ClickType": "public enum ClickType {PICKUP, QUICK_MOVE}",
    "net.minecraft.world.inventory.AbstractContainerMenu": """public class AbstractContainerMenu {
 public int containerId=1;public net.minecraft.world.item.ItemStack getCarried(){return net.minecraft.world.item.ItemStack.EMPTY;}
}""",
    "net.minecraft.world.inventory.ChestMenu": "public class ChestMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.inventory.Slot": """public class Slot {
 public final int index;public net.minecraft.world.item.ItemStack item=net.minecraft.world.item.ItemStack.EMPTY;
 public Slot(Object o,int n){index=n;}public boolean isActive(){return true;}
 public boolean mayPickup(net.minecraft.world.entity.player.Player p){return true;}
 public net.minecraft.world.item.ItemStack getItem(){return item;}
}""",
    "net.minecraft.world.entity.player.Player": "public class Player {public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=new net.minecraft.world.inventory.ChestMenu();}",
    "net.minecraft.client.Minecraft": "public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();public static Minecraft getInstance(){return INSTANCE;}}",
    "net.minecraftforge.event.entity.player.PlayerEvent": "public class PlayerEvent {public record ItemCraftedEvent(net.minecraft.world.entity.player.Player getEntity,net.minecraft.world.item.ItemStack getCrafting){}}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.registries.ForgeRegistries": "public class ForgeRegistries {public static final Registry ITEMS=new Registry();public static class Registry {public String getKey(String id){return id;}}}",
    "fr.assistantia.compat.emi.CoffresArbreEmi": "public class CoffresArbreEmi {public static Object arbreActif(){return Boolean.TRUE;}}",
    "fr.assistantia.compat.emi.GregTechMachines": """public class GregTechMachines {
 public record Interface(Object machine,java.util.List<?> entrees,java.util.List<?> cuves,Object circuit){
   public net.minecraft.core.BlockPos position(){return new net.minecraft.core.BlockPos(0,64,0);}
 }
 public static Interface ui;
 public static Interface ouvrir(net.minecraft.world.inventory.AbstractContainerMenu menu){return ui;}
 public static java.util.List<net.minecraft.world.inventory.Slot> outputSlots=java.util.List.of();
 public static java.util.List<net.minecraft.world.inventory.Slot> sorties(net.minecraft.world.inventory.AbstractContainerMenu menu)throws ReflectiveOperationException{return outputSlots;}
}""",
}


class CraftProofCostTests(unittest.TestCase):
    def test_only_items_with_pending_craft_proof_are_counted(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as directory:
            sources = []
            for name, body in STUBS.items():
                source = Path(directory) / (name.replace(".", "/") + ".java")
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(source))
            sources += [
                str(ROOT / p)
                for p in (
                    "mod/src/main/java/fr/assistantia/PlanRetrait.java",
                    "mod/src/main/java/fr/assistantia/ReceptionSortie.java",
                    "mod/src/main/java/fr/assistantia/compat/emi/OrigineFabrication.java",
                    "tests/java/fr/assistantia/OrigineFabricationTest.java",
                    "tests/java/fr/assistantia/OrigineFabricationCoutTest.java",
                )
            ]
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for main in ("OrigineFabricationTest", "OrigineFabricationCoutTest"):
                result = subprocess.run(
                    [java, "-ea", "-cp", directory, "fr.assistantia." + main],
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                if result.stdout:
                    print(result.stdout.strip())
