package fr.assistantia;

import java.util.List;

/** Valide uniquement les cibles finales, aux quantités demandées, jamais leurs ingrédients. */
public final class SuiviFabrication<T> {
    private final List<PlanRetrait.Besoin<T>> cibles;
    private final boolean[] obtenues;

    public SuiviFabrication(List<PlanRetrait.Besoin<T>> cibles) {
        this.cibles = List.copyOf(cibles);
        obtenues = new boolean[cibles.size()];
    }

    public boolean actualiser(List<PlanRetrait.Stock<T>> inventaire) {
        if (cibles.isEmpty()) return false;
        var allocation = PlanRetrait.calculer(inventaire, cibles);
        boolean termine = true;
        for (int i = 0; i < cibles.size(); i++) {
            obtenues[i] |= allocation.fournis().get(i) >= cibles.get(i).quantite();
            termine &= obtenues[i];
        }
        return termine;
    }
}
