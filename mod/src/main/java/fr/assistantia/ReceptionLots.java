package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

/** Répartir un gain physique une seule fois entre les lots qui l'attendent. */
public final class ReceptionLots {
    public record Source<T>(LotFabrication<T> lot, Predicate<T> accepte) {}

    public record Resultat<T>(List<PlanRetrait.Stock<T>> restants, boolean change) {}

    private ReceptionLots() {}

    /** Travail déjà acquis hors des poches : aucune addition aux vraies sources de transfert. */
    public static <T> List<PlanRetrait.Stock<T>> credits(
            List<PlanRetrait.Stock<T>> obtenus,
            List<PlanRetrait.Stock<T>> physiques,
            List<PlanRetrait.Stock<T>> dejaCredites,
            BiPredicate<T, T> meme) {
        List<T> vus = new ArrayList<>();
        List<PlanRetrait.Stock<T>> credits = new ArrayList<>();
        for (var pile : obtenus) {
            if (vus.stream().anyMatch(v -> meme.test(v, pile.objet()))) continue;
            vus.add(pile.objet());
            long acquis =
                    obtenus.stream()
                            .filter(s -> meme.test(s.objet(), pile.objet()))
                            .mapToLong(PlanRetrait.Stock::quantite)
                            .sum();
            long present =
                    physiques.stream()
                            .filter(s -> meme.test(s.objet(), pile.objet()))
                            .mapToLong(PlanRetrait.Stock::quantite)
                            .sum();
            long compte =
                    dejaCredites.stream()
                            .filter(s -> meme.test(s.objet(), pile.objet()))
                            .mapToLong(PlanRetrait.Stock::quantite)
                            .sum();
            long n = Math.max(0, acquis - present - compte);
            if (n > 0)
                credits.add(
                        new PlanRetrait.Stock<>(pile.objet(), (int) Math.min(1_000_000L, n), true));
        }
        return List.copyOf(credits);
    }

    public static <T> Resultat<T> repartir(List<PlanRetrait.Stock<T>> gains, List<Source<T>> lots) {
        List<PlanRetrait.Stock<T>> disponibles = List.copyOf(gains);
        boolean change = false;
        for (var source : lots) {
            int avant = source.lot().totalRecu();
            var acceptes =
                    disponibles.stream().filter(s -> source.accepte().test(s.objet())).toList();
            var nonUtilises = new ArrayList<>(source.lot().recevoir(acceptes));
            disponibles.stream()
                    .filter(s -> !source.accepte().test(s.objet()))
                    .forEach(nonUtilises::add);
            disponibles = List.copyOf(nonUtilises);
            change |= avant != source.lot().totalRecu();
        }
        return new Resultat<>(disponibles, change);
    }
}
