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

        /** Sélection après allocation globale : ne compte pas deux fois les variantes communes. */
        public Plan pour(ItemStack objet) {
            return new Plan(
                    lots.stream()
                            .filter(l -> ItemStack.isSameItemSameTags(l.objet(), objet))
                            .toList(),
                    0);
        }

        public List<ItemStack> objets() {
            List<ItemStack> objets = new ArrayList<>();
            for (Lot lot : lots)
                if (objets.stream().noneMatch(i -> ItemStack.isSameItemSameTags(i, lot.objet())))
                    objets.add(lot.objet().copy());
            return List.copyOf(objets);
        }
    }

    private static AbstractContainerMenu menu;
    private static AbstractContainerScreen<?> ecran;
    private static Method clicStockage;
    private static List<Lot> attente = List.of();
    private static int index, reste, pris, delai, synchronisation, stable;
    private static boolean confirmation;
    private static final int ATTENTE_SERVEUR = 60; // Trois secondes sans renvoyer de clic.
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

    private record Contenu(List<Slot> slots, List<PlanRetrait.Stock<ItemStack>> stocks) {}

    private static Contenu contenu(AbstractContainerMenu m) {
        var p = Minecraft.getInstance().player;
        List<Slot> slots = new ArrayList<>();
        List<PlanRetrait.Stock<ItemStack>> stocks = new ArrayList<>();
        if (p != null && estCoffre(m))
            for (Slot s : m.slots) {
                if (!s.isActive()
                        || !s.hasItem()
                        || !(personnel(s) || source(m, s) && s.mayPickup(p))) continue;
                slots.add(s);
                stocks.add(
                        new PlanRetrait.Stock<>(
                                s.getItem().copy(), s.getItem().getCount(), personnel(s)));
            }
        return new Contenu(slots, stocks);
    }

    private static Plan convertir(Contenu contenu, List<PlanRetrait.Prise> prises, long manque) {
        List<Lot> lots = new ArrayList<>();
        for (var prise : prises) {
            Slot s = contenu.slots().get(prise.index());
            lots.add(new Lot(s, s.getItem().copy(), prise.quantite()));
        }
        return new Plan(List.copyOf(lots), manque);
    }

    public static Plan plan(AbstractContainerMenu m, List<PlanRetrait.Besoin<ItemStack>> besoins) {
        var contenu = contenu(m);
        var r = PlanRetrait.calculer(contenu.stocks(), besoins);
        return convertir(contenu, r.prises(), r.manquants());
    }

    public static Plan planRecherche(
            AbstractContainerMenu m,
            java.util.function.Function<List<PlanRetrait.Stock<String>>, List<PlanRetrait.Prise>>
                    selection,
            java.util.function.Function<ItemStack, String> identifiant) {
        var contenu = contenu(m);
        List<Slot> slots = new ArrayList<>();
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        for (Slot slot : contenu.slots())
            if (!personnel(slot)) {
                slots.add(slot);
                stocks.add(
                        new PlanRetrait.Stock<>(
                                identifiant.apply(slot.getItem()),
                                slot.getItem().getCount(),
                                false));
            }
        List<Lot> lots = new ArrayList<>();
        for (var prise : selection.apply(stocks)) {
            Slot slot = slots.get(prise.index());
            int n = Math.min(prise.quantite(), slot.getItem().getCount());
            if (n > 0) lots.add(new Lot(slot, slot.getItem().copy(), n));
        }
        return new Plan(List.copyOf(lots), 0);
    }

    public static Plan planArbre(
            AbstractContainerMenu m,
            ArbreCollecte.Noeud arbre,
            long lots,
            java.util.function.Function<
                            java.util.Set<String>, java.util.function.Predicate<ItemStack>>
                    accepte) {
        var contenu = contenu(m);
        var r = PlanArbre.calculer(arbre, lots, contenu.stocks(), accepte);
        return convertir(
                contenu,
                r.prises(),
                r.manquants().stream().mapToLong(MemoireCollecte.Ingredient::quantite).sum());
    }

    public static void arreter() {
        if (occupe()) terminer("");
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
        delai = synchronisation = stable = 0;
        confirmation = false;
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
        String ecart = ecart();
        if (confirmation) {
            synchronisation++;
            if (ecart != null || !menu.getCarried().isEmpty()) {
                stable = 0;
                if (synchronisation >= ATTENTE_SERVEUR) {
                    System.getLogger(RetraitCoffre.class.getName())
                            .log(
                                    System.Logger.Level.WARNING,
                                    "Transfert non confirmé : "
                                            + (ecart == null ? "curseur encore occupé" : ecart)
                                            + ", menu="
                                            + menu.getClass().getName()
                                            + ", déplacés côté client="
                                            + pris);
                    terminer(
                            "Transfert arrêté : le contenu ne correspond pas au déplacement attendu.");
                }
                return; // Une réponse intermédiaire n'est pas un refus. Aucun nouveau clic.
            }
            if (++stable < 2) return;
            confirmation = false;
            synchronisation = stable = 0;
        } else {
            if (!menu.getCarried().isEmpty()) {
                terminer("Retrait arrêté : objet sur le curseur.");
                return;
            }
            if (ecart != null) {
                terminer("Contenu modifié avant le transfert : relance le retrait.");
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
        int surplus = curseur.getCount() - quantite;
        if (surplus == 0) clic(dest, 0);
        else if (surplus < quantite) {
            // Rendre le petit surplus, puis poser le reste : moins de paquets qu'un clic par objet.
            for (int i = 0; i < surplus; i++) clic(src, 1);
            clic(dest, 0);
        } else for (int i = 0; i < quantite; i++) clic(dest, 1);
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
        delai = 4;
        confirmation = true;
        synchronisation = stable = 0;
    }

    private static boolean concerne(ItemStack item) {
        if (item.isEmpty()) return false;
        for (Lot lot : attente) if (ItemStack.isSameItemSameTags(item, lot.objet())) return true;
        return false;
    }

    private static String ecart() {
        for (var entry : attendu.entrySet()) {
            Slot slot = entry.getKey();
            ItemStack avant = entry.getValue(), maintenant = slot.getItem();
            // Un outil qui se recharge ou un objet sans rapport ne doit pas arrêter le retrait.
            if (personnel(slot) && !concerne(avant) && !concerne(maintenant)) continue;
            if (!menu.slots.contains(slot) || !identique(maintenant, avant))
                return "slot="
                        + slot.index
                        + ", quantité attendue="
                        + avant.getCount()
                        + ", reçue="
                        + maintenant.getCount()
                        + ", même objet/NBT="
                        + (avant.isEmpty() && maintenant.isEmpty()
                                || ItemStack.isSameItemSameTags(avant, maintenant));
        }
        return null;
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
        confirmation = false;
        synchronisation = stable = 0;
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
