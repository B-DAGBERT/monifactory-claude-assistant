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

    /**
     * Le même objet qu'attendu. Un outil (outil GregTech, électrique compris, ou objet qui s'use)
     * est reconnu par son objet seul, quelle que soit son usure ou sa charge, comme le fait la
     * recette dans le jeu : avant, un outil entamé ou chargé n'était pas toujours surligné.
     */
    public static boolean meme(EmiStack reel, EmiStack attendu) {
        if (circuit(attendu)) return circuit(reel);
        if (reel.isEqual(attendu)) return true;
        var objet = attendu.getItemStack();
        return !objet.isEmpty()
                && outil(objet)
                && !reel.getItemStack().isEmpty()
                && reel.getItemStack().getItem() == objet.getItem();
    }

    private static boolean outil(net.minecraft.world.item.ItemStack objet) {
        return objet.isDamageableItem() || OUTIL_GT != null && OUTIL_GT.isInstance(objet.getItem());
    }
}
