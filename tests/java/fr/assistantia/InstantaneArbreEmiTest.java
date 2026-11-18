package fr.assistantia.compat.emi;

import com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.bom.MaterialNode;
import fr.assistantia.LotFabrication;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.RechercheEmi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

public final class InstantaneArbreEmiTest {
    public static final class Arbre {
        public MaterialNode goal = new MaterialNode(pile("test:gear", 1));
        public long batches = 1;
    }

    private static EmiStack pile(String id, int n) {
        return EmiStack.of(new ItemStack(id, n));
    }

    private static MaterialNode composant(EmiStack produit, EmiRecipe recette, String entree) {
        var branche = new MaterialNode(produit);
        branche.recipe = recette;
        branche.divisor = 2;
        branche.children.add(new MaterialNode(pile(entree, 1)));
        return branche;
    }

    private static void verifierSurplus(Arbre arbre, EmiRecipe recette, int attendu, int portees)
            throws Exception {
        InstantaneArbreEmi.arbreModifie();
        var vue = InstantaneArbreEmi.lire(arbre);
        var ingredients =
                List.of(
                        new PlanRetrait.Stock<>(pile("test:gear", 1), portees, true),
                        new PlanRetrait.Stock<>(pile("test:ingot", 1), 64, false),
                        new PlanRetrait.Stock<>(pile("test:alternate_ingot", 1), 64, false));
        int operations =
                PlanMachines.calculer(vue.machines(), vue.lots(), ingredients).stream()
                        .filter(o -> o.recette() == recette)
                        .mapToInt(PlanMachines.Operation::fois)
                        .sum();
        var prisesMe = PlanMachines.retraits(vue.machines(), vue.lots(), ingredients);
        var collecte = new MemoireCollecte(vue.collecte(), vue.lots());
        collecte.actualiser(Map.of("test:gear", portees));
        var prisesCoffre =
                collecte.dansCoffres(
                        List.of(
                                new PlanRetrait.Stock<>("test:ingot", 64, false),
                                new PlanRetrait.Stock<>("test:alternate_ingot", 64, false)));
        assert operations == attendu : "quantité opérations machine/Recette pour " + attendu;
        assert prisesMe.stream().mapToInt(PlanRetrait.Prise::quantite).sum() == attendu
                : "même quantité au réseau ME";
        assert prisesCoffre.stream().mapToInt(PlanRetrait.Prise::quantite).sum() == attendu
                : "même quantité dans le Repérage";
    }

    private static void surplusBranches() throws Exception {
        var arbre = new Arbre();
        arbre.goal = new MaterialNode(pile("test:machine", 1));
        var recette = new EmiRecipe(List.of(pile("test:ingot", 1)), List.of());
        recette.outputs = List.of(pile("test:gear", 2));
        var a = composant(pile("test:gear", 1), recette, "test:ingot");
        var b = composant(pile("test:gear", 1), recette, "test:ingot");
        arbre.goal.children.addAll(List.of(a, b));
        verifierSurplus(arbre, recette, 1, 0);
        arbre.batches = 3;
        verifierSurplus(arbre, recette, 3, 0);
        verifierSurplus(arbre, recette, 2, 2);
        arbre.batches = 1;

        var intermediaire = new MaterialNode(pile("test:subassembly", 1));
        intermediaire.children.add(b);
        arbre.goal.children.set(1, intermediaire);
        verifierSurplus(arbre, recette, 1, 0); // Même lot à une autre profondeur.

        arbre.goal.children.set(
                1, composant(pile("test:gear", 1), recette, "test:alternate_ingot"));
        verifierSurplus(arbre, recette, 2, 0); // Choix d'entrée EMI différent.

        ItemStack autreVariante = new ItemStack("test:gear", 2);
        autreVariante.tag = 7;
        recette.outputs = List.of(pile("test:gear", 2), EmiStack.of(autreVariante));
        autreVariante.setCount(1);
        arbre.goal.children.set(1, composant(EmiStack.of(autreVariante), recette, "test:ingot"));
        verifierSurplus(arbre, recette, 2, 0);

        var aleatoire = pile("test:gear", 2);
        aleatoire.chance = 0.75f;
        recette.outputs = List.of(aleatoire);
        arbre.goal.children.set(1, b);
        verifierSurplus(arbre, recette, 2, 0); // Un bonus aléatoire ne couvre aucun autre besoin.
    }

    public static void main(String[] args) throws Exception {
        surplusBranches();
        var arbre = new Arbre();
        var recipe = new GTEmiRecipe(List.of(), List.of());
        arbre.goal.recipe = recipe;
        var circuit = new MaterialNode(pile("gtceu:programmed_circuit", 1));
        circuit.catalyst = true;
        arbre.goal.children.add(circuit);
        arbre.goal.children.add(new MaterialNode(pile("test:hammer", 1)));
        InstantaneArbreEmi.arbreModifie();
        long structural = InstantaneArbreEmi.revisionStructureProduction();
        long cache = InstantaneArbreEmi.revisionProduction();
        InstantaneArbreEmi.invalider();
        assert InstantaneArbreEmi.revisionProduction() > cache;
        assert InstantaneArbreEmi.revisionStructureProduction() == structural;
        var premiere = InstantaneArbreEmi.lire(arbre);
        assert InstantaneArbreEmi.lire(arbre) == premiere;
        assert premiere.collecte().enfants().size() == 1;
        assert premiere.machines().enfants().size() == 1;
        assert premiere.collecte().enfants().get(0).reutilisable();
        assert premiere.machines().enfants().get(0).reutilisable();
        assert !premiere.accepte(new ItemStack("gtceu:programmed_circuit", 1));

        arbre.batches = 5;
        var quantite = InstantaneArbreEmi.lire(arbre);
        assert quantite != premiere && quantite.lots() == 5;
        arbre.goal.children.set(1, new MaterialNode(pile("test:mold", 1)));
        InstantaneArbreEmi.arbreModifie();
        assert InstantaneArbreEmi.revisionStructureProduction() > structural;
        var modifie = InstantaneArbreEmi.lire(arbre);
        assert !quantite.memeCollecte(modifie);
        assert modifie.accepte(new ItemStack("test:mold", 1));
        assert !modifie.accepte(new ItemStack("test:hammer", 1));

        var inventaire = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        var suivi = new SuiviLotsEmi(() -> inventaire, () -> {});
        recipe.outputs = List.of(pile("test:gear", 1));
        int id = suivi.ajouter(recipe, 2, null);
        assert suivi.actifs().get(0).manuel();
        RechercheEmi.disponibles = Map.of("test:gear", 1);
        suivi.actualiser();
        var lot = suivi.actifs().get(0).suivi();
        assert lot.etat() == LotFabrication.Etat.A_RECUPERER;
        inventaire.add(new PlanRetrait.Stock<>(pile("test:gear", 1), 1, true));
        suivi.recevoir(List.of());
        assert lot.totalRecu() == 1 && lot.total() == 2;
        long revision = suivi.revision();
        assert suivi.annuler(id) && suivi.revision() > revision;
        assert !lot.annulable() && lot.totalRecu() == 1;
        suivi.vider();
        assert suivi.actifs().isEmpty();
    }
}
