package fr.assistantia.compat.emi;

import java.lang.reflect.Field;

/**
 * Seules les modifications de l'arbre réel d'EMI ({@code BoM.tree}) invalident les calculs de
 * l'assistant. EMI construit aussi des arbres temporaires : l'infobulle de coût d'une recette
 * ({@code RecipeCostTooltipComponent}) en crée un à chaque image tant qu'une sortie est survolée.
 * Chaque recalcul réel commence par redéfinir le but de l'arbre (la racine), puis ses enfants : la
 * racine suffit donc à reconnaître une vraie modification. Un nouvel arbre (choix d'un but) change
 * d'identité, ce que les consommateurs vérifient déjà.
 */
public final class ArbreReel {
    private static Field arbreBom, but;
    private static boolean indisponible;

    private ArbreReel() {}

    /** Point d'appel du mixin, à la fin de chaque {@code MaterialNode.defineRecipe}. */
    public static void defini(Object noeud) {
        if (racine(noeud)) {
            InstantaneArbreEmi.arbreModifie();
            fr.assistantia.CatalogueProduction.rechargement();
        } else {
            // Le crochet natif fonctionne : le cache de lecture peut rester valable.
            InstantaneArbreEmi.notificationDisponible();
        }
    }

    /**
     * Vrai si ce nœud est le but de {@code BoM.tree}. En cas de lecture impossible, on prévient
     * comme avant plutôt que de manquer une vraie modification.
     */
    static boolean racine(Object noeud) {
        if (indisponible) return true;
        try {
            if (arbreBom == null) arbreBom = Class.forName("dev.emi.emi.bom.BoM").getField("tree");
            Object arbre = arbreBom.get(null);
            if (arbre == null) return false;
            if (but == null || but.getDeclaringClass() != arbre.getClass())
                but = arbre.getClass().getField("goal");
            return but.get(arbre) == noeud;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            indisponible = true;
            return true;
        }
    }
}
