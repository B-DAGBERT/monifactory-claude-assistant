package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.Comparison;

public final class ComparaisonEmi {
    private ComparaisonEmi() {}

    private static final Class<?> OUTIL_GT = classeOutil();

    private static Class<?> classeOutil() {
        try {
            return Class.forName(
                    "com.gregtechceu.gtceu.api.item.IGTTool",
                    false,
                    ComparaisonEmi.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Garder la règle EMI des outils GT : type et matériau appartiennent à l'Item. */
    public static EmiStack pourInventaire(EmiStack source) {
        var item = source.getItemStack();
        boolean outil = !item.isEmpty() && OUTIL_GT != null && OUTIL_GT.isInstance(item.getItem());
        return source.copy()
                .comparison(
                        c ->
                                c == Comparison.DEFAULT_COMPARISON && !outil
                                        ? Comparison.compareNbt()
                                        : c);
    }

    public static boolean circuit(EmiStack s) {
        return s.getId().toString().equals("gtceu:programmed_circuit");
    }

    /** Un catalyseur GT représente le réglage de la machine, jamais un objet à collecter. */
    public static boolean circuitVirtuel(
            EmiRecipe parent, EmiIngredient ingredient, boolean catalyst) {
        return parent != null
                && parent.getClass()
                        .getName()
                        .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe")
                && catalyst
                && !ingredient.getEmiStacks().isEmpty()
                && ingredient.getEmiStacks().stream().allMatch(ComparaisonEmi::circuit);
    }

    public static boolean meme(EmiStack reel, EmiStack attendu) {
        return circuit(attendu) ? circuit(reel) : reel.isEqual(attendu);
    }
}
