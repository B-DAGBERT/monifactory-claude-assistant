package fr.assistantia;

import java.util.*;
import java.util.function.BiPredicate;

/** Rapproche une baisse de sortie et un gain personnel, même reçus dans deux ticks différents. */
public final class ReceptionSortie<T> {
    private final BiPredicate<T, T> meme;
    private List<PlanRetrait.Stock<T>> stock = List.of(), sorties = List.of();
    private final List<Mouvement<T>> gains = new ArrayList<>(), retraits = new ArrayList<>();
    private int tick;
    private boolean initialise;

    private static final class Mouvement<T> {
        final T objet;
        final int tick;
        int quantite;

        Mouvement(T objet, int quantite, int tick) {
            this.objet = objet;
            this.quantite = quantite;
            this.tick = tick;
        }
    }

    public ReceptionSortie(BiPredicate<T, T> meme) {
        this.meme = meme;
    }

    /** Lecture de cohérence uniquement : aucun mouvement en vol ne devient une réception. */
    public boolean enAttente() {
        return gains.stream().anyMatch(m -> m.quantite > 0)
                || retraits.stream().anyMatch(m -> m.quantite > 0);
    }

    private int compte(List<PlanRetrait.Stock<T>> liste, T objet) {
        return liste.stream()
                .filter(s -> meme.test(objet, s.objet()))
                .mapToInt(PlanRetrait.Stock::quantite)
                .sum();
    }

    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> maintenant, List<PlanRetrait.Stock<T>> disponibles) {
        return actualiser(maintenant, disponibles, List.of());
    }

    /** Un gain déjà confirmé par un craft ne peut pas aussi valider une sortie machine. */
    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> maintenant,
            List<PlanRetrait.Stock<T>> disponibles,
            List<PlanRetrait.Stock<T>> dejaAttribues) {
        tick++;
        List<PlanRetrait.Stock<T>> preuves = new ArrayList<>();
        gains.removeIf(m -> tick - m.tick > 20 || m.quantite == 0);
        retraits.removeIf(m -> tick - m.tick > 20 || m.quantite == 0);
        if (initialise) {
            List<T> objets = new ArrayList<>();
            // Seules les sorties connues peuvent prouver une réception. Éviter une comparaison
            // quadratique de tous les objets sans rapport d'un grand backpack.
            for (var liste : List.of(sorties, disponibles))
                for (var s : liste)
                    if (objets.stream().noneMatch(o -> meme.test(o, s.objet())))
                        objets.add(s.objet());
            for (var mouvements : List.of(gains, retraits))
                for (var mouvement : mouvements)
                    if (objets.stream().noneMatch(o -> meme.test(o, mouvement.objet)))
                        objets.add(mouvement.objet);
            for (T objet : objets) {
                int gain = compte(maintenant, objet) - compte(stock, objet);
                int retire = compte(sorties, objet) - compte(disponibles, objet);
                if (gain > 0) {
                    int libre = Math.max(0, gain - compte(dejaAttribues, objet));
                    if (libre > 0) gains.add(new Mouvement<>(objet, libre, tick));
                } else if (gain < 0)
                    for (var g : gains)
                        if (meme.test(g.objet, objet)) {
                            int n = Math.min(g.quantite, -gain);
                            g.quantite -= n;
                            gain += n;
                        }
                if (retire > 0) retraits.add(new Mouvement<>(objet, retire, tick));
            }
            for (var r : retraits)
                for (var g : gains)
                    if (r.quantite > 0 && g.quantite > 0 && meme.test(r.objet, g.objet)) {
                        int n = Math.min(r.quantite, g.quantite);
                        r.quantite -= n;
                        g.quantite -= n;
                        preuves.add(new PlanRetrait.Stock<>(g.objet, n, true));
                    }
        }
        stock = List.copyOf(maintenant);
        sorties = List.copyOf(disponibles);
        initialise = true;
        return List.copyOf(preuves);
    }
}
