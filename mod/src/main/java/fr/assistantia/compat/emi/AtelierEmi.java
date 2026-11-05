package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import fr.assistantia.AideEcran;
import fr.assistantia.Emi;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Propositions d'établi issues des branches choisies, validées par le remplisseur natif EMI. */
public final class AtelierEmi {
    private static Method trouverRemplisseur;

    private AtelierEmi() {}

    /** null signifie qu'aucun arbre n'est actif ; une liste vide reste un résultat d'arbre. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<AideEcran.Proposition> propositions(
            AbstractContainerScreen<?> ecran, Map<Item, Integer> stock, int maximum)
            throws ReflectiveOperationException {
        if (CoffresArbreEmi.arbreActif() == null) return null;
        if (trouverRemplisseur == null)
            trouverRemplisseur =
                    Class.forName("dev.emi.emi.registry.EmiRecipeFiller")
                            .getMethod(
                                    "getFirstValidHandler",
                                    EmiRecipe.class,
                                    AbstractContainerScreen.class);
        var resultat = new ArrayList<AideEcran.Proposition>();
        for (var ligne : MachinesEmi.lignes()) {
            EmiRecipe recette = ligne.recette();
            if (ligne.lot() >= 0
                    || ligne.fois() <= 0
                    || !"minecraft:crafting".equals(recette.getCategory().getId().toString()))
                continue;
            EmiRecipeHandler remplisseur =
                    (EmiRecipeHandler) trouverRemplisseur.invoke(null, recette, ecran);
            if (remplisseur == null || !remplisseur.supportsRecipe(recette)) continue;
            // L'inventaire du handler contient les cases qu'il peut déplacer. Le stock du
            // backpack compte dans le plan, mais ne permet pas de remplir directement la grille.
            var contexte =
                    new EmiCraftContext(
                            ecran,
                            remplisseur.getInventory(ecran),
                            EmiCraftContext.Type.FILL_BUTTON);
            if (!remplisseur.canCraft(recette, contexte)) continue;
            ItemStack produit =
                    recette.getOutputs().stream()
                            .map(s -> s.getItemStack())
                            .filter(s -> !s.isEmpty())
                            .findFirst()
                            .orElse(ItemStack.EMPTY);
            if (produit.isEmpty()) continue;
            int possibles = Emi.possibles(recette, stock);
            if (possibles <= 0) continue;
            resultat.add(
                    new AideEcran.Proposition(
                            recette,
                            produit,
                            produit.getHoverName().getString(),
                            ligne.fois(),
                            possibles));
            if (resultat.size() >= maximum) break;
        }
        return resultat;
    }
}
