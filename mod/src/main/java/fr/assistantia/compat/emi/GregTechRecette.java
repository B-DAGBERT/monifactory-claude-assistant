package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Lecture GregTech de l'onglet Recette avec des accès réflexifs mis en cache par classe : type de
 * recette, tension, ampérage et durée. Aucune recherche de champ ou de méthode par recette.
 */
final class GregTechRecette {
    private static final String GT_EMI = "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe";

    private static Class<?> classeEmi, classeGt, classeEnergie;
    private static Field recette, type, duree;
    private static Method energie, tension, amperes;

    private GregTechRecette() {}

    /** Recette GregTech native d'une recette EMI, ou null pour une autre recette. */
    static Object recette(EmiRecipe r) {
        if (r == null || !r.getClass().getName().equals(GT_EMI)) return null;
        try {
            if (r.getClass() != classeEmi) {
                recette = GregTechMachines.champ(r.getClass(), "recipe");
                classeEmi = r.getClass();
            }
            return recette.get(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void charger(Object gt) throws ReflectiveOperationException {
        if (gt.getClass() == classeGt) return;
        type = gt.getClass().getField("recipeType");
        duree = gt.getClass().getField("duration");
        energie = gt.getClass().getMethod("getInputEUt");
        classeGt = gt.getClass();
    }

    private static Object energie(Object gt) throws ReflectiveOperationException {
        charger(gt);
        Object e = energie.invoke(gt);
        if (e.getClass() != classeEnergie) {
            tension = e.getClass().getMethod("voltage");
            amperes = e.getClass().getMethod("amperage");
            classeEnergie = e.getClass();
        }
        return e;
    }

    static Object type(Object gt) throws ReflectiveOperationException {
        charger(gt);
        return type.get(gt);
    }

    static long tension(Object gt) throws ReflectiveOperationException {
        return ((Number) tension.invoke(energie(gt))).longValue();
    }

    /** Énergie d'une fabrication (EU) et sa durée (ticks). */
    static double[] cout(Object gt) throws ReflectiveOperationException {
        Object e = energie(gt);
        charger(gt);
        int ticks = duree.getInt(gt);
        long v = ((Number) tension.invoke(e)).longValue();
        long a = ((Number) amperes.invoke(e)).longValue();
        return new double[] {(double) v * a * ticks, ticks};
    }
}
