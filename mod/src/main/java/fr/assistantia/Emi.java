package fr.assistantia;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Accès à EMI (le JEI du pack) par réflexion : le mod se compile sans EMI et continue de marcher sans lui.
 * Remplir la grille d'un établi comme le bouton « + » d'EMI, ouvrir les recettes d'un objet, ou l'arbre de
 * fabrication d'EMI avec un objectif. Les noms viennent de l'API d'EMI 1.1 (dev.emi.emi).
 */
public final class Emi {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean charge;
    private static boolean present;
    private static Method getRecipeManager, getRecipe, getRecipesByOutput, stackOf, displayRecipes, viewRecipeTree,
            performFill, getFirstValidHandler, setGoal, getCategory, categoryGetId, displayUses, getHoveredStack,
            interactionGetStack, getEmiStacks, getItemStack, isSearchFocused;
    private static Object typeBouton, destinationAucune;

    private Emi() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean pret() {
        if (charge) {
            return present;
        }
        charge = true;
        try {
            Class<?> api = Class.forName("dev.emi.emi.api.EmiApi");
            Class<?> gestion = Class.forName("dev.emi.emi.api.recipe.EmiRecipeManager");
            Class<?> recette = Class.forName("dev.emi.emi.api.recipe.EmiRecipe");
            Class<?> categorie = Class.forName("dev.emi.emi.api.recipe.EmiRecipeCategory");
            Class<?> stack = Class.forName("dev.emi.emi.api.stack.EmiStack");
            Class<?> ingredient = Class.forName("dev.emi.emi.api.stack.EmiIngredient");
            Class<?> remplisseur = Class.forName("dev.emi.emi.registry.EmiRecipeFiller");
            Class<?> type = Class.forName("dev.emi.emi.api.recipe.handler.EmiCraftContext$Type");
            Class<?> destination = Class.forName("dev.emi.emi.api.recipe.handler.EmiCraftContext$Destination");
            getRecipeManager = api.getMethod("getRecipeManager");
            getRecipe = gestion.getMethod("getRecipe", ResourceLocation.class);
            getRecipesByOutput = gestion.getMethod("getRecipesByOutput", stack);
            stackOf = stack.getMethod("of", ItemStack.class);
            displayRecipes = api.getMethod("displayRecipes", ingredient);
            viewRecipeTree = api.getMethod("viewRecipeTree");
            performFill = remplisseur.getMethod("performFill", recette, AbstractContainerScreen.class, type, destination, int.class);
            getFirstValidHandler = remplisseur.getMethod("getFirstValidHandler", recette, AbstractContainerScreen.class);
            getCategory = recette.getMethod("getCategory");
            categoryGetId = categorie.getMethod("getId");
            displayUses = api.getMethod("displayUses", ingredient);
            getHoveredStack = api.getMethod("getHoveredStack", boolean.class);
            interactionGetStack = Class.forName("dev.emi.emi.api.stack.EmiStackInteraction").getMethod("getStack");
            getEmiStacks = ingredient.getMethod("getEmiStacks");
            getItemStack = stack.getMethod("getItemStack");
            isSearchFocused = api.getMethod("isSearchFocused");
            typeBouton = Enum.valueOf((Class) type, "FILL_BUTTON");
            destinationAucune = Enum.valueOf((Class) destination, "NONE");
            try {
                setGoal = Class.forName("dev.emi.emi.bom.BoM").getMethod("setGoal", recette);
            } catch (ReflectiveOperationException e) {
                setGoal = null; // l'arbre d'EMI n'est pas indispensable
            }
            present = true;
        } catch (Throwable t) {
            LOGGER.info("Assistant IA : EMI introuvable, pas de remplissage automatique ({})", t.toString());
            present = false;
        }
        return present;
    }

    public static boolean present() {
        return pret();
    }

    /**
     * La recette EMI à utiliser : celle donnée par l'assistant si on la connaît, sinon une recette qui produit
     * l'objet et que l'écran ouvert sait remplir (l'établi d'abord).
     */
    public static Object recette(String idRecette, ItemStack produit, AbstractContainerScreen<?> ecran) {
        if (!pret()) {
            return null;
        }
        try {
            Object gestion = getRecipeManager.invoke(null);
            if (idRecette != null && !idRecette.isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(idRecette);
                Object r = id == null ? null : getRecipe.invoke(gestion, id);
                if (r != null && (ecran == null || remplissable(r, ecran))) {
                    return r;
                }
            }
            List<?> candidates = (List<?>) getRecipesByOutput.invoke(gestion, stackOf.invoke(null, produit));
            Object premiere = null;
            for (Object r : candidates) {
                if (ecran != null && !remplissable(r, ecran)) {
                    continue;
                }
                if ("minecraft:crafting".equals(String.valueOf(categoryGetId.invoke(getCategory.invoke(r))))) {
                    return r;
                }
                if (premiere == null) {
                    premiere = r;
                }
            }
            return premiere;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean remplissable(Object recette, AbstractContainerScreen<?> ecran) {
        try {
            return recette != null && getFirstValidHandler.invoke(null, recette, ecran) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Range les ingrédients dans la grille, comme le bouton « + » d'EMI (fois = nombre de fabrications). */
    public static boolean remplir(Object recette, AbstractContainerScreen<?> ecran, int fois) {
        try {
            return (boolean) performFill.invoke(null, recette, ecran, typeBouton, destinationAucune, Math.max(1, fois));
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : remplissage impossible", t);
            return false;
        }
    }

    /** Ouvre les recettes d'un objet dans EMI. */
    public static void afficherRecettes(ItemStack objet) {
        if (!pret()) {
            return;
        }
        try {
            displayRecipes.invoke(null, stackOf.invoke(null, objet));
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : recettes non affichées", t);
        }
    }

    /** Ouvre les utilisations d'un objet dans EMI (pour un contrôleur GregTech : la structure du multibloc). */
    public static void afficherUtilisations(ItemStack objet) {
        if (!pret()) {
            return;
        }
        try {
            displayUses.invoke(null, stackOf.invoke(null, objet));
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : utilisations non affichées", t);
        }
    }

    /** L'objet sous la souris dans la liste, les favoris ou une recette d'EMI, ou un objet vide. */
    public static ItemStack objetSurvole() {
        if (!pret()) {
            return ItemStack.EMPTY;
        }
        try {
            Object interaction = getHoveredStack.invoke(null, true);
            Object ingredient = interaction == null ? null : interactionGetStack.invoke(interaction);
            List<?> stacks = ingredient == null ? List.of() : (List<?>) getEmiStacks.invoke(ingredient);
            return stacks.isEmpty() ? ItemStack.EMPTY : (ItemStack) getItemStack.invoke(stacks.get(0));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** Vrai quand on écrit dans la recherche d'EMI : les touches sont alors du texte. */
    public static boolean rechercheActive() {
        try {
            return pret() && (boolean) isSearchFocused.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Met l'objectif dans l'arbre de fabrication d'EMI et l'ouvre : EMI suit ce que le joueur a déjà. */
    public static boolean ouvrirArbre(String idRecette, ItemStack produit) {
        Object r = recette(idRecette, produit, null);
        if (r == null || setGoal == null) {
            return false;
        }
        try {
            setGoal.invoke(null, r);
            viewRecipeTree.invoke(null);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : arbre d'EMI non ouvert", t);
            return false;
        }
    }
}
