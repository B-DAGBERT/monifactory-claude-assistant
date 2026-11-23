"""Exchange files are rewritten only when they change, and never make the game thread wait.

Java contracts run the real writer and inventory export against small Minecraft stubs.
"""

import os
import re
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
                "net.p3pp3rf1y.sophisticatedbackpacks",
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
 public final java.util.List<net.minecraft.world.item.ItemStack> items=inventory.items,extra=new java.util.ArrayList<>();
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
    "net.minecraftforge.fml.loading.FMLPaths": """
public class FMLPaths {public static final FMLPaths CONFIGDIR=new FMLPaths();
 public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.game")).resolve("config");}}
""",
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
  check(InventaireJoueur.caseRelevee(2).isEmpty(),"empty native slot");
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
  var charbon=p.inventory.items.get(2);
  p.inventory.items.set(2,ItemStack.EMPTY);p.inventory.items.set(3,charbon);
  check(InventaireJoueur.caseRelevee(2).getCount()==5&&InventaireJoueur.caseRelevee(3).isEmpty(),"slot and totals use the same snapshot");
  avancer(1);
  check(InventaireJoueur.caseRelevee(2).isEmpty()&&InventaireJoueur.caseRelevee(3).getCount()==5,"slot move observed with unchanged totals");
  revision=InventaireJoueur.revision();
  outil.getTag().putInt("Damage",4);avancer(20);
  check(InventaireJoueur.revision()==revision+1,"in-place NBT change caught by the periodic check");
  revision=InventaireJoueur.revision();piles=InventaireJoueur.piles();
  p.inventory.items.set(3,new ItemStack("minecraft:coal",5));avancer(1);
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

CRAFT_STOCK_TEST = r"""
package fr.assistantia;

import fr.assistantia.compat.backpacks.SacsPortes;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler;

public class StockFabricationSacTest {
    private static final String FER = "minecraft:iron_ingot";
    private static final UUID SAC = UUID.fromString("075f3fac-24fe-4fd7-a39f-eba957c2e5f2");
    private static long temps = 1_000_000;

    private static void tick() {
        temps += 500;
        InventaireJoueur.tick(new TickEvent.ClientTickEvent());
    }

    private static int compte(List<ItemStack> piles) {
        return piles.stream().filter(s -> s.id.equals(FER)).mapToInt(ItemStack::getCount).sum();
    }

    private static void repondre(int n) {
        var fer = new CompoundTag();
        fer.putString("id", FER);
        fer.putInt("Count", n);
        fer.putInt("Slot", 0);
        var liste = new ListTag();
        liste.add(fer);
        var inventaire = new CompoundTag();
        inventaire.putInt("Size", 9);
        inventaire.put("Items", liste);
        var stockage = new CompoundTag();
        stockage.put("inventory", inventaire);
        BackpackStorage.get().contents.put(SAC, stockage);
        SacsPortes.reception(SAC, stockage);
    }

    public static void main(String[] args) throws Exception {
        var config = FMLPaths.CONFIGDIR.get();
        var preference = config.resolve("assistantia-sacs-craft.txt");
        var voisine = config.resolve("assistantia-visuel.json");
        if (args[0].equals("relire")) {
            long avant = System.nanoTime();
            while (SacsFabrication.actif()
                    && System.nanoTime() - avant < Duration.ofSeconds(3).toNanos()) {
                Thread.sleep(1);
            }
            assert !SacsFabrication.actif() : "le choix OFF survit au redémarrage";
            assert Files.readString(voisine).equals("{\"fullbright\":true}");
            System.out.println("Préférence relue");
            return;
        }
        Files.createDirectories(config);
        Files.writeString(voisine, "{\"fullbright\":true}");
        assert SacsFabrication.actif() && !Files.exists(preference) : "activée par défaut";
        InventaireJoueur.horloge = () -> temps;
        var mc = Minecraft.getInstance();
        mc.player = new Player();
        mc.level = new Minecraft.Level("minecraft:overworld");
        mc.connection = new Object();
        mc.player.inventory.items.set(0, new ItemStack(FER, 3));
        var sac = new ItemStack("sophisticatedbackpacks:diamond_backpack", 1);
        var tag = new CompoundTag();
        tag.putUUID("contentsUuid", SAC);
        sac.setTag(tag);
        mc.player.inventory.items.set(1, sac);
        tick();
        assert SBPPacketHandler.requested.equals(List.of(SAC));
        assert !InventaireJoueur.disponible() && !InventaireJoueur.fabricationDisponible();

        long brut = InventaireJoueur.revision();
        long fabrication = InventaireJoueur.revisionFabrication();
        SacsFabrication.regler(false);
        assert InventaireJoueur.fabricationDisponible() && InventaireJoueur.fabricationSynchronisee();
        assert !InventaireJoueur.disponible() && !InventaireJoueur.synchronise();
        assert InventaireJoueur.revision() == brut;
        assert InventaireJoueur.revisionFabrication() == fabrication + 1;
        assert compte(InventaireJoueur.pilesFabrication()) == 3;
        var poches = InventaireJoueur.pilesFabrication();

        fabrication = InventaireJoueur.revisionFabrication();
        repondre(5);
        tick();
        assert InventaireJoueur.disponible() && InventaireJoueur.fabricationDisponible();
        assert InventaireJoueur.pilesFabrication() == poches;
        assert InventaireJoueur.revisionFabrication() == fabrication;
        assert InventaireJoueur.revision() > brut && compte(InventaireJoueur.piles()) == 8;
        assert compte(InventaireJoueur.sacs()) == 5 && InventaireJoueur.sacsFabrication().isEmpty();
        assert Pont.dernier.getAsJsonObject("inventaire").get(FER).getAsInt() == 8;
        assert Pont.dernier.getAsJsonObject("backpacks").get(FER).getAsInt() == 5;

        brut = InventaireJoueur.revision();
        SacsFabrication.regler(true);
        assert InventaireJoueur.revisionFabrication() == fabrication + 1;
        var piles = InventaireJoueur.piles();
        assert InventaireJoueur.pilesFabrication() == piles;
        assert InventaireJoueur.sacsFabrication() == InventaireJoueur.sacs();
        SacsFabrication.regler(false);
        SacsFabrication.regler(false);
        assert InventaireJoueur.revisionFabrication() == fabrication + 2;
        assert InventaireJoueur.piles() == piles && InventaireJoueur.revision() == brut;
        assert InventaireJoueur.pilesFabrication() == poches;
        assert Ecritures.attendre(3000) : "écriture en file terminée";
        assert Files.readString(preference).equals("false");
        assert Files.readString(voisine).equals("{\"fullbright\":true}");

        mc.connection = new Object();
        assert !InventaireJoueur.fabricationDisponible() : "stock de l'ancienne connexion interdit";
        tick();
        assert InventaireJoueur.fabricationDisponible() && compte(InventaireJoueur.pilesFabrication()) == 3;
        assert !InventaireJoueur.disponible() : "ancien contenu du sac encore sans preuve";
        mc.player = null;
        tick();
        assert InventaireJoueur.pilesFabrication().isEmpty() && !InventaireJoueur.fabricationDisponible();
        System.out.println("Stock des crafts OK");
    }
}
"""


class JavaContract(unittest.TestCase):
    """Compiles real mod classes with stubs and runs a contract; holds no test itself."""

    def compile_and_run(self, stubs, sources, main, extra=(), executions=((),)):
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
            resultats = []
            for args in executions:
                ran = subprocess.run(
                    [java, "-ea", f"-Dtest.game={game}", "-cp", classpath, main, *args],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=60,
                )
                self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                resultats.append(ran.stdout)
            return "".join(resultats)


INVENTORY_SOURCES = [
    str(JAVA / name)
    for name in (
        "Ecritures.java",
        "InventaireJoueur.java",
        "SacsFabrication.java",
        "compat/backpacks/SacsPortes.java",
    )
]


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
            INVENTORY_SOURCES,
            "fr.assistantia.InventaireJoueurTest",
            extra=[("fr/assistantia/InventaireJoueurTest.java", INVENTORY_TEST)],
        )
        self.assertIn("InventaireJoueur OK", output)

    def test_backpack_craft_preference_revises_only_planning_and_survives_restart(self):
        output = self.compile_and_run(
            INVENTORY_STUBS,
            INVENTORY_SOURCES,
            "fr.assistantia.StockFabricationSacTest",
            extra=[("fr/assistantia/StockFabricationSacTest.java", CRAFT_STOCK_TEST)],
            executions=(("appliquer",), ("relire",)),
        )
        self.assertIn("Stock des crafts OK", output)
        self.assertIn("Préférence relue", output)


class TemporaryNameTests(unittest.TestCase):
    def test_writer_temporaries_are_never_taken_for_memory_copies_by_the_migration(self):
        from monifactory_assistant import world_migration

        source = (JAVA / "Ecritures.java").read_text(encoding="utf-8")
        prefix = re.search(r'PREFIXE_TEMPORAIRE = "([^"]+)"', source)[1]
        for name in ("stockage.json", "filons.json"):
            orphan = f"{prefix}{name}-123456789.tmp"
            # A truncated orphan (crash while writing) stays aside instead of blocking migration.
            self.assertFalse(world_migration._selected("kubejs-assistant", orphan), orphan)
            # The previous naming would have been parsed as a copy of the memory file.
            self.assertTrue(world_migration._selected("kubejs-assistant", f".{name}-123.tmp"))


if __name__ == "__main__":
    unittest.main()
