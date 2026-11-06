package fr.assistantia.compat.emi;

import com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.MaterialNode;
import fr.assistantia.LotFabrication;
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

    public static void main(String[] args) throws Exception {
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
