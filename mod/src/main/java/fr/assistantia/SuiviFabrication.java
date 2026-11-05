package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Crédit des cibles obtenues pendant ce craft, conservé après rangement ou consommation. */
public final class SuiviFabrication<T> {
    private List<PlanRetrait.Besoin<T>> cibles;
    private final boolean depuisDepart;
    private final BiPredicate<T, T> meme;
    private final List<Releve<T>> releves = new ArrayList<>();
    private boolean initialise;
    private int tick;

    private static final class Gain {
        final int tick;
        final boolean credite;
        int quantite;

        Gain(int tick, int quantite, boolean credite) {
            this.tick = tick;
            this.quantite = quantite;
            this.credite = credite;
        }
    }

    private static final class Releve<T> {
        final T objet;
        int precedent, acquis, sortis;
        final List<Gain> gainsRecents = new ArrayList<>();

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

    /** Modifier les quantités d'une même cible ne fait pas oublier les objets déjà obtenus. */
    public void quantites(List<PlanRetrait.Besoin<T>> cibles) {
        this.cibles = List.copyOf(cibles);
    }

    public boolean actualiser(List<PlanRetrait.Stock<T>> inventaire) {
        return actualiser(inventaire, List.of());
    }

    /** Les preuves sont des gains constatés après craft ou retrait d'une vraie sortie machine. */
    public boolean actualiser(
            List<PlanRetrait.Stock<T>> inventaire, List<PlanRetrait.Stock<T>> preuves) {
        if (cibles.isEmpty()) return false;
        tick++;
        for (var s : inventaire)
            if (cibles.stream().anyMatch(c -> c.accepte().test(s.objet()))
                    && releves.stream().noneMatch(r -> meme.test(r.objet, s.objet())))
                releves.add(new Releve<>(s.objet()));
        for (var r : releves) {
            r.gainsRecents.removeIf(g -> tick - g.tick > 20 || g.quantite == 0);
            int actuel =
                    inventaire.stream()
                            .filter(s -> meme.test(r.objet, s.objet()))
                            .mapToInt(PlanRetrait.Stock::quantite)
                            .sum();
            if (!initialise) {
                r.acquis = depuisDepart ? 0 : actuel;
            } else {
                int delta = actuel - r.precedent;
                int confirme =
                        preuves.stream()
                                .filter(p -> meme.test(r.objet, p.objet()))
                                .mapToInt(PlanRetrait.Stock::quantite)
                                .sum();
                if (delta < 0) {
                    r.sortis = addition(r.sortis, -delta);
                    r.gainsRecents.clear();
                } else if (delta > 0) {
                    int nouveau = Math.min(delta, confirme);
                    confirme -= nouveau;
                    int retour = Math.min(r.sortis, delta - nouveau);
                    r.sortis -= retour;
                    r.acquis = addition(r.acquis, delta - retour);
                    int dejaCompte = delta - retour - nouveau;
                    if (dejaCompte > 0) r.gainsRecents.add(new Gain(tick, dejaCompte, true));
                    if (retour > 0) r.gainsRecents.add(new Gain(tick, retour, false));
                }
                // Une preuve peut suivre le gain d'un tick. Consommer d'abord les gains déjà
                // comptés évite de transformer une même sortie en deux objets fabriqués.
                for (boolean dejaCompte : new boolean[] {true, false})
                    for (var gain : r.gainsRecents)
                        if (confirme > 0 && gain.credite == dejaCompte) {
                            int n = Math.min(confirme, gain.quantite);
                            gain.quantite -= n;
                            confirme -= n;
                            if (!gain.credite) {
                                r.acquis = addition(r.acquis, n);
                                // L'ancien objet est donc toujours rangé, pas réellement revenu.
                                r.sortis = addition(r.sortis, n);
                            }
                        }
            }
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

    public List<PlanRetrait.Stock<T>> obtenus() {
        return releves.stream()
                .filter(r -> r.acquis > 0)
                .map(r -> new PlanRetrait.Stock<>(r.objet, r.acquis, true))
                .toList();
    }

    private static int addition(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
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
