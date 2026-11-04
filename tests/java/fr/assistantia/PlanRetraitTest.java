package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class PlanRetraitTest {
    private static PlanRetrait.Stock<String> stock(String id, int n, boolean owned) {
        return new PlanRetrait.Stock<>(id, n, owned);
    }

    private static PlanRetrait.Besoin<String> besoin(String ids, int n) {
        return new PlanRetrait.Besoin<>(ids::contains, n);
    }

    public static void main(String[] args) {
        var r =
                PlanRetrait.calculer(
                        List.of(stock("a", 2, true), stock("a", 64, false)),
                        List.of(besoin("a", 3)));
        assert r.total() == 1 && r.manquants() == 0 : r;
        r =
                PlanRetrait.calculer(
                        List.of(stock("a", 64, false)), List.of(besoin("a", 3), besoin("a", 5)));
        assert r.total() == 8 : r;
        r =
                PlanRetrait.calculer(
                        List.of(stock("a", 1, true), stock("b", 1, true)),
                        List.of(besoin("ab", 1), besoin("a", 1)));
        assert r.total() == 0 && r.manquants() == 0
                : r; // Réaffectation d'une variante déjà réservée.
        r =
                PlanRetrait.calculer(
                        List.of(stock("a", 2, false)), List.of(besoin("a", 3), besoin("b", 4)));
        assert r.total() == 2 && r.manquants() == 5 : r;
        r =
                PlanRetrait.calculer(
                        List.of(stock("a", 8, true), stock("a", 64, false)),
                        List.of(besoin("a", 3)));
        assert r.total() == 0 : r;
        r = PlanRetrait.calculer(List.of(stock("a", 100000, false)), List.of(besoin("a", 1000)));
        assert r.total() == 1000 : r; // Stocks empilés Sophisticated Storage.
        // Oracle exhaustif indépendant : attribution de chaque unité sur de petits stocks
        // aléatoires.
        Random rng = new Random(213);
        for (int trial = 0; trial < 500; trial++) {
            List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
            List<PlanRetrait.Besoin<String>> besoins = new ArrayList<>();
            List<PlanRetrait.Stock<String>> units = new ArrayList<>();
            int[] remaining = new int[3];
            for (int i = 0; i < 4; i++) {
                var s = stock("" + (char) ('a' + rng.nextInt(3)), rng.nextInt(3), i < 2);
                stocks.add(s);
                for (int j = 0; j < s.quantite(); j++) units.add(s);
            }
            for (int i = 0; i < 3; i++) {
                String ids = "";
                for (int j = 0; j < 3; j++) if (rng.nextBoolean()) ids += (char) ('a' + j);
                remaining[i] = rng.nextInt(3);
                besoins.add(besoin(ids, remaining[i]));
            }
            int[] best = {-1, -1};
            brute(units, besoins, remaining, 0, 0, 0, best);
            var result = PlanRetrait.calculer(stocks, besoins);
            int requested = java.util.Arrays.stream(remaining).sum();
            assert result.manquants() == requested - best[0] : result;
            assert result.total() == best[0] - best[1] : result;
        }
        System.out.println(
                "Allocation : déduction, variantes, doublons, stock partiel et 500 cas exhaustifs OK");
    }

    private static void brute(
            List<PlanRetrait.Stock<String>> units,
            List<PlanRetrait.Besoin<String>> needs,
            int[] remain,
            int i,
            int total,
            int owned,
            int[] best) {
        if (i == units.size()) {
            if (total > best[0] || total == best[0] && owned > best[1]) {
                best[0] = total;
                best[1] = owned;
            }
            return;
        }
        brute(units, needs, remain, i + 1, total, owned, best);
        var s = units.get(i);
        for (int j = 0; j < remain.length; j++)
            if (remain[j] > 0 && needs.get(j).accepte().test(s.objet())) {
                remain[j]--;
                brute(
                        units,
                        needs,
                        remain,
                        i + 1,
                        total + 1,
                        owned + (s.inventaire() ? 1 : 0),
                        best);
                remain[j]++;
            }
    }
}
