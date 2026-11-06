package fr.assistantia.compat.emi;

import fr.assistantia.PlanRetrait;
import fr.assistantia.ReceptionSortie;
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
    private static List<PlanRetrait.Stock<ItemStack>> precedent = List.of();
    private static ReceptionSortie<ItemStack> receptionSortie =
            new ReceptionSortie<>(ItemStack::isSameItemSameTags);
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
        precedent = receptions = List.of();
        receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags);
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
            receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags);
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
        if (!crafts.isEmpty()) {
            for (var pile : inventaire) {
                ItemStack item = pile.objet();
                // Sans événement sur cet item, son gain ne constitue aucune preuve de craft.
                // Filtrer avant les comptages évite de comparer tout un backpack à chaque tick.
                if (crafts.stream().noneMatch(s -> s.getItem() == item.getItem())) continue;
                if (vus.stream().anyMatch(s -> ItemStack.isSameItemSameTags(s, item))) continue;
                vus.add(item);
                int gain = Math.max(0, compte(inventaire, item) - compte(precedent, item));
                if (gain > 0) gains.add(new PlanRetrait.Stock<>(item.copy(), gain, true));
            }
        }
        // Le serveur peut synchroniser les poches et le slot de sortie dans deux ticks.
        // Les gains de craft sont exclus du rapprochement pour rester crédités une seule fois.
        var fabriques = List.copyOf(gains);
        if (!slotsSortie.isEmpty())
            gains.addAll(receptionSortie.actualiser(inventaire, maintenant, fabriques));
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
        crafts.removeIf(
                item -> fabriques.stream().anyMatch(s -> s.objet().getItem() == item.getItem()));
        if (++age > 20) crafts.clear();
        precedent = List.copyOf(inventaire);
    }
}
