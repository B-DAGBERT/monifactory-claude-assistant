package fr.assistantia.compat.emi;

import fr.assistantia.ArbreCollecte;
import fr.assistantia.ArbreEmi;
import java.util.Set;
import java.util.TreeSet;

/**
 * Matières premières de l'arbre choisi dans EMI Recipe Tree (feuilles consommées, sans les outils),
 * pour trier les filons utiles à la recette. Lecture via l'instantané partagé, mise en cache.
 */
public final class MatieresArbreEmi {
    private static InstantaneArbreEmi dernier;
    private static Set<String> feuilles = Set.of();

    private MatieresArbreEmi() {}

    /** Identifiants des objets des feuilles, vide sans arbre choisi. Thread client uniquement. */
    public static Set<String> feuilles() {
        try {
            Object arbre = ArbreEmi.consulte();
            if (arbre == null) {
                dernier = null;
                return feuilles = Set.of();
            }
            var vue = InstantaneArbreEmi.lire(arbre);
            if (vue == dernier) return feuilles;
            dernier = vue;
            Set<String> ids = new TreeSet<>();
            visiter(vue.collecte(), ids, 0);
            return feuilles = Set.copyOf(ids);
        } catch (ReflectiveOperationException | RuntimeException e) {
            dernier = null;
            return feuilles = Set.of();
        }
    }

    private static void visiter(ArbreCollecte.Noeud noeud, Set<String> ids, int profondeur) {
        if (noeud == null || profondeur > 128 || ids.size() > 4096) return;
        if (noeud.enfants().isEmpty()) {
            if (!noeud.reutilisable()) ids.addAll(noeud.variantes());
            return;
        }
        for (var enfant : noeud.enfants()) visiter(enfant, ids, profondeur + 1);
    }
}
