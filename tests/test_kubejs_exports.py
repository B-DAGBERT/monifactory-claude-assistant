"""The KubeJS client script writes its frames only when they change, off the game thread.

The Java entry points run against small stubs; the client script is replayed tick by tick
in Node.js with the mod present or absent.
"""

import json
import shutil
import subprocess
import unittest
from pathlib import Path

from tests.test_change_only_exports import JAVA, LOGGER, JavaContract

ROOT = Path(__file__).resolve().parents[1]

EXCHANGE_STUBS = {
    **LOGGER,
    "net.minecraft.nbt.CompoundTag": """
public class CompoundTag {private final java.util.Map<String,String> data=new java.util.HashMap<>();
 public void putString(String k,String v){data.put(k,v);}public String getString(String k){return data.getOrDefault(k,"");}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public final Options options=new Options();
 public static Minecraft getInstance(){return INSTANCE;}public static class Options {public String languageCode="fr_fr";}}
""",
    "net.minecraftforge.fml.loading.FMLPaths": """
public enum FMLPaths {GAMEDIR;public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.game"));}}
""",
}

EXCHANGE_TEST = r"""
package fr.assistantia;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
public class EchangesKubeJSTest {
 static final String W="m1_"+"a".repeat(64),S="1".repeat(32),DIM="minecraft:overworld";
 static class Equipe {private final Map<Long,Long> completed=new HashMap<>(),started=new HashMap<>(),taskProgress=new HashMap<>();}
 static class Inconnue {private final Map<Long,Long> autre=new HashMap<>();}
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static CompoundTag paquet(String... parties){var t=new CompoundTag();for(int i=0;i<parties.length;i++)t.putString("p"+i,parties[i]);return t;}
 public static void main(String[] args)throws Exception{
  Object fichier=new Object();var equipe=new Equipe();
  String q1=EchangesKubeJS.empreinteQuetes(fichier,equipe),p1=EchangesKubeJS.progresQuetes(equipe);
  check(!q1.isEmpty()&&!p1.isEmpty(),"fingerprints readable");
  check(q1.equals(EchangesKubeJS.empreinteQuetes(fichier,equipe))&&p1.equals(EchangesKubeJS.progresQuetes(equipe)),"stable while unchanged");
  equipe.taskProgress.put(7L,3L);
  String p2=EchangesKubeJS.progresQuetes(equipe);
  check(!p2.equals(p1)&&EchangesKubeJS.empreinteQuetes(fichier,equipe).equals(q1),"task progress changes only the progress fingerprint");
  equipe.taskProgress.put(7L,4L);
  check(!EchangesKubeJS.progresQuetes(equipe).equals(p2),"every progress step detected");
  equipe.completed.put(7L,123456L);
  String q2=EchangesKubeJS.empreinteQuetes(fichier,equipe);
  check(!q2.equals(q1),"completion detected");
  equipe.started.put(8L,1L);
  check(!EchangesKubeJS.empreinteQuetes(fichier,equipe).equals(q2),"started quest detected");
  check(!EchangesKubeJS.empreinteQuetes(new Object(),equipe).equals(EchangesKubeJS.empreinteQuetes(fichier,equipe)),"reloaded quest file detected");
  String avant=EchangesKubeJS.empreinteQuetes(fichier,equipe);Minecraft.getInstance().options.languageCode="en_us";
  check(!EchangesKubeJS.empreinteQuetes(fichier,equipe).equals(avant),"language change detected");
  check(EchangesKubeJS.empreinteQuetes(fichier,new Inconnue()).isEmpty()&&EchangesKubeJS.progresQuetes(new Inconnue()).isEmpty(),"unknown FTB structure falls back");
  check(EchangesKubeJS.empreinteQuetes(null,equipe).isEmpty(),"no quest file");
  Path dossier=Path.of(System.getProperty("test.game"),"kubejs","assistant");
  check(EchangesKubeJS.transmettreMonde(paquet("{\"dimension\":\""+DIM+"\",\"t\":5,","\"blocs\":[]}"),2,W,S,DIM),"frame accepted");
  check(Ecritures.attendre(5000),"frame written");
  var monde=JsonParser.parseString(Files.readString(dossier.resolve("monde.json"))).getAsJsonObject();
  check(monde.get("monde_actif").getAsString().equals(W)&&monde.get("session_monde").getAsString().equals(S)&&monde.get("dimension_monde").getAsString().equals(DIM),"frame annotated with the local context");
  check(monde.get("t").getAsInt()==5&&monde.getAsJsonArray("blocs").size()==0,"frame content kept");
  check(Files.readString(dossier.resolve("monde.json")).contains("\n\t\"blocs\""),"indented like KubeJS");
  check(EchangesKubeJS.recu(W,S,DIM)>0&&EchangesKubeJS.recu(W,"2".repeat(32),DIM)==0,"reception keyed by context");
  String ecrit=Files.readString(dossier.resolve("monde.json"));
  check(EchangesKubeJS.transmettreMonde(paquet("{\"dimension\":\"minecraft:the_nether\",\"t\":6}"),1,W,S,DIM),"queued");
  check(EchangesKubeJS.transmettreMonde(paquet("{\"dimension\":{\"x\":1},\"t\":6}"),1,W,S,DIM),"queued");
  check(Ecritures.attendre(5000)&&Files.readString(dossier.resolve("monde.json")).equals(ecrit),"frame of another dimension never written");
  check(EchangesKubeJS.erreurMonde().isEmpty(),"a dropped frame is not an error");
  check(EchangesKubeJS.transmettreMonde(paquet("{oops"),1,W,S,DIM),"queued");
  check(Ecritures.attendre(5000)&&Files.readString(dossier.resolve("monde.json")).equals(ecrit),"malformed frame never written");
  String erreur=EchangesKubeJS.erreurMonde();
  check(!erreur.isEmpty()&&EchangesKubeJS.erreurMonde().isEmpty(),"malformed frame reported once");
  check(EchangesKubeJS.transmettreMonde(paquet("{\"dimension\":\"x:y\",\"t\":7}"),1,"","",""),"legacy frame");
  check(Ecritures.attendre(5000),"legacy written");
  var ancien=JsonParser.parseString(Files.readString(dossier.resolve("monde.json"))).getAsJsonObject();
  check(ancien.get("t").getAsInt()==7&&!ancien.has("monde_actif"),"legacy frame written without annotation");
  check(EchangesKubeJS.recu("","","")>0,"legacy reception");
  check(!EchangesKubeJS.transmettreMonde(paquet("{}"),0,W,S,DIM)&&!EchangesKubeJS.transmettreMonde(paquet("{}"),31,W,S,DIM)&&!EchangesKubeJS.transmettreMonde(null,1,W,S,DIM),"part count bounds");
  check(EchangesKubeJS.publier("etat.json","{\"t\":1}")&&EchangesKubeJS.publier("quetes.json","{}"),"script frames accepted");
  check(!EchangesKubeJS.publier("../etat.json","{}")&&!EchangesKubeJS.publier("stockage.json","{}")&&!EchangesKubeJS.publier("etat.json",null),"only the two script frames");
  check(Ecritures.attendre(5000)&&Files.readString(dossier.resolve("etat.json")).equals("{\"t\":1}"),"frame text written as given");
  try(var fichiers=Files.list(dossier)){check(fichiers.noneMatch(f->f.toString().endsWith(".tmp")),"no temporary file left");}
  System.out.println("EchangesKubeJS OK");
 }
}
"""


MEMORY_TEST = r"""
package fr.assistantia;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class MemoireMondeKubeJSTest {
 static final String W="m1_"+"c".repeat(64),W2="m1_"+"d".repeat(64);
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static JsonObject coffres(int n){var j=new JsonObject();var a=new JsonArray();var c=new JsonObject();
  c.addProperty("v",2);c.addProperty("cle","k");c.addProperty("n",n);a.add(c);j.add("conteneurs",a);j.add("machines",new JsonArray());return j;}
 static int n(Map<?,?> m){return ((Number)((Map<?,?>)((List<?>)m.get("conteneurs")).get(0)).get("n")).intValue();}
 // Holds the single writer thread, so that the following writes stay queued.
 static CountDownLatch bloquer(Path ailleurs)throws Exception{
  var latch=new CountDownLatch(1);var entre=new CountDownLatch(1);
  Ecritures.publier(ailleurs,new Ecritures.Contenu(){protected byte[] produire()throws java.io.IOException{
   entre.countDown();try{latch.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}return "{}".getBytes();}});
  check(entre.await(5,TimeUnit.SECONDS),"writer thread held");return latch;}
 interface Appel{void run()throws Exception;}
 static void erreur(Appel a,String m)throws Exception{try{a.run();}catch(java.io.IOException attendu){return;}throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  Path racine=Path.of(System.getProperty("test.game"),"kubejs","assistant");
  Path partition=racine.resolve("mondes").resolve(W),stockage=partition.resolve("stockage.json");
  var latch=bloquer(racine.resolve("bloquant.json"));
  long debut=System.nanoTime();
  MemoireMondeKubeJS.ecrire(W,"stockage.json",coffres(1));
  MemoireMondeKubeJS.ecrire(W,"stockage.json",coffres(2));
  check((System.nanoTime()-debut)/1_000_000<1000,"the script never waits for the busy writer");
  check(!Files.exists(stockage),"the call returned before any disk work");
  check(n(MemoireMondeKubeJS.lire(W,"stockage.json"))==2,"a read in the same tick sees the queued content");
  check(Ecritures.enAttente(stockage).estDurable(),"isolated memory is forced to disk");
  latch.countDown();
  check(Ecritures.attendre(5000),"flushed");
  check(Files.isDirectory(partition),"partition prepared by the writer thread");
  String ecrit=Files.readString(stockage);
  check(ecrit.equals(coffres(2).toString()),"latest content, compact as before: "+ecrit);
  check(n(MemoireMondeKubeJS.lire(W,"stockage.json"))==2,"read back from disk");
  latch=bloquer(racine.resolve("bloquant.json"));
  MemoireMondeKubeJS.ecrireHistorique("stockage.json",coffres(3));
  check(!Ecritures.enAttente(racine.resolve("stockage.json")).estDurable(),"legacy memory not forced, like JsonIO.write");
  check(n(MemoireMondeKubeJS.lireHistorique("stockage.json"))==3,"legacy same-tick read");
  latch.countDown();
  check(Ecritures.attendre(5000),"flushed");
  check(Files.readString(racine.resolve("stockage.json")).equals(Ecritures.serialiser(coffres(3),true)),"KubeJS tab layout kept");
  check(n(MemoireMondeKubeJS.lireHistorique("stockage.json"))==3,"legacy read from disk");
  check(MemoireMondeKubeJS.lireHistorique("filons.json")==null,"absent legacy memory is null, like JsonIO.read");
  Files.writeString(racine.resolve("filons.json"),"[]");
  check(MemoireMondeKubeJS.lireHistorique("filons.json")==null,"non-object legacy memory is null");
  for(String nom:new String[]{"etat.json","../stockage.json",null})
   erreur(()->MemoireMondeKubeJS.ecrire(W,nom,coffres(1)),"name refused at once: "+nom);
  erreur(()->MemoireMondeKubeJS.ecrire("m1_bad","stockage.json",coffres(1)),"world id refused at once");
  erreur(()->MemoireMondeKubeJS.ecrireHistorique("quetes.json",coffres(1)),"legacy name refused at once");
  check(Ecritures.attendre(1000),"nothing queued for refused names");
  Path faux=racine.resolve("mondes").resolve(W2);Files.writeString(faux,"not a folder");
  MemoireMondeKubeJS.ecrire(W2,"stockage.json",coffres(7));
  Thread.sleep(300);
  try{MemoireMondeKubeJS.ecrire(W2,"stockage.json",coffres(8));throw new AssertionError("previous failure not reported");}
  catch(java.io.IOException attendu){check(attendu.getMessage().contains("stockage.json"),attendu.getMessage());}
  check(Files.readString(faux).equals("not a folder"),"never written through an invalid partition");
  Files.delete(faux);
  check(Ecritures.attendre(5000),"retried until the partition is valid");
  check(n(MemoireMondeKubeJS.lire(W2,"stockage.json"))==8,"newest data written after recovery");
  MemoireMondeKubeJS.ecrire(W2,"stockage.json",coffres(9));
  check(Ecritures.attendre(5000)&&n(MemoireMondeKubeJS.lire(W2,"stockage.json"))==9,"no error once recovered");
  try(var f=Files.walk(racine)){check(f.noneMatch(p->p.toString().endsWith(".tmp")),"no temporary file left");}
  System.out.println("MemoireMondeKubeJS OK");
 }
}
"""


class KubeJSExchangeTests(JavaContract):
    def test_kubejs_exchanges_fingerprint_quests_and_relay_server_frames_off_thread(self):
        output = self.compile_and_run(
            EXCHANGE_STUBS,
            [str(JAVA / "Ecritures.java"), str(JAVA / "EchangesKubeJS.java")],
            "fr.assistantia.EchangesKubeJSTest",
            extra=[("fr/assistantia/EchangesKubeJSTest.java", EXCHANGE_TEST)],
        )
        self.assertIn("EchangesKubeJS OK", output)

    def test_kubejs_memories_are_queued_forced_in_isolation_and_read_back_from_memory(self):
        output = self.compile_and_run(
            EXCHANGE_STUBS,
            [str(JAVA / "Ecritures.java"), str(JAVA / "MemoireMondeKubeJS.java")],
            "fr.assistantia.MemoireMondeKubeJSTest",
            extra=[("fr/assistantia/MemoireMondeKubeJSTest.java", MEMORY_TEST)],
        )
        self.assertIn("MemoireMondeKubeJS OK", output)


# ---------- KubeJS client script, replayed tick by tick ----------

SCRIPT_PREFIX = r"""
var horloge = 1000000;
Date.now = () => horloge;
var sansMod = SANS_MOD, publies = [], ecritures = [], transmis = [], erreursConsole = [];
var empreinte = 'Q1', progresEmpreinte = 'P1', recuMs = 0, erreurServeur = '';
var appels = {chapitres: 0}, termine = {A: true}, progres = 3;
var runTick = null, recevoir = null, memoires = [];
var memoireStub = {lire: () => null, ecrire: () => {}};
if (MEMOIRE_DIFFEREE) {
  memoireStub.lireHistorique = nom => { memoires.push(['lire', nom]); return null; };
  memoireStub.ecrireHistorique = (nom, json) => {
    memoires.push(['ecrire', nom, JSON.parse(JSON.stringify(json))]);
  };
}
var EchangesStub = {
  publier: (nom, texte) => { publies.push([nom, JSON.parse(texte)]); return true; },
  empreinteQuetes: (f, e) => empreinte, progresQuetes: e => progresEmpreinte,
  recu: () => recuMs,
  erreurMonde: () => { const e = erreurServeur; erreurServeur = ''; return e; },
  transmettreMonde: (d, n, m, s, dim) => { transmis.push([n, m, s, dim]); return true; }
};
function composant(s) { return {getString: () => s}; }
var tache = {getTitle: () => composant('Collect'), getMaxProgress: () => 64, getItemStack: () => null};
function quete(code) {
  return {getCodeString: () => code, getTitle: () => composant('Q' + code), isOptional: () => false,
    getTasksAsList: () => [tache], getRawDescription: () => ['desc'], isVisible: () => true};
}
var equipe = {isCompleted: q => termine[q.getCodeString()] === true, canStartTasks: () => true, getProgress: t => progres};
var chapitre = {getTitle: () => composant('Ch'), getQuests: () => [quete('A'), quete('B')]};
var fichierQuetes = {selfTeamData: equipe, getVisibleChapters: e => { appels.chapitres++; return [chapitre]; }};
var QuestFileStub = {exists: () => true, INSTANCE: fichierQuetes};
var Java = {loadClass: function (name) {
  if (name === 'fr.assistantia.EchangesKubeJS') { if (sansMod) throw Error('absent'); return EchangesStub; }
  if (name === 'dev.ftb.mods.ftbquests.client.ClientQuestFile') return QuestFileStub;
  if (name === 'fr.assistantia.IdentiteMonde') return {isolation: () => false, monde: () => '', session: () => ''};
  if (name === 'fr.assistantia.MemoireMondeKubeJS') return memoireStub;
  if (name.indexOf('dev.emi') === 0) throw Error('no EMI');
  return function () {};
}};
function pile(id, n) { return {id: id, isEmpty: () => n <= 0, getCount: () => n}; }
var vide = pile('minecraft:air', 0);
var poches = []; for (let i = 0; i < 41; i++) poches.push(vide);
poches[0] = pile('minecraft:iron_ingot', 12);
var coffre = []; for (let i = 0; i < 27; i++) coffre.push(vide);
var position = {x: 0}, jour = 1000, visee = null;
var joueur = {
  getName: () => composant('Damien'), getX: () => position.x, getY: () => 64, getZ: () => 0, getYRot: () => 90,
  getMainHandItem: () => poches[0], getHealth: () => 20, getMaxHealth: () => 20,
  getFoodData: () => ({getFoodLevel: () => 20}), isDeadOrDying: () => false,
  getInventory: () => ({getItem: i => poches[i], getContainerSize: () => 41}),
  rayTrace: () => visee, openInventory: {getItems: () => coffre.concat(poches.slice(0, 36))}, sendData: () => {}
};
var Client = {player: joueur, screen: null, font: {width: s => s.length * 6}, options: {hideGui: false},
  level: {dimension: 'minecraft:overworld', getDayTime: () => jour, isRaining: () => false,
    getBlock: (x, y, z) => ({id: 'minecraft:chest'})}};
var JsonIO = {read: () => null, of: v => ({getAsJsonObject: () => v}),
  write: (path, data) => ecritures.push([path, JSON.parse(JSON.stringify(data))]),
  parseRaw: s => { throw Error('fallback parse not expected'); }};
var Platform = {isLoaded: () => true};
var ClientEvents = {tick: fn => { runTick = fn; }, paintScreen: fn => {}};
var NetworkEvents = {dataReceived: (canal, fn) => { recevoir = fn; }};
var console = {error: v => erreursConsole.push(String(v)), warn: () => {}, log: () => {}};
"""

SCRIPT_SUFFIX = r"""
function tick(n) { for (let i = 0; i < (n || 1); i++) { horloge += 50; jour += 1; runTick({}); } }
function compte(liste, nom) { return liste.filter(e => e[0] === nom).length; }
function dernier(liste, nom) { const l = liste.filter(e => e[0] === nom); return l[l.length - 1][1]; }
var r = {};
"""


def run_script(test, scenario, *, without_mod=False, console_errors=(), deferred_memory=False):
    node = shutil.which("node")
    if not node:
        test.skipTest("Node.js requis")
    client = (ROOT / "jeu/assistant_ia.js").read_text(encoding="utf-8")
    source = (
        SCRIPT_PREFIX.replace("SANS_MOD", "true" if without_mod else "false").replace(
            "MEMOIRE_DIFFEREE", "true" if deferred_memory else "false"
        )
        + client
        + SCRIPT_SUFFIX
        + scenario
        + "\nprocess.stdout.write(JSON.stringify({r: r, erreurs: erreursConsole}));"
    )
    result = subprocess.run(
        [node, "-"],
        input=source,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=30,
    )
    test.assertEqual(result.returncode, 0, result.stderr)
    data = json.loads(result.stdout)
    test.assertEqual(data["erreurs"], list(console_errors))
    return data["r"]


class KubeJSChangeOnlyTests(unittest.TestCase):
    def test_state_is_confirmed_every_two_seconds_and_written_on_change(self):
        r = run_script(
            self,
            r"""
        tick(20);
        r.premier = compte(publies, 'etat.json');
        r.heure = dernier(publies, 'etat.json').heure;
        r.date = dernier(publies, 'etat.json').t === horloge;
        tick(20);   // 1 s later: only the day time moved
        r.uneSeconde = compte(publies, 'etat.json');
        tick(20);   // 2 s after the last write
        r.confirmation = compte(publies, 'etat.json');
        r.heureConfirmee = dernier(publies, 'etat.json').heure;
        position.x = 10;
        tick(20);
        r.deplacement = compte(publies, 'etat.json');
        r.x = dernier(publies, 'etat.json').x;
        r.racine = ecritures.filter(e => e[0].indexOf('etat.json') >= 0).length;
        """,
        )
        self.assertEqual(r["premier"], 1)
        self.assertIsNotNone(r["heure"])
        self.assertTrue(r["date"])
        self.assertEqual(r["uneSeconde"], 1, "the day time alone is not a change")
        self.assertEqual(r["confirmation"], 2)
        self.assertGreater(r["heureConfirmee"], r["heure"])
        self.assertEqual(r["deplacement"], 3)
        self.assertEqual(r["x"], 10)
        self.assertEqual(r["racine"], 0, "with the mod, the game thread never writes etat.json")

    def test_quests_are_rewritten_only_when_they_change(self):
        r = run_script(
            self,
            r"""
        tick(40);
        r.premier = compte(publies, 'quetes.json');
        r.lecturesInitiales = appels.chapitres;
        tick(200);  // 10 s: next check, nothing changed
        r.inchange = compte(publies, 'quetes.json');
        r.lecturesInchangees = appels.chapitres;
        progresEmpreinte = 'P2'; progres = 12;
        tick(200);  // task progress alone: rewritten at most every 30 s
        r.progresTot = compte(publies, 'quetes.json');
        tick(400);
        r.progres = compte(publies, 'quetes.json');
        r.valeurProgres = dernier(publies, 'quetes.json').disponibles[0].taches[0].progres;
        empreinte = 'Q2'; termine.B = true;
        tick(20);   // etat.json counts the completed quest within a second
        r.etatTerminees = dernier(publies, 'etat.json').quetes_terminees;
        tick(200);
        r.terminee = compte(publies, 'quetes.json');
        r.titres = dernier(publies, 'quetes.json').terminees_titres;
        """,
        )
        self.assertEqual(r["premier"], 1)
        self.assertEqual(r["lecturesInitiales"], 2, "one count for etat.json, one full read")
        self.assertEqual(r["inchange"], 1)
        self.assertEqual(r["lecturesInchangees"], 2, "unchanged quests are never walked again")
        self.assertEqual(r["progresTot"], 1)
        self.assertEqual(r["progres"], 2)
        self.assertEqual(r["valeurProgres"], 12)
        self.assertEqual(r["etatTerminees"], 2)
        self.assertEqual(r["terminee"], 3)
        self.assertEqual(r["titres"], ["Ch > QA", "Ch > QB"])

    def test_without_mod_script_keeps_direct_writes(self):
        r = run_script(
            self,
            r"""
        tick(240);
        r.quetes = ecritures.filter(e => e[0] === 'kubejs/assistant/quetes.json').length;
        r.etat = ecritures.filter(e => e[0] === 'kubejs/assistant/etat.json').length;
        r.publies = publies.length;
        """,
            without_mod=True,
        )
        self.assertEqual(r["publies"], 0)
        self.assertEqual(
            r["quetes"], 2, "without fingerprints, quests are read every 10 s as before"
        )
        self.assertEqual(r["etat"], 6, "state still written on change or every 2 s")

    def test_chest_memory_is_written_at_most_every_two_seconds_and_on_close(self):
        r = run_script(
            self,
            r"""
        tick(20);
        Client.screen = {getTitle: () => composant('Chest')};
        visee = {block: {id: 'minecraft:chest', x: 1, y: 64, z: 2}};
        var depart = ecritures.length;
        for (let i = 0; i < 24; i++) { coffre[0] = pile('minecraft:coal', i + 1); tick(5); }   // 6 s of sorting
        r.pendant = ecritures.filter(e => e[0] === 'kubejs/assistant/stockage.json').length;
        coffre[0] = pile('minecraft:coal', 99); tick(5);
        Client.screen = null; tick(5);
        r.total = ecritures.filter(e => e[0] === 'kubejs/assistant/stockage.json').length;
        var memoire = dernier(ecritures, 'kubejs/assistant/stockage.json');
        r.contenu = memoire.conteneurs[0].objets;
        """,
        )
        self.assertLessEqual(r["pendant"], 4, "a chest being sorted is not rewritten every 5 ticks")
        self.assertGreaterEqual(r["pendant"], 2)
        self.assertEqual(r["total"], r["pendant"] + 1, "closing the chest writes at once")
        self.assertEqual(r["contenu"], [{"item": "minecraft:coal", "n": 99}])

    def test_chest_memory_is_handed_to_the_mod_writer_without_any_direct_write(self):
        r = run_script(
            self,
            r"""
        tick(20);
        r.lectures = memoires.filter(m => m[0] === 'lire').map(m => m[1]);
        Client.screen = {getTitle: () => composant('Chest')};
        visee = {block: {id: 'minecraft:chest', x: 1, y: 64, z: 2}};
        coffre[0] = pile('minecraft:coal', 4); tick(5);
        Client.screen = null; tick(5);
        var ecrits = memoires.filter(m => m[0] === 'ecrire');
        r.ecrits = ecrits.map(m => m[1]);
        r.objets = ecrits[ecrits.length - 1][2].conteneurs[0].objets;
        r.directes = ecritures.filter(e => e[0].indexOf('stockage.json') >= 0).length;
        """,
            deferred_memory=True,
        )
        self.assertEqual(r["lectures"], ["stockage.json", "filons.json"])
        self.assertEqual(r["ecrits"], ["stockage.json"])
        self.assertEqual(r["objets"], [{"item": "minecraft:coal", "n": 4}])
        self.assertEqual(r["directes"], 0, "the game thread never writes the chest memory")

    def test_server_frame_is_handed_to_the_mod_and_errors_still_reported(self):
        r = run_script(
            self,
            r"""
        tick(20);
        erreurServeur = 'relevé illisible';
        recevoir({data: {getInt: k => 2, getString: k => k === 'p0' ? '{"a":' : '1}'}});
        r.transmis = transmis;
        recuMs = 123456;
        tick(20);
        r.recu = dernier(publies, 'etat.json').serveur_recu;
        r.erreurs = dernier(publies, 'etat.json').erreurs;
        r.monde = ecritures.filter(e => e[0].indexOf('monde.json') >= 0).length;
        """,
            console_errors=["Assistant IA, données du serveur : relevé illisible"],
        )
        self.assertEqual(r["transmis"], [[2, "", "", ""]])
        self.assertEqual(r["recu"], 123456)
        self.assertEqual(r["erreurs"], ["données du serveur : relevé illisible"])
        self.assertEqual(r["monde"], 0, "the game thread neither parses nor writes monde.json")


if __name__ == "__main__":
    unittest.main()
