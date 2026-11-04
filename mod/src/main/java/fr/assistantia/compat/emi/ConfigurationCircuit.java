package fr.assistantia.compat.emi;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;

/** Configuration exigée par la recette GregTech, indépendamment de son aperçu EMI. */
public final class ConfigurationCircuit {
    private ConfigurationCircuit() {}

    public static int lire(Object gt) throws ReflectiveOperationException {
        if (gt == null) throw new IllegalArgumentException("Recette GregTech absente");
        Map<?, ?> inputs = (Map<?, ?>) champ(gt.getClass(), "inputs").get(gt);
        Integer valeur = null;
        for (Object contents : inputs.values())
            for (Object c : (List<?>) contents) {
                Object ingredient = champ(c.getClass(), "content").get(c);
                for (int depth = 0; depth < 8 && ingredient != null; depth++) {
                    String name = ingredient.getClass().getName();
                    if (name.equals(
                            "com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient")) {
                        int config =
                                champ(ingredient.getClass(), "configuration").getInt(ingredient);
                        if (config < 0 || config > 32 || valeur != null && valeur != config)
                            throw new IllegalArgumentException(
                                    "Configurations de circuit incompatibles");
                        valeur = config;
                        break;
                    }
                    if (!name.equals("com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient"))
                        break;
                    ingredient = ingredient.getClass().getMethod("getInner").invoke(ingredient);
                }
            }
        if (valeur == null) throw new IllegalArgumentException("Configuration du circuit inconnue");
        return valeur;
    }

    private static Field champ(Class<?> type, String nom) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(nom);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(nom);
    }
}
