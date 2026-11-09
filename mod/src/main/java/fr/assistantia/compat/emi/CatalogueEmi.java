package fr.assistantia.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Le catalogue exact et borné de la sélection EMI. Aucun parcours des 134000 recettes du pack. */
public final class CatalogueEmi {
    private record Node(
            EmiRecipe recipe, List<JsonObject> options, JsonObject selected, List<Node> children) {}

    private final Object tree, root;
    private final long batches, revision;
    private final EmiRecipeManager manager;
    private final TreeMap<String, EmiRecipe> recipes = new TreeMap<>();
    private final TreeMap<String, JsonObject> converted = new TreeMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final JsonObject metadata = new JsonObject();
    private final Node goal;
    private final List<EmiRecipe> recipeList;
    private int cursor, proofEntries;
    private final long captureDeadline = System.nanoTime() + 100_000_000L;

    public static CatalogueEmi capturer() throws ReflectiveOperationException {
        return new CatalogueEmi();
    }

    private CatalogueEmi() throws ReflectiveOperationException {
        if (!charge()) throw new IllegalArgumentException("EMI charge encore les recettes.");
        tree = ArbreEmi.consulte();
        if (tree == null)
            throw new IllegalArgumentException("Ouvre un Recipe Tree EMI avant de calculer.");
        manager = EmiApi.getRecipeManager();
        root = tree.getClass().getField("goal").get(tree);
        batches = tree.getClass().getField("batches").getLong(tree);
        revision = InstantaneArbreEmi.revisionProduction();
        goal =
                node(
                        root,
                        null,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        0,
                        new int[] {0});
        if (goal.recipe() == null || goal.selected() == null || recipes.isEmpty())
            throw new IllegalArgumentException(
                    "La cible du Recipe Tree doit être une recette explicite.");
        recipeList = List.copyOf(recipes.values());
        long amount = root.getClass().getField("amount").getLong(root);
        long quantity = Math.multiplyExact(amount, batches);
        if (quantity <= 0) throw new IllegalArgumentException("Quantité EMI invalide.");
        JsonObject target = new JsonObject();
        target.add("resource", goal.selected().deepCopy());
        target.addProperty("quantity", quantity);
        metadata.add("cible", target);
        metadata.addProperty("lots_emi", batches);
        metadata.addProperty("recette_racine", goal.recipe().getId().toString());
        metadata.addProperty("portee", "arbre_emi");
        metadata.addProperty("recettes_total", recipes.size());
        if (!valide())
            throw new IllegalArgumentException("L'arbre EMI a changé pendant sa lecture.");
    }

    private Node node(
            Object object, EmiRecipe parentRecipe, Set<Object> path, int depth, int[] visited)
            throws ReflectiveOperationException {
        if (System.nanoTime() > captureDeadline)
            throw new IllegalArgumentException(
                    "Lecture de l’arbre trop coûteuse ; réduis la sélection EMI.");
        if (object == null || depth > 128 || ++visited[0] > 4096 || !path.add(object))
            throw new IllegalArgumentException(
                    "Arbre EMI trop grand ou cyclique ; aucune troncature.");
        try {
            var type = object.getClass();
            var ingredient = (EmiIngredient) type.getField("ingredient").get(object);
            var recipe = (EmiRecipe) type.getField("recipe").get(object);
            boolean reusable =
                    type.getField("catalyst").getBoolean(object)
                            || type.getField("consumeChance").getFloat(object) == 0;
            if (ComparaisonEmi.circuitVirtuel(parentRecipe, ingredient, reusable)) return null;
            proofEntries = Math.addExact(proofEntries, ingredient.getEmiStacks().size());
            if (proofEntries > 16384)
                throw new IllegalArgumentException(
                        "Trop d’alternatives dans cet arbre ; aucune troncature.");
            if (ingredient.getEmiStacks().size() > 4096)
                throw new IllegalArgumentException("Alternatives EMI trop nombreuses.");
            TreeMap<String, JsonObject> options = new TreeMap<>();
            for (EmiStack stack : ingredient.getEmiStacks()) {
                JsonObject resource = RecetteProduction.resource(stack);
                options.put(resource.toString(), resource);
            }
            JsonObject selected = options.size() == 1 ? options.firstEntry().getValue() : null;
            if (recipe != null
                    && recipe.getClass()
                            .getName()
                            .equals("dev.emi.emi.api.recipe.EmiResolutionRecipe")) {
                var chosen = (EmiStack) recipe.getClass().getField("stack").get(recipe);
                JsonObject resolved = RecetteProduction.resource(chosen);
                if (!options.containsKey(resolved.toString()))
                    throw new IllegalArgumentException("Alternative EMI hors ingrédient");
                selected = resolved;
                recipe = null; // Native variant selection, never a fabricated self-conversion
                // recipe.
            }
            if (recipe != null) {
                if (recipe.getId() == null)
                    throw new IllegalArgumentException("Recette sélectionnée sans identifiant.");
                String id = recipe.getId().toString();
                EmiRecipe prior = recipes.putIfAbsent(id, recipe);
                if (prior != null && prior != recipe)
                    throw new IllegalArgumentException(
                            "Deux recettes distinctes portent le même identifiant EMI.");
                if (manager.getRecipe(recipe.getId()) != recipe)
                    throw new IllegalArgumentException(
                            "Une recette sélectionnée n'appartient plus à la génération EMI.");
                TreeMap<String, JsonObject> produced = new TreeMap<>();
                proofEntries = Math.addExact(proofEntries, recipe.getOutputs().size());
                if (proofEntries > 16384)
                    throw new IllegalArgumentException(
                            "Trop de preuves de sorties dans cet arbre.");
                for (var output : recipe.getOutputs()) {
                    JsonObject resource = RecetteProduction.resource(output);
                    if (options.containsKey(resource.toString()))
                        produced.put(resource.toString(), resource);
                }
                if (produced.size() == 1) selected = produced.firstEntry().getValue();
            }
            List<Node> children = new ArrayList<>();
            if (type.getField("children").get(object) instanceof List<?> descendants)
                for (Object child : descendants) {
                    Node captured = node(child, recipe, path, depth + 1, visited);
                    if (captured != null) children.add(captured);
                }
            Node result =
                    new Node(
                            recipe, List.copyOf(options.values()), selected, List.copyOf(children));
            nodes.add(result);
            return result;
        } finally {
            path.remove(object);
        }
    }

    public boolean valide() throws ReflectiveOperationException {
        return charge()
                && EmiApi.getRecipeManager() == manager
                && ArbreEmi.consulte() == tree
                && tree.getClass().getField("goal").get(tree) == root
                && tree.getClass().getField("batches").getLong(tree) == batches
                && InstantaneArbreEmi.revisionProduction() == revision;
    }

    private static boolean charge() throws ReflectiveOperationException {
        return (boolean)
                Class.forName("dev.emi.emi.runtime.EmiReloadManager")
                        .getMethod("isLoaded")
                        .invoke(null);
    }

    /** Au plus huit recettes et environ deux millisecondes entre deux ticks. */
    public boolean avancer() throws ReflectiveOperationException {
        if (!valide())
            throw new IllegalArgumentException(
                    "L'arbre ou les recettes EMI ont changé. Relance le calcul.");
        long start = System.nanoTime();
        int count = 0;
        while (cursor < recipeList.size() && count++ < 8) {
            var recipe = recipeList.get(cursor++);
            converted.put(recipe.getId().toString(), RecetteProduction.lire(recipe, manager));
            if (System.nanoTime() - start >= 2_000_000) break;
        }
        return cursor == recipeList.size();
    }

    public JsonObject metadata() {
        JsonObject result = metadata.deepCopy();
        result.addProperty("recettes_lues", cursor);
        return result;
    }

    public JsonObject catalogue(long generation) {
        if (cursor != recipes.size())
            throw new IllegalStateException("Catalogue sélectionné incomplet.");
        JsonObject result = new JsonObject();
        result.addProperty("schema", "monifactory.production.recipes");
        result.addProperty("version", 1);
        result.addProperty("generation", generation);
        JsonArray rows = new JsonArray();
        converted.values().forEach(rows::add);
        result.add("recipes", rows);
        return result;
    }

    public JsonObject selection() {
        JsonObject result = metadata();
        JsonArray limits = new JsonArray();
        limits.add(
                "Portée limitée aux recettes sélectionnées dans cet arbre EMI ; les recettes hors sélection sont inconnues.");
        limits.add(
                "Les feuilles sans recette restent à obtenir. L'usure des outils et les conditions non interprétées bloquent le calcul exact.");
        TreeMap<String, JsonObject> choices = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        for (Node node : nodes) {
            if (node.recipe() == null || node.selected() == null) continue;
            JsonObject entry = new JsonObject();
            entry.add("resource", node.selected());
            entry.addProperty("recipe", node.recipe().getId().toString());
            String key = node.selected().toString();
            JsonObject previous = choices.putIfAbsent(key, entry);
            if (previous != null && !previous.equals(entry)) conflicts.add(key);
        }
        conflicts.forEach(choices::remove);
        if (!conflicts.isEmpty())
            limits.add(
                    "Deux branches choisissent des recettes différentes pour la même ressource : choix global non déduit.");
        JsonArray recipeChoices = new JsonArray();
        choices.values().forEach(recipeChoices::add);
        result.add("choix_recettes", recipeChoices);
        TreeMap<String, JsonObject> ingredientChoices = new TreeMap<>();
        Set<String> ingredientConflicts = new TreeSet<>();
        for (Node node : nodes) {
            if (node.recipe() == null) continue;
            String id = node.recipe().getId().toString();
            JsonArray inputs = converted.get(id).getAsJsonArray("inputs");
            for (int i = 0; i < inputs.size(); i++) {
                JsonArray options = inputs.get(i).getAsJsonObject().getAsJsonArray("options");
                if (options.size() < 2) continue;
                Set<String> resources = new TreeSet<>();
                options.forEach(a -> resources.add(a.getAsJsonObject().get("resource").toString()));
                TreeMap<String, JsonObject> chosen = new TreeMap<>();
                for (Node child : node.children()) {
                    Set<String> childResources = new TreeSet<>();
                    child.options().forEach(a -> childResources.add(a.toString()));
                    if (child.selected() != null && resources.equals(childResources))
                        chosen.put(child.selected().toString(), child.selected());
                }
                if (chosen.size() != 1) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("recipe", id);
                entry.addProperty("index", i);
                entry.add("resource", chosen.firstEntry().getValue());
                String key = id + "#" + i;
                JsonObject prior = ingredientChoices.putIfAbsent(key, entry);
                if (prior != null && !prior.equals(entry)) ingredientConflicts.add(key);
            }
        }
        ingredientConflicts.forEach(ingredientChoices::remove);
        JsonArray selectedIngredients = new JsonArray();
        ingredientChoices.values().forEach(selectedIngredients::add);
        result.add("choix_ingredients", selectedIngredients);
        result.add("limites", limits);
        return result;
    }
}
