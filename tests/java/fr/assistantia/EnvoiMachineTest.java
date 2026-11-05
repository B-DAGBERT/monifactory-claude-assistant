package fr.assistantia;

import fr.assistantia.compat.emi.*;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.event.TickEvent;
import java.util.*;

public final class EnvoiMachineTest {
    static Minecraft mc = Minecraft.getInstance();
    static ChestMenu menu;
    static AbstractContainerScreen<ChestMenu> screen;
    static int done;

    static void setup() throws Exception {
        var f = EnvoiMachine.class.getDeclaredField("dernierClic");
        f.setAccessible(true);
        f.setLong(null, 0);
        menu = new ChestMenu();
        screen = new AbstractContainerScreen<>(menu);
        mc.screen = screen;
        mc.player.containerMenu = menu;
        mc.gameMode.refuse = false;
        mc.gameMode.clicks = 0;
        done = 0;
        ArbreEmi.tree = new Object();
        GregTechMachines.busy = false;
        GregTechMachines.configuration = 7;
        GregTechMachines.machineCircuit = -2;
        RetraitCoffre.busy = false;
        for (int i = 0; i < 8; i++)
            menu.slots.add(new Slot(i < 2 ? new Object() : mc.player.getInventory(), i));
        GregTechMachines.ui =
                new GregTechMachines.Interface(
                        new Object(),
                        menu.slots.subList(0, 2),
                        List.of(new GregTechMachines.Cuve()),
                        null);
    }

    static EmiIngredient item(String id, int n) {
        return EmiStack.of(new ItemStack(id, n));
    }

    static EmiRecipe recipe(EmiIngredient... items) {
        return new EmiRecipe(List.of(items), List.of());
    }

    static void finish() {
        for (int i = 0; i < 1200 && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert !EnvoiMachine.occupe();
    }

    static void launch(EmiRecipe r, int n) {
        var p = EnvoiMachine.preparer(screen, r, n);
        assert p.possible() : p.probleme();
        EnvoiMachine.lancer(screen, p, () -> done++);
    }

    public static void main(String[] args) throws Exception {
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        var r = recipe(item("tin", 2));
        launch(r, 5);
        EnvoiMachine.lancer(screen, EnvoiMachine.preparer(screen, r, 5), () -> done++);
        finish();
        assert done == 1
                && menu.slots.get(0).item.count == 10
                && menu.slots.get(2).item.count == 54;
        setup();
        menu.slots.get(0).item = new ItemStack("tin", 3);
        menu.slots.get(2).item = new ItemStack("tin", 20);
        launch(r, 5);
        finish();
        assert menu.slots.get(0).item.count == 10 && menu.slots.get(2).item.count == 13;
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 5);
        var p = EnvoiMachine.preparer(screen, r, 10);
        assert p.possible() && p.fois() == 2;
        setup();
        menu.slots.get(0).item = new ItemStack("wrong", 1);
        menu.slots.get(2).item = new ItemStack("tin", 64);
        assert !EnvoiMachine.preparer(screen, r, 1).possible();
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        mc.gameMode.refuse = true;
        launch(r, 5);
        finish();
        assert done == 0 && menu.slots.get(2).item.count == 64 && mc.gameMode.clicks == 12
                : "Pas de répétition des clics refusés";
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        launch(r, 5);
        mc.screen = null;
        finish();
        assert mc.gameMode.clicks == 0;
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        launch(r, 5);
        ArbreEmi.tree = null;
        finish();
        assert mc.gameMode.clicks == 0;
        setup();
        var cell = new ItemStack("water_cell", 2);
        cell.fluid = "water";
        cell.amount = 1000;
        menu.slots.get(2).item = cell;
        menu.slots.get(3).item = new ItemStack("tin", 8);
        var wet = recipe(item("tin", 2), EmiStack.of(new Fluid("water"), null, 144));
        p = EnvoiMachine.preparer(screen, wet, 2);
        assert p.possible() : p.probleme();
        assert p.details().stream().anyMatch(s -> s.contains("1000 mB"));
        assert GregTechMachines.ui.cuves().get(0).tank.isEmpty()
                : "Une prévisualisation ne modifie aucun fluide";
        EnvoiMachine.lancer(screen, p, () -> done++);
        finish();
        assert done == 1 && menu.slots.get(0).item.count == 4 && menu.slots.get(2).item.count == 1;
        assert GregTechMachines.ui.cuves().get(0).tank.getFluidAmount() == 1000;
        assert menu.slots.stream()
                .anyMatch(s -> s.item.id.equals("empty_cell") && s.item.count == 1);
        assert GregTechMachines.ui.cuves().get(0).calls == 1 && menu.getCarried().isEmpty();
        setup();
        menu.slots.get(2).item = cell.copy();
        menu.slots.get(2).item.setCount(2);
        menu.slots.get(3).item = new ItemStack("tin", 8);
        GregTechMachines.ui.cuves().get(0).tank.capacity = 100;
        assert !EnvoiMachine.preparer(screen, wet, 1).possible()
                : "Un seau ne se vide pas dans une cuve trop petite";
        setup();
        menu.slots.get(2).item = cell.copy();
        menu.slots.get(2).item.setCount(2);
        menu.slots.get(3).item = new ItemStack("tin", 8);
        var twoFluids =
                recipe(
                        item("tin", 1),
                        EmiStack.of(new Fluid("water"), null, 1000),
                        EmiStack.of(new Fluid("water"), null, 1000));
        launch(twoFluids, 1);
        finish();
        assert done == 1 && GregTechMachines.ui.cuves().get(0).tank.getFluidAmount() == 2000;
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        GregTechMachines.busy = true;
        assert !EnvoiMachine.preparer(screen, r, 1).possible();
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        launch(r, 1);
        GregTechMachines.busy = true;
        finish();
        assert mc.gameMode.clicks == 0 && done == 0 : "machine devenue occupée après le clic";
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        launch(r, 1);
        menu.slots.get(2).pickup = false;
        finish();
        assert mc.gameMode.clicks == 0 && done == 0 : "source verrouillée entre plan et envoi";
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 64);
        p = EnvoiMachine.preparer(screen, r, 1);
        RetraitCoffre.busy = true;
        EnvoiMachine.lancer(screen, p, () -> done++);
        assert !EnvoiMachine.occupe() && mc.gameMode.clicks == 0;
        RetraitCoffre.busy = false;
        mc.screen = null;
        EnvoiMachine.lancer(screen, p, () -> done++);
        assert !EnvoiMachine.occupe() : "un ancien écran ne lance rien";
        setup();
        var actualCircuit = new ItemStack("gtceu:programmed_circuit", 1);
        actualCircuit.tag = 2;
        var fakeCircuit = actualCircuit.copy();
        fakeCircuit.tag = 99;
        menu.slots.get(2).item = new ItemStack("tin", 4);
        menu.slots.get(3).item = actualCircuit;
        var catalyst = EmiStack.of(fakeCircuit);
        catalyst.chance = 0;
        var withCircuit = new EmiRecipe(List.of(item("tin", 1), catalyst), List.of(catalyst));
        launch(withCircuit, 4);
        finish();
        assert done == 1
                        && menu.slots.get(0).item.count == 4
                        && menu.slots.get(1).item.tag == 2
                        && menu.slots.get(1).item.count == 1
                : "circuit physique reconnu sans fake NBT, réutilisé sans double comptage";
        var virtualCircuit =
                new com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe(
                        withCircuit.getInputs(), withCircuit.getCatalysts());
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 4);
        var ghost = new ItemStack("gtceu:programmed_circuit", 1);
        ghost.tag = 1;
        GregTechMachines.ui =
                new GregTechMachines.Interface(
                        new Object(), menu.slots.subList(0, 2), List.of(), ghost);
        p = EnvoiMachine.preparer(screen, virtualCircuit, 4);
        assert !p.possible()
                        && p.probleme().contains("régler sur 7")
                        && p.probleme().contains("actuel : 1")
                : p.probleme();
        assert ghost.tag == 1 && mc.gameMode.clicks == 0 : "vérifier sans modifier le circuit";
        ghost.tag = 7;
        p = EnvoiMachine.preparer(screen, virtualCircuit, 4);
        assert p.possible() && p.details().contains("Circuit machine : 7 · vérifié") : p.probleme();
        EnvoiMachine.lancer(screen, p, () -> done++);
        finish();
        assert done == 1 && ghost.tag == 7 && menu.slots.get(1).item.isEmpty()
                : "circuit virtuel déjà configuré, aucun objet circuit demandé ni transféré";
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 4);
        menu.slots.get(3).item = actualCircuit;
        p = EnvoiMachine.preparer(screen, virtualCircuit, 4);
        assert !p.possible() && p.probleme().startsWith("Circuit machine")
                : "un circuit physique ne remplace pas la configuration virtuelle";
        GregTechMachines.machineCircuit = 7;
        p = EnvoiMachine.preparer(screen, virtualCircuit, 4);
        assert p.possible() && GregTechMachines.ui.circuit() == null
                : "lecture native sans GhostCircuitSlotWidget (CircuitFancyConfigurator)";
        EnvoiMachine.lancer(screen, p, () -> done++);
        finish();
        assert done == 1
                        && menu.slots.get(3).item == actualCircuit
                        && menu.slots.get(1).item.isEmpty()
                : "le circuit physique reste dans l'inventaire";
        setup();
        menu.slots.get(2).item = new ItemStack("tin", 4);
        GregTechMachines.machineCircuit = 7;
        p = EnvoiMachine.preparer(screen, virtualCircuit, 4);
        EnvoiMachine.lancer(screen, p, () -> done++);
        GregTechMachines.machineCircuit = 2;
        finish();
        assert done == 0 && mc.gameMode.clicks == 0
                : "un réglage modifié après prévisualisation bloque l'envoi";
        GregTechMachines.configuration = 99;
        assert !EnvoiMachine.preparer(screen, virtualCircuit, 4).possible()
                : "configuration de recette invalide refusée";
        setup();
        var tagged = new ItemStack("tool", 1);
        tagged.tag = 42;
        menu.slots.get(2).item = new ItemStack("tool", 1);
        assert !EnvoiMachine.preparer(screen, recipe(EmiStack.of(tagged)), 1).possible()
                : "l'exception NBT est limitée au programmed circuit";
    }
}
