package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import java.util.ArrayList;
import java.util.List;

/** Les catalyseurs physiques GT séparés des entrées restent nécessaires au plan. */
public final class CatalyseursArbreEmi {
    private CatalyseursArbreEmi() {}

    public static void completer(Object node, EmiRecipe recipe)
            throws ReflectiveOperationException {
        if (recipe == null
                || !recipe.getClass()
                        .getName()
                        .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe")) return;
        var type = node.getClass();
        var champEnfants = type.getField("children");
        var champCatalyseur = type.getField("catalyst");
        var champChance = type.getField("consumeChance");
        var champIngredient = type.getField("ingredient");
        var champQuantite = type.getField("amount");
        var enfants = new ArrayList<Object>((List<?>) champEnfants.get(node));
        for (var ingredient : recipe.getCatalysts()) {
            if (ingredient.isEmpty() || ComparaisonEmi.circuitVirtuel(recipe, ingredient, true))
                continue;
            Object catalyseur = null;
            for (var enfant : enfants)
                if ((champCatalyseur.getBoolean(enfant) || champChance.getFloat(enfant) == 0)
                        && memes((EmiIngredient) champIngredient.get(enfant), ingredient)) {
                    catalyseur = enfant;
                    break;
                }
            if (catalyseur == null) {
                catalyseur = type.getConstructor(EmiIngredient.class).newInstance(ingredient);
                enfants.add(catalyseur);
            } else {
                champQuantite.setLong(
                        catalyseur,
                        Math.max(champQuantite.getLong(catalyseur), ingredient.getAmount()));
            }
            // Dans TreeCost, catalyst évite le débit ; une chance de 0 empêche de lire le stock.
            champCatalyseur.setBoolean(catalyseur, true);
            champChance.setFloat(catalyseur, 1);
        }
        champEnfants.set(node, enfants);
    }

    private static boolean memes(EmiIngredient a, EmiIngredient b) {
        return a.getEmiStacks().stream()
                        .allMatch(
                                v ->
                                        b.getEmiStacks().stream()
                                                .anyMatch(w -> ComparaisonEmi.meme(v, w)))
                && b.getEmiStacks().stream()
                        .allMatch(
                                v ->
                                        a.getEmiStacks().stream()
                                                .anyMatch(w -> ComparaisonEmi.meme(v, w)));
    }
}
