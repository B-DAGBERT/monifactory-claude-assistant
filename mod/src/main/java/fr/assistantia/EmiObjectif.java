package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Import explicite dans EMI 1.1. Ne touche ni au plan, ni aux recettes favorites d'EMI. */
public final class EmiObjectif {
    public record Resultat(boolean succes, String message) {}

    private static Object gestionPrecedente, arbrePrecedent;
    private static boolean suspendu;
    private static String signaturePrecedente = "", avertissementPrecedent = "";

    private EmiObjectif() {}

    public static boolean disponible() {
        try {
            return (boolean)
                    Class.forName("dev.emi.emi.runtime.EmiReloadManager")
                            .getMethod("isLoaded")
                            .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /**
     * Retire l'arbre actif d'EMI et conserve les choix de l'objectif pour une réouverture
     * explicite.
     */
    public static void suspendre() {
        if (!Emi.present()) return;
        try {
            Class<?> bom = Class.forName("dev.emi.emi.bom.BoM");
            Field field = bom.getField("tree");
            Object tree = field.get(null);
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen != null
                    && mc.screen.getClass().getName().equals("dev.emi.emi.screen.BoMScreen"))
                mc.screen.onClose(); // Ne pas laisser l'écran EMI lire un arbre devenu nul.
            if (tree != null) suspendu = tree == arbrePrecedent;
            field.set(null, null);
            bom.getField("craftingMode").setBoolean(null, false);
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Assistant : arrêt de l’arbre EMI impossible", e);
        }
    }

    /** Construit avant de publier : un échec laisse l'arbre personnel d'EMI intact. */
    public static Resultat preparer(JsonObject objectif) {
        if (PauseObjectifEmi.enPause())
            return new Resultat(
                    false, "Objectif en pause : reprends-le avant de rouvrir son arbre EMI.");
        if (!disponible())
            return new Resultat(false, "EMI est absent ou charge encore ses recettes.");
        if (Pont.liste(objectif, "cibles").isEmpty())
            return new Resultat(
                    false, "Cet objectif ne contient pas encore de cible de fabrication.");
        try {
            Class<?> api = Class.forName("dev.emi.emi.api.EmiApi");
            Class<?> recipe = Class.forName("dev.emi.emi.api.recipe.EmiRecipe");
            Class<?> managerType = Class.forName("dev.emi.emi.api.recipe.EmiRecipeManager");
            Class<?> stackType = Class.forName("dev.emi.emi.api.stack.EmiStack");
            Class<?> treeType = Class.forName("dev.emi.emi.bom.MaterialTree");
            Class<?> bom = Class.forName("dev.emi.emi.bom.BoM");
            Object manager = api.getMethod("getRecipeManager").invoke(null);
            Field treeField = bom.getField("tree");
            String signature = objectif.toString();
            // Garder les branches dépliées et les choix locaux de l'utilisateur à la réouverture.
            if (manager == gestionPrecedente
                    && signature.equals(signaturePrecedente)
                    && (treeField.get(null) == arbrePrecedent
                            || suspendu && treeField.get(null) == null)) {
                treeField.set(null, arbrePrecedent);
                suspendu = false;
                return new Resultat(true, avertissementPrecedent);
            }
            Method of = stackType.getMethod("of", ItemStack.class, long.class);
            Method getRecipe = managerType.getMethod("getRecipe", ResourceLocation.class);
            Method getByOutput = managerType.getMethod("getRecipesByOutput", stackType);
            Method outputs = recipe.getMethod("getOutputs");
            Method supports = recipe.getMethod("supportsRecipeTree");
            Method getId = recipe.getMethod("getId");
            Method stackId = stackType.getMethod("getId");
            List<Object> targets = new ArrayList<>();
            Map<String, Object> stacks = new LinkedHashMap<>();
            for (JsonElement e : Pont.liste(objectif, "cibles")) {
                JsonObject cible = e.getAsJsonObject();
                String item = Pont.texte(cible, "item");
                ItemStack objet = objet(item);
                if (objet.isEmpty()) return new Resultat(false, "Objet inconnu dans EMI : " + item);
                long quantite =
                        Math.max(
                                1,
                                Math.min(1_000_000_000L, (long) Pont.nombre(cible, "quantite", 1)));
                Object stack = of.invoke(null, objet, quantite);
                targets.add(stack);
                stacks.put(item, of.invoke(null, objet, 1L));
            }
            Map<String, String> ids = new LinkedHashMap<>();
            for (JsonElement e : Pont.liste(objectif, "recettes")) {
                JsonObject r = e.getAsJsonObject();
                String item = Pont.texte(r, "item");
                ItemStack objet = objet(item);
                if (!objet.isEmpty()) stacks.putIfAbsent(item, of.invoke(null, objet, 1L));
                ids.put(item, Pont.texte(r, "id"));
            }
            Map<Object, Object> resolutions = new LinkedHashMap<>();
            List<String> manquantes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : stacks.entrySet()) {
                String item = entry.getKey(), id = ids.getOrDefault(item, "");
                Object r = null;
                if (!id.isEmpty()) {
                    ResourceLocation location = ResourceLocation.tryParse(id);
                    if (location != null) r = getRecipe.invoke(manager, location);
                    if (!produit(r, item, outputs, stackId, supports, getId)) r = null;
                } else {
                    // Sans recette choisie, ne pas deviner entre établi, machine et recyclage.
                    List<Object> candidates = new ArrayList<>();
                    for (Object candidate :
                            (List<?>) getByOutput.invoke(manager, entry.getValue())) {
                        if (produit(candidate, item, outputs, stackId, supports, getId))
                            candidates.add(candidate);
                    }
                    if (candidates.size() == 1) r = candidates.get(0);
                }
                resolutions.put(entry.getValue(), r); // null = branche à résoudre dans EMI
                if (r == null) manquantes.add(objet(item).getHoverName().getString());
            }
            ItemStack icone = new ItemStack(Items.BOOK);
            icone.setHoverName(Component.literal("Objectif : " + Pont.texte(objectif, "titre")));
            icone.getOrCreateTag().putString("assistantia_objectif", signature);
            Object rootStack = of.invoke(null, icone, 1L);
            Class<?> comparison = Class.forName("dev.emi.emi.api.stack.Comparison");
            stackType
                    .getMethod("comparison", comparison)
                    .invoke(rootStack, comparison.getMethod("compareNbt").invoke(null));
            Class<?> category = Class.forName("dev.emi.emi.api.recipe.EmiRecipeCategory");
            Object rootCategory =
                    category.getConstructor(
                                    ResourceLocation.class,
                                    Class.forName("dev.emi.emi.api.render.EmiRenderable"))
                            .newInstance(
                                    new ResourceLocation("assistantia", "objectif"), rootStack);
            Object rootRecipe =
                    RecetteObjectifEmi.creer(
                            recipe,
                            rootCategory,
                            new ResourceLocation("assistantia", "objectif"),
                            targets,
                            List.of(rootStack),
                            EmiObjectif::widgets);
            Object tree = treeType.getConstructor(recipe).newInstance(rootRecipe);
            // Résolutions limitées à cet arbre : aucun changement des préférences EMI globales.
            @SuppressWarnings("unchecked")
            Map<Object, Object> local =
                    (Map<Object, Object>) treeType.getField("resolutions").get(tree);
            local.putAll(resolutions);
            // Un ingrédient à plusieurs variantes (tag) doit aussi être relié au choix du plan.
            Class<?> ingredientType = Class.forName("dev.emi.emi.api.stack.EmiIngredient");
            Method getStacks = ingredientType.getMethod("getEmiStacks");
            Method inputs = recipe.getMethod("getInputs");
            var resolutionConstructor =
                    Class.forName("dev.emi.emi.api.recipe.EmiResolutionRecipe")
                            .getConstructor(ingredientType, stackType);
            for (Object r : resolutions.values()) {
                if (r == null) continue;
                for (Object ingredient : (List<?>) inputs.invoke(r)) {
                    List<?> alternatives = (List<?>) getStacks.invoke(ingredient);
                    if (alternatives.size() <= 1) continue;
                    List<Object> choisies = new ArrayList<>();
                    for (Object alternative : alternatives) {
                        Object choix = stacks.get(String.valueOf(stackId.invoke(alternative)));
                        if (choix != null && !choisies.contains(choix)) choisies.add(choix);
                    }
                    if (choisies.size() == 1)
                        local.put(
                                ingredient,
                                resolutionConstructor.newInstance(ingredient, choisies.get(0)));
                }
            }
            treeType.getMethod("recalculate").invoke(tree);
            treeType.getMethod("calculateCost").invoke(tree);
            treeField.set(null, tree);
            bom.getField("craftingMode").setBoolean(null, false);
            gestionPrecedente = manager;
            arbrePrecedent = tree;
            suspendu = false;
            signaturePrecedente = signature;
            avertissementPrecedent =
                    manquantes.isEmpty()
                            ? ""
                            : "Dans EMI, choisis la recette de : "
                                    + String.join(
                                            ", ",
                                            manquantes.subList(0, Math.min(3, manquantes.size())))
                                    + (manquantes.size() > 3 ? "…" : "");
            return new Resultat(true, avertissementPrecedent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LogUtils.getLogger()
                    .warn("Assistant : synchronisation de l'objectif EMI impossible", e);
            return new Resultat(
                    false, "Impossible de synchroniser EMI. Détails dans le journal du jeu.");
        }
    }

    private static ItemStack objet(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && ForgeRegistries.ITEMS.containsKey(location)
                ? new ItemStack(ForgeRegistries.ITEMS.getValue(location))
                : ItemStack.EMPTY;
    }

    private static boolean produit(
            Object r, String item, Method outputs, Method stackId, Method supports, Method recipeId)
            throws ReflectiveOperationException {
        if (r == null || !(boolean) supports.invoke(r)) return false;
        Object id = recipeId.invoke(r);
        if (id == null || id.toString().startsWith("emi_loot:")) return false;
        for (Object output : (List<?>) outputs.invoke(r))
            if (item.equals(String.valueOf(stackId.invoke(output)))) return true;
        return false;
    }

    private static void widgets(Object holder) {
        try {
            Class.forName("dev.emi.emi.api.widget.WidgetHolder")
                    .getMethod(
                            "addText",
                            Component.class,
                            int.class,
                            int.class,
                            int.class,
                            boolean.class)
                    .invoke(
                            holder,
                            Component.literal("Cibles de l’objectif"),
                            0,
                            0,
                            0x404040,
                            false);
        } catch (ReflectiveOperationException e) {
            LogUtils.getLogger().debug("Assistant : titre de l'objectif EMI indisponible", e);
        }
    }

    public static boolean afficher() {
        try {
            Class.forName("dev.emi.emi.api.EmiApi").getMethod("viewRecipeTree").invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Assistant : ouverture de l'arbre EMI impossible", e);
            return false;
        }
    }
}
