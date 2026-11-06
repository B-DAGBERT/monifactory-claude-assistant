package fr.assistantia;

import fr.assistantia.compat.emi.OrigineFabrication;
import fr.assistantia.compat.emi.GregTechMachines;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;

public final class OrigineFabricationCoutTest {
    static PlanRetrait.Stock<ItemStack> stock(String id, int n, int tag) {
        var item = new ItemStack(id, n);
        item.tag = tag;
        return new PlanRetrait.Stock<>(item, n, true);
    }

    public static void main(String[] args) {
        boolean borne = true;
        for (int taille : new int[] {500, 1000}) {
            var stock = new ArrayList<PlanRetrait.Stock<ItemStack>>();
            for (int i = 0; i < taille; i++) stock.add(stock("test:item_" + i, 1, 0));
            OrigineFabrication.vider();
            GregTechMachines.outputSlots = List.of();
            OrigineFabrication.actualiser(stock);
            ItemStack.comparisons = 0;
            OrigineFabrication.actualiser(stock);
            long stable = ItemStack.comparisons;
            assert OrigineFabrication.receptions().isEmpty();
            stock.add(stock("test:plate", 1, 0));
            stock.add(stock("test:plate", 2, 1));
            OrigineFabrication.actualiser(stock);
            OrigineFabrication.craft(
                    new PlayerEvent.ItemCraftedEvent(
                            Minecraft.getInstance().player, new ItemStack("test:plate", 1)));
            stock.add(stock("test:plate", 3, 0)); // même item réparti + variante NBT inchangée
            ItemStack.comparisons = 0;
            OrigineFabrication.actualiser(stock);
            long sparse = ItemStack.comparisons;
            assert OrigineFabrication.receptions().size() == 1;
            assert OrigineFabrication.receptions().get(0).quantite() == 3;
            assert OrigineFabrication.receptions().get(0).objet().tag == 0;
            assert OrigineFabrication.totaux().get("test:plate") == 3;
            System.out.println(
                    "N="
                            + taille
                            + " comparaisons sans craft="
                            + stable
                            + ", craft épars="
                            + sparse);
            borne &= stable <= taille && sparse < 8L * taille;
        }
        assert borne
                : "Les objets sans événement de craft ne doivent pas déclencher un comptage quadratique";
    }
}
