package fr.assistantia;

import java.util.*;
import java.util.function.BiPredicate;

/** Une réservation n'est jamais une preuve de production. */
public final class LotFabrication<T> {
    public enum Etat {
        EN_MACHINE("En machine"),
        A_RECUPERER("À récupérer"),
        TERMINE("Terminé"),
        ANNULE("Annulé");
        public final String texte;

        Etat(String texte) {
            this.texte = texte;
        }
    }

    private final List<PlanRetrait.Stock<T>> attendu;
    private int[] recus;
    private final BiPredicate<T, T> meme;
    private boolean annule, disponible;

    public LotFabrication(List<PlanRetrait.Stock<T>> attendu, BiPredicate<T, T> meme) {
        this.attendu = new ArrayList<>(attendu);
        this.meme = meme;
        recus = new int[attendu.size()];
    }

    /** Agrandir le lot choisi conserve ses réceptions réelles et son identité dans le suivi. */
    public boolean ajouter(List<PlanRetrait.Stock<T>> supplement) {
        if (annule) return false;
        for (var pile : supplement) {
            if (pile.quantite() <= 0) continue;
            int index = -1;
            for (int i = 0; i < attendu.size(); i++)
                if (meme.test(attendu.get(i).objet(), pile.objet())) {
                    index = i;
                    break;
                }
            if (index >= 0) {
                var avant = attendu.get(index);
                attendu.set(
                        index,
                        new PlanRetrait.Stock<>(
                                avant.objet(),
                                (int)
                                        Math.min(
                                                1_000_000L,
                                                (long) avant.quantite() + pile.quantite()),
                                true));
            } else {
                attendu.add(pile);
                recus = Arrays.copyOf(recus, attendu.size());
            }
        }
        return true;
    }

    public List<PlanRetrait.Stock<T>> restants() {
        if (annule) return List.of();
        List<PlanRetrait.Stock<T>> reste = new ArrayList<>();
        for (int i = 0; i < recus.length; i++)
            if (recus[i] < attendu.get(i).quantite())
                reste.add(
                        new PlanRetrait.Stock<>(
                                attendu.get(i).objet(),
                                attendu.get(i).quantite() - recus[i],
                                true));
        return List.copyOf(reste);
    }

    public List<PlanRetrait.Stock<T>> recevoir(List<PlanRetrait.Stock<T>> gains) {
        if (annule) return gains;
        List<PlanRetrait.Stock<T>> nonUtilises = new ArrayList<>();
        for (var gain : gains) {
            int reste = gain.quantite();
            for (int i = 0; i < recus.length && reste > 0; i++)
                if (meme.test(attendu.get(i).objet(), gain.objet())) {
                    int n = Math.min(reste, attendu.get(i).quantite() - recus[i]);
                    recus[i] += n;
                    reste -= n;
                }
            if (reste > 0) nonUtilises.add(new PlanRetrait.Stock<>(gain.objet(), reste, true));
        }
        return List.copyOf(nonUtilises);
    }

    public void disponible(boolean oui) {
        disponible = oui;
    }

    public void annuler() {
        annule = true;
    }

    public boolean annulable() {
        return !annule && totalRecu() < total();
    }

    public int total() {
        return attendu.stream().mapToInt(PlanRetrait.Stock::quantite).sum();
    }

    public int totalRecu() {
        return Arrays.stream(recus).sum();
    }

    public Etat etat() {
        if (annule) return Etat.ANNULE;
        if (total() > 0 && totalRecu() >= total()) return Etat.TERMINE;
        return disponible ? Etat.A_RECUPERER : Etat.EN_MACHINE;
    }
}
