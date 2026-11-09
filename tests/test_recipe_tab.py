"""Onglet Recette : moteur pur (chaînes, comparaison, rangs, « facile »), écran à 320 × 240
avec des stubs Minecraft, et lecture de la mémoire des coffres. Aucun jeu ni EMI réel."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOD = ROOT / "mod/src/main/java/fr/assistantia"


def jdk():
    home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
    java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
    javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
    return java, javac


def gson_jar():
    return next(
        iter(
            sorted(
                (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                    "**/gson-*.jar"
                )
            )
        ),
        None,
    )


def compile_sources(javac, directory, stubs, sources, classpath=None):
    files = []
    for name, body in stubs.items():
        path = Path(directory) / (name.replace(".", "/") + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
        files.append(str(path))
    command = [javac, "-encoding", "UTF-8", "-d", str(directory)]
    if classpath:
        command += ["-cp", classpath]
    result = subprocess.run(
        [*command, *files, *map(str, sources)], capture_output=True, text=True, timeout=120
    )
    if result.returncode:
        raise AssertionError(result.stderr)


class RecipeEngineTests(unittest.TestCase):
    """Contrats purs : aucune classe Minecraft ni EMI n'est compilée."""

    @classmethod
    def setUpClass(cls):
        java, javac = jdk()
        if not java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        sources = [
            MOD / f"{name}.java" for name in ("PlanRetrait", "PlanMachines", "OrdreMachines")
        ]
        sources += [
            MOD / "compat/emi" / f"{name}.java"
            for name in (
                "ChaineRecette",
                "ComparaisonAlternatives",
                "RangsRecette",
                "FabricationFacile",
                "SelectionAlternatives",
            )
        ]
        sources.append(ROOT / "tests/java/fr/assistantia/compat/emi/RecetteMoteurTest.java")
        compile_sources(javac, cls.temp.name, {}, sources)
        cls.command = [
            java,
            "-ea",
            "-cp",
            cls.temp.name,
            "fr.assistantia.compat.emi.RecetteMoteurTest",
        ]

    def run_case(self, case):
        result = subprocess.run([*self.command, case], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS " + case, result.stdout)

    def test_iron_plate_bender_beats_hammer_crafting(self):
        self.run_case("plaque")

    def test_bronze_plates_reference_costs(self):
        self.run_case("bronze")

    def test_twelve_hand_steps_replaced_by_two(self):
        self.run_case("douze")

    def test_cycles_cut_like_emi_without_corrupting_memo(self):
        self.run_case("cycle")

    def test_deep_chains_are_bounded_and_incomparable(self):
        self.run_case("budget")

    def test_tag_resolution_is_not_a_step(self):
        self.run_case("transparente")

    def test_fluids_stay_in_millibuckets(self):
        self.run_case("fluide")

    def test_strictly_better_rules(self):
        self.run_case("comparaison")

    def test_french_number_formatting(self):
        self.run_case("nombres")

    def test_plan_numbering_states_and_lots(self):
        self.run_case("rangs")

    def test_world_ranks_star_dimming_and_cap(self):
        self.run_case("reperes")

    def test_real_ordering_feeds_plan_ranks(self):
        self.run_case("ordre")

    def test_easy_machine_definition_is_conservative(self):
        self.run_case("facile")

    def test_root_goal_is_analysed_and_bender_found(self):
        self.run_case("racine")


# Reproduction fidèle (javap d'emi-1.1.22) de MaterialTree/MaterialNode : addResolution sur la clé
# du but appelle goal.defineRecipe puis remet goal.amount à la quantité de la clé (1) ; la quantité
# visée vaut batches × goal.amount (TreeCost.calculate).
ROOT_STUBS = {
    "dev.emi.emi.api.stack.EmiIngredient": """public interface EmiIngredient {long getAmount();java.util.List<EmiStack> getEmiStacks();}""",
    "dev.emi.emi.api.stack.EmiStack": """public final class EmiStack implements EmiIngredient {public final String id;public final long amount;
 public EmiStack(String id,long amount){this.id=id;this.amount=amount;}public long getAmount(){return amount;}
 public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}public EmiStack withAmount(long n){return new EmiStack(id,n);}
 public boolean equals(Object o){return o instanceof EmiStack s&&s.id.equals(id);}public int hashCode(){return id.hashCode();}public String toString(){return id;}}""",
    "dev.emi.emi.api.recipe.EmiRecipe": """public interface EmiRecipe {java.util.List<dev.emi.emi.api.stack.EmiStack> getOutputs();java.util.List<dev.emi.emi.api.stack.EmiIngredient> getInputs();}""",
    "dev.emi.emi.api.recipe.EmiResolutionRecipe": """public class EmiResolutionRecipe implements EmiRecipe {
 public java.util.List<dev.emi.emi.api.stack.EmiStack> getOutputs(){return java.util.List.of();}public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getInputs(){return java.util.List.of();}}""",
    "dev.emi.emi.bom.BoM": """import dev.emi.emi.api.recipe.EmiRecipe;import dev.emi.emi.api.stack.EmiIngredient;
public class BoM {public static java.util.Set<EmiRecipe> disabledRecipes=new java.util.HashSet<>();public static java.util.Map<EmiIngredient,EmiRecipe> defaultRecipes=new java.util.HashMap<>();
 public static EmiRecipe getRecipe(EmiIngredient i){return defaultRecipes.get(i);}}""",
    "dev.emi.emi.bom.MaterialNode": """import dev.emi.emi.api.recipe.EmiRecipe;import dev.emi.emi.api.stack.*;import java.util.*;
public class MaterialNode {public final EmiIngredient ingredient;public EmiRecipe recipe;public List<MaterialNode> children=new ArrayList<>();
 public long amount;public long divisor=1;public boolean catalyst;public float consumeChance=1;
 public MaterialNode(EmiIngredient i){amount=i.getAmount();ingredient=((EmiStack)i).withAmount(1);}
 public void defineRecipe(EmiRecipe r){if(r==null)return;recipe=r;divisor=0;for(EmiStack o:r.getOutputs())if(o.equals(ingredient))divisor+=o.getAmount();
  if(divisor<=0)divisor=1;children=new ArrayList<>();for(EmiIngredient in:r.getInputs())children.add(new MaterialNode(in));}
 void recalculate(MaterialTree tree,List<EmiRecipe> used){EmiRecipe r=recipe;if(!used.isEmpty())r=tree.getRecipe(ingredient);if(r==null||used.contains(r))return;
  used.add(r);defineRecipe(r);for(MaterialNode c:children)c.recalculate(tree,used);used.remove(used.size()-1);}}""",
    "dev.emi.emi.bom.MaterialTree": """import dev.emi.emi.api.recipe.EmiRecipe;import dev.emi.emi.api.stack.*;import java.util.*;
public class MaterialTree {public MaterialNode goal;public long batches=1;public Map<EmiIngredient,EmiRecipe> resolutions=new HashMap<>();public int couts;
 public MaterialTree(EmiRecipe r){goal=new MaterialNode(r.getOutputs().get(0));goal.defineRecipe(r);recalculate();}
 public EmiRecipe getRecipe(EmiIngredient i){EmiRecipe r=resolutions.get(i);if(r==null&&!resolutions.containsKey(i))r=BoM.getRecipe(i);return r;}
 public void addResolution(EmiIngredient i,EmiRecipe r){resolutions.put(i,r);if(i.equals(goal.ingredient)){goal.defineRecipe(r);goal.amount=i.getAmount();}recalculate();}
 public void recalculate(){goal.recalculate(this,new ArrayList<>());}public void calculateCost(){couts++;}}""",
    "dev.emi.emi.screen.BoMScreen": "public class BoMScreen {public int recalculs;public void recalculateTree(){recalculs++;}}",
    "net.minecraft.client.Minecraft": """public class Minecraft {private static final Minecraft I=new Minecraft();public static Minecraft getInstance(){return I;}public Object screen;}""",
    "fr.assistantia.compat.emi.ComparaisonEmi": """public final class ComparaisonEmi {public static boolean circuitVirtuel(dev.emi.emi.api.recipe.EmiRecipe p,dev.emi.emi.api.stack.EmiIngredient i,boolean c){return false;}}""",
    "fr.assistantia.compat.emi.InventaireArbreEmi": "public final class InventaireArbreEmi {public static int invalidations;public static void invalider(){invalidations++;}}",
}

ROOT_HARNESS = """package fr.assistantia.compat.emi;
import dev.emi.emi.api.recipe.EmiRecipe;import dev.emi.emi.api.stack.*;import dev.emi.emi.bom.*;import dev.emi.emi.screen.BoMScreen;
import java.util.List;import net.minecraft.client.Minecraft;
public class ArbreRecetteRacineTest {
 record R(String id,List<EmiStack> getOutputs,List<EmiIngredient> getInputs) implements EmiRecipe {}
 static EmiStack s(String id,long n){return new EmiStack(id,n);}
 public static void main(String[] a)throws Exception{
  var marteau=new R("forge_hammer_plate",List.of(s("plate",2)),List.of(s("ingot",3)));
  var bender=new R("bend_plate",List.of(s("plate",1)),List.of(s("ingot",1)));
  var etabli=new R("craft_plate",List.of(s("plate",1)),List.of(s("ingot",2),s("hammer_tool",1)));
  switch(a[0]){
   case "quantite"->{
    var arbre=new MaterialTree(marteau);arbre.batches=4;var ecran=new BoMScreen();Minecraft.getInstance().screen=ecran;
    assert ArbreRecette.recetteRacine(arbre)==marteau;
    var avant=ArbreRecette.resoudreRacine(arbre,bender);
    assert arbre.goal.recipe==bender&&arbre.goal.divisor==1&&arbre.goal.children.size()==1;
    assert arbre.goal.amount==2&&arbre.batches==4:"target quantity kept: 4 batches x 2 = 8 plates";
    assert arbre.resolutions.get(arbre.goal.ingredient)==bender:"tree-local resolution";
    assert BoM.defaultRecipes.isEmpty()&&BoM.disabledRecipes.isEmpty():"EMI defaults untouched";
    assert arbre.couts==1&&ecran.recalculs==1&&InventaireArbreEmi.invalidations==1:"costs and open Recipe Tree refreshed";
    assert avant.recette()==marteau&&avant.quantite()==2&&avant.lots()==4&&!avant.avait()&&avant.resolution()==null;
    var natif=new MaterialTree(marteau);natif.batches=4;natif.addResolution(natif.goal.ingredient,bender);
    assert natif.goal.amount*natif.batches==4:"witness: the bare native path would halve the target";
   }
   case "etabli"->{
    var arbre=new MaterialTree(etabli);arbre.batches=8;
    var avant=ArbreRecette.resoudreRacine(arbre,bender);
    assert arbre.goal.amount*arbre.batches==8&&arbre.goal.recipe==bender;
    ArbreRecette.restaurerRacine(arbre,avant);
    assert arbre.goal.recipe==etabli&&arbre.goal.amount==1&&arbre.batches==8:"exact previous root";
    assert !arbre.resolutions.containsKey(arbre.goal.ingredient):"no leftover resolution";
    assert arbre.goal.children.size()==2:"old recipe inputs rebuilt";
   }
   case "lots-joueur"->{
    var arbre=new MaterialTree(marteau);arbre.batches=4;var avant=ArbreRecette.resoudreRacine(arbre,bender);
    arbre.batches=10;ArbreRecette.restaurerRacine(arbre,avant);
    assert arbre.goal.recipe==marteau&&arbre.goal.amount==2&&arbre.batches==10:"undo keeps a later batch choice";
   }
   case "resolution"->{
    var arbre=new MaterialTree(etabli);arbre.resolutions.put(arbre.goal.ingredient,etabli);
    var avant=ArbreRecette.resoudreRacine(arbre,bender);
    assert avant.avait()&&avant.resolution()==etabli;
    ArbreRecette.restaurerRacine(arbre,avant);
    assert arbre.resolutions.get(arbre.goal.ingredient)==etabli&&arbre.goal.recipe==etabli:"previous local resolution restored";
   }
   case "lecture"->{
    var arbre=new MaterialTree(marteau);arbre.batches=4;var lecture=ArbreRecette.lire(arbre);var racine=lecture.noeuds().get(0);
    assert racine.profondeur()==0&&racine.recette()==marteau&&racine.besoin()==8&&racine.production()==2:"root read with batches x amount";
    assert lecture.noeuds().get(1).besoin()==12:"3 ingots x 4 crafts";
   }
   default->throw new AssertionError(a[0]);
  }
  System.out.println("PASS "+a[0]);
 }
}
"""


class RootResolutionTests(unittest.TestCase):
    """Le vrai ArbreRecette sur un MaterialTree reproduit : la racine change sans perdre sa quantité."""

    @classmethod
    def setUpClass(cls):
        java, javac = jdk()
        if not java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        harness = Path(cls.temp.name) / "ArbreRecetteRacineTest.java"
        harness.write_text(ROOT_HARNESS, encoding="utf-8")
        compile_sources(
            javac, cls.temp.name, ROOT_STUBS, [MOD / "compat/emi/ArbreRecette.java", harness]
        )
        cls.command = [
            java,
            "-ea",
            "-cp",
            cls.temp.name,
            "fr.assistantia.compat.emi.ArbreRecetteRacineTest",
        ]

    def run_case(self, case):
        result = subprocess.run([*self.command, case], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS " + case, result.stdout)

    def test_apply_keeps_target_quantity_and_stays_tree_local(self):
        self.run_case("quantite")

    def test_undo_restores_previous_root_exactly(self):
        for case in ("etabli", "resolution", "lots-joueur"):
            with self.subTest(case=case):
                self.run_case(case)

    def test_root_is_read_with_batches_times_amount(self):
        self.run_case("lecture")


UI_STUBS = {
    "net.minecraft.network.chat.Style": "public class Style {public static final Style EMPTY=new Style();}",
    "net.minecraft.util.FormattedCharSink": "public interface FormattedCharSink {boolean accept(int i,net.minecraft.network.chat.Style s,int c);}",
    "net.minecraft.util.FormattedCharSequence": """public record FormattedCharSequence(String text){
 public boolean accept(FormattedCharSink sink){for(int i=0;i<text.length();i++)if(!sink.accept(i,net.minecraft.network.chat.Style.EMPTY,text.charAt(i)))return false;return true;}}""",
    "net.minecraft.network.chat.Component": """public record Component(String text){public static Component literal(String t){return new Component(t);}public String getString(){return text;}}""",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {public static final ItemStack EMPTY=new ItemStack("");public final String id;
 public ItemStack(String id){this.id=id;}public boolean isEmpty(){return id.isEmpty();}}""",
    "net.minecraft.world.entity.player.Player": "public class Player {}",
    "net.minecraft.client.gui.Font": """import net.minecraft.network.chat.Component;import net.minecraft.util.FormattedCharSequence;
public class Font {public int width(String s){return s==null?0:s.length()*6;}
 public String plainSubstrByWidth(String s,int w){return s.substring(0,Math.max(0,Math.min(s.length(),w/6)));}
 public java.util.List<FormattedCharSequence> split(Component c,int w){var out=new java.util.ArrayList<FormattedCharSequence>();String t=c.getString();int n=Math.max(1,w/6);
  for(int i=0;i<t.length();i+=n)out.add(new FormattedCharSequence(t.substring(i,Math.min(t.length(),i+n))));if(out.isEmpty())out.add(new FormattedCharSequence(""));return out;}}""",
    "net.minecraft.client.gui.GuiGraphics": """import net.minecraft.util.FormattedCharSequence;
public class GuiGraphics {public record Texte(String texte,int x,int y,int couleur,boolean clippe){}
 public final java.util.List<Texte> textes=new java.util.ArrayList<>();public final java.util.List<int[]> items=new java.util.ArrayList<>();
 public int scissors;public boolean clip;
 public void fill(int x,int y,int r,int b,int c){if(r<x||b<y)throw new AssertionError("negative rectangle");}
 public void renderOutline(int x,int y,int w,int h,int c){if(w<0||h<0)throw new AssertionError("negative outline");}
 public int drawString(Font f,String t,int x,int y,int c,boolean s){textes.add(new Texte(t,x,y,c,clip));return x+f.width(t);}
 public int drawString(Font f,FormattedCharSequence t,int x,int y,int c,boolean s){textes.add(new Texte(t.text(),x,y,c,clip));return x;}
 public void renderItem(net.minecraft.world.item.ItemStack i,int x,int y){items.add(new int[]{x,y});}
 public void enableScissor(int a,int b,int c,int d){if(clip)throw new AssertionError("nested scissor");clip=true;scissors++;}
 public void disableScissor(){clip=false;}}""",
    "net.minecraft.client.gui.components.AbstractWidget": "public class AbstractWidget {}",
    "net.minecraft.client.gui.screens.Screen": """public class Screen {public int width=320,height=240;public java.util.List<?> tooltip;
 public void setTooltipForNextRenderPass(java.util.List<net.minecraft.util.FormattedCharSequence> l){tooltip=l;}}""",
    "net.minecraft.client.gui.screens.inventory.InventoryScreen": """public class InventoryScreen extends net.minecraft.client.gui.screens.Screen {
 public InventoryScreen(net.minecraft.world.entity.player.Player p){}}""",
    "net.minecraft.client.Minecraft": """public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public static Minecraft getInstance(){return INSTANCE;}
 public final net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();public net.minecraft.client.gui.screens.Screen screen=new net.minecraft.client.gui.screens.Screen();
 public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();
 public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;}}""",
    "fr.assistantia.DispositionCoffre": "public class DispositionCoffre {public static final int BARRE=3;}",
    "fr.assistantia.Emi": """public class Emi {public static boolean present;public static int recettes;
 public static boolean present(){return present;}public static void afficherRecettes(net.minecraft.world.item.ItemStack s){recettes++;}}""",
}

UI_HARNESS = """package fr.assistantia;
import java.util.*;
import net.minecraft.client.Minecraft;import net.minecraft.client.gui.GuiGraphics;import net.minecraft.world.item.ItemStack;
public class OngletRecetteTest {
 static final int X=10,Y=36,L=300,BAS=198;
 static List<String> appliquees=new ArrayList<>();static int annulations;
 static VueRecette.Objet objet(String nom,String q){return new VueRecette.Objet(new ItemStack(nom),nom,q,nom+" detail");}
 static VueRecette vue(int etapes){
  List<VueRecette.Etape> liste=new ArrayList<>();
  for(int i=1;i<=etapes;i++)liste.add(new VueRecette.Etape(i,i==1,objet(i==2?"Very Long Electronic Component With An Endless Name":"Iron Plate","8"),
   i==3?"Basic Bender Of The Very Long Name Workshop":"Basic Bender",new ItemStack("bender"),i%2==0?"Circuit 1":"","8 Iron Ingot, 2 Copper Wire, 1 Rubber Sheet, 4 Steel Bolts",
   i==1?"Prête":i==2?"Attend l’étape 1":"Machine manquante",i==1?VueRecette.Ton.PRET:i==2?VueRecette.Ton.ATTENTE:VueRecette.Ton.PROBLEME,"detail "+i));
  var alternatives=List.of(new VueRecette.Alternative("id-1",objet("Iron Plate",""),"Basic Bender",new ItemStack("bender"),"1 Iron Ingot au lieu de 2 par Iron Plate · 2 étapes au lieu de 12","detail alt"));
  var introuvables=List.of(new VueRecette.Introuvable(objet("Redstone",""),"Redstone : introuvable"));
  var machines=List.of(new VueRecette.MachineAFabriquer(objet("Basic Compressor",""),"tu as tout","Iron Plate : 1 Iron Ingot au lieu de 2","detail machine"));
  return new VueRecette(VueRecette.Etat.PRET,"",objet("Basic Electronic Circuit","16"),liste,alternatives,introuvables,machines,
   "Annuler : Iron Plate → Basic Bender","","Numéros sur les machines : active le mode fabrication de Recipe Tree ou Coffres.",1,
   new VueRecette.EtapeSuivante(new ItemStack("bender"),"★ Basic Bender","8 Iron Ingot → 8 Iron Plate"));
 }
 static GuiGraphics rendre(OngletRecette o,int mx,int my){var g=new GuiGraphics();o.render(g,X,Y,L,BAS,mx,my);return g;}
 // Sorties ASCII : un message accentué casse le décodage de la sortie sous Windows.
 static String ascii(Object o){var b=new StringBuilder();for(char c:String.valueOf(o).toCharArray())b.append(c<128?String.valueOf(c):"?");return b.toString();}
 static GuiGraphics.Texte texte(GuiGraphics g,String debut){return g.textes.stream().filter(t->t.texte().startsWith(debut)).findFirst().orElseThrow(()->new AssertionError(ascii("missing "+debut+" in "+g.textes)));}
 static void dansLaZone(GuiGraphics g){
  var font=Minecraft.getInstance().font;
  // Une ligne partiellement visible est coupée par le scissor ; la largeur, elle, ne déborde jamais.
  for(var t:g.textes){assert t.x()>=X&&t.x()+font.width(t.texte())<=X+L:ascii("text outside the tab: "+t);
   assert t.clippe()||t.y()>=Y&&t.y()+9<=BAS+1:ascii("text below the tab: "+t);}
 }
 public static void main(String[] args){
  VueRecette.brancher(new VueRecette.Actions(){public String appliquer(String id){appliquees.add(id);return "Appliqué : Iron Plate → Basic Bender";}
   public String annuler(){annulations++;return "Annulé : Iron Plate → Basic Bender";}});
  var o=new OngletRecette();o.init(w->{});
  switch(args[0]){
   case "absent"->{VueRecette.publier(null);Emi.present=false;var g=rendre(o,0,0);assert g.textes.stream().anyMatch(t->t.texte().contains("EMI n"));dansLaZone(g);
    Emi.present=true;g=rendre(o,0,0);assert g.textes.stream().anyMatch(t->t.texte().contains("EMI charge"));}
   case "sans-arbre"->{VueRecette.publier(VueRecette.vide(VueRecette.Etat.SANS_ARBRE,""));var g=rendre(o,0,0);
    assert g.textes.stream().anyMatch(t->t.texte().contains("Choisis un objet"));dansLaZone(g);assert VueRecette.etapeSuivante()==null;}
   case "plan"->{VueRecette.publier(vue(3));var g=rendre(o,0,0);dansLaZone(g);
    assert texte(g,"Basic Electronic Circuit").y()<Y+16;assert g.textes.stream().anyMatch(t->t.texte().equals("3 étapes · 1 prête")):"header summary";
    assert g.textes.stream().anyMatch(t->t.texte().equals("★"));assert g.textes.stream().anyMatch(t->t.texte().contains("Very Long")&&t.texte().endsWith("…")):"long names cut";
    assert g.textes.stream().anyMatch(t->t.texte().equals("Plan"));assert g.scissors==1&&!g.clip;
    assert VueRecette.etapeSuivante().titre().equals("★ Basic Bender");}
   case "appliquer"->{VueRecette.publier(vue(1));var g=rendre(o,0,0);var bouton=texte(g,"Appliquer");
    assert o.cliquer(bouton.x()+2,bouton.y()+2,0);assert appliquees.equals(List.of("id-1")):appliquees;
    assert o.cliquer(bouton.x()+2,bouton.y()+2,0);assert appliquees.size()==1:"double click ignored";
    g=rendre(o,0,0);assert g.textes.stream().anyMatch(t->t.texte().startsWith("Appliqué"));dansLaZone(g);
    assert !o.cliquer(X+L+20,Y+40,0):"outside the tab";}
   case "annuler"->{VueRecette.publier(vue(1));var g=rendre(o,0,0);var bouton=texte(g,"Annuler");
    assert o.cliquer(bouton.x()+2,bouton.y()+2,0);assert annulations==1;}
   case "icone"->{VueRecette.publier(vue(1));var g=rendre(o,0,0);int[] icone=g.items.get(1);
    Minecraft.getInstance().screen=new net.minecraft.client.gui.screens.Screen();
    assert o.cliquer(icone[0]+4,icone[1]+4,0);assert Emi.recettes==1;
    assert Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen;}
   case "defilement"->{VueRecette.publier(vue(40));var g=rendre(o,0,0);dansLaZone(g);
    int visibles=(int)g.textes.stream().filter(t->t.texte().contains("Iron Plate")||t.texte().startsWith("Very Long")).count();assert visibles<20:"only visible rows are drawn";
    assert o.defiler(X+20,Y+60,-3);g=rendre(o,0,0);dansLaZone(g);assert g.textes.stream().noneMatch(t->t.texte().equals("Plan")):"scrolled past the title";
    assert !o.defiler(X+20,Y+2,-1):"header does not scroll";
    assert o.cliquer(X+L-2,BAS-3,0):"scroll bar grabbed";assert o.glisser(Y+30);o.relacher();assert !o.glisser(BAS);
    g=rendre(o,0,0);dansLaZone(g);}
   case "survol"->{VueRecette.publier(vue(3));var g=rendre(o,0,0);var etape=texte(g,"8 × Very Long");
    Minecraft.getInstance().screen=new net.minecraft.client.gui.screens.Screen();rendre(o,etape.x()+60,etape.y()+2);
    assert Minecraft.getInstance().screen.tooltip!=null:"row detail on hover";}
   case "introuvable"->{VueRecette.publier(vue(1));rendre(o,0,0);assert o.defiler(X+20,Y+60,-10);var g=rendre(o,0,0);var nom=texte(g,"Redstone");
    int avant=Emi.recettes;assert o.cliquer(nom.x()-12,nom.y()+2,0):"click consumed inside the tab";assert Emi.recettes==avant:"decision B: no shortcut on missing materials";
    assert g.textes.stream().anyMatch(t->t.texte().equals("nulle part"));}
   case "masque"->{VueRecette.publier(vue(1));var g=rendre(o,0,0);var bouton=texte(g,"Appliquer");o.visible(false);
    assert !o.cliquer(bouton.x()+2,bouton.y()+2,0):"hidden tab ignores clicks";assert !o.defiler(X+20,Y+60,-1);}
   case "erreur"->{VueRecette.publier(VueRecette.vide(VueRecette.Etat.ERREUR,"Arbre EMI trop grand ou illisible : réduis la sélection dans Recipe Tree."));
    var g=rendre(o,0,0);dansLaZone(g);assert g.textes.stream().anyMatch(t->t.texte().contains("Arbre EMI"));}
   default->throw new AssertionError(args[0]);
  }
  System.out.println("PASS "+args[0]);
 }
}
"""


class RecipeTabScreenTests(unittest.TestCase):
    """L'onglet compilé avec le vrai thème commun et des stubs Minecraft, à 320 × 240."""

    @classmethod
    def setUpClass(cls):
        java, javac = jdk()
        if not java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        harness = Path(cls.temp.name) / "OngletRecetteTest.java"
        harness.write_text(UI_HARNESS, encoding="utf-8")
        sources = [
            MOD / f"{name}.java"
            for name in ("Theme", "BarreDefilement", "VueRecette", "OngletRecette")
        ]
        compile_sources(javac, cls.temp.name, UI_STUBS, [*sources, harness])
        cls.command = [java, "-ea", "-cp", cls.temp.name, "fr.assistantia.OngletRecetteTest"]

    def run_case(self, case):
        result = subprocess.run([*self.command, case], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS " + case, result.stdout)

    def test_emi_absent_loading_and_empty_states(self):
        for case in ("absent", "sans-arbre", "erreur"):
            with self.subTest(case=case):
                self.run_case(case)

    def test_plan_fits_320x240_with_ellipsis_and_star(self):
        self.run_case("plan")

    def test_apply_and_undo_buttons_once_per_click(self):
        for case in ("appliquer", "annuler"):
            with self.subTest(case=case):
                self.run_case(case)

    def test_icon_opens_emi_recipes(self):
        self.run_case("icone")

    def test_scroll_wheel_and_drag_draw_visible_rows_only(self):
        self.run_case("defilement")

    def test_row_detail_tooltip_and_hidden_tab(self):
        for case in ("survol", "masque"):
            with self.subTest(case=case):
                self.run_case(case)

    def test_missing_materials_are_information_only(self):
        self.run_case("introuvable")


MEMORY_STUBS = {
    "net.minecraft.client.Minecraft": """public class Minecraft {private static final Minecraft I=new Minecraft();public static Minecraft getInstance(){return I;}
 public Object level,player;public Object getConnection(){return null;}}""",
    "net.minecraftforge.fml.loading.FMLPaths": """public enum FMLPaths {GAMEDIR;public java.nio.file.Path get(){return java.nio.file.Path.of(".");}}""",
    "com.mojang.logging.LogUtils": """public class LogUtils {public static Log getLogger(){return new Log();}public static class Log {public void debug(String m,Throwable t){}}}""",
    "fr.assistantia.IdentiteMonde": """public class IdentiteMonde {public static boolean isolation(){return false;}public static String monde(){return "";}}""",
}

MEMORY_HARNESS = """package fr.assistantia;
import com.google.gson.*;
public class CoffresConnusTest {
 public static void main(String[] args){
  String json="{\\"conteneurs\\":["
   +"{\\"v\\":2,\\"nom\\":\\"Large Chest\\",\\"bloc\\":\\"minecraft:chest\\",\\"dimension\\":\\"minecraft:overworld\\",\\"x\\":1,\\"y\\":64,\\"z\\":0,\\"t\\":10,\\"objets\\":[{\\"item\\":\\"minecraft:redstone\\",\\"n\\":5}]},"
   +"{\\"v\\":2,\\"nom\\":\\"Large Chest\\",\\"bloc\\":\\"minecraft:chest\\",\\"dimension\\":\\"minecraft:overworld\\",\\"x\\":2,\\"y\\":64,\\"z\\":0,\\"t\\":20,\\"objets\\":[{\\"item\\":\\"minecraft:redstone\\",\\"n\\":7}]},"
   +"{\\"v\\":2,\\"nom\\":\\"Barrel\\",\\"bloc\\":\\"minecraft:barrel\\",\\"dimension\\":\\"minecraft:the_nether\\",\\"x\\":9,\\"y\\":70,\\"z\\":9,\\"t\\":5,\\"objets\\":[{\\"item\\":\\"minecraft:redstone\\",\\"n\\":1},{\\"item\\":\\"gtceu:lv_bender\\",\\"n\\":1}]},"
   +"{\\"v\\":1,\\"nom\\":\\"Old\\",\\"bloc\\":\\"minecraft:chest\\",\\"dimension\\":\\"minecraft:overworld\\",\\"x\\":5,\\"y\\":5,\\"z\\":5,\\"t\\":1,\\"objets\\":[{\\"item\\":\\"minecraft:diamond\\",\\"n\\":64}]}],"
   +"\\"machines\\":[{\\"v\\":2,\\"bloc\\":\\"gtceu:lv_alloy_smelter\\"},{\\"v\\":1,\\"bloc\\":\\"gtceu:old\\"}]}";
  var lu=CoffresConnus.analyser(JsonParser.parseString(json).getAsJsonObject());
  assert lu.objets().get("minecraft:redstone")==8L:"large chest halves counted once (latest) plus nether barrel: "+lu.objets();
  assert lu.objets().get("gtceu:lv_bender")==1L;
  assert !lu.objets().containsKey("minecraft:diamond"):"outdated memory version ignored";
  assert lu.machines().equals(java.util.Set.of("gtceu:lv_alloy_smelter"));
  var vide=CoffresConnus.analyser(new JsonObject());
  assert vide.objets().isEmpty()&&vide.machines().isEmpty();
  System.out.println("PASS memoire");
 }
}
"""


class KnownChestMemoryTests(unittest.TestCase):
    def test_known_chest_totals_and_opened_machines(self):
        java, javac = jdk()
        gson = gson_jar()
        if not java or not javac or gson is None:
            self.skipTest("JDK 17 et Gson requis")
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / "CoffresConnusTest.java"
            harness.write_text(MEMORY_HARNESS, encoding="utf-8")
            compile_sources(
                javac,
                tmp,
                MEMORY_STUBS,
                [MOD / "CoffresConnus.java", harness],
                classpath=str(gson),
            )
            result = subprocess.run(
                [
                    java,
                    "-ea",
                    "-cp",
                    tmp + os.pathsep + str(gson),
                    "fr.assistantia.CoffresConnusTest",
                ],
                capture_output=True,
                text=True,
                timeout=60,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS memoire", result.stdout)


if __name__ == "__main__":
    unittest.main()
