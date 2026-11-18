package fr.assistantia.compat.emi;

import fr.assistantia.PlanRetrait;
import fr.assistantia.ReceptionSortie;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Preuves locales : un craft ou un retrait natif de machine, puis un gain réel. */
public final class OrigineFabrication {
    public record ReceptionMachine(
            AbstractContainerMenu menu,
            BlockPos position,
            List<PlanRetrait.Stock<ItemStack>> objets) {}

    public record Provenances(
            List<PlanRetrait.Stock<ItemStack>> crafts, ReceptionMachine machine) {}

    private static final List<ItemStack> crafts = new ArrayList<>();
    private static List<PlanRetrait.Stock<ItemStack>> precedent = List.of();
    private static ReceptionSortie<ItemStack> receptionSortie =
            new ReceptionSortie<>(ItemStack::isSameItemSameTags, true);
    private static AbstractContainerMenu menu;
    private static BlockPos position;
    private static List<Slot> slotsSortie = List.of();
    private static int age;
    private static final Map<String, Integer> totalFabrique = new HashMap<>();

    /** Une lecture de production ne force jamais le rapprochement des preuves différées. */
    public static boolean receptionEnAttente() {
        return !crafts.isEmpty() || receptionSortie.enAttente();
    }

    public static Map<String, Integer> totaux() {
        return Map.copyOf(totalFabrique);
    }

    private static List<PlanRetrait.Stock<ItemStack>> receptions = List.of();
    private static Provenances provenances = new Provenances(List.of(), null);

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
        provenances = new Provenances(List.of(), null);
        receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags, true);
        slotsSortie = List.of();
        menu = null;
        position = null;
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

    public static Provenances provenances() {
        return provenances;
    }

    static List<PlanRetrait.Stock<ItemStack>> retraitsAttendus(AbstractContainerMenu courant) {
        return courant == menu && position != null ? receptionSortie.gestesEnCours() : List.of();
    }

    private static void observer(AbstractContainerMenu ouvert) {
        if (ouvert == menu) return;
        menu = ouvert;
        position = null;
        receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags, true);
        slotsSortie = List.of();
        try {
            var machine = GregTechMachines.ouvrir(ouvert);
            if (machine != null && machine.position() != null) {
                position = machine.position().immutable();
                slotsSortie = GregTechMachines.sorties(ouvert);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Aucun retrait ne peut être attribué sans sortie native lisible.
        }
    }

    /**
     * Observé avant le clic natif. Les clics de dépôt et les déplacements automatiques sont
     * ignorés.
     */
    public static void clicSortie(
            int conteneur, int index, int bouton, ClickType type, Player joueur) {
        if (joueur == null
                || joueur != Minecraft.getInstance().player
                || CoffresArbreEmi.arbreActif() == null
                || joueur.containerMenu.containerId != conteneur) return;
        if (type != ClickType.QUICK_MOVE
                && (type != ClickType.PICKUP
                        || bouton < 0
                        || bouton > 1
                        || !joueur.containerMenu.getCarried().isEmpty())) return;
        observer(joueur.containerMenu);
        for (var slot : slotsSortie) {
            if (slot.index != index || !slot.isActive() || !slot.mayPickup(joueur)) continue;
            var pile = slot.getItem();
            if (pile.isEmpty()) return;
            int n = pile.getCount();
            receptionSortie.autoriser(
                    pile.copy(), type == ClickType.PICKUP && bouton == 1 ? (n + 1) / 2 : n);
            return;
        }
    }

    public static void actualiser(List<PlanRetrait.Stock<ItemStack>> inventaire) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            vider();
            return;
        }
        observer(player.containerMenu);
        List<PlanRetrait.Stock<ItemStack>> maintenant = new ArrayList<>();
        for (Slot slot : slotsSortie)
            if (slot.isActive() && !slot.getItem().isEmpty())
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
        var retires =
                slotsSortie.isEmpty()
                        ? List.<PlanRetrait.Stock<ItemStack>>of()
                        : receptionSortie.actualiser(inventaire, maintenant, fabriques);
        provenances =
                new Provenances(
                        fabriques,
                        retires.isEmpty() ? null : new ReceptionMachine(menu, position, retires));
        gains.addAll(retires);
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
