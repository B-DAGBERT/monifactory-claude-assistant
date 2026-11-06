package fr.assistantia.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * Production owner + read-only JSON adapter; the only mutations are explicit fixture operations.
 */
public final class ProjectionLotsEmiTest {
    private static final String WORLD = "m1_" + "a".repeat(64), SESSION = "b".repeat(32);
    private static final Object TREE = new Object(), CONNECTION = new Object();
    private static final dev.emi.emi.api.recipe.EmiRecipeManager MANAGER =
            new dev.emi.emi.api.recipe.EmiRecipeManager();
    private static ProjectionLotsEmi.Portee scope = portee(TREE, CONNECTION, MANAGER, "principal");
    private static List<PlanRetrait.Stock<EmiStack>> inventory = List.of();
    private static int changes;

    private static ProjectionLotsEmi.Portee portee(
            Object tree, Object connection, Object manager, String project) {
        return new ProjectionLotsEmi.Portee(
                tree, connection, manager, WORLD, SESSION, "minecraft:overworld", project);
    }

    private static EmiStack item(String id, int n) {
        return EmiStack.of(new ItemStack(id, n));
    }

    private static PlanRetrait.Stock<EmiStack> stock(EmiStack item, int n) {
        return new PlanRetrait.Stock<>(item, n, true);
    }

    private static EmiRecipe recipe(EmiStack... outputs) {
        var recipe = new EmiRecipe(List.of(), List.of());
        recipe.outputs = List.of(outputs);
        MANAGER.recipe = recipe;
        return recipe;
    }

    private static JsonObject context(String project) {
        var value = new JsonObject();
        value.addProperty("monde_actif", WORLD);
        value.addProperty("session_monde", SESSION);
        value.addProperty("dimension_monde", "minecraft:overworld");
        value.addProperty("projet", project);
        value.addProperty("revision_projet", "project-revision");
        return value;
    }

    private static JsonObject catalogue() {
        var value = new JsonObject();
        value.addProperty("source", "emi/fixture");
        value.addProperty("generation", 7);
        value.addProperty("sha256", "c".repeat(64));
        return value;
    }

    private static JsonObject project(SuiviLotsEmi owner) {
        return ProjectionLotsEmi.lire(
                owner, scope, inventory, true, context(scope.projet()), catalogue());
    }

    private static JsonObject only(JsonObject projection) {
        assert projection.get("disponible").getAsBoolean() : projection;
        assert projection.getAsJsonArray("lots").size() == 1 : projection;
        return projection.getAsJsonArray("lots").get(0).getAsJsonObject();
    }

    private static long quantity(JsonObject lot, String field, String id) {
        long total = 0;
        for (var value : lot.getAsJsonArray(field)) {
            var amount = value.getAsJsonObject();
            if (amount.getAsJsonObject("resource").get("id").getAsString().equals(id))
                total += amount.get("quantity").getAsLong();
        }
        return total;
    }

    public static void main(String[] args) {
        String scenario = args[0];
        inventory = List.of(stock(item("test:unrelated", 1), 1));
        var owner = new SuiviLotsEmi(() -> inventory, () -> changes++, () -> scope);
        var output = item("test:plate", 2);
        EmiRecipe recipe = recipe(output);
        int id = owner.ajouter(recipe, 5, null);
        assert id > 0;
        if (scenario.equals("initial")) {
            var view = project(owner);
            var lot = only(view);
            assert lot.get("id").getAsString().equals("emi-lot/" + SESSION + "/" + id);
            assert lot.get("origine").getAsString().equals("declaration_joueur");
            assert lot.get("etat").getAsString().equals("en_cours");
            assert !lot.has("machine_key");
            assert quantity(lot, "total", "test:plate") == 10;
            assert quantity(lot, "restants", "test:plate") == 10;
            assert lot.getAsJsonArray("recus").isEmpty() && lot.getAsJsonArray("annules").isEmpty();
            assert view.get("empreinte").getAsString().matches("[0-9a-f]{64}");
            assert !view.has("reserved") && !lot.has("stock");
        } else if (scenario.equals("partial_cancel")) {
            inventory = List.of(stock(item("test:plate", 3), 3));
            owner.recevoir(
                    List.of(new PlanRetrait.Stock<>(new ItemStack("test:plate", 3), 3, true)));
            var partial = only(project(owner));
            assert quantity(partial, "total", "test:plate") == 10;
            assert quantity(partial, "recus", "test:plate") == 3;
            assert quantity(partial, "restants", "test:plate") == 7;
            assert owner.annuler(id);
            var cancelled = only(project(owner));
            assert cancelled.get("etat").getAsString().equals("annule");
            assert quantity(cancelled, "total", "test:plate") == 10;
            assert quantity(cancelled, "recus", "test:plate") == 3;
            assert quantity(cancelled, "annules", "test:plate") == 7;
            assert cancelled.getAsJsonArray("restants").isEmpty();
        } else if (scenario.equals("complete_move")) {
            inventory = List.of(stock(item("test:plate", 10), 10));
            owner.recevoir(
                    List.of(new PlanRetrait.Stock<>(new ItemStack("test:plate", 10), 10, true)));
            var complete = only(project(owner));
            assert complete.get("etat").getAsString().equals("termine");
            inventory = List.of();
            owner.recevoir(List.of());
            var moved = only(project(owner));
            assert quantity(moved, "recus", "test:plate") == 10;
            assert moved.getAsJsonArray("restants").isEmpty();
            assert quantity(moved, "total", "test:plate") == 10;
        } else if (scenario.equals("extension")) {
            inventory = List.of(stock(item("test:plate", 3), 3));
            owner.recevoir(
                    List.of(new PlanRetrait.Stock<>(new ItemStack("test:plate", 3), 3, true)));
            assert owner.completer(id, recipe, 2);
            var lot = only(project(owner));
            assert quantity(lot, "total", "test:plate") == 14;
            assert quantity(lot, "recus", "test:plate") == 3;
            assert quantity(lot, "restants", "test:plate") == 11;
            assert lot.get("id").getAsString().endsWith("/" + id);
        } else if (scenario.equals("stock_changed")) {
            var before = project(owner);
            inventory = List.of(stock(item("test:plate", 3), 3));
            long revision = owner.revision();
            var uncertain = project(owner);
            assert !uncertain.get("disponible").getAsBoolean();
            assert uncertain.getAsJsonArray("lots").isEmpty();
            assert owner.revision() == revision;
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            assert !before.get("empreinte").equals(uncertain.get("empreinte"));
        } else if (scenario.equals("recipe_reload")) {
            MANAGER.recipe = recipe(item("test:other", 1));
            var excluded = project(owner);
            assert excluded.getAsJsonArray("lots").isEmpty();
            assert !excluded.getAsJsonArray("limites").isEmpty();
        } else if (scenario.equals("scope_tree")
                || scenario.equals("scope_project")
                || scenario.equals("scope_connection")
                || scenario.equals("scope_recipes")) {
            scope =
                    portee(
                            scenario.equals("scope_tree") ? new Object() : TREE,
                            scenario.equals("scope_connection") ? new Object() : CONNECTION,
                            scenario.equals("scope_recipes") ? new Object() : MANAGER,
                            scenario.equals("scope_project") ? "other" : "principal");
            var excluded = project(owner);
            assert excluded.getAsJsonArray("lots").isEmpty();
            assert !excluded.getAsJsonArray("limites").isEmpty();
            assert owner.actifs().size() == 1;
        } else if (scenario.equals("extension_scope")) {
            scope = portee(TREE, CONNECTION, MANAGER, "other");
            assert owner.completer(id, recipe, 1);
            assert project(owner).getAsJsonArray("lots").isEmpty();
        } else if (scenario.equals("unattributed")) {
            var old = new SuiviLotsEmi(() -> inventory, () -> {});
            old.ajouter(recipe, 5, null);
            var excluded = project(old);
            assert excluded.getAsJsonArray("lots").isEmpty();
            assert !excluded.getAsJsonArray("limites").isEmpty();
        } else if (scenario.equals("read_only")) {
            long revision = owner.revision();
            int count = changes;
            var before = project(owner);
            before.getAsJsonArray("lots").get(0).getAsJsonObject().addProperty("etat", "fake");
            var after = project(owner);
            assert only(after).get("etat").getAsString().equals("en_cours");
            assert owner.revision() == revision && changes == count;
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            assert after.equals(project(owner));
        } else if (scenario.equals("available_label")) {
            var before = project(owner);
            long revision = owner.revision();
            owner.actifs().get(0).suivi().disponible(true);
            var after = project(owner);
            assert only(after).get("etat").getAsString().equals("a_recuperer");
            assert revision == owner.revision();
            assert !before.get("empreinte").equals(after.get("empreinte"));
        } else if (scenario.equals("fluids_variants")) {
            owner.vider();
            var tagged = new ItemStack("test:plate", 1);
            tagged.tag = 8;
            recipe =
                    recipe(
                            item("test:plate", 1),
                            EmiStack.of(tagged),
                            EmiStack.of(new Fluid("test:water"), null, 500));
            owner.ajouter(recipe, 2, new BlockPos(2, 64, -3));
            var lot = only(project(owner));
            assert lot.get("machine_key").getAsString().equals("minecraft:overworld|2|64|-3");
            assert lot.get("origine").getAsString().equals("envoi_machine");
            assert lot.getAsJsonArray("total").size() == 3;
            assert quantity(lot, "total", "test:water") == 1000;
        } else if (scenario.equals("duplicate_outputs")) {
            owner.vider();
            owner.ajouter(recipe(item("test:plate", 1), item("test:plate", 2)), 2, null);
            var lot = only(project(owner));
            assert lot.getAsJsonArray("total").size() == 1;
            assert quantity(lot, "total", "test:plate") == 6;
        } else if (scenario.equals("bounded")) {
            owner.vider();
            owner.ajouter(recipe(item("test:plate", 2)), 1_000_000, null);
            var result = project(owner);
            assert result.getAsJsonArray("lots").isEmpty();
            assert !result.getAsJsonArray("limites").isEmpty();
        } else if (scenario.equals("pending_receipt")) {
            inventory = List.of(stock(item("test:plate", 2), 2));
            owner.recevoir(List.of());
            inventory = List.of();
            owner.recevoir(List.of());
            inventory = List.of(stock(item("test:plate", 2), 2));
            owner.recevoir(List.of());
            assert owner.receptionEnAttente();
            assert !project(owner).get("disponible").getAsBoolean();
        } else if (scenario.equals("clear")) {
            long revision = owner.revision();
            owner.vider();
            var result = project(owner);
            assert result.get("revision").getAsLong() > revision;
            assert result.getAsJsonArray("lots").isEmpty();
            assert result.get("disponible").getAsBoolean();
        } else if (scenario.equals("inventory_variant")) {
            var tagged = new ItemStack("test:unrelated", 1);
            tagged.tag = 4;
            inventory = List.of(stock(EmiStack.of(tagged), 1));
            assert !project(owner).get("disponible").getAsBoolean();
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
        } else if (scenario.equals("foreign_native_scope")) {
            for (String key : List.of("monde_actif", "session_monde", "dimension_monde")) {
                var context = context("principal");
                context.addProperty(
                        key,
                        key.equals("monde_actif")
                                ? "m1_" + "c".repeat(64)
                                : key.equals("session_monde")
                                        ? "c".repeat(32)
                                        : "minecraft:the_nether");
                var result =
                        ProjectionLotsEmi.lire(owner, scope, inventory, true, context, catalogue());
                assert !result.get("disponible").getAsBoolean();
                assert result.getAsJsonArray("lots").isEmpty();
            }
        } else if (scenario.equals("foreign_context")) {
            var result =
                    ProjectionLotsEmi.lire(
                            owner, scope, inventory, true, context("other"), catalogue());
            assert !result.get("disponible").getAsBoolean();
            assert result.getAsJsonArray("lots").isEmpty();
        } else throw new IllegalArgumentException(scenario);
        System.out.println("PASS " + scenario);
    }
}
