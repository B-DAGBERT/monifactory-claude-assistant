package fr.assistantia.compat.emi;

import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.compat.emi.ClesEmiMesure.EmiStack;
import fr.assistantia.compat.emi.ClesEmiMesure.ItemStack;
import fr.assistantia.compat.emi.ClesEmiMesure.RevisionCouverture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Same allocations and realistic stock shape, independent of the Minecraft render loop. */
public final class RevisionEmiBenchmark {
    private static final int ETAPES = 24, PILES = 1_024, ESSAIS = 25, REPETITIONS = 250;
    private static final int LOTS = 8;
    private static volatile Object resultat;

    private record Calcul(
            List<PlanMachines.Operation<String>> operations, List<PlanRetrait.Prise> retraits) {}

    private record Etat(
            Object contexte,
            Object vue,
            long inventaire,
            long lots,
            long coffres,
            long tampons,
            List<PlanRetrait.Stock<EmiStack>> stock,
            List<PlanRetrait.Stock<ItemStack>> credits,
            List<PlanRetrait.Stock<EmiStack>> outils) {}

    private static final class Cache {
        private final boolean revisions;
        private final PlanMachines.Noeud<EmiStack, String> arbre;
        private Etat precedent;
        private Object cle;
        private Calcul valeur;
        private int calculs;

        Cache(boolean revisions, PlanMachines.Noeud<EmiStack, String> arbre) {
            this.revisions = revisions;
            this.arbre = arbre;
        }

        Calcul actualiser(Etat etat) {
            if (precedent == null
                    || precedent.contexte() != etat.contexte()
                    || precedent.vue() != etat.vue()) cle = null;
            precedent = etat;
            // Le code historique composait le stock avant de comparer sa clé triée.
            var stock = revisions ? null : new ArrayList<>(etat.stock());
            Object suivante =
                    revisions
                            ? new RevisionCouverture(
                                    etat.inventaire(),
                                    etat.lots(),
                                    etat.coffres(),
                                    etat.tampons(),
                                    0,
                                    etat.credits(),
                                    etat.outils())
                            : ClesEmiMesure.cleStock(stock)
                                    + "|"
                                    + etat.lots()
                                    + "|"
                                    + etat.coffres()
                                    + "|"
                                    + etat.tampons();
            if (suivante.equals(cle)) return valeur;
            if (stock == null) stock = new ArrayList<>(etat.stock());
            valeur =
                    new Calcul(
                            PlanMachines.calculer(arbre, LOTS, stock),
                            PlanMachines.retraits(arbre, LOTS, stock));
            cle = suivante;
            calculs++;
            return valeur;
        }
    }

    private static PlanMachines.Noeud<EmiStack, String> arbre() {
        var branche =
                new PlanMachines.Noeud<EmiStack, String>(
                        s -> s.id().equals("fixture:base"), 1, 1, false, null, List.of());
        for (int i = 0; i < ETAPES; i++) {
            String id = "fixture:produit_" + i;
            branche =
                    new PlanMachines.Noeud<EmiStack, String>(
                            s -> s.id().equals(id), 1, 1, false, id, List.of(branche));
        }
        return branche;
    }

    private static PlanRetrait.Stock<EmiStack> pile(
            String id, String variante, int n, boolean porte) {
        return new PlanRetrait.Stock<>(new EmiStack("fixture:" + id, variante), n, porte);
    }

    private static Etat initial() {
        var stock = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (int i = 0; i < PILES - 2; i++)
            stock.add(pile("autre_" + i / 3, "{variante:" + i % 3 + "}", (i % 64) + 1, i % 2 == 0));
        stock.add(pile("base", "{}", 4, false));
        stock.add(pile("produit_19", "{}", 2, true));
        return new Etat(
                new Object(),
                new Object(),
                10,
                20,
                30,
                40,
                List.copyOf(stock),
                List.of(),
                List.of());
    }

    private static List<Etat> changements(Etat base) {
        var stock = new ArrayList<>(base.stock());
        stock.add(pile("produit_22", "{}", 3, true));
        var change = List.copyOf(stock);
        return List.of(
                new Etat(base.contexte(), base.vue(), 11, 20, 30, 40, change, List.of(), List.of()),
                new Etat(base.contexte(), base.vue(), 10, 21, 30, 40, change, List.of(), List.of()),
                new Etat(base.contexte(), base.vue(), 10, 20, 31, 40, change, List.of(), List.of()),
                new Etat(base.contexte(), base.vue(), 10, 20, 30, 41, change, List.of(), List.of()),
                new Etat(
                        base.contexte(),
                        base.vue(),
                        10,
                        20,
                        30,
                        40,
                        change,
                        List.of(
                                new PlanRetrait.Stock<>(
                                        new ItemStack("fixture:produit_22"), 3, true)),
                        List.of()),
                new Etat(
                        base.contexte(),
                        base.vue(),
                        10,
                        20,
                        30,
                        40,
                        change,
                        List.of(),
                        List.of(pile("produit_22", "{}", 3, true))),
                new Etat(
                        base.contexte(),
                        new Object(),
                        10,
                        20,
                        30,
                        40,
                        base.stock(),
                        List.of(),
                        List.of()),
                new Etat(
                        new Object(),
                        base.vue(),
                        10,
                        20,
                        30,
                        40,
                        base.stock(),
                        List.of(),
                        List.of()));
    }

    private static double mesurer(Function<Etat, Calcul> calculer, Etat etat) {
        long debut = System.nanoTime();
        for (int i = 0; i < REPETITIONS; i++) resultat = calculer.apply(etat);
        return (System.nanoTime() - debut) / (REPETITIONS * 1_000.0);
    }

    public static void main(String[] args) {
        var arbre = arbre();
        var etat = initial();
        var avant = new Cache(false, arbre);
        var apres = new Cache(true, arbre);
        var attendu = avant.actualiser(etat);
        assert attendu.equals(apres.actualiser(etat));
        assert attendu.operations().size() == ETAPES;
        assert attendu.retraits().equals(List.of(new PlanRetrait.Prise(PILES - 2, 4)));
        assert avant.actualiser(etat) == attendu && apres.actualiser(etat) == apres.valeur;
        assert avant.calculs == 1 && apres.calculs == 1;

        for (int i = 0; i < 7; i++) {
            mesurer(avant::actualiser, etat);
            mesurer(apres::actualiser, etat);
        }
        double[] tempsAvant = new double[ESSAIS], tempsApres = new double[ESSAIS];
        for (int i = 0; i < ESSAIS; i++) {
            if (i % 2 == 0) {
                tempsAvant[i] = mesurer(avant::actualiser, etat);
                tempsApres[i] = mesurer(apres::actualiser, etat);
            } else {
                tempsApres[i] = mesurer(apres::actualiser, etat);
                tempsAvant[i] = mesurer(avant::actualiser, etat);
            }
        }
        assert avant.calculs == 1 && apres.calculs == 1;
        var changements = changements(etat);
        for (var nouveau : changements) {
            avant.actualiser(etat);
            apres.actualiser(etat);
            int calculsAvant = avant.calculs, calculsApres = apres.calculs;
            assert avant.actualiser(nouveau).equals(apres.actualiser(nouveau));
            assert avant.calculs == calculsAvant + 1;
            assert apres.calculs == calculsApres + 1;
            assert avant.actualiser(nouveau).equals(apres.actualiser(nouveau));
            assert avant.calculs == calculsAvant + 1;
            assert apres.calculs == calculsApres + 1;
        }
        Arrays.sort(tempsAvant);
        Arrays.sort(tempsApres);
        int p95 = (int) Math.ceil(ESSAIS * .95) - 1;
        System.out.printf(
                Locale.ROOT,
                """
                {
                  "reference": "%s",
                  "etapes": %d,
                  "piles": %d,
                  "essais": %d,
                  "relectures_par_essai": %d,
                  "invalidations_verifiees": %d,
                  "mediane_stable_us_avant": %.3f,
                  "mediane_stable_us_apres": %.3f,
                  "p95_stable_us_avant": %.3f,
                  "p95_stable_us_apres": %.3f
                }
                """,
                args[0],
                ETAPES,
                PILES,
                ESSAIS,
                REPETITIONS,
                changements.size(),
                tempsAvant[ESSAIS / 2],
                tempsApres[ESSAIS / 2],
                tempsAvant[p95],
                tempsApres[p95]);
    }
}
