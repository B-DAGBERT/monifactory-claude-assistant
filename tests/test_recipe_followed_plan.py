"""Transitions Recette/Suivi sur un arbre EMI simulé et les calculs Java de production."""

import subprocess
import tempfile
import unittest

from tests.test_emi_backpack_tree import STUBS as EMI_STUBS
from tests.test_machine_panel_click import corps
from tests.test_recipe_tab import MOD, ROOT, ROOT_STUBS, compile_sources, jdk

EMI = MOD / "compat/emi"

STUBS = {
    name: EMI_STUBS[name]
    for name in (
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.nbt.Tag",
        "net.minecraft.nbt.CompoundTag",
        "net.minecraft.nbt.ListTag",
        "net.minecraftforge.fluids.FluidUtil",
        "dev.emi.emi.api.stack.Comparison",
        "fr.assistantia.compat.emi.ComparaisonEmi",
    )
} | {
    name: ROOT_STUBS[name]
    for name in (
        "dev.emi.emi.api.recipe.EmiResolutionRecipe",
        "dev.emi.emi.bom.BoM",
        "dev.emi.emi.bom.MaterialNode",
        "dev.emi.emi.bom.MaterialTree",
        "fr.assistantia.compat.emi.InventaireArbreEmi",
    )
}
STUBS.update(
    {
        "dev.emi.emi.api.stack.EmiIngredient": ROOT_STUBS["dev.emi.emi.api.stack.EmiIngredient"],
        "dev.emi.emi.api.stack.EmiStack": EMI_STUBS["dev.emi.emi.api.stack.EmiStack"].replace(
            "public Object getKey()",
            """public EmiStack withAmount(long n){return copy().setAmount(n);}
 public net.minecraft.network.chat.Component getName(){
    return new net.minecraft.network.chat.Component(getId().toString());}
 public Object getKey()""",
        ),
        "dev.emi.emi.api.recipe.EmiRecipe": ROOT_STUBS["dev.emi.emi.api.recipe.EmiRecipe"].replace(
            "public interface EmiRecipe {",
            """public interface EmiRecipe {
 default net.minecraft.resources.ResourceLocation getId(){return null;}
 record Category(net.minecraft.network.chat.Component getName) {}
 default Category getCategory(){return new Category(new net.minecraft.network.chat.Component("Atelier"));}""",
        ),
        "dev.emi.emi.api.EmiApi": """
public class EmiApi {
    private static final Object MANAGER = new Object();
    public static Object getRecipeManager() { return MANAGER; }
}
""",
        "dev.emi.emi.bom.MaterialTree": ROOT_STUBS["dev.emi.emi.bom.MaterialTree"].replace(
            "public class MaterialTree {",
            """public class MaterialTree {
 public boolean equals(Object autre){return autre instanceof MaterialTree t && goal.recipe == t.goal.recipe;}
 public int hashCode(){return System.identityHashCode(goal.recipe);}""",
        ),
        "net.minecraft.world.item.ItemStack": EMI_STUBS[
            "net.minecraft.world.item.ItemStack"
        ].replace(
            "public ItemStack(String id,int n)",
            """public ItemStack(String id){this(id,1);}
 public net.minecraft.network.chat.Component getHoverName(){
    return new net.minecraft.network.chat.Component(id);}
 public ItemStack(String id,int n)""",
        ),
        "net.minecraft.world.item.Items": """
public class Items {
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    public static final String AIR = "minecraft:air", BUCKET = "minecraft:bucket";
}
""",
        "net.minecraft.world.level.material.Fluid": """
public record Fluid(String id) { public String getBucket() { return null; } }
""",
        "net.minecraft.network.chat.Component": "public record Component(String getString) {}",
        "net.minecraft.client.Minecraft": ROOT_STUBS["net.minecraft.client.Minecraft"],
        "fr.assistantia.InventaireJoueur": """
import java.util.List;
import net.minecraft.world.item.ItemStack;
public class InventaireJoueur {
    public static int lectures;
    public static List<ItemStack> pilesFabrication() { lectures++; return List.of(); }
    public static boolean fabricationSynchronisee() { return true; }
    public static boolean fabricationDisponible() { return true; }
    public static long revisionFabrication() { return 0; }
}
""",
        "fr.assistantia.CoffreTampon": EMI_STUBS["fr.assistantia.CoffreTampon"],
        "fr.assistantia.CoffresConnus": """
import java.util.Map;
public class CoffresConnus {
    public static Map<String, Long> proches() { return Map.of(); }
    public static long revisionProches() { return 0; }
    public static Etat etat() { return new Etat(true); }
    public record Etat(boolean pret) {}
}
""",
        "fr.assistantia.Pont": """
import net.minecraft.world.item.ItemStack;
public class Pont { public static ItemStack objet(String id) { return new ItemStack(id); } }
""",
        "fr.assistantia.compat.emi.PossessionsRecette": """
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.world.item.ItemStack;
final class PossessionsRecette {
    enum Possession { POSEE, RANGEE, AUCUNE, INCONNUE }
    record PosteGT() {}
    record Atelier(String nom, ItemStack icone) {}
    static EmiRecipe aPoser;
    static EmiRecipe absent;
    static PosteGT machineAbsente;
    static Atelier atelierMachine;
    static long version;
    static long revision() { return version; }
    static boolean complet() { return true; }
    static Object poste(EmiRecipe r) {
        return r == absent && machineAbsente != null ? machineAbsente : r;
    }
    static boolean etabli(Object poste) { return false; }
    static Atelier atelier(Object poste) { return poste == machineAbsente ? atelierMachine : null; }
    static Atelier atelierSousTier(PosteGT poste) { return null; }
    static boolean sousTier(PosteGT poste) { return false; }
    static Possession possession(Object poste) {
        if (poste != null && (poste == absent || poste == machineAbsente)) return Possession.AUCUNE;
        return poste == aPoser ? Possession.RANGEE : Possession.POSEE;
    }
}
""",
        "fr.assistantia.compat.emi.OrdonnancementEmi": """
import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import java.util.List;
final class OrdonnancementEmi {
    record Objet(dev.emi.emi.api.stack.EmiStack pile, boolean outil) {}
    static List<OrdreMachines.Regle<Objet>> regles(EmiRecipe r, Object menu) { return List.of(); }
    static List<OrdreMachines.StockSource<Objet>> stocks(
            List<PlanMachines.Operation<EmiRecipe>> operations, Object menu) { return List.of(); }
}
""",
        "fr.assistantia.compat.emi.StockFabricationEmi": """
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanRetrait;
import java.util.List;
import net.minecraft.world.item.ItemStack;
final class StockFabricationEmi {
    static List<PlanRetrait.Stock<EmiStack>> tampons(List<ItemStack> piles, boolean accessible) {
        return List.of();
    }
}
""",
        "fr.assistantia.compat.emi.LotMachine": """
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.LotFabrication;
record LotMachine(int id, EmiRecipe recette, int fois, LotFabrication<EmiStack> suivi) {}
""",
    }
)


def corps_production(nom, signatures, environnement):
    # Les corps qui choisissent, calculent et affichent le plan sont compilés tels quels.
    # Les abonnements Forge et les fonctions sans rapport avec les étapes sont hors du harnais.
    source = (EMI / f"{nom}.java").read_text(encoding="utf-8")
    imports = "\n".join(
        line
        for line in source.splitlines()
        if line.startswith("import ") and line.split(".")[-1] not in {"ArbreEmi;", "BlockPos;"}
    )
    membres = "\n".join(corps(source, signature) for signature in signatures)
    return f"{imports}\nfinal class {nom} {{\n{membres}\n{environnement}\n}}"


def analyse_recette():
    return corps_production(
        "AnalyseRecette",
        (
            "private static final class Cle {",
            "private static Cle cleActuelle(",
            "private static void nouvelleQuantite(",
            "private static boolean assistant(",
            "private static boolean actualiserPlan(",
            "private static void attendre(",
            "private static void ordonner(",
            "static List<PlanRetrait.Stock<EmiStack>> stockTampon(",
            "private static List<PlanRetrait.Stock<EmiStack>> stockEmi(",
            "private static List<OrdreMachines.StockSource<OrdonnancementEmi.Objet>> coffres(",
            "private static OrdreMachines.Condition condition(",
            "private static OrdreMachines.Condition conditionPosteAbsent(",
            "private static boolean tension(",
            "private static String lotsEnMarche(",
            "private static int lotAttendu(",
            "static EmiRecipe recetteEnCours(",
            "private record CleVue(",
            "private static VueRecette construire(",
            "private static VueRecette.Objet cible(",
            "private static EmiStack premiere(",
            "static ItemStack icone(",
            "private static VueRecette.Etape etape(",
            "private static String explication(",
            "private static boolean provenance(",
            "private static String court(",
            "private static VueRecette.EtapeSuivante suivante(",
        ),
        """
    private static Cle cle;
    private static final int MAX_NOEUDS_ANALYSES = 256;
    private static ArbreRecette.Lecture lecture;
    private static long generation, revisionVue;
    private static int ticks, prochainPlan, attenteSuivi, tachesFaites, tachesTotales;
    private static boolean sale;
    private static String clePlan = "", planAttente = "";
    private static RangsRecette.Cle<EmiRecipe> enCours;
    private static Set<EmiRecipe> recettesPlan = Set.of();
    private static List<RangsRecette.Etape<EmiRecipe>> etapes = List.of();
    private static Object cleReperes, cleEtapes, cleIntrouvables;
    private static List<VueRecette.Etape> vuesEtapes;
    private static List<VueRecette.Introuvable> vuesIntrouvables;
    private static VueRecette.EtapeSuivante vueSuivante;
    private static Map<String, Proposition> propositions;
    private static final List<Object> taches = List.of();
    private static final List<Analyse> analyses = List.of();
    private record Proposition() {}
    private static final class Analyse { ArbreRecette.Noeud noeud; double besoin; }

    static void consulter(Object arbre) throws Exception {
        cle = cleActuelle(arbre);
        lecture = ArbreRecette.lire(arbre);
    }
    static void changerQuantite(Object arbre) throws Exception { nouvelleQuantite(cleActuelle(arbre)); }
    static boolean evaluer() { return actualiserPlan(); }
    static VueRecette vue() { return construire(); }

    private static void nouvelArbre(Cle cle) { throw new AssertionError("Échec de lecture"); }
    private static List<SelectionAlternatives.Noeud<EmiIngredient>> descriptions() {
        return List.of();
    }
    private static boolean enMarche(LotMachine lot) { throw new AssertionError("Lot inattendu"); }
    private static List<VueRecette.Introuvable> introuvables() { return List.of(); }
    private static void propositions(List<VueRecette.Alternative> a,
            List<VueRecette.MachineAFabriquer> m, Map<String, Proposition> p) {}
    private static String annulation() { return ""; }
    private static String circuit(EmiRecipe r) { return ""; }
    private static String entrees(EmiRecipe r, int fois) { return ""; }
""",
    )


def machines_emi():
    source = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
    # Reprendre le point de lecture complet pour rejouer la même fixture avant et après.
    lectures = source.split("// Onglet Recette : lectures seules", 1)[1].split(
        "/** Lit le calcul déjà retenu.", 1
    )[0]
    return """
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.List;
final class MachinesEmi {
    static Object arbre;
    static InstantaneArbreEmi instantane;
    static List<PlanMachines.Operation<EmiRecipe>> toutes, utiles, operations, operationsUtiles;
    static long revisionLotsProduction() { return 0; }
    static String problemeOrdre() { return ""; }
    static List<LotMachine> lotsSuivis() { return List.of(); }
    static List<PlanRetrait.Stock<EmiStack>> outilsInstalles() { return List.of(); }
""" + "\n".join(
        (
            lectures.split("\n", 1)[1],
            corps(source, "static List<PlanRetrait.Stock<EmiStack>> stockCoffres("),
            "}",
        )
    )


class RecipeFollowedPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java, javac = jdk()
        if not cls.java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        cls.classes = directory.name
        compile_sources(
            javac,
            cls.classes,
            STUBS
            | {
                "fr.assistantia.compat.emi.AnalyseRecette": analyse_recette(),
                "fr.assistantia.compat.emi.MachinesEmi": machines_emi(),
            },
            [
                *(
                    MOD / f"{nom}.java"
                    for nom in (
                        "ArbreCollecte",
                        "LotFabrication",
                        "MemoireCollecte",
                        "OrdreMachines",
                        "PlanArbre",
                        "PlanMachines",
                        "PlanRetrait",
                        "RessourceCollecte",
                        "VueRecette",
                    )
                ),
                *(
                    EMI / f"{nom}.java"
                    for nom in (
                        "ArbreRecette",
                        "DecisionTension",
                        "InstantaneArbreEmi",
                        "RangsRecette",
                        "SelectionAlternatives",
                        "StockPersonnelEmi",
                    )
                ),
                ROOT / "tests/java/fr/assistantia/compat/emi/RecipeFollowedPlanTest.java",
            ],
        )

    def rejouer(self, cas):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                self.classes,
                "fr.assistantia.compat.emi.RecipeFollowedPlanTest",
                cas,
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_no_followed_tree(self):
        self.rejouer("absent")

    def test_consulted_and_followed_trees_have_different_identities(self):
        self.rejouer("autre")

    def test_matching_snapshot_reuses_useful_operations_and_their_identity(self):
        self.rejouer("correspond")

    def test_stale_snapshot_waits_three_times_then_falls_back_and_recovers(self):
        self.rejouer("decalage")

    def test_quantity_change_keeps_the_current_step_and_updates_the_display(self):
        self.rejouer("quantite")

    def test_missing_workstation_machine_and_insufficient_voltage_keep_visible_texts(self):
        for cas in ("poste-manquant", "machine-manquante", "tension-insuffisante"):
            with self.subTest(cas=cas):
                self.rejouer(cas)
