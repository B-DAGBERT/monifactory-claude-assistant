package fr.assistantia;

import java.util.*;
import java.util.function.BiPredicate;

/** Gains personnels depuis la déclaration d'un lot manuel, sans compter deux fois une réception. */
public final class ReceptionManuelle<T> {
    private final BiPredicate<T, T> meme;
    private List<PlanRetrait.Stock<T>> precedent;

    public ReceptionManuelle(BiPredicate<T, T> meme) {
        this.meme = meme;
    }

    public void initialiser(List<PlanRetrait.Stock<T>> stock) {
        precedent = List.copyOf(stock);
    }

    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> stock, List<PlanRetrait.Stock<T>> dejaAttribues) {
        var gains =
                precedent == null ? List.<PlanRetrait.Stock<T>>of() : soustraire(stock, precedent);
        initialiser(stock);
        return soustraire(gains, dejaAttribues);
    }

    /** Regrouper les piles avant soustraction évite de créditer deux fois une même réception. */
    public List<PlanRetrait.Stock<T>> soustraire(
            List<PlanRetrait.Stock<T>> total, List<PlanRetrait.Stock<T>> deduit) {
        List<PlanRetrait.Stock<T>> resultat = new ArrayList<>();
        List<T> vus = new ArrayList<>();
        for (var s : total) {
            if (vus.stream().anyMatch(v -> meme.test(v, s.objet()))) continue;
            vus.add(s.objet());
            long n = compter(total, s.objet()) - compter(deduit, s.objet());
            if (n > 0)
                resultat.add(
                        new PlanRetrait.Stock<>(s.objet(), (int) Math.min(n, 1_000_000), true));
        }
        return List.copyOf(resultat);
    }

    private long compter(List<PlanRetrait.Stock<T>> stock, T objet) {
        return stock.stream()
                .filter(s -> meme.test(s.objet(), objet))
                .mapToLong(PlanRetrait.Stock::quantite)
                .sum();
    }
}
