package fr.assistantia.compat.emi;

import fr.assistantia.PlanRetrait;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Preuves locales : événement de craft + gain réel, ou baisse d'une sortie machine + gain réel. */
public final class OrigineFabrication {
    private static final List<ItemStack> crafts = new ArrayList<>();
    private static List<PlanRetrait.Stock<ItemStack>> precedent = List.of(), sorties = List.of();
    private static AbstractContainerMenu menu;
    private static List<Slot> slotsSortie = List.of();
    private static int age;
    private static final Map<String, Integer> totalFabrique = new HashMap<>();

    public static Map<String, Integer> totaux() {
        return Map.copyOf(totalFabrique);
    }

    private static List<PlanRetrait.Stock<ItemStack>> receptions = List.of();

    @SubscribeEvent
    public static void craft(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() != Minecraft.getInstance().player
                || CoffresArbreEmi.arbreActif() == null) return;
        ItemStack item = new ItemStack(event.getCrafting().getItem());
        if (item.isEmpty()) return;
        crafts.add(item);
        age = 0;
    }

    public static void vider() {
        crafts.clear();
        totalFabrique.clear();
        precedent = sorties = receptions = List.of();
        slotsSortie = List.of();
        menu = null;
        age = 0;
    }

    private static int compte(List<PlanRetrait.Stock<ItemStack>> stock, ItemStack item) {
        return stock.stream()
                .filter(s -> ItemStack.isSameItemSameTags(s.objet(), item))
                .mapToInt(PlanRetrait.Stock::quantite)
                .sum();
    }

    public static List<PlanRetrait.Stock<ItemStack>> receptions() {
        return receptions;
    }

    public static void actualiser(List<PlanRetrait.Stock<ItemStack>> inventaire) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            vider();
            return;
        }
        AbstractContainerMenu ouvert = player.containerMenu;
        if (ouvert != menu) {
            menu = ouvert;
            sorties = List.of();
            try {
                slotsSortie = GregTechMachines.sorties(ouvert);
            } catch (ReflectiveOperationException | RuntimeException e) {
                slotsSortie = List.of();
            }
        }
        List<PlanRetrait.Stock<ItemStack>> maintenant = new ArrayList<>();
        for (Slot slot : slotsSortie)
            if (!slot.getItem().isEmpty())
                maintenant.add(
                        new PlanRetrait.Stock<>(
                                slot.getItem().copy(), slot.getItem().getCount(), true));
        List<PlanRetrait.Stock<ItemStack>> gains = new ArrayList<>();
        List<ItemStack> vus = new ArrayList<>();
        for (var pile : inventaire) {
            ItemStack item = pile.objet();
            if (vus.stream().anyMatch(s -> ItemStack.isSameItemSameTags(s, item))) continue;
            vus.add(item);
            int gain = Math.max(0, compte(inventaire, item) - compte(precedent, item));
            boolean craft = crafts.stream().anyMatch(s -> s.getItem() == item.getItem());
            int retire = Math.max(0, compte(sorties, item) - compte(maintenant, item));
            int prouve = craft ? gain : Math.min(gain, retire);
            if (prouve > 0) gains.add(new PlanRetrait.Stock<>(item.copy(), prouve, true));
        }
        receptions = List.copyOf(gains);
        for (var gain : gains) {
            var id =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(
                            gain.objet().getItem());
            if (id != null)
                totalFabrique.merge(
                        id.toString(),
                        gain.quantite(),
                        (a, b) -> (int) Math.min(1_000_000_000L, (long) a + b));
        }
        if (!gains.isEmpty() || ++age > 20) crafts.clear();
        precedent = List.copyOf(inventaire);
        sorties = List.copyOf(maintenant);
    }
}
