package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.MaterialTree;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanMachines;
import fr.assistantia.VueRecette;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class RecipeFollowedPlanTest {
    private record Recette(List<EmiStack> getOutputs, List<EmiIngredient> getInputs)
            implements EmiRecipe {}

    private static final EmiStack plaques = EmiStack.of(new ItemStack("test:plaque", 2));
    private static final Recette plaque = new Recette(List.of(plaques), List.of());
    private static final Recette cible =
            new Recette(List.of(EmiStack.of(new ItemStack("test:cible", 1))), List.of(plaques));
    private static final String A_POSER = "Poste à poser : Atelier";

    public static void main(String[] args) throws Exception {
        BoM.defaultRecipes.put(plaques, plaque);
        var arbre = new MaterialTree(cible);
        InstantaneArbreEmi.notificationDisponible();
        AnalyseRecette.consulter(arbre);
        var courant = InstantaneArbreEmi.lire(arbre);
        switch (args[0]) {
            case "absent" -> verifierLocal();
            case "autre" -> {
                // Le même instantané seul ne suffit pas : il faut aussi l'identité de l'arbre.
                var autre = new MaterialTree(cible);
                assert autre != arbre && autre.equals(arbre);
                suivre(autre, courant, utiles(1));
                verifierLocal();
            }
            case "correspond" -> {
                var operations = utiles(1);
                suivre(arbre, courant, operations);
                assert AnalyseRecette.evaluer();
                verifierVueSuivie(2, "Ingrédients alloués pour une fabrication");
                assert !AnalyseRecette.evaluer() : "mêmes entrées : pas de recalcul";
                var copie = List.copyOf(new ArrayList<>(operations));
                assert copie != operations && copie.equals(operations);
                suivre(arbre, courant, copie);
                assert AnalyseRecette.evaluer() : "la signature suit l'identité de la liste utile";
                verifierVueSuivie(2, "Ingrédients alloués pour une fabrication");
            }
            case "decalage" -> {
                var copie =
                        new InstantaneArbreEmi(
                                courant.collecte(),
                                courant.machines(),
                                courant.lots(),
                                courant.variantes(),
                                courant.fluides(),
                                courant.cles(),
                                courant.cible());
                assert copie != courant && copie.equals(courant);
                suivre(arbre, copie, utiles(4));
                for (int i = 1; i <= 3; i++) {
                    assert !AnalyseRecette.evaluer() : "évaluation d'attente " + i;
                    var attente = AnalyseRecette.vue();
                    assert attente.etapes().isEmpty();
                    assert attente.plan().equals("Calcul du plan…") : attente;
                    assert attente.suivante() == null;
                    assert InventaireJoueur.lectures == 0;
                }
                verifierLocal();
                suivre(arbre, courant, utiles(1));
                assert AnalyseRecette.evaluer();
                verifierVueSuivie(2, "Ingrédients alloués pour une fabrication", 1);

                InstantaneArbreEmi.arbreModifie();
                var revise = InstantaneArbreEmi.lire(arbre);
                assert revise != courant;
                var affichee = AnalyseRecette.vue().etapes().get(0);
                for (int i = 1; i <= 3; i++) {
                    assert !AnalyseRecette.evaluer() : "compteur réinitialisé, attente " + i;
                    assert AnalyseRecette.vue().etapes().get(0) == affichee;
                    assert InventaireJoueur.lectures == 1;
                }
                suivre(arbre, revise, utiles(2));
                assert AnalyseRecette.evaluer();
                verifierVueSuivie(4, "Ingrédients alloués pour une fabrication", 1);
            }
            case "quantite" -> {
                PossessionsRecette.aPoser = plaque;
                suivre(arbre, courant, utiles(1));
                assert AnalyseRecette.evaluer();
                verifierVueSuivie(2, A_POSER);
                assert AnalyseRecette.recetteEnCours() == plaque;

                arbre.batches = 2;
                AnalyseRecette.changerQuantite(arbre);
                var nouvelle = InstantaneArbreEmi.lire(arbre);
                assert nouvelle != courant && nouvelle.lots() == 2;
                assert !AnalyseRecette.evaluer() : "l'ancien instantané reste périmé";
                var attente = verifierVueSuivie(2, A_POSER);
                assert attente.cible().quantite().equals("2");
                assert AnalyseRecette.recetteEnCours() == plaque;

                suivre(arbre, nouvelle, List.of(operation(cible, 2), operation(plaque, 2)));
                assert AnalyseRecette.evaluer();
                var vue = verifierVueSuivie(4, A_POSER, 0, 2);
                assert AnalyseRecette.recetteEnCours() == plaque;
                var autre = vue.etapes().get(1);
                assert autre.rang() == 2 && !autre.etoile() : autre;
                assert autre.produit().quantite().equals("2") : autre;
                assert autre.ton() == VueRecette.Ton.PRET : autre;
            }
            default -> throw new AssertionError(args[0]);
        }
    }

    private static void suivre(
            Object arbre,
            InstantaneArbreEmi instantane,
            List<PlanMachines.Operation<EmiRecipe>> utiles) {
        MachinesEmi.arbre = arbre;
        MachinesEmi.instantane = instantane;
        MachinesEmi.utiles = utiles;
        MachinesEmi.toutes = List.of(operation(cible, 9), operation(plaque, 7));
        MachinesEmi.operations = List.of(operation(cible, 9));
        MachinesEmi.operationsUtiles = List.of();
    }

    private static List<PlanMachines.Operation<EmiRecipe>> utiles(int fois) {
        return List.of(operation(plaque, fois));
    }

    private static PlanMachines.Operation<EmiRecipe> operation(EmiRecipe recette, int fois) {
        return new PlanMachines.Operation<>(recette, fois);
    }

    private static void verifierLocal() {
        assert AnalyseRecette.evaluer() : "le calcul local doit démarrer";
        assert InventaireJoueur.lectures == 1 : "le calcul local lit le stock personnel";
        var vue = AnalyseRecette.vue();
        assert vue.plan().isEmpty() && vue.etapes().size() == 2 : vue;
        assert vue.etapes().stream()
                        .anyMatch(
                                e ->
                                        e.produit().nom().equals("test:cible")
                                                && e.produit().quantite().equals("1"))
                : vue;
    }

    private static VueRecette verifierVueSuivie(int quantite, String raison) {
        return verifierVueSuivie(quantite, raison, 0, 1);
    }

    private static VueRecette verifierVueSuivie(int quantite, String raison, int lectures) {
        return verifierVueSuivie(quantite, raison, lectures, 1);
    }

    private static VueRecette verifierVueSuivie(
            int quantite, String raison, int lectures, int nombre) {
        assert InventaireJoueur.lectures == lectures : "le suivi réutilise les opérations utiles";
        var vue = AnalyseRecette.vue();
        assert vue.plan().isEmpty() && vue.etapes().size() == nombre : vue;
        var etape = vue.etapes().get(0);
        assert etape.produit().nom().equals("test:plaque") : etape;
        assert etape.produit().quantite().equals(String.valueOf(quantite)) : etape;
        assert etape.rang() == 1 && etape.etoile() : etape;
        assert etape.detail().contains(" · " + raison + "\n") : etape;
        assert vue.suivante().titre().startsWith("★ ") : vue.suivante();
        assert vue.suivante().detail().contains(quantite + " test:plaque") : vue.suivante();
        return vue;
    }
}
