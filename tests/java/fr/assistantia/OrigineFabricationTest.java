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

    private static void cliquer(Minecraft mc, Slot output) {
        cliquer(mc, output, ClickType.QUICK_MOVE, 0);
    }

    private static void cliquer(Minecraft mc, Slot output, ClickType type, int bouton) {
        OrigineFabrication.clicSortie(
                mc.player.containerMenu.containerId, output.index, bouton, type, mc.player);
    }

    private static void retraitsDepuisLaSortie(Minecraft mc, Slot output) {
        OrigineFabrication.vider();
        mc.player.containerMenu = new ChestMenu();
        output.item = s(3).objet();
        OrigineFabrication.actualiser(List.of());
        cliquer(mc, output, ClickType.SWAP, 4);
        output.item = ItemStack.EMPTY;
        OrigineFabrication.actualiser(List.of(s(3)));
        assert OrigineFabrication.provenances().machine().objets().get(0).quantite() == 3
                : "touche de barre rapide : seul le retrait réel de trois unités est validé";
        OrigineFabrication.actualiser(List.of(s(3)));
        assert OrigineFabrication.receptions().isEmpty() : "retrait déjà compté";

        OrigineFabrication.vider();
        OrigineFabrication.actualiser(List.of(s(63)));
        mc.player.containerMenu = new ChestMenu();
        mc.player.containerMenu.carried = s(63).objet();
        output.item = s(3).objet();
        cliquer(mc, output, ClickType.PICKUP, 0);
        output.item = s(2).objet();
        mc.player.containerMenu.carried = s(64).objet();
        OrigineFabrication.actualiser(List.of(s(64)));
        assert OrigineFabrication.provenances().machine().objets().get(0).quantite() == 1
                : "premier clic dans le menu avec une pile compatible au curseur";
        output.item = s(1).objet();
        OrigineFabrication.actualiser(List.of(s(65)));
        assert OrigineFabrication.receptions().isEmpty() : "aucun crédit hors du clic observé";
        mc.player.containerMenu.carried = ItemStack.EMPTY;
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
        assert OrigineFabrication.provenances().machine() == null
                && OrigineFabrication.provenances().crafts().size() == 1;
        OrigineFabrication.actualiser(List.of(s(4)));
        assert OrigineFabrication.receptions().isEmpty();
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(4)));
        assert OrigineFabrication.receptions().isEmpty()
                : "le retour n'est pas une deuxième preuve";
        OrigineFabrication.vider();
        var output = new Slot(new Object(), 0);
        output.item = new ItemStack("plate", 3);
        GregTechMachines.ui =
                new GregTechMachines.Interface(new Object(), List.of(), List.of(), null);
        GregTechMachines.outputSlots = List.of(output);
        OrigineFabrication.actualiser(List.of());
        cliquer(mc, output);
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().get(0).quantite() == 2;
        var preuve = OrigineFabrication.provenances();
        assert preuve.crafts().isEmpty() && preuve.machine().objets().get(0).quantite() == 2;
        assert preuve.machine().menu() == mc.player.containerMenu;
        assert preuve.machine().position().equals(new net.minecraft.core.BlockPos(0, 64, 0));
        assert OrigineFabrication.totaux().get("plate") == 2;
        // Les paquets du menu et de l'inventaire peuvent arriver dans un ordre différent.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        cliquer(mc, output);
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
        cliquer(mc, output);
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
        cliquer(mc, output);
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of());
        mc.player.containerMenu = new ChestMenu();
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty();
        // Un gain déjà validé par l'événement de craft ne redevient pas une sortie machine.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        cliquer(mc, output);
        OrigineFabrication.craft(
                new PlayerEvent.ItemCraftedEvent(mc.player, new ItemStack("plate", 1)));
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.totaux().get("plate") == 2;
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty()
                : "le même gain ne valide pas deux origines";
        // Un clic sur une autre case ne prouve pas le retrait d'une sortie par convoyeur.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.clicSortie(
                mc.player.containerMenu.containerId,
                output.index + 1,
                0,
                ClickType.QUICK_MOVE,
                mc.player);
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty() && OrigineFabrication.totaux().isEmpty();
        // Un ancien gain n'est pas réutilisé si le joueur clique ensuite sur la machine.
        OrigineFabrication.vider();
        output.item = new ItemStack("plate", 3);
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(2)));
        cliquer(mc, output);
        output.item = new ItemStack("plate", 1);
        OrigineFabrication.actualiser(List.of(s(2)));
        assert OrigineFabrication.receptions().isEmpty();
        retraitsDepuisLaSortie(mc, output);
        OrigineFabrication.vider();
        GregTechMachines.outputSlots = List.of();
        GregTechMachines.ui = null;
        OrigineFabrication.actualiser(List.of());
        OrigineFabrication.craft(
                new PlayerEvent.ItemCraftedEvent(mc.player, new ItemStack("plate", 1)));
        for (int i = 0; i < 22; i++) OrigineFabrication.actualiser(List.of());
        OrigineFabrication.actualiser(List.of(s(1)));
        assert OrigineFabrication.receptions().isEmpty() : "événement sans gain expiré";
    }
}
