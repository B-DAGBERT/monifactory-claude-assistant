package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Répartir un gain physique une seule fois entre les lots qui l'attendent. */
public final class ReceptionLots {
    public record Source<T>(LotFabrication<T> lot, Predicate<T> accepte) {}

    public record Resultat<T>(List<PlanRetrait.Stock<T>> restants, boolean change) {}

    private ReceptionLots() {}

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
