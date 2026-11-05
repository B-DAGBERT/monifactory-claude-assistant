package fr.assistantia;

import java.util.*;
import fr.assistantia.compat.emi.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;

public class OrigineFabricationTest {
    static PlanRetrait.Stock<ItemStack> s(int n) {
        return new PlanRetrait.Stock<>(new ItemStack("plate", n), n, true);
    }

    public static void main(String[] args) {
        var mc = Minecraft.getInstance();
        mc.player.containerMenu = new ChestMenu();
        OrigineFabrication.vider();
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(1)));
        assert OrigineFabrication.receptions().isEmpty()
                : "un gain dans un coffre n'est pas un craft";
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.craft(
                new PlayerEvent.ItemCraftedEvent(mc.player, new ItemStack("plate", 1)));
        OrigineFabrication.actualiser(List.of(s(4)));
        assert OrigineFabrication.receptions().get(0).quantite() == 4
                : "Maj-clic, quantité réellement reçue";
        OrigineFabrication.actualiser(List.of(s(4)));
        assert OrigineFabrication.receptions().isEmpty();
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(4)));
        assert OrigineFabrication.receptions().isEmpty()
                : "le retour n'est pas une deuxième preuve";
        OrigineFabrication.vider();
        var output = new Slot(new Object(), 0);
        output.item = new ItemStack("plate", 3);
        GregTechMachines.outputSlots = List.of(output);
        OrigineFabrication.actualiser(List.of());
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().get(0).quantite() == 2;
        assert OrigineFabrication.totaux().get("plate") == 2;
        // Les paquets du menu et de l'inventaire peuvent arriver dans un ordre différent.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of());
        assert OrigineFabrication.receptions().isEmpty();
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().size() == 1
                        && OrigineFabrication.receptions().get(0).quantite() == 2
                : "sortie reçue avant l'inventaire : conserver la preuve";
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty() : "une preuve n'est livrée qu'une fois";
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty();
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().size() == 1
                        && OrigineFabrication.receptions().get(0).quantite() == 2
                : "inventaire reçu avant la sortie : conserver la preuve";
        assert OrigineFabrication.totaux().get("plate") == 2;
        // Un changement de menu ne rapproche jamais deux machines différentes.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of());
        mc.player.containerMenu = new ChestMenu();
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty();
        // Un gain déjà validé par l'événement de craft ne redevient pas une sortie machine.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.craft(
                new PlayerEvent.ItemCraftedEvent(mc.player, new ItemStack("plate", 1)));
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.totaux().get("plate") == 2;
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty()
                : "le même gain ne valide pas deux origines";
        OrigineFabrication.vider();
        GregTechMachines.outputSlots = List.of();
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.craft(
                new PlayerEvent.ItemCraftedEvent(mc.player, new ItemStack("plate", 1)));
        for (int i = 0; i < 22; i++) OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(1)));
        assert OrigineFabrication.receptions().isEmpty() : "événement sans gain expiré";
    }
}
