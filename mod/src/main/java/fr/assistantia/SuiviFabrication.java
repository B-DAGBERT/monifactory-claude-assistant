package fr.assistantia;

import java.util.ArrayList;
import java.util.List;

/**
 * Valide uniquement les cibles finales, aux quantités demandées, jamais leurs ingrédients. Ce que
 * le joueur possédait déjà au premier relevé ne compte pas : seul ce qui arrive ensuite termine.
 */
public final class SuiviFabrication<T> {
    private final List<PlanRetrait.Besoin<T>> cibles;
    private final boolean[] obtenues;
    private List<PlanRetrait.Besoin<T>> attendues;

    public SuiviFabrication(List<PlanRetrait.Besoin<T>> cibles) {
        this.cibles = List.copyOf(cibles);
        obtenues = new boolean[cibles.size()];
    }

    public boolean actualiser(List<PlanRetrait.Stock<T>> inventaire) {
        if (cibles.isEmpty()) return false;
        if (attendues == null) attendues = ajouterDepart(inventaire);
        var allocation = PlanRetrait.calculer(inventaire, attendues);
        boolean termine = true;
        for (int i = 0; i < cibles.size(); i++) {
            obtenues[i] |= allocation.fournis().get(i) >= attendues.get(i).quantite();
            termine &= obtenues[i];
        }
        return termine;
    }

    private List<PlanRetrait.Besoin<T>> ajouterDepart(List<PlanRetrait.Stock<T>> inventaire) {
        // Mesurer sans plafond ce que chaque cible possède déjà, sans compter deux fois un objet.
        List<PlanRetrait.Besoin<T>> sansPlafond = new ArrayList<>();
        for (var cible : cibles)
            sansPlafond.add(new PlanRetrait.Besoin<>(cible.accepte(), Integer.MAX_VALUE));
        var depart = PlanRetrait.calculer(inventaire, sansPlafond).fournis();
        List<PlanRetrait.Besoin<T>> resultat = new ArrayList<>();
        for (int i = 0; i < cibles.size(); i++) {
            long quantite = (long) cibles.get(i).quantite() + depart.get(i);
            resultat.add(
                    new PlanRetrait.Besoin<>(
                            cibles.get(i).accepte(), (int) Math.min(Integer.MAX_VALUE, quantite)));
        }
        return List.copyOf(resultat);
    }
}
