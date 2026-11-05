"""Run the real Java collection controller against a deterministic client contract."""

import json
import os
import queue
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

STUBS = {
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.event.TickEvent": "public class TickEvent {public enum Phase {START,END} public record ClientTickEvent(Phase phase){public static final Phase END=Phase.END;} }",
    "net.minecraft.network.chat.Component": "public record Component(String value){public static Component literal(String v){return new Component(v);}public String getString(){return value;}}",
    "net.minecraft.resources.ResourceLocation": "public record ResourceLocation(String value){public String toString(){return value;}}",
    "net.minecraft.world.item.ItemStack": "public record ItemStack(String item,int count){public boolean isEmpty(){return count<=0;}public int getCount(){return count;}public String getItem(){return item;}}",
    "net.minecraft.world.inventory.AbstractContainerMenu": "public class AbstractContainerMenu {}",
    "net.minecraft.client.Minecraft": r"""
public class Minecraft {
 private static final Minecraft MC=new Minecraft();public static Minecraft getInstance(){return MC;}
 public Player player=new Player();public Level level=new Level();public Object connection=new Object();public Object getConnection(){return connection;}
 public static class Player {public void displayClientMessage(net.minecraft.network.chat.Component c,boolean overlay){fr.assistantia.Pont.messages.add(c.getString());}}
 public static class Level {public String id="test:world";public Dimension dimension(){return new Dimension(id);}}
 public record Dimension(String id){public net.minecraft.resources.ResourceLocation location(){return new net.minecraft.resources.ResourceLocation(id);}}
}
""",
    "net.minecraftforge.registries.ForgeRegistries": "public class ForgeRegistries {public static final Registry ITEMS=new Registry();public static class Registry {public net.minecraft.resources.ResourceLocation getKey(String id){return new net.minecraft.resources.ResourceLocation(id);}}}",
    "fr.assistantia.InventaireJoueur": "public class InventaireJoueur {public static boolean sync=true;public static java.util.List<net.minecraft.world.item.ItemStack> items=java.util.List.of();public static boolean synchronise(){return sync;}public static java.util.List<net.minecraft.world.item.ItemStack> piles(){return items;}}",
    "fr.assistantia.Filons": 'public class Filons {public static String monde="test:server";public static String monde(){return monde;}public static boolean synchroniserMonde(){return true;}}',
    "fr.assistantia.AideCoffre": "public class AideCoffre {public static void vider(){}}",
    "fr.assistantia.Emi": r"""
public class Emi {public static boolean actif=true,busy;public static java.util.Map<String,Integer> reservations=java.util.Map.of(),preuves=java.util.Map.of();
 public static java.util.List<PlanRetrait.Stock<net.minecraft.world.item.ItemStack>> credits=java.util.List.of();
 public static java.util.Map<String,Integer> outils=java.util.Map.of();public static java.util.Map<String,Integer> outilsMachines(){return outils;}
 public static boolean arbreActif(){return actif;}public static boolean envoiMachineEnCours(){return busy;}
 public static java.util.Map<String,Integer> fabricationsConfirmees(){return preuves;}
 public static java.util.Map<String,Integer> reservationsFabrication(){return reservations;}
 public static java.util.List<PlanRetrait.Stock<net.minecraft.world.item.ItemStack>> creditsFabrication(){return credits;}}
""",
    "fr.assistantia.Pont": r"""
import com.google.gson.*;
public class Pont {
 public static JsonObject request,reply=new JsonObject();public static boolean actif=true,write=true;public static int sends;
 public static java.util.List<String> messages=new java.util.ArrayList<>();
 public record Repere(int x,int y,int z,String dimension,String texte){}
 public static boolean retraitCoffres(){return true;}public static boolean assistantActif(){return actif;}
 public static boolean envoyerRechercheEmi(JsonObject j){sends++;if(write)request=j.deepCopy();return write;}
 public static JsonObject partie(String name){return reply;}
 public static String texte(JsonObject j,String k){return j.has(k)&&!j.get(k).isJsonNull()?j.get(k).getAsString():"";}
 public static double nombre(JsonObject j,String k,double d){return j.has(k)&&!j.get(k).isJsonNull()?j.get(k).getAsDouble():d;}
 public static JsonArray liste(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonArray()?j.getAsJsonArray(k):new JsonArray();}}
""",
    "fr.assistantia.RetraitCoffre": r"""
import java.util.*;
public class RetraitCoffre {public static boolean busy;public static Map<String,Integer> selection=Map.of();
 public record Plan(List<Object> lots,long manquants){}
 public static boolean occupe(){return busy;}public static void arreter(){busy=false;}
 public static Plan planRecherche(net.minecraft.world.inventory.AbstractContainerMenu menu,
 java.util.function.Function<List<PlanRetrait.Stock<String>>,List<PlanRetrait.Prise>> selectionner,
 java.util.function.Function<net.minecraft.world.item.ItemStack,String> id){
 var stock=new ArrayList<PlanRetrait.Stock<String>>();for(var c:Pont.liste(Pont.reply,"coffres"))
  for(var e:c.getAsJsonObject().getAsJsonObject("objets").entrySet())stock.add(new PlanRetrait.Stock<>(e.getKey(),e.getValue().getAsInt(),false));
 var result=new HashMap<String,Integer>();for(var p:selectionner.apply(stock))result.merge(stock.get(p.index()).objet(),p.quantite(),Integer::sum);
 selection=Map.copyOf(result);return new Plan(List.of(),0);}}
""",
}
# The real event exposes phase as a public field.
STUBS["net.minecraftforge.event.TickEvent"] = (
    "public class TickEvent {public enum Phase {START,END} public static class ClientTickEvent {public final Phase phase;public ClientTickEvent(Phase p){phase=p;}}}"
)

HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
public final class ParcoursCoffres {
 static long temps=1_000_000;
 static final List<LotFabrication<String>> lots=new ArrayList<>();
 static final ReceptionManuelle<String> receptions=new ReceptionManuelle<>(String::equals);
 static List<PlanRetrait.Stock<String>> stock(){return InventaireJoueur.items.stream().map(s->new PlanRetrait.Stock<>(s.item(),s.count(),true)).toList();}
 static ArbreCollecte.Noeud arbre(JsonObject j){var children=new ArrayList<ArbreCollecte.Noeud>();for(var n:Pont.liste(j,"enfants"))children.add(arbre(n.getAsJsonObject()));
  var ids=new LinkedHashSet<String>();for(var n:Pont.liste(j,"ids"))ids.add(n.getAsString());return new ArbreCollecte.Noeud(ids,(long)Pont.nombre(j,"quantite",1),(long)Pont.nombre(j,"production",1),j.has("outil")&&j.get("outil").getAsBoolean(),children);}
 static JsonObject appliquer(JsonObject j)throws Exception {
  var mc=Minecraft.getInstance();if(j.has("temps"))temps=j.get("temps").getAsLong();else temps++;
  if(j.has("inventaire")){var items=new ArrayList<ItemStack>();for(var e:j.getAsJsonObject("inventaire").entrySet())if(e.getValue().getAsInt()>0)items.add(new ItemStack(e.getKey(),e.getValue().getAsInt()));InventaireJoueur.items=List.copyOf(items);}
  if(j.has("outils")){var m=new HashMap<String,Integer>();for(var e:j.getAsJsonObject("outils").entrySet())m.put(e.getKey(),e.getValue().getAsInt());Emi.outils=Map.copyOf(m);}
  if(j.has("sync"))InventaireJoueur.sync=j.get("sync").getAsBoolean();
  if(j.has("actif"))Emi.actif=j.get("actif").getAsBoolean();
  if(j.has("dimension"))mc.level.id=j.get("dimension").getAsString();
  if(j.has("monde"))Filons.monde=j.get("monde").getAsString();
  if(j.has("reconnecter")&&j.get("reconnecter").getAsBoolean())mc.connection=new Object();
  if(j.has("assistance"))Pont.actif=j.get("assistance").getAsBoolean();
  if(j.has("ecriture"))Pont.write=j.get("ecriture").getAsBoolean();
  if(j.has("reponse"))Pont.reply=j.getAsJsonObject("reponse");
  if(j.has("lot")){var l=j.getAsJsonObject("lot");lots.add(new LotFabrication<>(List.of(new PlanRetrait.Stock<>(l.get("item").getAsString(),l.get("n").getAsInt(),true)),String::equals));receptions.initialiser(stock());}
  if(InventaireJoueur.sync){var gains=receptions.actualiser(stock(),List.of());ReceptionLots.repartir(gains,lots.stream().map(l->new ReceptionLots.Source<>(l,(java.util.function.Predicate<String>)s->true)).toList());}
  var reserves=new HashMap<String,Integer>();var recus=new ArrayList<PlanRetrait.Stock<String>>();for(var l:lots){for(var s:l.restants())reserves.merge(s.objet(),s.quantite(),Integer::sum);recus.addAll(l.obtenus());}Emi.reservations=Map.copyOf(reserves);
  Emi.credits=ReceptionLots.credits(recus,stock(),List.of(),String::equals).stream().map(s->new PlanRetrait.Stock<>(new ItemStack(s.objet(),s.quantite()),s.quantite(),true)).toList();
  if(j.has("arbre")){receptions.initialiser(stock());RechercheEmi.basculer("assistantia:coffres_arbre",new MemoireCollecte(arbre(j.getAsJsonObject("arbre")),1),s->true);}
  if(j.has("arreter")&&j.get("arreter").getAsBoolean())RechercheEmi.arreter();
  var tick=new TickEvent.ClientTickEvent(TickEvent.Phase.END);RechercheEmi.tick(tick);RechercheEmi.tick(tick);
  RetraitCoffre.selection=Map.of();RechercheEmi.retrait(null);
  var result=new JsonObject();result.addProperty("temps",temps);result.addProperty("actif",RechercheEmi.actif());result.addProperty("envois",Pont.sends);result.add("demande",Pont.request==null?JsonNull.INSTANCE:Pont.request);
  result.add("selection",new Gson().toJsonTree(RetraitCoffre.selection));result.add("restants",new Gson().toJsonTree(reserves));
  var coffres=new JsonArray();for(var r:RechercheEmi.reperes()){var c=new JsonObject();c.addProperty("x",r.x());c.addProperty("jaune",RechercheEmi.resultatPret(r));coffres.add(c);}result.add("coffres",coffres);
  result.add("messages",new Gson().toJsonTree(Pont.messages));return result;
 }
 public static void main(String[] args)throws Exception {
  var f=RechercheEmi.class.getDeclaredField("horloge");f.setAccessible(true);f.set(null,(java.util.function.LongSupplier)()->temps);
  var scanner=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));String line;
  while((line=scanner.readLine())!=null){try{System.out.println(appliquer(JsonParser.parseString(line).getAsJsonObject()));}catch(Throwable e){var j=new JsonObject();j.addProperty("erreur",e.toString());System.out.println(j);}System.out.flush();}
 }
}
"""


class ClientJava:
    def __init__(self, case):
        self.process = subprocess.Popen(
            [case.java, "-ea", "-cp", case.classpath, "fr.assistantia.ParcoursCoffres"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.lines = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.last = None

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def step(self, **event):
        self.process.stdin.write(json.dumps(event) + chr(10))
        self.process.stdin.flush()
        line = self.lines.get(timeout=10)
        if line is None:
            raise AssertionError(self.process.stderr.read())
        self.last = json.loads(line)
        if "erreur" in self.last:
            raise AssertionError(self.last["erreur"])
        return self.last

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process.stdout.close()
        self.process.stderr.close()


class ContratJava:
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not cls.java:
            raise AssertionError("JDK 17 requis pour rejouer les scénarios")
        caches = [
            Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))),
            ROOT / ".runtime/gradle",
        ]
        gson = next(
            (
                jar
                for cache in caches
                for jar in (cache / "caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                    "**/gson-*.jar"
                )
            ),
            None,
        )
        if gson is None:
            raise AssertionError("Gson absent : lance d’abord l’installation du projet")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        directory = Path(cls.temp.name)
        files = []
        for name, body in STUBS.items():
            p = directory / (name.replace(".", "/") + ".java")
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("package " + name.rsplit(".", 1)[0] + ";" + chr(10) + body)
            files.append(str(p))
        harness = directory / "ParcoursCoffres.java"
        harness.write_text(HARNESS)
        names = [
            "RechercheEmi",
            "MemoireCollecte",
            "ArbreCollecte",
            "PlanArbre",
            "PlanRetrait",
            "LotFabrication",
            "ReceptionLots",
            "ReceptionManuelle",
        ]
        sources = [ROOT / f"mod/src/main/java/fr/assistantia/{n}.java" for n in names]
        result = subprocess.run(
            [
                javac,
                "-encoding",
                "UTF-8",
                "-cp",
                str(gson),
                "-d",
                str(directory),
                *files,
                *map(str, sources),
                str(harness),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.classpath = os.pathsep.join((str(directory), str(gson)))
