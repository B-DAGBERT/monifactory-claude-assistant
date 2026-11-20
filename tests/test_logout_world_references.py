"""At logout, the features forget the references to the world they would keep until next use.

The handler is registered with the other client handlers and reaches every feature; the EMI ones
only when EMI is loaded. Each feature's own test checks that it then behaves as on first use:
test_buffer_chest (last right click), test_machine_inputs (machine screen and send recoveries),
test_machine_order_menu (return from an EMI recipe) and, below, the vein scanner's world access.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"


def jdk():
    home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
    javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
    java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
    return java, javac


def compiler(dossier, stubs, *sources):
    chemins = []
    for name, body in stubs.items():
        path = Path(dossier) / (name.replace(".", "/") + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
        chemins.append(str(path))
    result = subprocess.run(
        [jdk()[1], "-encoding", "UTF-8", "-d", dossier, *chemins, *map(str, sources)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if result.returncode:
        raise AssertionError(result.stdout + result.stderr)


def oubli(nom):
    return f"""public class {nom} {{
 public static int oublis;public static void oublierMonde(){{oublis++;}}}}"""


HANDLER_STUBS = {
    "fr.assistantia.CoffreTampon": oubli("CoffreTampon"),
    "fr.assistantia.Filons": oubli("Filons"),
    "fr.assistantia.Emi": """public class Emi {
 public static boolean present=true;public static boolean present(){return present;}}""",
    "fr.assistantia.compat.emi.EnvoiMachine": oubli("EnvoiMachine"),
    "fr.assistantia.compat.emi.MachinesEmi": oubli("MachinesEmi"),
    "fr.assistantia.compat.emi.SuiviEmiEcran": oubli("SuiviEmiEcran"),
    "net.minecraftforge.client.event.ClientPlayerNetworkEvent": """public class ClientPlayerNetworkEvent {
 public static class LoggingOut extends ClientPlayerNetworkEvent {}}""",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
}

HANDLER_HARNESS = r"""package fr.assistantia;
import fr.assistantia.compat.emi.*;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
public class DeconnexionTest {
 public static void main(String[] args){
  Deconnexion.deconnexion(new ClientPlayerNetworkEvent.LoggingOut());
  assert CoffreTampon.oublis==1&&Filons.oublis==1;
  assert EnvoiMachine.oublis==1&&MachinesEmi.oublis==1&&SuiviEmiEcran.oublis==1;
  Emi.present=false;
  Deconnexion.deconnexion(new ClientPlayerNetworkEvent.LoggingOut());
  assert CoffreTampon.oublis==2&&Filons.oublis==2:"world references are forgotten without EMI too";
  assert EnvoiMachine.oublis==1&&MachinesEmi.oublis==1&&SuiviEmiEcran.oublis==1:"EMI classes untouched";
  System.out.println("PASS deconnexion");
 }
}
"""

# Accès au monde du scanner de filons, repris tel quel de Filons.java avec sa méthode d'oubli.
MONDE_STUBS = {
    "net.minecraft.client.multiplayer.ClientLevel": """public class ClientLevel {
 public final java.util.Map<Long,net.minecraft.world.level.chunk.LevelChunk> chunks=new java.util.HashMap<>();
 public static long cle(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 public Source getChunkSource(){return new Source();}
 public int getMinBuildHeight(){return -64;}public int getMaxBuildHeight(){return 320;}
 public class Source {public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x,int z){return chunks.get(cle(x,z));}}}""",
    "net.minecraft.world.level.chunk.LevelChunk": """public class LevelChunk {
 private final LevelChunkSection[] sections;public LevelChunk(LevelChunkSection... s){sections=s;}
 public int getMinSection(){return 0;}public LevelChunkSection[] getSections(){return sections;}}""",
    "net.minecraft.world.level.chunk.LevelChunkSection": """
import net.minecraft.world.level.block.state.BlockState;
public class LevelChunkSection {
 private final BlockState etat;public LevelChunkSection(BlockState e){etat=e;}
 public boolean hasOnlyAir(){return false;}
 public boolean maybeHas(java.util.function.Predicate<BlockState> p){return p.test(etat);}
 public BlockState getBlockState(int x,int y,int z){return etat;}}""",
    "net.minecraft.world.level.block.Block": """public class Block {
 public final String id;public Block(String id){this.id=id;}}""",
    "net.minecraft.world.level.block.state.BlockState": """
public record BlockState(net.minecraft.world.level.block.Block getBlock) {}""",
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String id) {
 public String toString(){return id;}}""",
    "net.minecraftforge.registries.ForgeRegistries": """public class ForgeRegistries {
 public static final Blocs BLOCKS=new Blocs();
 public static class Blocs {public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.level.block.Block b){
  return new net.minecraft.resources.ResourceLocation(b.id);}}}""",
    "fr.assistantia.FilonMemoire": """public class FilonMemoire {
 public final java.util.Map<String,Integer> types=new java.util.HashMap<>();public int lectures;
 public int typesConnus(){return types.size();}
 public int typeConnu(String id){lectures++;return types.getOrDefault(id,-1);}
 public static class Filon {public boolean dansComposition(int type){return type>=0;}}}""",
    "fr.assistantia.ScanFilon": """public class ScanFilon {
 public interface Monde {int minY();int maxY();boolean charge(int cx,int cz);
  boolean sansMinerai(int cx,int sy,int cz,FilonMemoire.Filon filon);int type(int x,int y,int z);}}""",
}

MONDE_HARNESS = r"""package fr.assistantia;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
public class MondeFilonsTest {
 static ClientLevel niveau(Block bloc){
  var n=new ClientLevel();n.chunks.put(ClientLevel.cle(0,0),new LevelChunk(new LevelChunkSection(new BlockState(bloc))));
  return n;}
 public static void main(String[] args)throws Exception{
  var or=new Block("gtceu:gold_ore");
  var premier=niveau(or);var ancienne=new FilonMemoire();ancienne.types.put("gtceu:gold_ore",3);
  Filons.preparer(premier,ancienne);
  assert Filons.monde().type(1,1,1)==3&&Filons.monde().type(2,2,2)==3&&ancienne.lectures==1;
  assert !Filons.monde().sansMinerai(0,0,0,new FilonMemoire.Filon());
  assert Filons.champ("niveau")==premier&&Filons.champ("chunk")!=null&&Filons.champ("types")==ancienne;
  assert Filons.champ("filonPalette")!=null;
  Filons.oublierMonde();
  for(String nom:List.of("niveau","chunk","types","filonPalette"))assert Filons.champ(nom)==null:nom;
  assert ((Map<?,?>)Filons.champ("typesBlocs")).isEmpty()&&(int)Filons.champ("typesConnus")==-1;
  assert ancienne.types.equals(Map.of("gtceu:gold_ore",3)):"the vein memory itself is kept";
  // The next session reads its own level and memory, as on first use.
  var second=niveau(or);var nouvelle=new FilonMemoire();nouvelle.types.put("gtceu:gold_ore",7);
  Filons.preparer(second,nouvelle);
  assert Filons.monde().type(1,1,1)==7&&nouvelle.lectures==1&&ancienne.lectures==1;
  assert Filons.champ("niveau")==second&&Filons.champ("types")==nouvelle;
  System.out.println("PASS monde");
 }
}
"""


def projection_filons():
    source = (JAVA / "Filons.java").read_text(encoding="utf-8")
    debut = source.index("    static void oublierMonde() {")
    fin = source.index("    // ---------- Rendu ----------")
    return f"""package fr.assistantia;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.registries.ForgeRegistries;
public final class Filons {{
    private static final MondeClient MONDE = new MondeClient();
{source[debut:fin]}
    static ScanFilon.Monde monde() {{ return MONDE; }}
    static void preparer(ClientLevel niveau, FilonMemoire memoire) {{ MONDE.preparer(niveau, memoire); }}
    static Object champ(String nom) throws ReflectiveOperationException {{
        var champ = MondeClient.class.getDeclaredField(nom);
        champ.setAccessible(true);
        return champ.get(MONDE);
    }}
}}
"""


class LogoutWorldReferenceTests(unittest.TestCase):
    def setUp(self):
        self.java, javac = jdk()
        if not self.java or not javac:
            self.skipTest("JDK 17 required")

    def rejouer(self, dossier, classe):
        result = subprocess.run(
            [self.java, "-ea", "-cp", dossier, classe],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def test_handler_is_registered_with_the_client_handlers(self):
        client = (JAVA / "Client.java").read_text(encoding="utf-8")
        self.assertIn("MinecraftForge.EVENT_BUS.register(Deconnexion.class);", client)
        handler = (JAVA / "Deconnexion.java").read_text(encoding="utf-8")
        self.assertRegex(
            handler,
            r"@SubscribeEvent\s+public static void deconnexion\("
            r"ClientPlayerNetworkEvent\.LoggingOut event\)",
        )

    def test_handler_forgets_each_feature_and_emi_ones_only_with_emi(self):
        with tempfile.TemporaryDirectory() as dossier:
            harness = Path(dossier) / "DeconnexionTest.java"
            harness.write_text(HANDLER_HARNESS, encoding="utf-8")
            compiler(dossier, HANDLER_STUBS, JAVA / "Deconnexion.java", harness)
            self.assertIn(
                "PASS deconnexion", self.rejouer(dossier, "fr.assistantia.DeconnexionTest")
            )

    def test_vein_scanner_world_access_is_released_and_rebuilt_as_on_first_use(self):
        with tempfile.TemporaryDirectory() as dossier:
            filons = Path(dossier) / "Filons.java"
            filons.write_text(projection_filons(), encoding="utf-8")
            harness = Path(dossier) / "MondeFilonsTest.java"
            harness.write_text(MONDE_HARNESS, encoding="utf-8")
            compiler(dossier, MONDE_STUBS, filons, harness)
            self.assertIn("PASS monde", self.rejouer(dossier, "fr.assistantia.MondeFilonsTest"))

    def test_only_world_references_are_forgotten(self):
        # Files, settings and vein memory survive: each method clears just the listed fields.
        attendus = {
            "CoffreTampon.java": ("static void oublierMonde()", {"interaction"}),
            "Filons.java": ("static void oublierMonde()", {"MONDE.oublier()"}),
            "compat/emi/EnvoiMachine.java": (
                "public static void oublierMonde()",
                {"REPRISES.clear()", "porteeReprises"},
            ),
            "compat/emi/MachinesEmi.java": (
                "public static void oublierMonde()",
                {"ecran", "plan", "commandeCircuit"},
            ),
            "compat/emi/SuiviEmiEcran.java": (
                "public static void oublierMonde()",
                {"retourRecette", "recetteAffichee"},
            ),
        }
        for fichier, (signature, instructions) in attendus.items():
            with self.subTest(fichier=fichier):
                source = (JAVA / fichier).read_text(encoding="utf-8")
                corps = source[source.index(signature) :]
                corps = corps[corps.index("{") + 1 : corps.index("}")]
                lues = {re.sub(r"\s*=\s*null$", "", i.strip()) for i in corps.split(";")} - {""}
                self.assertEqual(lues, instructions)


if __name__ == "__main__":
    unittest.main()
