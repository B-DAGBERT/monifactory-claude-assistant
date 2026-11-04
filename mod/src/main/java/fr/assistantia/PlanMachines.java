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
        var reserve = new ArrayList<>(stock);
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
            List<Integer> outilsPresents =
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
                                                            .mapToInt(PlanRetrait.Stock::quantite)
                                                            .sum()
                                                    : 0)
                            .toList();
            var allocation = PlanRetrait.calculer(reserve, besoins);
            for (int i = 0; i < reserve.size(); i++) {
                var s = reserve.get(i);
                reserve.set(
                        i,
                        new PlanRetrait.Stock<>(
                                s.objet(), s.quantite() - allocation.utilises().get(i), true));
            }
            List<Demande<T, R>> suite = new ArrayList<>();
            for (int i = 0; i < niveau.size(); i++) {
                var d = niveau.get(i);
                int manque = d.quantite() - allocation.fournis().get(i) - outilsPresents.get(i);
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
        return operations.entrySet().stream()
                .map(e -> new Operation<>(e.getKey(), e.getValue()))
                .toList();
    }

    public static int produit(long a, long b) {
        return (int)
                Math.min(
                        1_000_000,
                        Math.min(1_000_000, Math.max(0, a)) * Math.min(1_000_000, Math.max(0, b)));
    }
}
