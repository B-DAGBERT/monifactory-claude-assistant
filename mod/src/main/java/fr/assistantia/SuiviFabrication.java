package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Crédit des cibles obtenues pendant ce craft, conservé après rangement ou consommation. */
public final class SuiviFabrication<T> {
    private final List<PlanRetrait.Besoin<T>> cibles;
    private final boolean depuisDepart;
    private final BiPredicate<T, T> meme;
    private final List<Releve<T>> releves = new ArrayList<>();
    private boolean initialise;

    private static final class Releve<T> {
        final T objet;
        int precedent, acquis;

        Releve(T objet) {
            this.objet = objet;
        }
    }

    public SuiviFabrication(List<PlanRetrait.Besoin<T>> cibles) {
        this(cibles, true);
    }

    public SuiviFabrication(List<PlanRetrait.Besoin<T>> cibles, boolean depuisDepart) {
        this(cibles, depuisDepart, Objects::equals);
    }

    public SuiviFabrication(
            List<PlanRetrait.Besoin<T>> cibles, boolean depuisDepart, BiPredicate<T, T> meme) {
        this.cibles = List.copyOf(cibles);
        this.depuisDepart = depuisDepart;
        this.meme = meme;
    }

    public boolean actualiser(List<PlanRetrait.Stock<T>> inventaire) {
        if (cibles.isEmpty()) return false;
        for (var s : inventaire)
            if (cibles.stream().anyMatch(c -> c.accepte().test(s.objet()))
                    && releves.stream().noneMatch(r -> meme.test(r.objet, s.objet())))
                releves.add(new Releve<>(s.objet()));
        for (var r : releves) {
            int actuel =
                    inventaire.stream()
                            .filter(s -> meme.test(r.objet, s.objet()))
                            .mapToInt(PlanRetrait.Stock::quantite)
                            .sum();
            if (initialise || !depuisDepart)
                r.acquis =
                        (int)
                                Math.min(
                                        Integer.MAX_VALUE,
                                        (long) r.acquis + Math.max(0, actuel - r.precedent));
            r.precedent = actuel;
        }
        initialise = true;
        return PlanRetrait.calculer(
                                releves.stream()
                                        .map(r -> new PlanRetrait.Stock<>(r.objet, r.acquis, true))
                                        .toList(),
                                cibles)
                        .manquants()
                == 0;
    }

    /**
     * Stock virtuel uniquement pour les cibles finales déjà reçues puis sorties de l'inventaire.
     */
    public List<PlanRetrait.Stock<T>> credits() {
        return releves.stream()
                .filter(r -> r.acquis > r.precedent)
                .map(r -> new PlanRetrait.Stock<>(r.objet, r.acquis - r.precedent, true))
                .toList();
    }
}
