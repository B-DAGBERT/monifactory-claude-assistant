package fr.assistantia.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
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
    private static ProjectionLotsEmi.Portee scope = portee(TREE, CONNECTION, MANAGER);
    private static List<PlanRetrait.Stock<EmiStack>> inventory = List.of();
    private static int changes;
    private static long temps;

    static long nanoTime() {
        return temps;
    }

    private static ProjectionLotsEmi.Portee portee(Object tree, Object connection, Object manager) {
        return new ProjectionLotsEmi.Portee(
                tree, connection, manager, WORLD, SESSION, "minecraft:overworld", 0, 1);
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

    private static JsonObject context() {
        var value = new JsonObject();
        value.addProperty("monde_actif", WORLD);
        value.addProperty("session_monde", SESSION);
        value.addProperty("dimension_monde", "minecraft:overworld");
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
        return ProjectionLotsEmi.lire(owner, scope, inventory, true, context(), catalogue());
    }

    private static void machine() {
        if (net.minecraft.client.Minecraft.getInstance().player.containerMenu == null)
            net.minecraft.client.Minecraft.getInstance().player.containerMenu =
                    new net.minecraft.world.inventory.ChestMenu();
        GregTechMachines.ui =
                new GregTechMachines.Interface(new Object(), List.of(), List.of(), null);
    }

    private static List<PlanRetrait.Stock<ItemStack>> nativeInventory() {
        return inventory.stream()
                .filter(s -> !s.objet().getItemStack().isEmpty())
                .map(s -> new PlanRetrait.Stock<>(s.objet().getItemStack(), s.quantite(), true))
                .toList();
    }

    private static net.minecraft.world.inventory.Slot recevoirMachine(
            SuiviLotsEmi owner, int n, boolean clic) {
        machine();
        var slot = new net.minecraft.world.inventory.Slot(new Object(), 0);
        slot.item = new ItemStack("test:plate", n);
        GregTechMachines.outputSlots = List.of(slot);
        OrigineFabrication.vider();
        OrigineFabrication.actualiser(nativeInventory());
        var joueur = net.minecraft.client.Minecraft.getInstance().player;
        if (clic)
            OrigineFabrication.clicSortie(
                    joueur.containerMenu.containerId, slot.index, 0, ClickType.QUICK_MOVE, joueur);
        // Le volet sortie se synchronise avant l'inventaire.
        slot.item = ItemStack.EMPTY;
        OrigineFabrication.actualiser(nativeInventory());
        assert OrigineFabrication.receptions().isEmpty();
        inventory = List.of(stock(item("test:plate", n), n));
        OrigineFabrication.actualiser(nativeInventory());
        var preuves = OrigineFabrication.provenances();
        assert (preuves.machine() != null) == clic;
        owner.recevoir(preuves);
        long revision = owner.revision();
        owner.recevoir(preuves);
        assert owner.revision() == revision : "une réception répétée est sans effet";
        return slot;
    }

    // Pure model fixture: an explicitly proven receipt, not a native source or runtime fallback.
    private static void receiveExplicitModel(SuiviLotsEmi owner, int n) {
        owner.actifs().get(0).suivi().recevoir(List.of(stock(item("test:plate", n), n)), true);
    }

    private static void excluded(SuiviLotsEmi owner) {
        var projection = project(owner);
        assert projection.get("v").getAsInt() == 3;
        assert projection.get("disponible").getAsBoolean() : projection;
        assert projection.getAsJsonArray("lots").isEmpty() : projection;
        assert projection
                .getAsJsonArray("limites")
                .toString()
                .contains("sans preuve de transfert exact");
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

    private static void collecteDifferee(SuiviLotsEmi owner) {
        owner.vider();
        inventory = List.of();
        String id = "gtceu:programmed_circuit";
        var retire = new ItemStack(id, 1);
        retire.tag = 8;
        var autre = new ItemStack(id, 1);
        autre.tag = 9;
        var recette = recipe(EmiStack.of(retire));
        owner.ajouter(recette, 1, new BlockPos(0, 64, 0));
        var reception = owner.preparerCollecte(List.of(retire));
        reception.accept(retire, 1);
        assert owner.actifs().get(0).suivi().totalRecu() == 1;
        owner.recevoir(List.of()); // Le menu refermé a fourni le paquet, les poches sont en retard.
        owner.ajouter(recette, 2, null);
        var manuel = owner.actifs().get(1).suivi();
        assert owner.receptionEnAttente() && manuel.totalRecu() == 0;

        inventory = List.of(stock(EmiStack.of(autre), 1));
        owner.recevoir(List.of());
        assert manuel.totalRecu() == 1 && owner.receptionEnAttente()
                : "la règle du circuit accepte sa variante pour le lot, pas pour le retrait";

        inventory = List.of(stock(EmiStack.of(autre), 1), stock(EmiStack.of(retire), 1));
        owner.recevoir(List.of());
        assert manuel.totalRecu() == 1 && !owner.receptionEnAttente()
                : "le gain correspondant au retrait déjà suivi est déduit une fois";

        inventory = List.of(stock(EmiStack.of(autre), 1), stock(EmiStack.of(retire), 2));
        owner.recevoir(List.of());
        assert manuel.totalRecu() == 2 : "l'arrivée suivante n'est plus attribuée à ce retrait";
    }

    private static void releverEngrenages(
            SuiviLotsEmi owner, Inventory poches, int case4, int case8, int case9, int... recus) {
        var piles = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (int i = 0; i < 36; i++) {
            var pile = poches.getItem(i);
            int attendu = i == 4 ? case4 : i == 8 ? case8 : i == 9 ? case9 : 0;
            assert pile.getCount() == attendu : "case " + i + " = " + pile.getCount();
            if (!pile.isEmpty()) {
                assert pile.is("gtceu:bronze_gear");
                piles.add(stock(EmiStack.of(pile), pile.getCount()));
            }
        }
        inventory = List.copyOf(piles);
        owner.recevoir(List.of());
        assert owner.actifs().size() == recus.length;
        for (int i = 0; i < recus.length; i++) {
            var lot = owner.actifs().get(i);
            assert lot.suivi().total() == 1;
            assert lot.suivi().totalRecu() == recus[i]
                    : "cases 4/8/9 = "
                            + case4
                            + "/"
                            + case8
                            + "/"
                            + case9
                            + ", lot "
                            + (i + 1)
                            + " : attendu "
                            + recus[i]
                            + "/1, reçu "
                            + lot.suivi().totalRecu()
                            + "/1";
        }
    }

    private static void resynchroniserDepot(
            SuiviLotsEmi owner, long delai, boolean ancienEngrenage) {
        owner.vider();
        var engrenage = new ItemStack("gtceu:bronze_gear", 1);
        var recette = recipe(EmiStack.of(engrenage));
        var joueur = net.minecraft.client.Minecraft.getInstance().player;
        var poches = joueur.getInventory();
        int ancien = ancienEngrenage ? 1 : 0;
        if (ancienEngrenage) poches.setItem(8, engrenage.copy());
        releverEngrenages(owner, poches, 0, ancien, 0);
        owner.ajouter(recette, 1, new BlockPos(0, 64, 0));
        releverEngrenages(owner, poches, 0, ancien, 0, 0);
        joueur.containerMenu = new net.minecraft.world.inventory.ChestMenu();
        var reception = owner.preparerCollecte(List.of(engrenage));
        joueur.containerMenu = joueur.inventoryMenu;
        reception.accepter(engrenage, 1, new SuiviLotsEmi.DepotMe(poches, 4, 0));
        releverEngrenages(owner, poches, 0, ancien, 0, 1);
        owner.ajouter(recette, 1, null);
        releverEngrenages(owner, poches, 0, ancien, 0, 1, 0);

        temps = delai;
        poches.setItem(4, engrenage.copy());
        poches.setItem(8, ItemStack.EMPTY);
        releverEngrenages(owner, poches, 1, 0, 0, 1, 0);
        poches.setItem(9, engrenage.copy());
        releverEngrenages(owner, poches, 1, 0, 1, 1, 1);
    }

    private static void collecteMeTardive(SuiviLotsEmi owner, long delai, String ordre) {
        owner.vider();
        inventory = List.of();
        var engrenage = new ItemStack("gtceu:bronze_gear", 1);
        var pile = EmiStack.of(engrenage);
        var recette = recipe(pile);
        var joueur = net.minecraft.client.Minecraft.getInstance().player;
        var poches = joueur.getInventory();
        int destination = 4;
        owner.ajouter(recette, 1, new BlockPos(0, 64, 0));
        owner.preparerCollecte(List.of(engrenage))
                .accepter(engrenage, 1, new SuiviLotsEmi.DepotMe(poches, destination, 0));
        assert owner.actifs().get(0).suivi().totalRecu() == 1;
        owner.ajouter(recette, 1, null);
        var manuel = owner.actifs().get(1).suivi();
        assert manuel.totalRecu() == 0;

        temps = delai;
        owner.recevoir(List.of());
        if (ordre.equals("remplacee")) {
            var remplacement = new ItemStack("test:unrelated", 1);
            poches.setItem(destination, remplacement);
            inventory = List.of(stock(EmiStack.of(remplacement), 1));
            owner.recevoir(List.of());
            poches.setItem(destination, engrenage);
            inventory = List.of(stock(pile, 1));
            owner.recevoir(List.of());
            assert manuel.totalRecu() == 1 : "la case remplacée libère l'ancien rapprochement";
            return;
        }
        if (ordre.equals("avant")) {
            inventory = List.of(stock(pile, 1));
            owner.recevoir(List.of());
            assert manuel.totalRecu() == 1 : "un gain d'une autre case compte immédiatement";
            owner.ajouter(recette, 1, null);
            poches.setItem(destination, engrenage);
            inventory = List.of(stock(pile, 2));
            owner.recevoir(List.of());
            assert owner.actifs().get(2).suivi().totalRecu() == 0
                    : "le dépôt réapparu ne solde pas le nouveau lot";
            return;
        }
        assert ordre.equals("ensemble");
        poches.setItem(destination, engrenage.copy());
        poches.setItem(9, engrenage.copy());
        inventory = List.of(stock(pile, 2));
        owner.recevoir(List.of());
        assert manuel.totalRecu() == 1 : "un gain indépendant au même relevé reste compté";
    }

    public static void main(String[] args) {
        String scenario = args[0];
        inventory = List.of(stock(item("test:unrelated", 1), 1));
        var owner = new SuiviLotsEmi(() -> inventory, () -> changes++, () -> scope);
        var output = item("test:plate", 2);
        EmiRecipe recipe = recipe(output);
        int id = owner.ajouter(recipe, 5, null);
        assert id > 0;
        if (List.of("partial_cancel", "complete_move", "extension").contains(scenario)) {
            owner.vider();
            machine();
            id = owner.ajouter(recipe, 5, new BlockPos(0, 64, 0));
        }
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
        } else if (scenario.equals("collecte_differee")) {
            collecteDifferee(owner);
        } else if (scenario.equals("collecte_me_tardive")) {
            resynchroniserDepot(owner, 125_000_000_000L, false);
        } else if (scenario.equals("collecte_me_courte")) {
            resynchroniserDepot(owner, 5_000_000_000L, false);
        } else if (scenario.equals("collecte_me_total_constant")) {
            resynchroniserDepot(owner, 125_000_000_000L, true);
        } else if (scenario.equals("collecte_me_gain_avant")) {
            collecteMeTardive(owner, 125_000_000_000L, "avant");
        } else if (scenario.equals("collecte_me_gain_simultane")) {
            collecteMeTardive(owner, 125_000_000_000L, "ensemble");
        } else if (scenario.equals("collecte_me_case_remplacee")) {
            collecteMeTardive(owner, 125_000_000_000L, "remplacee");
        } else if (scenario.equals("partial_cancel")) {
            receiveExplicitModel(owner, 3);
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
        } else if (scenario.equals("manual_completion")) {
            receiveExplicitModel(owner, 3);
            long revision = owner.revision();
            assert !owner.retirerDuSuivi(id) && owner.visibleDansSuivi(owner.actifs().get(0));
            assert owner.terminer(id, TREE);
            assert owner.revision() == revision + 1;
            assert owner.retirerDuSuivi(id) && !owner.retirerDuSuivi(id);
            assert !owner.visibleDansSuivi(owner.actifs().get(0));
            assert owner.revision() == revision + 1;
            assert !owner.terminer(id, TREE) && !owner.annuler(id);
            assert !owner.completer(id, recipe, 2);
            var lot = owner.actifs().get(0).suivi();
            assert lot.etat() == fr.assistantia.LotFabrication.Etat.TERMINE;
            assert lot.termineManuellement() && lot.restants().isEmpty();
            assert owner.avancement(TREE).get(0).quantite() == 10;
            assert owner.avancement(new Object()).get(0).quantite() == 3;
            assert lot.obtenus().get(0).quantite() == 3;
            var physiques = List.of(stock(item("test:plate", 4), 4));
            var credit =
                    fr.assistantia.ReceptionLots.credits(
                            owner.avancement(TREE), physiques, List.of(), ComparaisonEmi::meme);
            assert credit.size() == 1 && credit.get(0).quantite() == 6
                    : "les exemplaires portés couvrent une partie du travail confirmé";

            var gain = List.of(stock(item("test:plate", 7), 7));
            assert lot.recevoir(gain).equals(gain) && lot.totalRecu() == 3;
            var view = project(owner);
            assert view.getAsJsonArray("lots").isEmpty();
            assert view.getAsJsonArray("limites").toString().contains("terminés manuellement");
            var fluide = EmiStack.of(new Fluid("test:acid"), null, 100);
            int fluideId = owner.ajouter(recipe(fluide), 2, new BlockPos(0, 64, 0));
            assert owner.terminer(fluideId, TREE);
            assert owner.avancement(TREE).stream()
                    .anyMatch(s -> s.objet().getItemStack().isEmpty() && s.quantite() == 200);
            assert owner.avancement(new Object()).stream()
                    .noneMatch(s -> s.objet().getItemStack().isEmpty());
        } else if (scenario.equals("complete_move")) {
            receiveExplicitModel(owner, 10);
            var complete = only(project(owner));
            assert complete.get("etat").getAsString().equals("termine");
            assert owner.retirerDuSuivi(id);
            assert !owner.visibleDansSuivi(owner.actifs().get(0));
            inventory = List.of();
            owner.recevoir(List.of());
            var moved = only(project(owner));
            assert quantity(moved, "recus", "test:plate") == 10;
            assert moved.getAsJsonArray("restants").isEmpty();
            assert quantity(moved, "total", "test:plate") == 10;
        } else if (scenario.equals("extension")) {
            receiveExplicitModel(owner, 3);
            assert owner.completer(id, recipe, 2);
            var lot = only(project(owner));
            assert quantity(lot, "total", "test:plate") == 14;
            assert quantity(lot, "recus", "test:plate") == 3;
            assert quantity(lot, "restants", "test:plate") == 11;
            assert lot.get("id").getAsString().endsWith("/" + id);
        } else if (scenario.equals("generic_manual") || scenario.equals("generic_machine")) {
            boolean machine = scenario.equals("generic_machine");
            if (machine) {
                owner.vider();
                id = owner.ajouter(recipe, 5, new BlockPos(0, 64, 0));
            }
            long version = owner.revision();
            inventory = List.of(stock(item("test:plate", 3), 3));
            owner.recevoir(List.of());
            assert owner.actifs().get(0).suivi().totalRecu() == (machine ? 0 : 3);
            assert owner.actifs().get(0).suivi().receptionNonAttribuee() != machine;
            if (machine) {
                assert owner.revision() == version;
                assert quantity(only(project(owner)), "restants", "test:plate") == 10;
                assert owner.terminer(id, TREE);
            } else {
                assert owner.revision() > version;
                excluded(owner);
                assert owner.completer(id, recipe, 1);
                excluded(owner);
                assert owner.annuler(id);
                excluded(owner);
            }
        } else if (scenario.equals("generic_independent")) {
            int second = owner.ajouter(recipe, 5, null);
            inventory = List.of(stock(item("test:plate", 3), 3));
            owner.recevoir(List.of());
            assert owner.actifs().get(0).suivi().receptionNonAttribuee();
            assert !owner.actifs().get(1).suivi().receptionNonAttribuee();
            var retained = only(project(owner));
            assert retained.get("id").getAsString().endsWith("/" + second);
            assert quantity(retained, "restants", "test:plate") == 10;
            assert retained.getAsJsonArray("recus").isEmpty();
        } else if (scenario.equals("generic_no_match")) {
            inventory = List.of(stock(item("test:unrelated", 2), 2));
            owner.recevoir(List.of());
            assert !owner.actifs().get(0).suivi().receptionNonAttribuee();
            assert quantity(only(project(owner)), "restants", "test:plate") == 10;
        } else if (List.of(
                        "correlated_machine",
                        "correlated_machine_independent",
                        "native_machine_ambiguous",
                        "native_machine_distinct")
                .contains(scenario)) {
            owner.vider();
            machine();
            boolean distinct = scenario.equals("native_machine_distinct");
            boolean ambigu = scenario.equals("native_machine_ambiguous");
            boolean clic = ambigu || distinct;
            int premier = owner.ajouter(recipe, 5, new BlockPos(distinct ? 1 : 0, 64, 0));
            if (!scenario.equals("correlated_machine")) {
                var autreRecette = ambigu ? new EmiRecipe(List.of(), List.of()) : recipe;
                autreRecette.outputs = recipe.getOutputs();
                owner.ajouter(autreRecette, 5, new BlockPos(0, 64, 0));
            }
            var sortie = recevoirMachine(owner, 3, clic);
            assert owner.actifs().get(0).suivi().totalRecu() == 0
                    : "un gain sans clic, ambigu ou provenant d'une autre machine n'acquitte pas le premier lot";
            if (distinct) {
                assert owner.actifs().get(1).suivi().totalRecu() == 3;
                assert owner.actifs().get(1).suivi().restants().get(0).quantite() == 7;
                assert owner.actifs().get(1).suivi().receptionNonAttribuee();
                assert only(project(owner)).get("id").getAsString().endsWith("/" + premier);
                sortie.item = new ItemStack("test:plate", 4);
                OrigineFabrication.actualiser(nativeInventory());
                var joueur = net.minecraft.client.Minecraft.getInstance().player;
                OrigineFabrication.clicSortie(
                        joueur.containerMenu.containerId,
                        sortie.index,
                        0,
                        ClickType.QUICK_MOVE,
                        joueur);
                inventory = List.of(stock(item("test:plate", 7), 7));
                OrigineFabrication.actualiser(nativeInventory());
                owner.recevoir(OrigineFabrication.provenances());
                assert owner.actifs().get(1).suivi().totalRecu() == 3;
                sortie.item = ItemStack.EMPTY;
                OrigineFabrication.actualiser(nativeInventory());
                owner.recevoir(OrigineFabrication.provenances());
                assert owner.actifs().get(1).suivi().totalRecu() == 7;
                assert owner.actifs().get(1).suivi().restants().get(0).quantite() == 3;
            } else {
                assert owner.actifs().stream().allMatch(l -> l.suivi().totalRecu() == 0);
                if (!ambigu)
                    assert project(owner).getAsJsonArray("lots").size() == owner.actifs().size();
            }
        } else if (scenario.equals("mixed_craft_machine")) {
            owner.vider();
            machine();
            owner.ajouter(recipe, 5, new BlockPos(0, 64, 0));
            owner.ajouter(recipe, 5, null);
            var slot = new net.minecraft.world.inventory.Slot(new Object(), 0);
            slot.item = new ItemStack("test:plate", 3);
            GregTechMachines.outputSlots = List.of(slot);
            OrigineFabrication.vider();
            OrigineFabrication.actualiser(nativeInventory());
            OrigineFabrication.craft(
                    new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
                            net.minecraft.client.Minecraft.getInstance().player,
                            new ItemStack("test:plate", 1)));
            inventory = List.of(stock(item("test:plate", 3), 3));
            slot.item = ItemStack.EMPTY;
            OrigineFabrication.actualiser(nativeInventory());
            owner.recevoir(OrigineFabrication.provenances());
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            assert owner.actifs().get(1).suivi().totalRecu() == 3;
            assert owner.actifs().get(1).suivi().receptionNonAttribuee();
            assert quantity(only(project(owner)), "restants", "test:plate") == 10;
        } else if (scenario.equals("correlated_fluids")) {
            owner.vider();
            machine();
            var fluid = new Fluid("test:water");
            inventory = List.of();
            owner.ajouter(recipe(EmiStack.of(fluid, null, 1000)), 1, new BlockPos(0, 64, 0));
            var tank = new GregTechMachines.Cuve();
            tank.remplissable = true;
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(fluid, 1000));
            GregTechMachines.outputTanks = List.of(tank);
            owner.actualiser();
            // A pipe removes fluid, then an unrelated container enters the player inventory.
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(fluid, 700));
            owner.actualiser();
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            inventory = List.of(stock(EmiStack.of(fluid, null, 300), 300));
            owner.actualiser();
            owner.recevoir(List.of());
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            // Le clic d'une cuve d'entrée n'autorise aucun rapprochement.
            owner.clicSortieFluide(new GregTechMachines.Cuve().widget());
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(fluid, 600));
            inventory = List.of(stock(EmiStack.of(fluid, null, 400), 400));
            owner.actualiser();
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            // Le bon geste sur une sortie dont le liquide diffère du lot ne le crédite pas.
            var autre = new Fluid("test:acid");
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(autre, 600));
            owner.actualiser();
            owner.clicSortieFluide(tank.widget());
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(autre, 300));
            inventory =
                    List.of(
                            stock(EmiStack.of(fluid, null, 400), 400),
                            stock(EmiStack.of(autre, null, 300), 300));
            owner.actualiser();
            assert owner.actifs().get(0).suivi().totalRecu() == 0;
            tank.tank.setFluid(new net.minecraftforge.fluids.FluidStack(fluid, 300));
            owner.actualiser();
            owner.clicSortieFluide(tank.widget());
            tank.tank.setFluid(net.minecraftforge.fluids.FluidStack.EMPTY);
            owner.actualiser();
            inventory =
                    List.of(
                            stock(EmiStack.of(fluid, null, 700), 700),
                            stock(EmiStack.of(autre, null, 300), 300));
            owner.actualiser();
            owner.actualiser();
            owner.recevoir(List.of());
            assert owner.actifs().get(0).suivi().totalRecu() == 300;
            assert owner.actifs().get(0).suivi().receptionNonAttribuee();
            excluded(owner);
        } else if (scenario.equals("tree_revision") || scenario.equals("tree_quantity")) {
            scope =
                    new ProjectionLotsEmi.Portee(
                            TREE,
                            CONNECTION,
                            MANAGER,
                            WORLD,
                            SESSION,
                            "minecraft:overworld",
                            scenario.equals("tree_revision") ? 1 : 0,
                            scenario.equals("tree_quantity") ? 2 : 1);
            var result = project(owner);
            assert result.get("disponible").getAsBoolean();
            assert result.getAsJsonArray("lots").isEmpty();
            assert !result.getAsJsonArray("limites").isEmpty();
            assert owner.actifs().size() == 1;
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
                || scenario.equals("scope_connection")
                || scenario.equals("scope_recipes")) {
            scope =
                    portee(
                            scenario.equals("scope_tree") ? new Object() : TREE,
                            scenario.equals("scope_connection") ? new Object() : CONNECTION,
                            scenario.equals("scope_recipes") ? new Object() : MANAGER);
            var excluded = project(owner);
            assert excluded.getAsJsonArray("lots").isEmpty();
            assert !excluded.getAsJsonArray("limites").isEmpty();
            assert owner.actifs().size() == 1;
        } else if (scenario.equals("extension_scope")) {
            scope = portee(new Object(), CONNECTION, MANAGER);
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
        } else if (scenario.equals("available_variant")) {
            owner.vider();
            var a = new ItemStack("test:gear", 1);
            a.tag = 1;
            var b = a.copy();
            b.tag = 2;
            owner.ajouter(recipe(EmiStack.of(a)), 1, null);
            owner.ajouter(recipe(EmiStack.of(b)), 1, null);
            fr.assistantia.RechercheEmi.disponibles = java.util.Map.of("test:gear|2", 1);
            owner.actualiser();
            assert owner.actifs().get(0).suivi().etat()
                    == fr.assistantia.LotFabrication.Etat.EN_MACHINE;
            assert owner.actifs().get(1).suivi().etat()
                    == fr.assistantia.LotFabrication.Etat.A_RECUPERER;
            assert owner.actifs().stream().allMatch(lot -> lot.suivi().totalRecu() == 0);
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
                var context = context();
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
            // Une demande sans identité de monde n'est jamais rattachée au propriétaire courant.
            var result =
                    ProjectionLotsEmi.lire(
                            owner, scope, inventory, true, new JsonObject(), catalogue());
            assert !result.get("disponible").getAsBoolean();
            assert result.getAsJsonArray("lots").isEmpty();
        } else throw new IllegalArgumentException(scenario);
        System.out.println("PASS " + scenario);
    }
}
