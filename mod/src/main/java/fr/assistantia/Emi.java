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
import java.util.function.BiConsumer;

/**
 * Accès à EMI (le JEI du pack) par réflexion : le mod se compile sans EMI et continue de marcher
 * sans lui. L'adaptateur remplit la grille d'un établi comme le bouton « + » et ouvre les recettes
 * d'un objet avec l'API d'EMI 1.1 (dev.emi.emi).
 */
public final class Emi {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean charge;
    private static boolean present;
    private static Method getRecipeManager,
            stackOf,
            displayRecipes,
            performFill,
            categoryGetId,
            displayUses,
            getHoveredStack,
            interactionGetStack,
            getEmiStacks,
            getItemStack,
            isSearchFocused,
            getCategories,
            getWorkstations,
            getRecipesOfCategory,
            categoryGetName,
            getInputs,
            ingredientIsEmpty,
            getAmount,
            isLoaded;
    private static Object typeBouton, destinationAucune;
    private static Method planCoffre, receptionCoffre;

    private Emi() {}

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
            Class<?> destination =
                    Class.forName("dev.emi.emi.api.recipe.handler.EmiCraftContext$Destination");
            getRecipeManager = api.getMethod("getRecipeManager");
            stackOf = stack.getMethod("of", ItemStack.class);
            displayRecipes = api.getMethod("displayRecipes", ingredient);
            performFill =
                    remplisseur.getMethod(
                            "performFill",
                            recette,
                            AbstractContainerScreen.class,
                            type,
                            destination,
                            int.class);
            categoryGetId = categorie.getMethod("getId");
            displayUses = api.getMethod("displayUses", ingredient);
            getHoveredStack = api.getMethod("getHoveredStack", boolean.class);
            interactionGetStack =
                    Class.forName("dev.emi.emi.api.stack.EmiStackInteraction")
                            .getMethod("getStack");
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
                isLoaded =
                        Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            } catch (ReflectiveOperationException e) {
                isLoaded = null; // on se contente de voir des catégories
            }
            present = true;
        } catch (Throwable t) {
            LOGGER.info(
                    "Assistant IA : EMI introuvable, pas de remplissage automatique ({})",
                    t.toString());
            present = false;
        }
        return present;
    }

    public static boolean present() {
        return pret();
    }

    /**
     * Combien de fois la recette peut être faite avec ce stock (inventaire et grille), en tenant
     * compte des équivalents d'EMI (n'importe quelles planches). Un outil (Hammer, File…) sert à
     * toutes les fabrications : il en faut un, pas un par fabrication. 0 si un ingrédient manque,
     * 64 au plus (une pile par case).
     */
    public static int possibles(Object recette, Map<Item, Integer> stock) {
        if (!pret() || recette == null) {
            return 0;
        }
        try {
            Map<Set<Item>, Long> parFabrication =
                    new HashMap<>(); // un ingrédient = les objets qui conviennent
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
                (outil ? outils : parFabrication)
                        .merge(objets, (long) getAmount.invoke(ingredient), Long::sum);
            }
            for (Map.Entry<Set<Item>, Long> outil : outils.entrySet()) {
                if (disponible(outil.getKey(), stock) < outil.getValue()) {
                    return 0;
                }
            }
            long fois = 64;
            for (Map.Entry<Set<Item>, Long> besoin : parFabrication.entrySet()) {
                fois =
                        Math.min(
                                fois,
                                disponible(besoin.getKey(), stock)
                                        / Math.max(1, besoin.getValue()));
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

    /**
     * Range les ingrédients dans la grille, comme le bouton « + » d'EMI (fois = nombre de
     * fabrications).
     */
    public static boolean remplir(Object recette, AbstractContainerScreen<?> ecran, int fois) {
        try {
            return (boolean)
                    performFill.invoke(
                            null, recette, ecran, typeBouton, destinationAucune, Math.max(1, fois));
        } catch (Throwable t) {
            LOGGER.warn("Assistant IA : remplissage impossible", t);
            return false;
        }
    }

    public static Map<String, Integer> fabricationsConfirmees() {
        return fabrications("totaux");
    }

    public static Map<String, Integer> fabricationsCollecte() {
        return fabrications("totauxCollecte");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> fabrications(String methode) {
        if (!pret()) return Map.of();
        try {
            return (Map<String, Integer>)
                    Class.forName("fr.assistantia.compat.emi.OrigineFabrication")
                            .getMethod(methode)
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Integer> reservationsFabrication() {
        if (!pret()) return Map.of();
        try {
            return (Map<String, Integer>)
                    Class.forName("fr.assistantia.compat.emi.MachinesEmi")
                            .getMethod("reservationsCollecte")
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Integer> outilsMachines() {
        if (!pret()) return Map.of();
        try {
            return (Map<String, Integer>)
                    Class.forName("fr.assistantia.compat.emi.MachinesEmi")
                            .getMethod("outilsCollecte")
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Map.of();
        }
    }

    public static boolean envoiMachineEnCours() {
        if (!pret()) return false;
        try {
            return (boolean)
                    Class.forName("fr.assistantia.compat.emi.EnvoiMachine")
                            .getMethod("occupe")
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<PlanRetrait.Stock<ItemStack>> creditsFabrication() {
        if (!pret()) return List.of();
        try {
            return (List<PlanRetrait.Stock<ItemStack>>)
                    Class.forName("fr.assistantia.compat.emi.MachinesEmi")
                            .getMethod("creditsObjets")
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return List.of();
        }
    }

    public static boolean arbreActif() {
        if (!present()) return false;
        try {
            return ArbreEmi.actif() != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** Quantités exactes à prendre dans le coffre, après déduction de l'inventaire. */
    public static RetraitCoffre.Plan retraitArbre(AbstractContainerScreen<?> ecran) {
        if (pret())
            try {
                if (planCoffre == null)
                    planCoffre =
                            Class.forName("fr.assistantia.compat.emi.RecuperationEmi")
                                    .getMethod("planArbre", AbstractContainerScreen.class);
                return (RetraitCoffre.Plan) planCoffre.invoke(null, ecran);
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Assistant IA : calcul du retrait de l'arbre impossible", e);
            }
        return new RetraitCoffre.Plan(List.of(), 0);
    }

    @SuppressWarnings("unchecked")
    public static BiConsumer<ItemStack, Integer> receptionRetraitCoffre(
            AbstractContainerScreen<?> ecran, RetraitCoffre.Plan plan) {
        if (pret())
            try {
                if (receptionCoffre == null)
                    receptionCoffre =
                            Class.forName("fr.assistantia.compat.emi.RecuperationEmi")
                                    .getMethod(
                                            "reception",
                                            AbstractContainerScreen.class,
                                            RetraitCoffre.Plan.class);
                return (BiConsumer<ItemStack, Integer>) receptionCoffre.invoke(null, ecran, plan);
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("Assistant IA : suivi du retrait EMI indisponible", e);
            }
        return (pile, quantite) -> {};
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

    /**
     * Ouvre les utilisations d'un objet dans EMI (pour un contrôleur GregTech : la structure du
     * multibloc).
     */
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
            Object ingredient =
                    interaction == null ? null : interactionGetStack.invoke(interaction);
            List<?> stacks =
                    ingredient == null ? List.of() : (List<?>) getEmiStacks.invoke(ingredient);
            return stacks.isEmpty()
                    ? ItemStack.EMPTY
                    : (ItemStack) getItemStack.invoke(stacks.get(0));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Quelle machine fait quelles recettes : une ligne par catégorie d'EMI, « catégorie | nom | N
     * recettes | machines ». Les recettes exportées ne disent que leur catégorie
     * (gtceu:alloy_smelter) : sans ça, impossible de savoir à quoi sert une machine dont la
     * catégorie ne porte pas le nom (la Steam Foundry, un multibloc...). Vide tant qu'EMI n'a pas
     * fini de charger les recettes du serveur.
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
            lignes.add(
                    "# catégorie des recettes | nom | nombre de recettes | machines qui les font (ids)");
            for (Object categorie : categories) {
                List<String> machines = new ArrayList<>();
                for (Object ingredient : (List<?>) getWorkstations.invoke(gestion, categorie)) {
                    for (Object stack : (List<?>) getEmiStacks.invoke(ingredient)) {
                        ItemStack objet = (ItemStack) getItemStack.invoke(stack);
                        ResourceLocation id =
                                objet.isEmpty()
                                        ? null
                                        : ForgeRegistries.ITEMS.getKey(objet.getItem());
                        if (id != null && !machines.contains(id.toString())) {
                            machines.add(id.toString());
                        }
                    }
                }
                lignes.add(
                        categoryGetId.invoke(categorie)
                                + " | "
                                + ((Component) categoryGetName.invoke(categorie)).getString()
                                + " | "
                                + ((List<?>) getRecipesOfCategory.invoke(gestion, categorie)).size()
                                + " recettes | "
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
}
