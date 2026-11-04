package fr.assistantia;

import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Retrait client par clics normaux : aucun paquet de création d'objet ni besoin de mod serveur. */
public final class RetraitCoffre {
    public record Lot(Slot source, ItemStack objet, int quantite) {}

    public record Plan(List<Lot> lots, long manquants) {
        public int total() {
            return lots.stream().mapToInt(Lot::quantite).sum();
        }
    }

    private static AbstractContainerMenu menu;
    private static AbstractContainerScreen<?> ecran;
    private static Method clicStockage;
    private static List<Lot> attente = List.of();
    private static int index, reste, pris, delai;
    private static long dernierClic;
    private static final Map<Slot, ItemStack> attendu = new IdentityHashMap<>();

    public static boolean estCoffre(AbstractContainerMenu m) {
        return m instanceof ChestMenu
                || m instanceof ShulkerBoxMenu
                || m.getClass()
                        .getName()
                        .equals(
                                "net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu");
    }

    private static boolean personnel(Slot s) {
        var p = Minecraft.getInstance().player;
        return p != null && s.container == p.getInventory() && s.getContainerSlot() < 36;
    }

    private static boolean source(AbstractContainerMenu m, Slot s) {
        if (personnel(s)) return false;
        if (m instanceof ChestMenu || m instanceof ShulkerBoxMenu) return true;
        // N'inclut ni les filtres fantômes, ni les upgrades, ni la grille d'un upgrade de craft.
        for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass())
            if (c.getName()
                    .equals("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot"))
                return true;
        return false;
    }

    public static Plan plan(AbstractContainerMenu m, List<PlanRetrait.Besoin<ItemStack>> besoins) {
        var p = Minecraft.getInstance().player;
        if (p == null || !estCoffre(m)) return new Plan(List.of(), 0);
        List<Slot> slots = new ArrayList<>();
        List<PlanRetrait.Stock<ItemStack>> stocks = new ArrayList<>();
        for (Slot s : m.slots) {
            if (!s.isActive() || !s.hasItem() || !(personnel(s) || source(m, s) && s.mayPickup(p)))
                continue;
            slots.add(s);
            stocks.add(
                    new PlanRetrait.Stock<>(
                            s.getItem().copy(), s.getItem().getCount(), personnel(s)));
        }
        var r = PlanRetrait.calculer(stocks, besoins);
        List<Lot> lots = new ArrayList<>();
        for (var prise : r.prises()) {
            Slot s = slots.get(prise.index());
            lots.add(new Lot(s, s.getItem().copy(), prise.quantite()));
        }
        return new Plan(List.copyOf(lots), r.manquants());
    }

    public static boolean occupe() {
        return menu != null;
    }

    public static boolean lancer(AbstractContainerScreen<?> ecran, Plan plan) {
        var mc = Minecraft.getInstance();
        if (occupe() || System.nanoTime() - dernierClic < 800_000_000L) return false;
        if (mc.player == null
                || mc.player.containerMenu != ecran.getMenu()
                || !estCoffre(ecran.getMenu())) return false;
        if (!ecran.getMenu().getCarried().isEmpty()) {
            message("Pose d’abord l’objet tenu par la souris.");
            return false;
        }
        if (plan.lots().isEmpty()) {
            message("Aucun ingrédient manquant à prendre dans ce coffre.");
            return false;
        }
        try {
            clicStockage = clicSpecial(ecran);
        } catch (ReflectiveOperationException e) {
            message("Transfert indisponible.");
            return false;
        }
        RetraitCoffre.ecran = ecran;
        menu = ecran.getMenu();
        attente = plan.lots();
        index = 0;
        reste = attente.get(0).quantite();
        pris = 0;
        delai = 0;
        dernierClic = System.nanoTime();
        memoriser();
        return true;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || menu == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.gameMode == null
                || mc.player.containerMenu != menu
                || !(mc.screen instanceof AbstractContainerScreen<?> s)
                || s.getMenu() != menu) {
            terminer("");
            return;
        }
        if (delai-- > 0) return;
        if (!menu.getCarried().isEmpty()) {
            terminer("Retrait arrêté : objet sur le curseur.");
            return;
        }
        for (var entry : attendu.entrySet()) {
            if (!menu.slots.contains(entry.getKey())
                    || !identique(entry.getKey().getItem(), entry.getValue())) {
                terminer(
                        "Inventaire modifié ou transfert corrigé par le serveur : relance le retrait.");
                return;
            }
        }
        if (index >= attente.size()) {
            terminer("");
            return;
        }
        Lot lot = attente.get(index);
        Slot src = lot.source();
        if (!src.isActive()
                || !src.mayPickup(mc.player)
                || !ItemStack.isSameItemSameTags(src.getItem(), lot.objet())) {
            terminer("Contenu du coffre modifié : relance le retrait.");
            return;
        }
        Slot dest = null;
        int place = 0;
        // Remplir les piles existantes avant d'occuper une nouvelle case.
        for (int pass = 0; pass < 2 && dest == null; pass++)
            for (Slot candidate : menu.slots) {
                ItemStack item = candidate.getItem();
                if (!personnel(candidate)
                        || !candidate.isActive()
                        || !candidate.mayPlace(lot.objet())
                        || (pass == 0 ? item.isEmpty() : !item.isEmpty())
                        || !item.isEmpty() && !ItemStack.isSameItemSameTags(item, lot.objet()))
                    continue;
                int libre =
                        Math.min(
                                        candidate.getMaxStackSize(lot.objet()),
                                        lot.objet().getMaxStackSize())
                                - item.getCount();
                if (libre > 0) {
                    dest = candidate;
                    place = libre;
                    break;
                }
            }
        if (dest == null) {
            terminer("Inventaire plein.");
            return;
        }
        int quantite =
                Math.min(
                        Math.min(reste, place),
                        Math.min(src.getItem().getCount(), lot.objet().getMaxStackSize()));
        if (quantite <= 0) {
            terminer("Retrait arrêté : quantité indisponible.");
            return;
        }
        try {
            deplacer(src, dest, quantite);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.getLogger(RetraitCoffre.class.getName())
                    .log(System.Logger.Level.WARNING, "Clic coffre impossible", e);
            terminer("Transfert interrompu.");
        }
    }

    private static void deplacer(Slot src, Slot dest, int quantite)
            throws ReflectiveOperationException {
        clic(src, 0);
        ItemStack curseur = menu.getCarried();
        if (curseur.isEmpty()
                || !ItemStack.isSameItemSameTags(curseur, attente.get(index).objet())) {
            terminer("Retrait refusé par le coffre.");
            return;
        }
        quantite = Math.min(quantite, curseur.getCount());
        int avant = dest.getItem().getCount();
        if (quantite == curseur.getCount()) clic(dest, 0);
        else for (int i = 0; i < quantite; i++) clic(dest, 1);
        // Remettre le surplus dans son emplacement d'origine, jamais hors de l'écran.
        if (!menu.getCarried().isEmpty()) clic(src, 0);
        int deplace = dest.getItem().getCount() - avant;
        if (!menu.getCarried().isEmpty() || deplace <= 0) {
            terminer("Retrait interrompu : vérifie l’objet sur le curseur.");
            return;
        }
        pris += deplace;
        reste -= deplace;
        if (reste <= 0) {
            index++;
            if (index < attente.size()) reste = attente.get(index).quantite();
        }
        memoriser();
        delai = 4; // Laisse au serveur le temps de synchroniser entre deux piles.
    }

    private static boolean identique(ItemStack a, ItemStack b) {
        return a.isEmpty() && b.isEmpty()
                || a.getCount() == b.getCount() && ItemStack.isSameItemSameTags(a, b);
    }

    private static void memoriser() {
        attendu.clear();
        for (Slot s : menu.slots) if (personnel(s)) attendu.put(s, s.getItem().copy());
        for (Lot lot : attente) attendu.put(lot.source(), lot.source().getItem().copy());
    }

    private static Method clicSpecial(AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName()
                    .equals("net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase")) {
                // Ce mod synchronise les piles étendues et les upgrades avec ses propres paquets.
                // Utiliser exactement le chemin de clic de son écran, pas le paquet vanilla.
                Method method =
                        type.getDeclaredMethod(
                                "handleInventoryMouseClick", int.class, int.class, ClickType.class);
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void clic(Slot s, int bouton) throws ReflectiveOperationException {
        if (clicStockage != null) {
            clicStockage.invoke(ecran, s.index, bouton, ClickType.PICKUP);
        } else {
            var mc = Minecraft.getInstance();
            mc.gameMode.handleInventoryMouseClick(
                    menu.containerId, s.index, bouton, ClickType.PICKUP, mc.player);
        }
    }

    private static void terminer(String texte) {
        menu = null;
        ecran = null;
        clicStockage = null;
        attente = List.of();
        attendu.clear();
        dernierClic = System.nanoTime();
        message(texte);
    }

    private static void message(String texte) {
        var p = Minecraft.getInstance().player;
        if (p != null && !texte.isEmpty()) p.displayClientMessage(Component.literal(texte), true);
    }
}
