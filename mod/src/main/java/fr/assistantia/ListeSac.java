package fr.assistantia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;

/** Regroupe sans confondre deux objets aux données différentes, sans modifier l'inventaire. */
public final class ListeSac {
    public record Entree(ItemStack pile, long nombre) {}

    public static String normaliser(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    public static List<Entree> lire(List<ItemStack> piles, String filtre) {
        var resultat = new ArrayList<Entree>();
        String recherche = normaliser(filtre.trim());
        for (var pile : piles) {
            if (pile.isEmpty() || !normaliser(pile.getHoverName().getString()).contains(recherche))
                continue;
            int index = -1;
            for (int i = 0; i < resultat.size(); i++)
                if (ItemStack.isSameItemSameTags(pile, resultat.get(i).pile())) {
                    index = i;
                    break;
                }
            if (index < 0) resultat.add(new Entree(pile.copy(), pile.getCount()));
            else {
                var entree = resultat.get(index);
                resultat.set(index, new Entree(entree.pile(), entree.nombre() + pile.getCount()));
            }
        }
        resultat.sort(Comparator.comparing(e -> normaliser(e.pile().getHoverName().getString())));
        return List.copyOf(resultat);
    }
}
