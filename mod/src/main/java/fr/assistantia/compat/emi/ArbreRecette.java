package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * Accès en réflexion à l'arbre interne d'EMI ({@code BoM.tree}, {@code MaterialTree}, {@code
 * MaterialNode}). La lecture conserve l'ingrédient de chaque nœud, clé des résolutions locales.
 * L'écriture se limite aux résolutions de cet arbre : jamais {@code BoM.addRecipe}, ni sauvegarde
 * EMI, ni favoris.
 */
final class ArbreRecette {
    static final int MAX_PROFONDEUR = 128, MAX_NOEUDS = 4096;

    /**
     * Un nœud : ingrédient (clé de résolution), recette retenue, quantité par fabrication du
     * parent, production par fabrication, recette du parent, besoin total estimé.
     */
    record Noeud(
            EmiIngredient ingredient,
            EmiRecipe recette,
            long quantite,
            long production,
            boolean reutilisable,
            EmiRecipe parent,
            int profondeur,
            double besoin) {}

    record Lecture(Object arbre, Object racine, long lots, Noeud cible, List<Noeud> noeuds) {
        Lecture {
            noeuds = List.copyOf(noeuds);
        }
    }

    private static Class<?> classeNoeud;
    private static Field ingredient, recette, enfants, quantite, diviseur, catalyseur, chance;
    private static Field but, lotsArbre, resolutions;
    private static Method getRecipe, recalculate, calculateCost, addResolution;
    private static Field desactivees;

    private ArbreRecette() {}

    private static void charger(Object arbre) throws ReflectiveOperationException {
        if (getRecipe != null && arbre.getClass() == but.getDeclaringClass()) return;
        Class<?> type = arbre.getClass();
        but = type.getField("goal");
        lotsArbre = type.getField("batches");
        resolutions = type.getField("resolutions");
        getRecipe = type.getMethod("getRecipe", EmiIngredient.class);
        recalculate = type.getMethod("recalculate");
        calculateCost = type.getMethod("calculateCost");
        addResolution = type.getMethod("addResolution", EmiIngredient.class, EmiRecipe.class);
        desactivees = Class.forName("dev.emi.emi.bom.BoM").getField("disabledRecipes");
    }

    private static void chargerNoeud(Class<?> type) throws ReflectiveOperationException {
        if (type == classeNoeud) return;
        ingredient = type.getField("ingredient");
        recette = type.getField("recipe");
        enfants = type.getField("children");
        quantite = type.getField("amount");
        diviseur = type.getField("divisor");
        catalyseur = type.getField("catalyst");
        chance = type.getField("consumeChance");
        classeNoeud = type;
    }

    static Object racine(Object arbre) throws ReflectiveOperationException {
        charger(arbre);
        return but.get(arbre);
    }

    static long lots(Object arbre) throws ReflectiveOperationException {
        charger(arbre);
        return lotsArbre.getLong(arbre);
    }

    /** Lecture bornée de tout l'arbre ; aucun nœud n'est créé ni recalculé. */
    static Lecture lire(Object arbre) throws ReflectiveOperationException {
        charger(arbre);
        Object goal = but.get(arbre);
        long lots = lotsArbre.getLong(arbre);
        List<Noeud> noeuds = new ArrayList<>();
        Set<Object> chemin = Collections.newSetFromMap(new IdentityHashMap<>());
        lire(goal, null, chemin, noeuds, 0, Math.max(1, lots), true);
        return new Lecture(arbre, goal, lots, noeuds.isEmpty() ? null : noeuds.get(0), noeuds);
    }

    private static void lire(
            Object noeud,
            EmiRecipe parent,
            Set<Object> chemin,
            List<Noeud> noeuds,
            int profondeur,
            double multiplicateur,
            boolean racine)
            throws ReflectiveOperationException {
        if (noeud == null) return;
        if (profondeur > MAX_PROFONDEUR || noeuds.size() >= MAX_NOEUDS || !chemin.add(noeud))
            throw new IllegalArgumentException("Arbre EMI trop grand ou cyclique");
        try {
            chargerNoeud(noeud.getClass());
            var ing = (EmiIngredient) ingredient.get(noeud);
            boolean reutilisable = catalyseur.getBoolean(noeud) || chance.getFloat(noeud) == 0;
            if (ComparaisonEmi.circuitVirtuel(parent, ing, reutilisable)) return;
            var choisie = (EmiRecipe) recette.get(noeud);
            long q = quantite.getLong(noeud), production = Math.max(1, diviseur.getLong(noeud));
            double besoin = racine ? q * multiplicateur : reutilisable ? q : q * multiplicateur;
            noeuds.add(
                    new Noeud(
                            ing, choisie, q, production, reutilisable, parent, profondeur, besoin));
            if (choisie == null) return;
            double fabrications = Math.ceil(besoin / production);
            if (enfants.get(noeud) instanceof List<?> liste)
                for (Object enfant : liste)
                    lire(enfant, choisie, chemin, noeuds, profondeur + 1, fabrications, false);
        } finally {
            chemin.remove(noeud);
        }
    }

    /** Recette que l'arbre retiendrait : résolution locale, puis choix global ou défaut EMI. */
    static EmiRecipe choix(Object arbre, EmiIngredient ing) {
        try {
            charger(arbre);
            return (EmiRecipe) getRecipe.invoke(arbre, ing);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Choix de l'arbre EMI illisible", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<EmiIngredient, EmiRecipe> resolutions(Object arbre)
            throws ReflectiveOperationException {
        charger(arbre);
        return (Map<EmiIngredient, EmiRecipe>) resolutions.get(arbre);
    }

    static boolean desactivee(EmiRecipe r) {
        try {
            if (desactivees == null)
                desactivees = Class.forName("dev.emi.emi.bom.BoM").getField("disabledRecipes");
            return desactivees.get(null) instanceof Set<?> set && set.contains(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Résolution locale (même chemin que le choix d'une recette dans EMI), puis coûts. */
    static void resoudre(Object arbre, EmiIngredient ing, EmiRecipe choisie)
            throws ReflectiveOperationException {
        charger(arbre);
        addResolution.invoke(arbre, ing, choisie);
        calculateCost.invoke(arbre);
        rafraichir();
    }

    /** Retire une résolution locale : l'ingrédient retrouve le choix global ou le défaut EMI. */
    static void retirer(Object arbre, EmiIngredient ing) throws ReflectiveOperationException {
        charger(arbre);
        resolutions(arbre).remove(ing);
        recalculate.invoke(arbre);
        calculateCost.invoke(arbre);
        rafraichir();
    }

    /** L'écran Recipe Tree ouvert relit l'arbre, comme après un choix natif. */
    private static void rafraichir() {
        InventaireArbreEmi.invalider();
        var mc = Minecraft.getInstance();
        if (mc.screen != null
                && mc.screen.getClass().getName().equals("dev.emi.emi.screen.BoMScreen"))
            try {
                mc.screen.getClass().getMethod("recalculateTree").invoke(mc.screen);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // L'écran se recalcule de toute façon à sa prochaine ouverture.
            }
    }

    static boolean transparente(EmiRecipe r) {
        return r instanceof EmiResolutionRecipe;
    }
}
