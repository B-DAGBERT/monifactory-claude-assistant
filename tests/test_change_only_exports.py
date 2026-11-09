"""Exchange files are rewritten only when they change, and never make the game thread wait.

Java contracts run the real writer and inventory export against small Minecraft stubs.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_backpack_inventory import STUBS as BACKPACK_STUBS

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"


def jdk():
    home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = home / f"bin/javac{suffix}"
    java = home / f"bin/java{suffix}"
    if javac.is_file() and java.is_file():
        return str(javac), str(java)
    return shutil.which("javac"), shutil.which("java")


def gson_jar():
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next(
        (cache / "caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/gson-*.jar"),
        None,
    )


LOGGER = {
    "com.mojang.logging.LogUtils": """
public class LogUtils {public static Logger getLogger(){return new Logger();}
 public static class Logger {public static int warnings;public void warn(String s,Throwable e){warnings++;}}}
""",
}

INVENTORY_STUBS = {
    **{
        k: v
        for k, v in BACKPACK_STUBS.items()
        if k.startswith(
            (
                "net.minecraft.nbt",
                "net.minecraft.resources",
                "net.minecraftforge.registries",
                "net.minecraftforge.items",
            )
        )
    },
    **LOGGER,
    "net.minecraft.world.item.ItemStack": """
import net.minecraft.nbt.*;
public class ItemStack {
 public static int copies;
 public static final ItemStack EMPTY=new ItemStack("",0); public final String id;private int count;private CompoundTag tag;
 public ItemStack(String id,int n){this.id=id;count=n;}public int getCount(){return count;}public void setCount(int n){count=n;}
 public boolean isEmpty(){return count<=0||id.isEmpty();} public boolean hasTag(){return tag!=null;}public CompoundTag getTag(){return tag;}
 public String getItem(){return id;}public void setTag(CompoundTag t){tag=t;}
 public ItemStack copy(){copies++;var s=new ItemStack(id,count);s.tag=tag==null?null:tag.copy();return s;}
 public static boolean matches(ItemStack a,ItemStack b){return a.id.equals(b.id)&&a.count==b.count&&java.util.Objects.equals(a.tag,b.tag);}
 public static ItemStack of(CompoundTag n){var s=new ItemStack(n.getString("id"),n.getInt("Count"));return s;}
}
""",
    "net.minecraft.world.inventory.AbstractContainerMenu": """
public class AbstractContainerMenu {public net.minecraft.world.item.ItemStack carried=net.minecraft.world.item.ItemStack.EMPTY;
 public net.minecraft.world.item.ItemStack getCarried(){return carried;}}
""",
    "net.minecraft.world.entity.player.Inventory": """
import net.minecraft.world.item.ItemStack;
public class Inventory {
 public final java.util.List<ItemStack> items=vide(36),armor=vide(4),offhand=vide(1);
 static java.util.List<ItemStack> vide(int n){var l=new java.util.ArrayList<ItemStack>();for(int i=0;i<n;i++)l.add(ItemStack.EMPTY);return l;}}
""",
    "net.minecraft.network.chat.Component": "public record Component(String text) {public String getString(){return text;}}",
    "net.minecraft.world.entity.player.Player": """
public class Player {
 public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=new net.minecraft.world.inventory.AbstractContainerMenu();
 public final Inventory inventory=new Inventory();public Inventory getInventory(){return inventory;}
 public net.minecraft.network.chat.Component getName(){return new net.minecraft.network.chat.Component("Damien");}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();
 public net.minecraft.world.entity.player.Player player;public Level level;public Object connection;
 public static Minecraft getInstance(){return INSTANCE;}public Object getConnection(){return connection;}
 public static class Level {private final Key key;public Level(String d){key=new Key(d);}public Key dimension(){return key;}}
 public record Key(String name){public String location(){return name;}}}
""",
    "net.minecraftforge.event.TickEvent": """
public class TickEvent {public enum Phase {START,END}public static class ClientTickEvent {public Phase phase=Phase.END;}}
""",
    "net.minecraftforge.eventbus.api.EventPriority": "public enum EventPriority {HIGHEST,HIGH,NORMAL,LOW,LOWEST}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {EventPriority priority() default EventPriority.NORMAL;}",
    "fr.assistantia.Pont": """
public class Pont {public static int exports;public static com.google.gson.JsonObject dernier;
 public static boolean envoyerInventaire(com.google.gson.JsonObject j){exports++;dernier=j.deepCopy();return true;}}
""",
}

INVENTORY_TEST = r"""
package fr.assistantia;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
public class InventaireJoueurTest {
 static long maintenant=1_000_000;
 static void tick(){InventaireJoueur.tick(new TickEvent.ClientTickEvent());}
 static void avancer(int ticks){for(int i=0;i<ticks;i++){maintenant+=50;tick();}}
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static int nombre(String id){var j=Pont.dernier.getAsJsonObject("inventaire");return j.has(id)?j.get(id).getAsInt():0;}
 public static void main(String[] args){
  InventaireJoueur.horloge=()->maintenant;
  var mc=Minecraft.getInstance();var p=new Player();
  mc.player=p;mc.level=new Minecraft.Level("minecraft:overworld");mc.connection=new Object();
  p.inventory.items.set(0,new ItemStack("minecraft:iron_ingot",12));
  var outil=new ItemStack("gtceu:steel_pickaxe",1);var tag=new CompoundTag();tag.putInt("Damage",3);outil.setTag(tag);
  p.inventory.items.set(1,outil);
  tick();
  check(Pont.exports==1&&Pont.dernier.get("t").getAsLong()==maintenant,"first snapshot exported at once");
  check(nombre("minecraft:iron_ingot")==12&&Pont.dernier.get("actif").getAsBoolean(),"first snapshot content");
  var piles=InventaireJoueur.piles();long revision=InventaireJoueur.revision();int copies=ItemStack.copies;
  avancer(39);
  check(ItemStack.copies==copies,"no ItemStack copy while nothing changes: "+(ItemStack.copies-copies));
  check(InventaireJoueur.piles()==piles&&InventaireJoueur.revision()==revision,"same view and revision");
  check(Pont.exports==1,"unchanged inventory not rewritten before the confirmation");
  avancer(1);
  check(Pont.exports==2&&Pont.dernier.get("t").getAsLong()==maintenant,"fresh confirmation after 2 s");
  maintenant+=50;p.inventory.items.set(2,new ItemStack("minecraft:coal",5));tick();
  check(InventaireJoueur.revision()==revision+1&&InventaireJoueur.piles()!=piles,"new stack detected at once");
  check(Pont.exports==2,"two exports stay 500 ms apart");
  avancer(9);
  check(Pont.exports==3&&nombre("minecraft:coal")==5,"change exported within 500 ms");
  revision=InventaireJoueur.revision();
  p.inventory.items.get(0).setCount(11);avancer(1);
  check(InventaireJoueur.revision()==revision+1,"in-place count change detected");
  revision=InventaireJoueur.revision();
  outil.getTag().putInt("Damage",4);avancer(20);
  check(InventaireJoueur.revision()==revision+1,"in-place NBT change caught by the periodic check");
  revision=InventaireJoueur.revision();piles=InventaireJoueur.piles();
  p.inventory.items.set(2,new ItemStack("minecraft:coal",5));avancer(1);
  check(InventaireJoueur.revision()==revision&&InventaireJoueur.piles()==piles,"equal replacement keeps the view");
  p.containerMenu.carried=new ItemStack("minecraft:coal",2);avancer(10);
  check(nombre("minecraft:coal")==7,"carried stack counted");
  int exports=Pont.exports;mc.level=new Minecraft.Level("minecraft:the_nether");avancer(10);
  check(Pont.exports==exports+1&&Pont.dernier.get("dimension").getAsString().equals("minecraft:the_nether"),"dimension change exported");
  mc.player=null;tick();
  check(!Pont.dernier.get("actif").getAsBoolean()&&InventaireJoueur.piles().isEmpty(),"inactive snapshot on disconnect");
  System.out.println("InventaireJoueur OK");
 }
}
"""


class JavaContract(unittest.TestCase):
    """Compiles real mod classes with stubs and runs a contract; holds no test itself."""

    def compile_and_run(self, stubs, sources, main, extra=()):
        javac, java = jdk()
        gson = gson_jar()
        if not javac or not java or gson is None:
            self.skipTest("JDK 17 and the Gson cache of the Forge build are required")
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            files = []
            for name, body in stubs.items():
                path = temp.joinpath(*name.split(".")).with_suffix(".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
                files.append(str(path))
            for name, body in extra:
                path = temp / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
                files.append(str(path))
            classpath = os.pathsep.join([str(temp), str(gson)])
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(temp), *files, *sources],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            game = temp / "game"
            game.mkdir()
            ran = subprocess.run(
                [java, "-ea", f"-Dtest.game={game}", "-cp", classpath, main],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=60,
            )
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            return ran.stdout


class JavaExportTests(JavaContract):
    def test_writer_never_sleeps_and_keeps_requests_in_order(self):
        output = self.compile_and_run(
            LOGGER,
            [
                str(JAVA / "Ecritures.java"),
                str(ROOT / "tests/java/fr/assistantia/EcrituresTest.java"),
            ],
            "fr.assistantia.EcrituresTest",
        )
        self.assertIn("Ecritures OK", output)

    def test_inventory_export_is_copy_free_when_unchanged_and_written_on_change(self):
        output = self.compile_and_run(
            INVENTORY_STUBS,
            [
                str(JAVA / "InventaireJoueur.java"),
                str(JAVA / "compat/backpacks/SacsPortes.java"),
            ],
            "fr.assistantia.InventaireJoueurTest",
            extra=[("fr/assistantia/InventaireJoueurTest.java", INVENTORY_TEST)],
        )
        self.assertIn("InventaireJoueur OK", output)


if __name__ == "__main__":
    unittest.main()
