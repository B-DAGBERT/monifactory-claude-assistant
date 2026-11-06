package fr.assistantia.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

/** Lecture seule d'une recette réellement chargée. Aucune recette déduite d'un nom de catégorie. */
public final class RecetteProduction {
    private static final String GT = "com.gregtechceu.gtceu.";
    private static final int MAX_OPTIONS = 4096;

    private RecetteProduction() {}

    public static JsonObject lire(EmiRecipe recipe, EmiRecipeManager manager)
            throws ReflectiveOperationException {
        if (recipe.getId() == null || recipe.getCategory() == null)
            throw new IllegalArgumentException("Recette EMI sans identité stable");
        JsonObject result = new JsonObject();
        result.addProperty("id", recipe.getId().toString());
        result.addProperty("category", recipe.getCategory().getId().toString());
        JsonArray inputs = new JsonArray(), outputs = new JsonArray();
        TreeSet<String> limits = new TreeSet<>();
        Object gt = GregTechMachines.recette(recipe);
        if (gt != null) {
            try {
                lireGregTech(gt, result, inputs, outputs, limits);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Keep the displayed recipe as evidence, but never plan guessed GT semantics.
                inputs = new JsonArray();
                outputs = new JsonArray();
                limits.add(
                        "Lecture native GregTech indisponible : " + ex.getClass().getSimpleName());
                lireEmi(recipe, inputs, outputs, limits);
            }
        } else {
            lireEmi(recipe, inputs, outputs, limits);
            String type = recipe.getClass().getName();
            if (!type.equals("dev.emi.emi.recipe.EmiShapedRecipe")
                    && !type.equals("dev.emi.emi.recipe.EmiShapelessRecipe"))
                limits.add(
                        "Sémantique de recette dynamique ou machine EMI non qualifiée : " + type);
            var backing = recipe.getBackingRecipe();
            if (backing == null
                    || (!backing.getClass()
                                    .getName()
                                    .equals("net.minecraft.world.item.crafting.ShapedRecipe")
                            && !backing.getClass()
                                    .getName()
                                    .equals("net.minecraft.world.item.crafting.ShapelessRecipe")))
                limits.add("Recette de craft native dynamique ou absente");
            else
                for (var nativeInput : backing.getIngredients())
                    if (nativeInput.getClass() != Ingredient.class)
                        limits.add("Prédicat de craft natif spécifique non qualifié");
        }
        if (outputs.isEmpty())
            throw new IllegalArgumentException("Recette sans sortie représentable");
        result.add("inputs", inputs);
        result.add("outputs", outputs);
        result.add("machines", machines(recipe, manager, gt));
        JsonArray unsupported = new JsonArray();
        limits.forEach(unsupported::add);
        result.add("unsupported", unsupported);
        return result;
    }

    private static void lireEmi(
            EmiRecipe recipe, JsonArray inputs, JsonArray outputs, TreeSet<String> limits) {
        for (var ingredient : recipe.getInputs()) {
            if (ingredient.isEmpty()) continue;
            if (ComparaisonEmi.circuitVirtuel(recipe, ingredient, ingredient.getChance() == 0)) {
                limits.add("Réglage du circuit GregTech non lisible");
                continue;
            }
            inputs.add(ingredient(ingredient, false, limits));
        }
        for (var catalyst : recipe.getCatalysts()) {
            if (catalyst.isEmpty()) continue;
            if (ComparaisonEmi.circuitVirtuel(recipe, catalyst, true)) {
                limits.add("Réglage du circuit GregTech non lisible");
                continue;
            }
            // EMI implementations may include a catalyst in both lists.
            JsonObject converted = ingredient(catalyst, true, limits);
            if (!contains(inputs, converted)) {
                for (var input : inputs)
                    if (input.getAsJsonObject().get("options").equals(converted.get("options")))
                        limits.add(
                                "Catalyseur présent aussi comme consommable : sémantique EMI ambiguë");
                inputs.add(converted);
            }
        }
        for (var stack : recipe.getOutputs()) {
            if (stack.isEmpty()) continue;
            JsonObject output = new JsonObject();
            output.add("amount", amount(stack, stack.getAmount()));
            float chance = stack.getChance();
            output.add("chance", chance(chance));
            outputs.add(output);
        }
        if (!recipe.supportsRecipeTree())
            limits.add("EMI ne garantit pas un arbre de fabrication pour cette recette");
    }

    private static boolean contains(JsonArray array, JsonObject value) {
        for (JsonElement element : array) if (element.equals(value)) return true;
        return false;
    }

    private static JsonObject ingredient(
            EmiIngredient ingredient, boolean catalyst, TreeSet<String> limits) {
        float consumption = ingredient.getChance();
        if (consumption != 0 && consumption != 1)
            limits.add("Consommation probabiliste non représentée");
        TreeMap<String, JsonObject> options = new TreeMap<>();
        if (ingredient.getEmiStacks().size() > MAX_OPTIONS)
            throw new IllegalArgumentException("Trop d'alternatives ; aucune troncature");
        boolean reusable = catalyst || consumption == 0;
        for (var stack : ingredient.getEmiStacks()) {
            JsonObject value = amount(stack, ingredient.getAmount());
            if (stack.getNbt() != null && !stack.getNbt().isEmpty())
                limits.add("Comparaison NBT EMI non qualifiée");
            if (!stack.getRemainder().isEmpty()) {
                limits.add("Reste ou usure d'outil EMI non représenté exactement");
                if (stack.getRemainder().getId().equals(stack.getId())) reusable = true;
            }
            options.put(value.get("resource").toString(), value);
        }
        return input(options.values().stream().toList(), reusable);
    }

    private static void lireGregTech(
            Object gt,
            JsonObject result,
            JsonArray inputs,
            JsonArray outputs,
            TreeSet<String> limits)
            throws ReflectiveOperationException {
        var nativeInputs = map(gt, "inputs");
        var nativeOutputs = map(gt, "outputs");
        for (String name : List.of("conditions", "ingredientActions"))
            if (!((List<?>) field(gt, name)).isEmpty())
                limits.add("GregTech " + name + " non interprété");
        Object defaultChance =
                Class.forName(GT + "api.recipe.chance.logic.ChanceLogic").getField("OR").get(null);
        for (String name :
                List.of(
                        "inputChanceLogics",
                        "outputChanceLogics",
                        "tickInputChanceLogics",
                        "tickOutputChanceLogics"))
            for (Object logic : map(gt, name).values())
                if (logic != defaultChance)
                    limits.add("Logique de probabilités GregTech non indépendante");
        if (((Number) field(gt, "ocLevel")).intValue() != 0)
            limits.add("Recette GregTech déjà surcadencée");
        Object data = field(gt, "data");
        if (data instanceof CompoundTag nbt && !nbt.isEmpty())
            limits.add("Métadonnées GregTech spécifiques non interprétées");
        for (String name : List.of("parallels", "subtickParallels", "batchParallels"))
            if (((Number) field(gt, name)).longValue() > 1)
                limits.add("Parallélisme GregTech non nominal");
        for (String name : List.of("tickInputs", "tickOutputs"))
            for (var entry : map(gt, name).entrySet())
                if (!entry.getKey().getClass().getSimpleName().equals("EURecipeCapability")
                        && !((List<?>) entry.getValue()).isEmpty())
                    limits.add("Flux par tick GregTech non énergétique non interprété");
        result.addProperty(
                "duration_ticks", nonnegative(((Number) field(gt, "duration")).longValue()));
        Object energy = call(gt, "getInputEUt");
        result.addProperty(
                "eut",
                Math.multiplyExact(
                        nonnegative(((Number) call(energy, "voltage")).longValue()),
                        nonnegative(((Number) call(energy, "amperage")).longValue())));
        Integer circuit = null;
        for (var entry : sorted(nativeInputs)) {
            String capability = entry.getKey().getClass().getSimpleName();
            for (Object content : (List<?>) entry.getValue()) {
                Object value = field(content, "content");
                int numerator = ((Number) field(content, "chance")).intValue();
                int denominator = ((Number) field(content, "maxChance")).intValue();
                chance(numerator, denominator);
                Object inner = unwrap(value);
                if (inner.getClass()
                        .getName()
                        .equals(GT + "api.recipe.ingredient.IntCircuitIngredient")) {
                    int setting = ((Number) field(inner, "configuration")).intValue();
                    if (setting < 0
                            || setting > 32
                            || circuit != null && circuit != setting
                            || numerator != 0)
                        throw new IllegalArgumentException("Circuit virtuel incohérent");
                    circuit = setting;
                    continue;
                }
                if (numerator != 0 && numerator != denominator)
                    limits.add("Consommation GregTech probabiliste non représentée");
                var options = options(value, capability, limits);
                if (options.isEmpty())
                    limits.add("Capacité GregTech d'entrée non prise en charge : " + capability);
                else inputs.add(input(options, numerator == 0));
            }
        }
        if (circuit != null) result.addProperty("circuit", circuit);
        for (var entry : sorted(nativeOutputs)) {
            String capability = entry.getKey().getClass().getSimpleName();
            for (Object content : (List<?>) entry.getValue()) {
                var options = options(field(content, "content"), capability, limits);
                if (options.isEmpty()) {
                    limits.add("Capacité GregTech de sortie non prise en charge : " + capability);
                    continue;
                }
                if (options.size() != 1)
                    throw new IllegalArgumentException(
                            "Sortie GregTech à alternatives non représentable sans troncature");
                int numerator = ((Number) field(content, "chance")).intValue();
                int denominator = ((Number) field(content, "maxChance")).intValue();
                if (((Number) field(content, "tierChanceBoost")).intValue() != 0)
                    limits.add("Probabilité GregTech dépendante du niveau machine");
                JsonObject output = new JsonObject();
                output.add("amount", options.get(0));
                output.add("chance", chance(numerator, denominator));
                outputs.add(output);
            }
        }
    }

    private static List<Map.Entry<?, ?>> sorted(Map<?, ?> map) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().getClass().getName()));
        return entries;
    }

    private static List<JsonObject> options(Object value, String capability, TreeSet<String> limits)
            throws ReflectiveOperationException {
        TreeMap<String, JsonObject> options = new TreeMap<>();
        if (capability.equals("ItemRecipeCapability") && value instanceof Ingredient ingredient) {
            Object inner = unwrap(value);
            if (inner.getClass() != Ingredient.class)
                limits.add("Prédicat objet GregTech spécifique : " + inner.getClass().getName());
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length > MAX_OPTIONS)
                throw new IllegalArgumentException("Trop d'alternatives ; aucune troncature");
            for (var stack : stacks) {
                if (stack.hasTag()) limits.add("Prédicat NBT GregTech non qualifié");
                JsonObject amount = amount(EmiStack.of(stack), stack.getCount());
                options.put(amount.get("resource").toString(), amount);
            }
        } else if (capability.equals("FluidRecipeCapability")
                && value.getClass()
                        .getName()
                        .equals(GT + "api.recipe.ingredient.FluidIngredient")) {
            FluidStack[] stacks = (FluidStack[]) call(value, "getStacks");
            if (stacks.length > MAX_OPTIONS)
                throw new IllegalArgumentException("Trop d'alternatives ; aucune troncature");
            for (var stack : stacks) {
                if (stack.hasTag()) limits.add("Prédicat NBT fluide GregTech non qualifié");
                JsonObject amount =
                        amount(
                                EmiStack.of(stack.getFluid(), stack.getTag(), stack.getAmount()),
                                stack.getAmount());
                options.put(amount.get("resource").toString(), amount);
            }
        }
        return List.copyOf(options.values());
    }

    private static Object unwrap(Object value) throws ReflectiveOperationException {
        for (int depth = 0;
                depth < 8
                        && value.getClass()
                                .getName()
                                .equals(GT + "api.recipe.ingredient.SizedIngredient");
                depth++) value = call(value, "getInner");
        return value;
    }

    private static JsonObject input(List<JsonObject> options, boolean reusable) {
        if (options.isEmpty()) throw new IllegalArgumentException("Ingrédient sans alternative");
        JsonObject result = new JsonObject();
        JsonArray values = new JsonArray();
        options.forEach(values::add);
        result.add("options", values);
        result.addProperty("reusable", reusable);
        return result;
    }

    public static JsonObject amount(EmiStack stack, long quantity) {
        if (quantity <= 0 || stack.isEmpty())
            throw new IllegalArgumentException("Quantité ou ressource vide");
        JsonObject result = new JsonObject();
        result.add("resource", resource(stack));
        result.addProperty("quantity", quantity);
        return result;
    }

    public static JsonObject resource(EmiStack stack) {
        Object key = stack.getKey();
        if (!(key instanceof Item) && !(key instanceof Fluid))
            throw new IllegalArgumentException("Type de ressource EMI non pris en charge");
        JsonObject result = new JsonObject();
        result.addProperty("kind", key instanceof Item ? "item" : "fluid");
        result.addProperty("id", stack.getId().toString());
        result.addProperty("unit", key instanceof Item ? "item" : "mB");
        result.addProperty(
                "variant",
                stack.getNbt() == null || stack.getNbt().isEmpty() ? "" : nbt(stack.getNbt()));
        return result;
    }

    private static String nbt(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return "{"
                    + String.join(
                            ",",
                            compound.getAllKeys().stream()
                                    .sorted()
                                    .map(
                                            k ->
                                                    new com.google.gson.JsonPrimitive(k).toString()
                                                            + ":"
                                                            + nbt(compound.get(k)))
                                    .toList())
                    + "}";
        }
        if (tag instanceof net.minecraft.nbt.ListTag list) {
            List<String> parts = new ArrayList<>();
            for (Tag value : list) parts.add(nbt(value));
            return "[" + String.join(",", parts) + "]";
        }
        return tag.toString();
    }

    private static JsonObject chance(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Probabilité EMI invalide");
        // The double constructor preserves the exact binary float value, without decimal rounding.
        var decimal = new java.math.BigDecimal((double) value);
        var numerator = decimal.unscaledValue();
        var denominator = java.math.BigInteger.TEN.pow(decimal.scale());
        var divisor = numerator.gcd(denominator);
        JsonObject result = new JsonObject();
        result.addProperty("numerator", numerator.divide(divisor));
        result.addProperty("denominator", denominator.divide(divisor));
        return result;
    }

    private static JsonObject chance(int numerator, int denominator) {
        if (numerator < 0 || denominator <= 0 || numerator > denominator)
            throw new IllegalArgumentException("Probabilité native invalide");
        JsonObject chance = new JsonObject();
        chance.addProperty("numerator", numerator);
        chance.addProperty("denominator", denominator);
        return chance;
    }

    private static JsonArray machines(EmiRecipe recipe, EmiRecipeManager manager, Object gt) {
        TreeSet<String> result = new TreeSet<>();
        for (var workstation : manager.getWorkstations(recipe.getCategory()))
            for (var stack : workstation.getEmiStacks()) {
                if (stack.isEmpty() || !(stack.getKey() instanceof Item)) continue;
                if (gt != null) {
                    try {
                        Object definition = call(stack.getKey(), "getDefinition");
                        Object recipeType = field(gt, "recipeType");
                        if (Arrays.stream((Object[]) call(definition, "getRecipeTypes"))
                                .noneMatch(t -> t == recipeType)) continue;
                        int tier = ((Number) call(definition, "getTier")).intValue();
                        long[] voltages =
                                (long[]) Class.forName(GT + "api.GTValues").getField("V").get(null);
                        long voltage =
                                ((Number) call(call(gt, "getInputEUt"), "voltage")).longValue();
                        if (tier < 0 || tier >= voltages.length || voltages[tier] < voltage)
                            continue;
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        continue;
                    }
                }
                result.add(stack.getId().toString());
            }
        JsonArray array = new JsonArray();
        result.forEach(array::add);
        return array;
    }

    private static long nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Valeur native négative");
        return value;
    }

    private static Map<?, ?> map(Object value, String name) throws ReflectiveOperationException {
        return (Map<?, ?>) field(value, name);
    }

    private static Object call(Object value, String name) throws ReflectiveOperationException {
        return value.getClass().getMethod(name).invoke(value);
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
