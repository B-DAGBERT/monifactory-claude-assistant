package fr.assistantia;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            interactionGetStack, getEmiStacks, getItemStack, isSearchFocused, getCategories, getWorkstations,
            getRecipesOfCategory, categoryGetName, getInputs, ingredientIsEmpty, getAmount, isLoaded;
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
            getCategories = gestion.getMethod("getCategories");
            getWorkstations = gestion.getMethod("getWorkstations", categorie);
            getRecipesOfCategory = gestion.getMethod("getRecipes", categorie);
            categoryGetName = categorie.getMethod("getName");
            getInputs = recette.getMethod("getInputs");
            ingredientIsEmpty = ingredient.getMethod("isEmpty");
            getAmount = ingredient.getMethod("getAmount");
            typeBouton = Enum.valueOf((Class) type, "FILL_BUTTON");
            destinationAucune = Enum.valueOf((Class) destination, "NONE");
            try {
                setGoal = Class.forName("dev.emi.emi.bom.BoM").getMethod("setGoal", recette);
            } catch (ReflectiveOperationException e) {
                setGoal = null; // l'arbre d'EMI n'est pas indispensable
            }
            try {
                isLoaded = Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            } catch (ReflectiveOperationException e) {
                isLoaded = null; // on se contente de voir des catégories
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
     * Toutes les recettes de l'objet que l'écran ouvert sait remplir, dans l'ordre de préférence : celle donnée par
     * l'assistant, puis celles de l'établi, puis les autres. Vide quand l'assistant le fait faire dans une machine :
     * l'autre façon de le faire à l'établi est un pis-aller (le Mortar donne 1 Clay Dust d'un bloc de Clay, le
     * Macerator 4).
     */
    public static List<Object> recettes(String idRecette, ItemStack produit, AbstractContainerScreen<?> ecran) {
        List<Object> liste = new ArrayList<>();
        if (!pret()) {
            return liste;
        }
        try {
            Object gestion = getRecipeManager.invoke(null);
            ResourceLocation id = idRecette == null || idRecette.isEmpty() ? null : ResourceLocation.tryParse(idRecette);
            Object donnee = id == null ? null : getRecipe.invoke(gestion, id);
            if (donnee != null) {
                if (!remplissable(donnee, ecran)) {
                    return liste;
                }
                liste.add(donnee);
            }
            List<Object> autres = new ArrayList<>();
            for (Object r : (List<?>) getRecipesByOutput.invoke(gestion, stackOf.invoke(null, produit))) {
                if (r == donnee || !remplissable(r, ecran)) {
                    continue;
                }
                boolean etabli = "minecraft:crafting".equals(String.valueOf(categoryGetId.invoke(getCategory.invoke(r))));
                (etabli ? liste : autres).add(r);
            }
            liste.addAll(autres);
        } catch (Throwable t) {
            // EMI pas prêt : rien à proposer pour l'instant
        }
        return liste;
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

    /**
     * Combien de fois la recette peut être faite avec ce stock (inventaire et grille), en tenant compte des
     * équivalents d'EMI (n'importe quelles planches). Un outil (Hammer, File…) sert à toutes les fabrications : il
     * en faut un, pas un par fabrication. 0 si un ingrédient manque, 64 au plus (une pile par case).
     */
    public static int possibles(Object recette, Map<Item, Integer> stock) {
        if (!pret() || recette == null) {
            return 0;
        }
        try {
            Map<Set<Item>, Long> parFabrication = new HashMap<>(); // un ingrédient = les objets qui conviennent
            Map<Set<Item>, Long> outils = new HashMap<>();
            for (Object ingredient : (List<?>) getInputs.invoke(recette)) {
                if ((boolean) ingredientIsEmpty.invoke(ingredient)) {
                    continue;
                }
                Set<Item> objets = new HashSet<>();
                boolean outil = false;
                for (Object stack : (List<?>) getEmiStacks.invoke(ingredient)) {
                    ItemStack objet = (ItemStack) getItemStack.invoke(stack);
                    if (!objet.isEmpty()) {
                        objets.add(objet.getItem());
                        outil |= objet.isDamageableItem();
                    }
                }
                if (objets.isEmpty()) {
                    return 0; // un fluide, par exemple : pas pour l'établi
                }
                (outil ? outils : parFabrication).merge(objets, (long) getAmount.invoke(ingredient), Long::sum);
            }
            for (Map.Entry<Set<Item>, Long> outil : outils.entrySet()) {
                if (disponible(outil.getKey(), stock) < outil.getValue()) {
                    return 0;
                }
            }
            long fois = 64;
            for (Map.Entry<Set<Item>, Long> besoin : parFabrication.entrySet()) {
                fois = Math.min(fois, disponible(besoin.getKey(), stock) / Math.max(1, besoin.getValue()));
            }
            return parFabrication.isEmpty() && outils.isEmpty() ? 0 : (int) fois;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long disponible(Set<Item> objets, Map<Item, Integer> stock) {
        long total = 0;
        for (Item objet : objets) {
            total += stock.getOrDefault(objet, 0);
        }
        return total;
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

    /**
     * Quelle machine fait quelles recettes : une ligne par catégorie d'EMI, « catégorie | nom | N recettes | machines ».
     * Les recettes exportées ne disent que leur catégorie (gtceu:alloy_smelter) : sans ça, impossible de savoir
     * à quoi sert une machine dont la catégorie ne porte pas le nom (la Steam Foundry, un multibloc...).
     * Vide tant qu'EMI n'a pas fini de charger les recettes du serveur.
     */
    public static List<String> postesDeTravail() {
        List<String> lignes = new ArrayList<>();
        if (!pret()) {
            return lignes;
        }
        try {
            Object gestion = getRecipeManager.invoke(null);
            List<?> categories = (List<?>) getCategories.invoke(gestion);
            if (categories.isEmpty() || isLoaded != null && !(boolean) isLoaded.invoke(null)) {
                return lignes;
            }
            lignes.add("# catégorie des recettes | nom | nombre de recettes | machines qui les font (ids)");
            for (Object categorie : categories) {
                List<String> machines = new ArrayList<>();
                for (Object ingredient : (List<?>) getWorkstations.invoke(gestion, categorie)) {
                    for (Object stack : (List<?>) getEmiStacks.invoke(ingredient)) {
                        ItemStack objet = (ItemStack) getItemStack.invoke(stack);
                        ResourceLocation id = objet.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(objet.getItem());
                        if (id != null && !machines.contains(id.toString())) {
                            machines.add(id.toString());
                        }
                    }
                }
                lignes.add(categoryGetId.invoke(categorie) + " | "
                        + ((Component) categoryGetName.invoke(categorie)).getString() + " | "
                        + ((List<?>) getRecipesOfCategory.invoke(gestion, categorie)).size() + " recettes | "
                        + String.join(", ", machines));
            }
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : machines des recettes non lues", t);
        }
        return lignes;
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
