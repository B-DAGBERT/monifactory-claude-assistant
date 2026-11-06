package fr.assistantia;

import com.google.gson.*;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.compat.emi.CatalogueEmi;
import fr.assistantia.compat.emi.RecetteProduction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;

public final class ProductionCatalogueTest {
    public record Energy(long voltage, long amperage) {}

    public record Content(Object content, int chance, int maxChance, int tierChanceBoost) {}

    public static final class GT {
        public Map<Object, List<Content>> inputs = new HashMap<>(),
                outputs = new HashMap<>(),
                tickInputs = new HashMap<>(),
                tickOutputs = new HashMap<>();
        public List<Object> conditions = new ArrayList<>(), ingredientActions = new ArrayList<>();
        public net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
        public int duration = 20,
                parallels = 1,
                subtickParallels = 1,
                batchParallels = 1,
                ocLevel = 0;
        public Map<Object, Object> inputChanceLogics = Map.of(),
                outputChanceLogics = Map.of(),
                tickInputChanceLogics = Map.of(),
                tickOutputChanceLogics = Map.of();

        public Energy getInputEUt() {
            return new Energy(16, 2);
        }
    }

    public static final class Node {
        public dev.emi.emi.api.stack.EmiIngredient ingredient;
        public EmiRecipe recipe;
        public List<Node> children = new ArrayList<>();
        public long amount = 1;
        public boolean catalyst;
        public float consumeChance = 1;

        Node(EmiStack stack, EmiRecipe recipe) {
            ingredient = stack;
            this.recipe = recipe;
        }
    }

    public record Alternative(java.util.List<EmiStack> getEmiStacks)
            implements dev.emi.emi.api.stack.EmiIngredient {
        public long getAmount() {
            return 1;
        }

        public float getChance() {
            return 1;
        }
    }

    public static final class Tree {
        public Node goal;
        public long batches = 8;

        Tree(Node node) {
            goal = node;
        }
    }

    private static EmiStack stack(String id, int quantity) {
        return EmiStack.of(new ItemStack(new Item(id), quantity));
    }

    private static Ingredient ingredient(String id, int quantity) {
        return new Ingredient(new ItemStack(new Item(id), quantity));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static JsonObject amount(String id, int quantity) {
        return RecetteProduction.amount(stack(id, quantity), quantity);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "gt" -> gt();
            case "tree" -> tree();
            case "service" -> service();
            case "publish" -> publish(Path.of(args[1]));
            default -> throw new AssertionError();
        }
    }

    private static void gt() throws Exception {
        GT nativeRecipe = new GT();
        Object items = new com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability();
        Object fluids = new com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability();
        var circuit =
                new com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient(
                        new com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient(1));
        nativeRecipe.inputs.put(
                items,
                List.of(
                        new Content(ingredient("gtceu:bronze_ingot", 3), 10000, 10000, 0),
                        new Content(circuit, 0, 10000, 0),
                        new Content(ingredient("gtceu:mold", 1), 0, 10000, 0)));
        var acid =
                new com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient(
                        new net.minecraftforge.fluids.FluidStack(
                                new net.minecraft.world.level.material.Fluid("gtceu:acid"), 750));
        nativeRecipe.inputs.put(fluids, List.of(new Content(acid, 10000, 10000, 0)));
        nativeRecipe.outputs.put(
                items,
                List.of(
                        new Content(ingredient("gtceu:bronze_plate", 2), 10000, 10000, 0),
                        new Content(ingredient("gtceu:dust", 1), 2500, 10000, 0)));
        var recipe = new com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe(nativeRecipe);
        JsonObject data = RecetteProduction.lire(recipe, EmiApi.manager);
        check(data.get("circuit").getAsInt() == 1, "virtual circuit");
        check(data.get("eut").getAsLong() == 32, "voltage times amperage");
        check(data.getAsJsonArray("inputs").size() == 3, "circuit never a physical ingredient");
        check(!data.get("inputs").toString().contains("programmed_circuit"), "no ghost transfer");
        check(data.get("inputs").toString().contains("750"), "fluid mB");
        check(data.get("inputs").toString().contains("\"reusable\":true"), "mold catalyst");
        check(data.get("outputs").toString().contains("2500"), "integer chance");
        check(data.getAsJsonArray("unsupported").isEmpty(), "supported nominal GT recipe");
        recipe.id = "test:bender";
        recipe.outputs = List.of(stack("gtceu:bronze_plate", 2));
        EmiApi.manager.recipes.put(recipe.id, recipe);
        Node goal = new Node(stack("gtceu:bronze_plate", 1), recipe);
        Node ghost = new Node(stack("gtceu:programmed_circuit", 1), new EmiRecipe());
        ghost.catalyst = true;
        ghost.recipe.id = "test:not_in_manager";
        goal.children.add(ghost);
        ArbreEmi.tree = new Tree(goal);
        CatalogueEmi selected = CatalogueEmi.capturer();
        check(selected.avancer(), "virtual circuit child skipped");
        check(
                selected.catalogue(1).getAsJsonArray("recipes").size() == 1,
                "only real production recipe exported");

        var simple = new dev.emi.emi.recipe.EmiShapelessRecipe();
        var random = stack("test:random", 1);
        random.chance = 0.25f;
        simple.outputs = List.of(random);
        simple.inputs = List.of(stack("test:input", 1));
        var probability =
                RecetteProduction.lire(simple, EmiApi.manager)
                        .getAsJsonArray("outputs")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("chance");
        check(
                probability.get("numerator").getAsInt() == 1
                        && probability.get("denominator").getAsInt() == 4,
                "EMI chance preserved as exact binary fraction");
        nativeRecipe.conditions.add("cleanroom");
        check(
                !RecetteProduction.lire(recipe, EmiApi.manager)
                        .getAsJsonArray("unsupported")
                        .isEmpty(),
                "conditions cannot silently disappear");
        ItemStack nbtStack = new ItemStack(new Item("test:variant"), 1);
        nbtStack.tag = new net.minecraft.nbt.CompoundTag();
        nbtStack.tag.entries.put("z", new net.minecraft.nbt.IntTag(2));
        nbtStack.tag.entries.put("a", new net.minecraft.nbt.IntTag(1));
        String variant =
                RecetteProduction.resource(EmiStack.of(nbtStack)).get("variant").getAsString();
        check(variant.equals("{\"a\":1,\"z\":2}"), "canonical exact NBT identity");
        nativeRecipe.inputs.put(
                items, List.of(new Content(new Ingredient(nbtStack), 10000, 10000, 0)));
        check(
                RecetteProduction.lire(recipe, EmiApi.manager)
                        .get("unsupported")
                        .toString()
                        .contains("NBT"),
                "NBT predicate not silently assumed exact");
    }

    private static void tree() throws Exception {
        var make = new dev.emi.emi.recipe.EmiShapelessRecipe();
        make.id = "test:plate";
        make.inputs = List.of(stack("test:ingot", 2));
        make.outputs = List.of(stack("test:plate", 3));
        EmiApi.manager.recipes.put(make.id, make);
        Node goal = new Node(stack("test:plate", 1), make);
        goal.amount = 3;
        goal.children.add(new Node(stack("test:ingot", 1), null));
        Tree tree = new Tree(goal);
        ArbreEmi.tree = tree;
        CatalogueEmi capture = CatalogueEmi.capturer();
        check(
                capture.metadata().getAsJsonObject("cible").get("quantity").getAsLong() == 24,
                "root amount times batches");
        check(capture.avancer(), "selected graph complete");
        check(capture.catalogue(15).get("generation").getAsInt() == 15, "generation");
        check(
                capture.selection().getAsJsonArray("choix_recettes").size() == 1,
                "native choice preserved");
        check(
                capture.selection().get("portee").getAsString().equals("arbre_emi"),
                "explicit selection scope");
        tree.batches = 9;
        check(!capture.valide(), "batch change invalidates");
        tree.batches = 8;
        fr.assistantia.compat.emi.InstantaneArbreEmi.revision++;
        check(!capture.valide(), "in-place selection invalidates");
        capture = CatalogueEmi.capturer();
        dev.emi.emi.runtime.EmiReloadManager.loaded = false;
        check(!capture.valide(), "recipe reload invalidates");
        dev.emi.emi.runtime.EmiReloadManager.loaded = true;
        ArbreEmi.tree = new Tree(goal);
        check(!capture.valide(), "new tree identity invalidates");
        var choices = new Alternative(List.of(stack("test:oak", 1), stack("test:birch", 1)));
        make.inputs = List.of(choices);
        Node resolved =
                new Node(
                        stack("test:oak", 1),
                        new dev.emi.emi.api.recipe.EmiResolutionRecipe(stack("test:oak", 1)));
        resolved.ingredient = choices;
        goal.children.clear();
        goal.children.add(resolved);
        capture = CatalogueEmi.capturer();
        capture.avancer();
        var selection = capture.selection().getAsJsonArray("choix_ingredients");
        check(
                selection.size() == 1
                        && selection
                                .get(0)
                                .getAsJsonObject()
                                .getAsJsonObject("resource")
                                .get("id")
                                .getAsString()
                                .equals("test:oak"),
                "explicit EMI resolution preserved without synthetic recipe");
        check(
                capture.catalogue(16).getAsJsonArray("recipes").size() == 1,
                "no self conversion for a variant selector");
        goal.children.add(goal);
        boolean rejected = false;
        try {
            CatalogueEmi.capturer();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "no silently truncated cycles");
        goal.children.clear();
        List<EmiStack> excessive = new ArrayList<>();
        for (int i = 0; i < 4096; i++) excessive.add(stack("test:alternative_" + i, 1));
        for (int i = 0; i < 5; i++) {
            Node child = new Node(stack("test:placeholder", 1), null);
            child.ingredient = new Alternative(excessive);
            goal.children.add(child);
        }
        rejected = false;
        try {
            CatalogueEmi.capturer();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "aggregate proof budget refuses many legal-sized alternative lists");
    }

    private static void service() throws Exception {
        Path folder = Files.createTempDirectory("catalogue-native-service");
        System.setProperty("catalogue.test.dir", folder.toString());
        var recipe = new dev.emi.emi.recipe.EmiShapelessRecipe();
        recipe.id = "test:service";
        recipe.inputs = List.of(stack("test:input", 1));
        recipe.outputs = List.of(stack("test:result", 1));
        EmiApi.manager.recipes.put(recipe.id, recipe);
        Tree tree = new Tree(new Node(stack("test:result", 1), recipe));
        ArbreEmi.tree = tree;
        check(
                CatalogueProduction.etat().get("etat").getAsString().equals("indisponible"),
                "no implicit export");
        check(
                !Files.exists(folder.resolve("kubejs/assistant/catalogue_production.json")),
                "no file before explicit request");
        check(CatalogueProduction.demander(), "explicit request accepted");
        CatalogueProduction.tick(new net.minecraftforge.event.TickEvent.ClientTickEvent());
        for (int i = 0;
                i < 400 && !CatalogueProduction.etat().get("etat").getAsString().equals("pret");
                i++) Thread.sleep(5);
        JsonObject ready = CatalogueProduction.etat();
        check(ready.get("etat").getAsString().equals("pret"), "background publication ready");
        Path output = folder.resolve("kubejs/assistant/catalogue_production.json");
        Path marker = folder.resolve("kubejs/assistant/catalogue_production_etat.json");
        JsonObject published = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonObject status = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        check(status.get("sha256").equals(published.get("sha256")), "marker and generation agree");
        check(status.get("source").equals(published.get("source")), "publication source agree");
        check(CatalogueProduction.demander(), "reuse valid cache");
        check(
                CatalogueProduction.etat().get("generation").equals(ready.get("generation")),
                "no duplicate generation");
        var writerField = CatalogueProduction.class.getDeclaredField("WRITER");
        writerField.setAccessible(true);
        ExecutorService writer = (ExecutorService) writerField.get(null);
        CountDownLatch unblockWriter = new CountDownLatch(1);
        writer.execute(
                () -> {
                    try {
                        unblockWriter.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            CatalogueProduction.rechargement();
            CatalogueProduction.rechargement();
        } finally {
            unblockWriter.countDown();
        }
        writer.submit(() -> {}).get(2, TimeUnit.SECONDS);
        status = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        check(
                status.get("etat").getAsString().equals("indisponible"),
                "repeated EMI events must still revoke the published receipt");
        check(CatalogueProduction.demander(), "request before temporary I/O failure");
        CatalogueProduction.tick(new net.minecraftforge.event.TickEvent.ClientTickEvent());
        writer.submit(() -> {}).get(2, TimeUnit.SECONDS);
        check(
                CatalogueProduction.etat().get("etat").getAsString().equals("pret"),
                "published before temporary I/O failure");
        Path directory = marker.getParent();
        Path savedDirectory = directory.resolveSibling("assistant-saved");
        Files.move(directory, savedDirectory);
        Files.createFile(directory);
        try {
            CatalogueProduction.rechargement();
            writer.submit(() -> {}).get(2, TimeUnit.SECONDS);
        } finally {
            Files.delete(directory);
            Files.move(savedDirectory, directory);
        }
        for (int i = 0; i < 300; i++) {
            status = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            if (status.get("etat").getAsString().equals("indisponible")) break;
            Thread.sleep(10);
        }
        check(
                status.get("etat").getAsString().equals("indisponible"),
                "revocation must be retried after a temporary publication failure");
        check(CatalogueProduction.demander(), "same tree after reload accepted");
        tree.batches++;
        check(
                CatalogueProduction.etat().get("etat").getAsString().equals("indisponible"),
                "tree change revokes cache before UI read");
        check(CatalogueProduction.demander(), "new selection accepted");
        CatalogueProduction.rechargement();
        CatalogueProduction.tick(new net.minecraftforge.event.TickEvent.ClientTickEvent());
        check(
                CatalogueProduction.etat().get("etat").getAsString().equals("indisponible"),
                "reload rejects in-flight request");
        check(CatalogueProduction.demander(), "another explicit request");
        CatalogueProduction.tick(new net.minecraftforge.event.TickEvent.ClientTickEvent());
        CatalogueProduction.deconnexion(
                new net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut());
        Thread.sleep(50);
        check(
                CatalogueProduction.etat().get("etat").getAsString().equals("indisponible"),
                "disconnect cannot publish a late ready result");
        net.minecraft.client.Minecraft.getInstance().connection =
                new net.minecraft.client.Minecraft.Connection();
        IdentiteMonde.world = "";
        check(!CatalogueProduction.demander(), "unknown identity fails closed in legacy too");
    }

    private static void publish(Path folder) throws Exception {
        var recipe = new dev.emi.emi.recipe.EmiShapelessRecipe();
        recipe.inputs = List.of(stack("test:input", 1));
        recipe.outputs = List.of(stack("test:result", 1));
        JsonObject row = RecetteProduction.lire(recipe, EmiApi.manager);
        JsonArray limits = new JsonArray();
        limits.add("Unicode é 日本語 😀\n\"<>&");
        row.add("unsupported", limits);
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", "monifactory.production.recipes");
        payload.addProperty("version", 1);
        payload.addProperty("generation", 1);
        JsonArray recipes = new JsonArray();
        recipes.add(row);
        payload.add("recipes", recipes);
        JsonObject document = PublicationCatalogue.enveloppe("fixture-native", payload);
        Path destination = folder.resolve("catalogue.json");
        Files.writeString(destination, "old");
        Path temporary = PublicationCatalogue.preparer(destination, document);
        check(Files.readString(destination).equals("old"), "old generation until atomic commit");
        PublicationCatalogue.publier(temporary, destination);
        check(!Files.exists(temporary), "temporary consumed");
        Path link = folder.resolve("link.json");
        Files.createSymbolicLink(link, destination);
        boolean rejected = false;
        try {
            PublicationCatalogue.preparer(link, document);
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        check(rejected, "no following destination symlink");
    }
}
