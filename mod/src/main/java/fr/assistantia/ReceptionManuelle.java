package fr.assistantia;

import java.util.*;
import java.util.function.BiPredicate;

/** Gains personnels depuis la déclaration d'un lot manuel, sans compter deux fois une réception. */
public final class ReceptionManuelle<T> {
    private final BiPredicate<T, T> meme;
    private List<PlanRetrait.Stock<T>> precedent;
    private List<PlanRetrait.Stock<T>> sortis = List.of();
    private final List<Gain<T>> gainsRecents = new ArrayList<>();
    private int tick;

    private static final class Gain<T> {
        final T objet;
        final int tick;
        final boolean credite;
        int quantite;

        Gain(T objet, int quantite, int tick, boolean credite) {
            this.objet = objet;
            this.quantite = quantite;
            this.tick = tick;
            this.credite = credite;
        }
    }

    public ReceptionManuelle(BiPredicate<T, T> meme) {
        this.meme = meme;
    }

    /**
     * Un arrêt de suivi ou un changement de monde ne conserve aucune dette de l'ancien contexte.
     */
    public void vider() {
        precedent = null;
        sortis = List.of();
        gainsRecents.clear();
        tick = 0;
    }

    public void initialiser(List<PlanRetrait.Stock<T>> stock) {
        precedent = List.copyOf(stock);
    }

    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> stock, List<PlanRetrait.Stock<T>> dejaAttribues) {
        return actualiser(stock, dejaAttribues, List.of());
    }

    /** Les sorties de la machine ouverte attendent leur confirmation avant un crédit manuel. */
    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> stock,
            List<PlanRetrait.Stock<T>> dejaAttribues,
            List<PlanRetrait.Stock<T>> attendusAutomatiques) {
        return actualiser(stock, dejaAttribues, attendusAutomatiques, List.of());
    }

    /** Les preuves restantes distinguent un nouveau craft de la reprise d'un objet rangé. */
    public List<PlanRetrait.Stock<T>> actualiser(
            List<PlanRetrait.Stock<T>> stock,
            List<PlanRetrait.Stock<T>> dejaAttribues,
            List<PlanRetrait.Stock<T>> attendusAutomatiques,
            List<PlanRetrait.Stock<T>> preuves) {
        tick++;
        gainsRecents.removeIf(g -> tick - g.tick > 20 || g.quantite == 0);
        if (precedent == null) {
            initialiser(stock);
            return List.of();
        }
        List<PlanRetrait.Stock<T>> gains = new ArrayList<>(), departs = new ArrayList<>();
        List<T> vus = new ArrayList<>();
        for (var piles : List.of(stock, precedent))
            for (var pile : piles) {
                if (vus.stream().anyMatch(v -> meme.test(v, pile.objet()))) continue;
                vus.add(pile.objet());
                long delta = compter(stock, pile.objet()) - compter(precedent, pile.objet());
                if (delta > 0) gains.add(stock(pile.objet(), (int) Math.min(1_000_000L, delta)));
                else if (delta < 0)
                    departs.add(stock(pile.objet(), (int) Math.min(1_000_000L, -delta)));
            }
        initialiser(stock);
        if (gains.isEmpty() && departs.isEmpty() && preuves.isEmpty()) return List.of();
        var dettes = new ArrayList<>(sortis);
        dettes.addAll(departs);
        sortis = soustraire(dettes, List.of());
        gainsRecents.removeIf(g -> compter(departs, g.objet) > 0);
        // Maximum par objet : preuve et attente peuvent désigner la même réception.
        var exclus = new ArrayList<>(dejaAttribues);
        exclus.addAll(soustraire(attendusAutomatiques, dejaAttribues));
        List<PlanRetrait.Stock<T>> resultat = new ArrayList<>();
        var restantes = preuves;
        for (var gain : soustraire(gains, exclus)) {
            int prouve = (int) Math.min(gain.quantite(), compter(restantes, gain.objet()));
            restantes = soustraire(restantes, List.of(stock(gain.objet(), prouve)));
            int retour = (int) Math.min(gain.quantite() - prouve, compter(sortis, gain.objet()));
            sortis = soustraire(sortis, List.of(stock(gain.objet(), retour)));
            int nouveau = gain.quantite() - retour;
            if (nouveau > 0) resultat.add(stock(gain.objet(), nouveau));
            if (nouveau > prouve)
                gainsRecents.add(new Gain<>(gain.objet(), nouveau - prouve, tick, true));
            if (retour > 0) gainsRecents.add(new Gain<>(gain.objet(), retour, tick, false));
        }
        // Une confirmation peut suivre le gain. Les crédits existants restent prioritaires,
        // puis un retour apparent peut être requalifié, en restaurant la dette de l'ancien objet.
        for (boolean credite : new boolean[] {true, false})
            for (var gain : gainsRecents)
                if (gain.credite == credite && gain.quantite > 0) {
                    int n = (int) Math.min(gain.quantite, compter(restantes, gain.objet));
                    if (n <= 0) continue;
                    gain.quantite -= n;
                    restantes = soustraire(restantes, List.of(stock(gain.objet, n)));
                    if (!gain.credite) {
                        resultat.add(stock(gain.objet, n));
                        dettes = new ArrayList<>(sortis);
                        dettes.add(stock(gain.objet, n));
                        sortis = soustraire(dettes, List.of());
                    }
                }
        return soustraire(resultat, List.of());
    }

    private PlanRetrait.Stock<T> stock(T objet, int quantite) {
        return new PlanRetrait.Stock<>(objet, quantite, true);
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
