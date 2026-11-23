package fr.assistantia.compat.emi;

import fr.assistantia.PlanRetrait;
import fr.assistantia.RechercheEmi;
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
    private static final Map<Slot, ItemStack> sortiesEnCours = new IdentityHashMap<>();
    private static int age;
    private static final Map<String, Integer> totalFabrique = new HashMap<>();
    private static Map<String, Integer> totalCollecte = Map.of();

    /** Une lecture de production ne force jamais le rapprochement des preuves différées. */
    public static boolean receptionEnAttente() {
        return !crafts.isEmpty() || receptionSortie.enAttente();
    }

    public static Map<String, Integer> totaux() {
        return Map.copyOf(totalFabrique);
    }

    /** Cumul immuable des preuves classées selon les branches du Repérage actif lors du gain. */
    public static Map<String, Integer> totauxCollecte() {
        return totalCollecte;
    }

    private static int additionner(int a, int b) {
        return (int) Math.min(1_000_000_000L, (long) a + b);
    }

    private static List<PlanRetrait.Stock<ItemStack>> receptions = List.of();
    private static Provenances provenances = new Provenances(List.of(), null);

    @SubscribeEvent
    public static void craft(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() != Minecraft.getInstance().player
                || CoffresArbreEmi.arbreActif() == null) return;
        ItemStack item = event.getCrafting().copy();
        if (item.isEmpty()) return;
        crafts.add(item);
        age = 0;
    }

    public static void vider() {
        crafts.clear();
        totalFabrique.clear();
        totalCollecte = Map.of();
        precedent = receptions = List.of();
        provenances = new Provenances(List.of(), null);
        receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags, true);
        sortiesEnCours.clear();
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

    static List<PlanRetrait.Stock<ItemStack>> retraitsAttendus(Player joueur) {
        return joueur.containerMenu == menu || joueur.containerMenu == joueur.inventoryMenu
                ? receptionSortie.gestesEnCours()
                : List.of();
    }

    private static void observer(AbstractContainerMenu ouvert) {
        if (ouvert == menu) return;
        menu = ouvert;
        position = null;
        receptionSortie = new ReceptionSortie<>(ItemStack::isSameItemSameTags, true);
        sortiesEnCours.clear();
    }

    private static Slot sortieNative(AbstractContainerMenu ouvert, int index, Player joueur) {
        try {
            for (var slot : GregTechMachines.sorties(ouvert))
                if (slot.index == index && slot.isActive() && slot.mayPickup(joueur)) {
                    if (position == null) {
                        var machine = GregTechMachines.ouvrir(ouvert);
                        if (machine == null || machine.position() == null) return null;
                        position = machine.position().immutable();
                    }
                    return slot;
                }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Aucun retrait ne peut être attribué sans sortie native lisible.
        }
        return null;
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
                || joueur.containerMenu == joueur.inventoryMenu
                || joueur.containerMenu.containerId != conteneur) return;
        if (type != ClickType.QUICK_MOVE
                && !(type == ClickType.PICKUP && bouton >= 0 && bouton <= 1)
                && !(type == ClickType.SWAP && (bouton >= 0 && bouton <= 8 || bouton == 40)))
            return;
        observer(joueur.containerMenu);
        var slot = sortieNative(joueur.containerMenu, index, joueur);
        if (slot == null) return;
        var pile = slot.getItem();
        if (pile.isEmpty()) return;
        int n = pile.getCount();
        if (type == ClickType.PICKUP) {
            var curseur = joueur.containerMenu.getCarried();
            if (!curseur.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(pile, curseur)) return;
                n = Math.min(n, curseur.getMaxStackSize() - curseur.getCount());
            } else if (bouton == 1) {
                n = (n + 1) / 2;
            }
        }
        if (n > 0) {
            if (!receptionSortie.enAttente()) {
                receptionSortie.preparerGeste(precedent, List.of());
                sortiesEnCours.clear();
            }
            sortiesEnCours.putIfAbsent(slot, pile.copy());
            receptionSortie.autoriser(slot, pile.copy(), n);
        }
    }

    private static void releverSorties() {
        for (var entree : sortiesEnCours.entrySet()) {
            var slot = entree.getKey();
            if (!slot.isActive()) continue;
            var avant = entree.getValue();
            var ici = slot.getItem();
            if (ItemStack.isSameItemSameTags(avant, ici)) {
                receptionSortie.variationSortie(slot, avant, avant.getCount() - ici.getCount());
            } else {
                if (!avant.isEmpty())
                    receptionSortie.variationSortie(slot, avant, avant.getCount());
                if (!ici.isEmpty()) receptionSortie.variationSortie(slot, ici, -ici.getCount());
            }
            entree.setValue(ici.copy());
        }
    }

    public static void actualiser(List<PlanRetrait.Stock<ItemStack>> inventaire) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            vider();
            return;
        }
        // Échap peut précéder la synchronisation des poches. Le geste déjà effectué reste
        // lié à sa vraie sortie et expire dans ReceptionSortie si aucun gain ne le confirme.
        if (player.containerMenu != player.inventoryMenu || !receptionSortie.enAttente())
            observer(player.containerMenu);
        releverSorties();
        List<PlanRetrait.Stock<ItemStack>> gains = new ArrayList<>();
        List<ItemStack> vus = new ArrayList<>();
        if (!crafts.isEmpty()) {
            for (var pile : inventaire) {
                ItemStack item = pile.objet();
                // Sans événement sur cet item, son gain ne constitue aucune preuve de craft.
                // Filtrer avant les comptages évite de comparer tout un backpack à chaque tick.
                if (crafts.stream().noneMatch(s -> ItemStack.isSameItemSameTags(s, item))) continue;
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
                receptionSortie.enAttente()
                        ? receptionSortie.actualiser(inventaire, List.of(), fabriques)
                        : List.<PlanRetrait.Stock<ItemStack>>of();
        if (!receptionSortie.enAttente()) sortiesEnCours.clear();
        provenances =
                new Provenances(
                        fabriques,
                        retires.isEmpty() ? null : new ReceptionMachine(menu, position, retires));
        gains.addAll(retires);
        receptions = List.copyOf(gains);
        Map<String, Integer> collecte = null;
        for (var gain : gains) {
            var id =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(
                            gain.objet().getItem());
            if (id == null) continue;
            totalFabrique.merge(id.toString(), gain.quantite(), OrigineFabrication::additionner);
            var variante = RechercheEmi.objetCollecte(gain.objet());
            if (!variante.isEmpty()) {
                if (collecte == null) collecte = new HashMap<>(totalCollecte);
                collecte.merge(variante, gain.quantite(), OrigineFabrication::additionner);
            }
        }
        if (collecte != null) totalCollecte = Map.copyOf(collecte);
        crafts.removeIf(
                item ->
                        fabriques.stream()
                                .anyMatch(s -> ItemStack.isSameItemSameTags(s.objet(), item)));
        if (++age > 20) crafts.clear();
        precedent = List.copyOf(inventaire);
    }
}
