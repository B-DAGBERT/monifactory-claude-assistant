package fr.assistantia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Besoins de fabrication après réservation des produits déjà disponibles, de haut en bas. */
public final class PlanMachines {
    public record Noeud<T, R>(
            Predicate<T> accepte,
            long quantite,
            long production,
            boolean reutilisable,
            R recette,
            List<Noeud<T, R>> enfants) {}

    public record Operation<R>(R recette, int fois) {}

    private record Demande<T, R>(Noeud<T, R> node, int quantite) {}

    public static <T, R> List<Operation<R>> calculer(
            Noeud<T, R> root, long batches, List<PlanRetrait.Stock<T>> stock) {
        return calculer(root, batches, stock, null);
    }

    /** Parts allouées aux besoins de l'arbre depuis une source extérieure à l'inventaire. */
    public static <T, R> List<PlanRetrait.Prise> retraits(
            Noeud<T, R> root, long batches, List<PlanRetrait.Stock<T>> stock) {
        int[] prises = new int[stock.size()];
        calculer(root, batches, stock, prises);
        var resultat = new ArrayList<PlanRetrait.Prise>();
        for (int i = 0; i < prises.length; i++)
            if (prises[i] > 0) resultat.add(new PlanRetrait.Prise(i, prises[i]));
        return List.copyOf(resultat);
    }

    private static <T, R> List<Operation<R>> calculer(
            Noeud<T, R> root, long batches, List<PlanRetrait.Stock<T>> stock, int[] prises) {
        var reserve = new ArrayList<>(stock);
        int[] outilsPris = prises == null ? null : new int[stock.size()];
        var operations = new LinkedHashMap<R, Integer>();
        var outilsPrevus = new LinkedHashMap<R, Integer>();
        List<Demande<T, R>> niveau =
                List.of(new Demande<>(root, produit(root.quantite(), batches)));
        int visites = 0, profondeur = 0;
        while (!niveau.isEmpty()) {
            if (++profondeur > 129 || (visites += niveau.size()) > 4096)
                throw new IllegalArgumentException("Arbre trop grand");
            var besoins =
                    niveau.stream()
                            .map(
                                    d ->
                                            new PlanRetrait.Besoin<>(
                                                    d.node().accepte(),
                                                    d.node().reutilisable() ? 0 : d.quantite()))
                            .toList();
            List<Long> outilsPresents =
                    niveau.stream()
                            .map(
                                    d ->
                                            d.node().reutilisable()
                                                    ? reserve.stream()
                                                            .filter(
                                                                    s ->
                                                                            d.node()
                                                                                    .accepte()
                                                                                    .test(
                                                                                            s
                                                                                                    .objet()))
                                                            .mapToLong(PlanRetrait.Stock::quantite)
                                                            .sum()
                                                    : 0L)
                            .toList();
            var allocation = PlanRetrait.calculer(reserve, besoins);
            for (int i = 0; i < reserve.size(); i++) {
                var s = reserve.get(i);
                int utilise = allocation.utilises().get(i);
                if (prises != null && !s.inventaire()) prises[i] += utilise;
                reserve.set(
                        i,
                        new PlanRetrait.Stock<>(s.objet(), s.quantite() - utilise, s.inventaire()));
            }
            List<Demande<T, R>> suite = new ArrayList<>();
            for (int i = 0; i < niveau.size(); i++) {
                var d = niveau.get(i);
                int manque =
                        (int)
                                Math.max(
                                        0L,
                                        d.quantite()
                                                - allocation.fournis().get(i)
                                                - outilsPresents.get(i));
                if (outilsPris != null && d.node().reutilisable())
                    reserverOutil(d, reserve, outilsPris);
                if (d.node().reutilisable() && d.node().recette() != null) {
                    int deja = outilsPrevus.getOrDefault(d.node().recette(), 0);
                    manque = Math.max(0, manque - deja);
                }
                if (manque <= 0) continue;
                long production = Math.max(1, d.node().production());
                int fois = (int) (manque / production + (manque % production == 0 ? 0 : 1));
                if (d.node().reutilisable() && d.node().recette() != null)
                    outilsPrevus.merge(
                            d.node().recette(),
                            produit(fois, production),
                            (a, b) -> Math.min(1_000_000, a + b));
                if (d.node().recette() != null)
                    operations.merge(d.node().recette(), fois, Integer::sum);
                for (var enfant : d.node().enfants())
                    suite.add(
                            new Demande<>(
                                    enfant,
                                    produit(enfant.quantite(), enfant.reutilisable() ? 1 : fois)));
            }
            niveau = suite;
        }
        if (prises != null)
            for (int i = 0; i < prises.length; i++)
                prises[i] = Math.min(stock.get(i).quantite(), prises[i] + outilsPris[i]);
        return operations.entrySet().stream()
                .map(e -> new Operation<>(e.getKey(), e.getValue()))
                .toList();
    }

    private static <T, R> void reserverOutil(
            Demande<T, R> demande, List<PlanRetrait.Stock<T>> stocks, int[] pris) {
        int manque = demande.quantite();
        for (int i = 0; i < stocks.size() && manque > 0; i++) {
            var stock = stocks.get(i);
            if (!demande.node().accepte().test(stock.objet())) continue;
            if (stock.inventaire()) manque -= stock.quantite();
            else manque -= Math.min(pris[i], stock.quantite());
        }
        for (int i = 0; i < stocks.size() && manque > 0; i++) {
            var stock = stocks.get(i);
            if (stock.inventaire() || !demande.node().accepte().test(stock.objet())) continue;
            int utiliser = Math.min(manque, Math.max(0, stock.quantite() - pris[i]));
            pris[i] += utiliser;
            manque -= utiliser;
        }
    }

    public static int produit(long a, long b) {
        return (int)
                Math.min(
                        1_000_000,
                        Math.min(1_000_000, Math.max(0, a)) * Math.min(1_000_000, Math.max(0, b)));
    }
}
