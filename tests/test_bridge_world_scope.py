"""Real Java bridge I/O against synthetic worlds: no game, network or personal files."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "fr.assistantia.IdentiteMonde": """public class IdentiteMonde {
 public static boolean isole; public static String monde="",session="";
 public static boolean isolation(){return isole;}public static String monde(){return monde;}
 public static String session(){return session;}}""",
    "net.minecraft.client.Minecraft": """public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();public Level level=new Level();
 public static Minecraft getInstance(){return INSTANCE;}
 public static class Level {public String dim="minecraft:overworld";
 public Dimension dimension(){return new Dimension(new net.minecraft.resources.ResourceLocation(dim));}}
 public record Dimension(net.minecraft.resources.ResourceLocation location){}}""",
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String value){
 public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}
 public String toString(){return value;}}""",
    "net.minecraft.world.item.Item": "public class Item {}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
 public static ItemStack EMPTY=new ItemStack(null);public ItemStack(Item i){}}""",
    "net.minecraftforge.registries.ForgeRegistries": """public class ForgeRegistries {
 public static final Items ITEMS=new Items();public static class Items {
 public net.minecraft.world.item.Item getValue(net.minecraft.resources.ResourceLocation r){return null;}}}""",
    "net.minecraftforge.fml.loading.FMLPaths": """public class FMLPaths {
 public static final Holder GAMEDIR=new Holder();public static class Holder {
 public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.game"));}}}""",
    "org.slf4j.Logger": "public class Logger {public void warn(String s,Object... args){}}",
    "com.mojang.logging.LogUtils": """public class LogUtils {
 public static org.slf4j.Logger getLogger(){return new org.slf4j.Logger();}}""",
}

HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import net.minecraft.client.Minecraft;
public class BridgeWorldScopeTest {
 static Path root;
 static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
 // Periodic exports are written on the mod's writer thread: wait for them before reading.
 static JsonObject read(String s)throws Exception{assert Ecritures.attendre(5000);return json(Files.readString(root.resolve(s)));}
 static void context(String world,String session,String dimension){
  IdentiteMonde.isole=true;IdentiteMonde.monde=world;IdentiteMonde.session=session;
  Minecraft.getInstance().level=new Minecraft.Level();Minecraft.getInstance().level.dim=dimension;
 }
 static JsonObject view(String title){
  JsonObject j=json("{\"besoins\":[\"minecraft:iron_ingot\"],\"recherche\":{\"objets\":[\"minecraft:*\"],\"coffres\":[{\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\",\"texte\":\"coffre\"}]},\"guidage\":[{\"x\":1,\"y\":2,\"z\":3}],\"ecran\":{\"demandes\":{\"protocole\":2}}}");
  j.addProperty("title",title);assert Pont.annoterContexte(j);return j;
 }
 static void publish(JsonObject j,long time)throws Exception{
  Files.writeString(root.resolve("vue_mod.json"),j.toString());
  Files.setLastModifiedTime(root.resolve("vue_mod.json"),FileTime.fromMillis(time));Pont.rafraichir();
 }
 static void empty(){
  assert Pont.vue().size()==0;assert Pont.partie("ecran").size()==0;
  assert Pont.besoins().isEmpty()&&Pont.cibles().isEmpty()&&Pont.reperes().isEmpty();
  assert !Pont.rechercheActive()&&!Pont.cherche("minecraft:iron_ingot")&&!Pont.assistantActif();
 }
 public static void main(String[] args)throws Exception{
  root=Path.of(System.getProperty("test.game")).resolve("kubejs/assistant");Files.createDirectories(root);
  String a="m1_"+"a".repeat(64),b="m1_"+"b".repeat(64),s1="1".repeat(32),s2="2".repeat(32);
  String dim="minecraft:overworld";long time=System.currentTimeMillis();
  if(args[0].equals("legacy")){
   publish(view("legacy"),time);assert Pont.vue().get("title").getAsString().equals("legacy");
   var request=json("{\"type\":\"question\"}");assert Pont.envoyerInventaire(request);
   assert !read("inventaire_joueur.json").has("monde_actif");
   Pont.ecrireTexte("inventaire_details.txt",List.of("ancien"));assert Files.readString(root.resolve("inventaire_details.txt")).contains("ancien");return;
  }
  if(args[0].equals("publication")){
   Path legacy=root.resolve("inventaire_details.txt");
   Files.writeString(legacy,"legacy intact");
   Path tmp=root.resolve("inventaire_details.txt.tmp");Files.createDirectory(tmp);
   assert !Pont.ecrireTexte("inventaire_details.txt",List.of("nouveau"));
   assert Files.readString(legacy).equals("legacy intact");Files.delete(tmp);
   assert Pont.ecrireTexte("inventaire_details.txt",List.of("nouveau"));
   assert Files.readString(legacy).contains("nouveau");
   context(a,s1,dim);
   String prefix="mondes/"+a+"/";Files.createDirectories(root.resolve(prefix));
   Path metadataTmp=root.resolve(prefix+"inventaire_details_contexte.json.tmp");
   Files.createDirectory(metadataTmp);
   assert !Pont.ecrireTexte("inventaire_details.txt",List.of("fer x2"));
   assert Files.readString(root.resolve(prefix+"inventaire_details.txt")).contains("fer x2");
   assert !Files.exists(root.resolve(prefix+"inventaire_details_contexte.json"));
   Files.delete(metadataTmp);
   assert Pont.ecrireTexte("inventaire_details.txt",List.of("fer x2"));
   assert Pont.contexteValide(read(prefix+"inventaire_details_contexte.json"));
   context("","",dim);
   assert !Pont.ecrireTexte("inventaire_details.txt",List.of("inconnu"));return;
  }
  context(a,s1,dim);
  if(args[0].equals("transition")){
   var old=view("A");publish(old,time);assert Pont.cherche("minecraft:iron_ingot");assert Pont.assistantActif();
   context(b,s2,dim);empty();Pont.rafraichir();empty(); // old file, unchanged mtime
   publish(view("B"),time);assert Pont.vue().get("title").getAsString().equals("B");
   publish(old,time+1);assert Pont.vue().get("title").getAsString().equals("B"); // late response cannot replace current
   context(b,s1,dim);empty();publish(view("reconnect"),time+2);assert Pont.assistantActif();
   Minecraft.getInstance().level.dim="minecraft:the_nether";empty();Pont.rafraichir();empty();
   publish(view("nether"),time+2);assert Pont.vue().get("title").getAsString().equals("nether");
   Minecraft.getInstance().level=null;empty();return;
  }
  if(args[0].equals("channels")){
   assert Pont.envoyerInventaire(new JsonObject());
   assert Pont.envoyerConfigurationIa(new JsonObject());assert Pont.envoyerRechercheEmi(new JsonObject());
   assert Pont.envoyer(json("{\"type\":\"question\"}"));
   for(String name:List.of("inventaire_joueur.json","configuration_ia.json","recherche_emi.json","demande_mod.json"))assert Pont.contexteValide(read(name)):name;
   assert !read("demande_mod.json").has("projet")&&read("demande_mod.json").has("dimension_envoi"):"no project binding, dimension kept";
   assert Pont.envoyer(json("{\"type\":\"production_v2\"}"));assert !read("demande_mod.json").has("dimension_envoi"):"production keeps its own binding";
   publish(view("queue"),time);assert Pont.envoyer(json("{\"type\":\"question\"}"));
   try(var paths=Files.list(root.resolve("demandes"))){var files=paths.toList();assert files.size()==1;assert Pont.contexteValide(json(Files.readString(files.get(0))));}
   var late=read("demande_mod.json");var bytes=Files.readAllBytes(root.resolve("demande_mod.json"));context(b,s2,dim);
   assert !Pont.envoyer(late);assert late.get("monde_actif").getAsString().equals(a);
   assert Arrays.equals(bytes,Files.readAllBytes(root.resolve("demande_mod.json")));return;
  }
  if(args[0].equals("unknown")){
   context("","",dim);assert !Pont.annoterContexte(new JsonObject());assert !Pont.envoyer(new JsonObject());
   assert !Pont.envoyerInventaire(new JsonObject());assert !Pont.envoyerRechercheEmi(new JsonObject());
   Pont.ecrireTexte("inventaire_details.txt",List.of("secret"));assert !Files.exists(root.resolve("mondes"));
   Files.writeString(root.resolve("vue_mod.json"),"{\"ecran\":{\"objectif\":\"ancien\"}}");Pont.rafraichir();empty();
   context(a,s1,dim);assert !Pont.contexteValide(json("{\"monde_actif\":true,\"session_monde\":[],\"dimension_monde\":{}}"));
   var partial=new JsonObject();partial.addProperty("monde_actif",a);assert !Pont.annoterContexte(partial);return;
  }
  if(args[0].equals("details")){
   Files.writeString(root.resolve("inventaire_details.txt"),"legacy exact\r\n");
   Pont.ecrireTexte("inventaire_details.txt",List.of("équipement","fer x2"));
   String prefix="mondes/"+a+"/";var metadata=read(prefix+"inventaire_details_contexte.json");assert Pont.contexteValide(metadata);
   byte[] bytes=Files.readAllBytes(root.resolve(prefix+"inventaire_details.txt"));
   assert metadata.get("sha256").getAsString().equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
   assert Files.readString(root.resolve("inventaire_details.txt")).equals("legacy exact\r\n");
   context(a,s2,dim);assert !Pont.contexteValide(metadata);Pont.ecrireTexte("inventaire_details.txt",List.of("équipement","fer x2"));
   assert Pont.contexteValide(read(prefix+"inventaire_details_contexte.json"));
   context(b,s1,dim);Pont.ecrireTexte("inventaire_details.txt",List.of("or"));
   assert Arrays.equals(bytes,Files.readAllBytes(root.resolve(prefix+"inventaire_details.txt")));
   Pont.ecrireTexte("stats_objets.txt",List.of("catalogue"));assert Files.exists(root.resolve("stats_objets.txt"));return;
  }
  throw new AssertionError(args[0]);
 }
}
"""


class BridgeWorldScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        cls.javac = (
            str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        )
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "*/*/*.jar"
            ),
            None,
        )
        if not cls.java or not cls.javac or gson is None:
            raise unittest.SkipTest("JDK and Gson cache required")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        sources = []
        for name, body in STUBS.items():
            source = cls.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}")
            sources.append(source)
        harness = cls.directory / "BridgeWorldScopeTest.java"
        harness.write_text(HARNESS)
        cls.classpath = str(cls.directory) + os.pathsep + str(gson)
        result = subprocess.run(
            [
                cls.javac,
                "-encoding",
                "UTF-8",
                "-cp",
                cls.classpath,
                "-d",
                str(cls.directory),
                *map(str, sources),
                str(ROOT / "mod/src/main/java/fr/assistantia/Pont.java"),
                str(ROOT / "mod/src/main/java/fr/assistantia/FileDemandes.java"),
                str(ROOT / "mod/src/main/java/fr/assistantia/Ecritures.java"),
                str(harness),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [
                    self.java,
                    "-ea",
                    f"-Dtest.game={directory}",
                    "-cp",
                    self.classpath,
                    "fr.assistantia.BridgeWorldScopeTest",
                    case,
                ],
                capture_output=True,
                text=True,
                timeout=30,
            )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_legacy_mode_preserves_existing_channels(self):
        self.run_case("legacy")

    def test_world_reconnect_dimension_and_late_view_with_equal_mtime(self):
        self.run_case("transition")

    def test_all_channels_queue_and_old_request_retry_keep_ownership(self):
        self.run_case("channels")

    def test_unknown_context_and_malformed_envelope_fail_closed(self):
        self.run_case("unknown")

    def test_scoped_inventory_text_hash_reconnect_and_legacy_preservation(self):
        self.run_case("details")

    def test_text_publication_acknowledges_both_files_and_retries_after_io_failure(self):
        self.run_case("publication")
