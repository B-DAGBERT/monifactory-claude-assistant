package fr.assistantia;

import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.BiConsumer;
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
    private static Portee portee;
    private static Method clicStockage;
    private static List<Lot> attente = List.of();
    private static int index, reste, pris, delai, synchronisation, stable;
    private static boolean confirmation;
    private static BiConsumer<ItemStack, Integer> reception;
    private static Deplacement receptionAttendue;
    private static final int ATTENTE_SERVEUR = 60; // Trois secondes sans renvoyer de clic.
    private static long dernierClic;
    private static final Map<Slot, ItemStack> attendu = new IdentityHashMap<>();

    private record Portee(Object joueur, Object monde, Object connexion) {
        static Portee courante(Minecraft mc) {
            var lien = mc.getConnection();
            return new Portee(mc.player, mc.level, lien == null ? null : lien.getConnection());
        }

        boolean meme(Portee autre) {
            return joueur == autre.joueur && monde == autre.monde && connexion == autre.connexion;
        }
    }

    /** Une confirmation en vol concerne seulement les deux cases du dernier déplacement. */
    private static final class Deplacement {
        final Slot source, destination;
        final ItemStack pile, sourceAttendue, destinationAttendue;
        ItemStack sourceCorrigee, destinationCorrigee;

        Deplacement(Slot source, Slot destination, ItemStack objet, int quantite) {
            this.source = source;
            this.destination = destination;
            pile = objet.copy();
            pile.setCount(quantite);
            sourceAttendue = source.getItem().copy();
            destinationAttendue = destination.getItem().copy();
        }

        boolean concordeApresFermeture(AbstractContainerMenu inventaire) {
            var restante = sourceCorrigee == null ? source.getItem() : sourceCorrigee;
            if (!identique(restante, sourceAttendue)
                    || destinationCorrigee != null
                            && !identique(destinationCorrigee, destinationAttendue)) return false;
            for (Slot slot : inventaire.slots)
                if (personnel(slot) && slot.getContainerSlot() == destination.getContainerSlot())
                    return identique(slot.getItem(), destinationAttendue);
            return false;
        }

        void observer(int slot, ItemStack pile) {
            if (slot == source.index) sourceCorrigee = pile.copy();
            if (slot == destination.index) destinationCorrigee = pile.copy();
        }
    }

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

    /**
     * Piles des cases du coffre ouvert (ni poches, ni filtres, ni upgrades) ; vide hors d'un
     * coffre. Lu à chaque tick : les piles du menu, sans copie, à ne pas modifier.
     */
    public static List<ItemStack> coffreOuvert() {
        var p = Minecraft.getInstance().player;
        if (p == null || p.containerMenu == null || !estCoffre(p.containerMenu)) return List.of();
        List<ItemStack> piles = new ArrayList<>();
        for (Slot s : p.containerMenu.slots)
            if (s.isActive() && s.hasItem() && source(p.containerMenu, s)) piles.add(s.getItem());
        return piles;
    }

    static List<ItemStack> pilesTampon(AbstractContainerMenu menu) {
        var joueur = Minecraft.getInstance().player;
        if (joueur == null || joueur.containerMenu != menu) return null;
        List<ItemStack> piles = new ArrayList<>();
        try {
            boolean sophistique = !(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu);
            var infini =
                    sophistique ? menu.getClass().getMethod("isInfiniteSlot", int.class) : null;
            int taille =
                    sophistique
                            ? (int)
                                    menu.getClass()
                                            .getMethod("getNumberOfStorageInventorySlots")
                                            .invoke(menu)
                            : menu.slots.size();
            for (int i = 0; i < taille; i++) {
                var slot = menu.getSlot(i);
                if (slot.isActive()
                        && slot.hasItem()
                        && source(menu, slot)
                        && slot.mayPickup(joueur)
                        && (infini == null || !(boolean) infini.invoke(menu, i)))
                    piles.add(slot.getItem());
            }
            return piles;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static Plan planRecherche(
            AbstractContainerMenu m,
            java.util.function.Function<List<PlanRetrait.Stock<String>>, List<PlanRetrait.Prise>>
                    selection,
            java.util.function.Function<ItemStack, String> identifiant) {
        return planRessources(
                m, selection, item -> new RessourceCollecte(identifiant.apply(item), 1));
    }

    public static Plan planRessources(
            AbstractContainerMenu m,
            java.util.function.Function<List<PlanRetrait.Stock<String>>, List<PlanRetrait.Prise>>
                    selection,
            java.util.function.Function<ItemStack, RessourceCollecte> ressource) {
        var joueur = Minecraft.getInstance().player;
        List<Slot> slots = new ArrayList<>();
        List<RessourceCollecte> unites = new ArrayList<>();
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        if (joueur != null && estCoffre(m))
            for (Slot slot : m.slots) {
                if (!slot.isActive()
                        || !slot.hasItem()
                        || !source(m, slot)
                        || !slot.mayPickup(joueur)) continue;
                var objet = slot.getItem();
                var unite = ressource.apply(objet);
                if (unite == null) continue;
                slots.add(slot);
                unites.add(unite);
                stocks.add(
                        new PlanRetrait.Stock<>(
                                unite.id(), unite.quantite(objet.getCount()), false));
            }
        List<Lot> lots = new ArrayList<>();
        for (var prise : selection.apply(stocks)) {
            Slot slot = slots.get(prise.index());
            int n =
                    Math.min(
                            unites.get(prise.index()).contenants(prise.quantite()),
                            slot.getItem().getCount());
            if (n > 0) lots.add(new Lot(slot, slot.getItem().copy(), n));
        }
        return new Plan(List.copyOf(lots), 0);
    }

    public static void arreter() {
        if (occupe()) terminer("");
    }

    public static boolean occupe() {
        return menu != null;
    }

    public static boolean lancer(AbstractContainerScreen<?> ecran, Plan plan) {
        return lancer(ecran, plan, null);
    }

    /** La réception suit chaque déplacement confirmé vers une case personnelle. */
    public static boolean lancer(
            AbstractContainerScreen<?> ecran, Plan plan, BiConsumer<ItemStack, Integer> reception) {
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
        RetraitCoffre.reception = reception;
        menu = ecran.getMenu();
        portee = Portee.courante(mc);
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
        if (mc.player == null || mc.gameMode == null || !portee.meme(Portee.courante(mc))) {
            terminer("");
            return;
        }
        boolean ouvert =
                mc.player.containerMenu == menu
                        && mc.screen instanceof AbstractContainerScreen<?> s
                        && s.getMenu() == menu;
        boolean ferme =
                !ouvert
                        && confirmation
                        && receptionAttendue != null
                        && mc.player.containerMenu == mc.player.inventoryMenu;
        if (!ouvert && !ferme) {
            terminer("");
            return;
        }
        if (delai-- > 0) return;
        String ecart = ferme ? ecartApresFermeture(mc.player.inventoryMenu) : ecart();
        if (confirmation) {
            synchronisation++;
            if (ecart != null
                    || !menu.getCarried().isEmpty()
                    || ferme && !mc.player.inventoryMenu.getCarried().isEmpty()) {
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
                            ferme
                                    ? ""
                                    : "Transfert arrêté : le contenu ne correspond pas au déplacement attendu.");
                }
                return; // Une réponse intermédiaire n'est pas un refus. Aucun nouveau clic.
            }
            if (++stable < 2) return;
            confirmation = false;
            synchronisation = stable = 0;
            var recue = receptionAttendue;
            receptionAttendue = null;
            if (recue != null) {
                try {
                    reception.accept(recue.pile, recue.pile.getCount());
                } catch (RuntimeException e) {
                    System.getLogger(RetraitCoffre.class.getName())
                            .log(System.Logger.Level.WARNING, "Suivi du retrait indisponible", e);
                }
            }
            if (ferme) {
                terminer("");
                return;
            }
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
        int avantSource = src.getItem().getCount();
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
        var placee = dest.getItem();
        int deplace = placee.getCount() - avant;
        if (!menu.getCarried().isEmpty() || deplace <= 0) {
            terminer("Retrait interrompu : vérifie l’objet sur le curseur.");
            return;
        }
        if (reception != null
                && deplace <= quantite
                && avantSource - src.getItem().getCount() == deplace
                && ItemStack.isSameItemSameTags(placee, attente.get(index).objet())) {
            receptionAttendue = new Deplacement(src, dest, placee, deplace);
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

    private static String ecartApresFermeture(AbstractContainerMenu inventaire) {
        return receptionAttendue.concordeApresFermeture(inventaire)
                ? null
                : "source ou gain personnel non confirmé après fermeture";
    }

    private static Deplacement retraitFerme(Object connexion, int conteneur) {
        var mc = Minecraft.getInstance();
        return receptionAttendue != null
                        && menu != null
                        && conteneur == menu.containerId
                        && mc.player != null
                        && mc.player.containerMenu == mc.player.inventoryMenu
                        && connexion == portee.connexion()
                        && portee.meme(Portee.courante(mc))
                ? receptionAttendue
                : null;
    }

    /** Le client ignore les corrections de la fenêtre refermée. Garder celles des deux cases. */
    public static void observerCorrection(
            Object connexion, int conteneur, int slot, ItemStack pile) {
        var retrait = retraitFerme(connexion, conteneur);
        if (retrait != null) retrait.observer(slot, pile);
    }

    public static void observerCorrection(Object connexion, int conteneur, List<ItemStack> piles) {
        var retrait = retraitFerme(connexion, conteneur);
        if (retrait == null) return;
        int source = retrait.source.index, destination = retrait.destination.index;
        if (source < piles.size()) retrait.observer(source, piles.get(source));
        if (destination < piles.size()) retrait.observer(destination, piles.get(destination));
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
        portee = null;
        clicStockage = null;
        reception = null;
        receptionAttendue = null;
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
