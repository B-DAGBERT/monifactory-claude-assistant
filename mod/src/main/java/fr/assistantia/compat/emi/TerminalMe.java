package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.BoutonTheme;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.Theme;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** L'inventaire ME est celui du menu AE2 synchronisé, valable le temps de son ouverture. */
final class TerminalMe {
    private record Entree(long serial, EmiStack pile, int quantite) {}

    private record Lot(long serial, ItemStack pile, int quantite) {}

    private record Acces(
            Class<?> menu,
            Class<?> ecran,
            Class<?> item,
            Method repo,
            Method powered,
            Method entrees,
            Method cle,
            Method quantite,
            Method serial,
            Method itemNatif,
            Method pile,
            Method retirer,
            Object un,
            Object pileEntiere) {}

    private record Vue(
            AbstractContainerMenu menu,
            InstantaneArbreEmi arbre,
            long inventaire,
            int menuRevision,
            List<Entree> stock,
            long lots,
            List<Lot> retraits) {}

    private static final Map<Screen, BoutonTheme> boutons = new WeakHashMap<>();
    private static final Acces ACCES = charger();
    private static Vue vue;
    private static AbstractContainerMenu menu;
    private static AbstractContainerScreen<?> ecran;
    private static InstantaneArbreEmi arbre;
    private static Object connexion;
    private static List<Lot> retraits = List.of();
    private static int index, reste, attendus, avant, recus, depotAvant;
    private static Slot destination;
    private static long echeance;
    private static boolean erreur;
    private static int ticks;

    private TerminalMe() {}

    private static Acces charger() {
        try {
            var menu = Class.forName("appeng.menu.me.common.MEStorageMenu");
            var ecran = Class.forName("appeng.client.gui.me.common.MEStorageScreen");
            var repo = Class.forName("appeng.menu.me.common.IClientRepo");
            var entree = Class.forName("appeng.menu.me.common.GridInventoryEntry");
            var item = Class.forName("appeng.api.stacks.AEItemKey");
            var action = Class.forName("appeng.helpers.InventoryAction");
            return new Acces(
                    menu,
                    ecran,
                    item,
                    menu.getMethod("getClientRepo"),
                    menu.getMethod("isPowered"),
                    repo.getMethod("getAllEntries"),
                    entree.getMethod("getWhat"),
                    entree.getMethod("getStoredAmount"),
                    entree.getMethod("getSerial"),
                    item.getMethod("getItem"),
                    item.getMethod("toStack"),
                    menu.getMethod("handleInteraction", long.class, action),
                    action.getField("PICKUP_SINGLE").get(null),
                    action.getField("PICKUP_OR_SET_DOWN").get(null));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            signaler(incompatible);
            return null;
        }
    }

    private static AbstractContainerMenu courant() {
        var mc = Minecraft.getInstance();
        if (ACCES == null || mc.player == null || mc.level == null) return null;
        var actif = mc.player.containerMenu;
        return ACCES.menu().isInstance(actif) ? actif : null;
    }

    static boolean occupe() {
        return menu != null;
    }

    static List<PlanRetrait.Stock<EmiStack>> stock() {
        if (vue == null || vue.menu() != courant() || !InventaireJoueur.disponible())
            return List.of();
        return vue.stock().stream()
                .map(e -> new PlanRetrait.Stock<>(e.pile(), e.quantite(), false))
                .toList();
    }

    private static List<Entree> lire(AbstractContainerMenu actif, InstantaneArbreEmi arbre)
            throws ReflectiveOperationException {
        if (!(boolean) ACCES.powered().invoke(actif)) return List.of();
        Object repo = ACCES.repo().invoke(actif);
        if (repo == null) return List.of();
        var variantes = new IdentityHashMap<Object, List<EmiStack>>();
        for (var pile : arbre.variantes())
            variantes.computeIfAbsent(pile.getKey(), k -> new ArrayList<>()).add(pile);
        var result = new ArrayList<Entree>();
        var vus = new HashSet<Long>();
        for (Object e : (Collection<?>) ACCES.entrees().invoke(repo)) {
            long n = (long) ACCES.quantite().invoke(e);
            if (n <= 0) continue;
            Object cle = ACCES.cle().invoke(e);
            if (!ACCES.item().isInstance(cle)) continue;
            var choix = variantes.get(ACCES.itemNatif().invoke(cle));
            if (choix == null) continue;
            long serial = (long) ACCES.serial().invoke(e);
            if (serial == -1 || !vus.add(serial)) continue;
            var pile =
                    ComparaisonEmi.pourInventaire(
                            EmiStack.of((ItemStack) ACCES.pile().invoke(cle)));
            if (choix.stream().anyMatch(v -> ComparaisonEmi.meme(pile, v)))
                result.add(new Entree(serial, pile, (int) Math.min(1_000_000L, n)));
        }
        result.sort(java.util.Comparator.comparingLong(Entree::serial));
        return List.copyOf(result);
    }

    private static Object arbreCourant() throws ReflectiveOperationException {
        return fr.assistantia.ArbreEmi.consulte();
    }

    private static void actualiser() throws ReflectiveOperationException {
        var actif = courant();
        Object tree = actif == null ? null : arbreCourant();
        if (tree == null || !InventaireJoueur.disponible()) {
            if (vue != null) InventaireArbreEmi.invalider();
            vue = null;
            return;
        }
        var instantane = InstantaneArbreEmi.lire(tree);
        var stock = lire(actif, instantane);
        long revision = InventaireJoueur.revision(), lots = MachinesEmi.revisionLotsProduction();
        if (vue != null
                && vue.menu() == actif
                && vue.arbre() == instantane
                && vue.inventaire() == revision
                && vue.menuRevision() == actif.getStateId()
                && vue.lots() == lots
                && vue.stock().equals(stock)) return;
        var personnels = InventaireArbreEmi.personnel();
        var virtuels = InventaireArbreEmi.stocksVirtuels(personnels);
        var physiques = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (var pile : personnels.inventory.values())
            physiques.add(
                    new PlanRetrait.Stock<>(
                            pile, (int) Math.min(1_000_000L, pile.getAmount()), true));
        physiques.addAll(virtuels);
        int debut = physiques.size();
        for (var e : stock) physiques.add(new PlanRetrait.Stock<>(e.pile(), e.quantite(), false));
        var retraits = new ArrayList<Lot>();
        if (!stock.isEmpty())
            for (var prise :
                    PlanMachines.retraits(instantane.machines(), instantane.lots(), physiques)) {
                var entree = stock.get(prise.index() - debut);
                retraits.add(
                        new Lot(entree.serial(), entree.pile().getItemStack(), prise.quantite()));
            }
        vue =
                new Vue(
                        actif,
                        instantane,
                        revision,
                        actif.getStateId(),
                        stock,
                        lots,
                        List.copyOf(retraits));
        InventaireArbreEmi.invalider();
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        if (ACCES == null || !ACCES.ecran().isInstance(event.getScreen())) return;
        var screen = (AbstractContainerScreen<?>) event.getScreen();
        var bouton = BoutonTheme.creer("Prendre pour EMI", b -> lancer(screen));
        bouton.visible = false;
        boutons.put(screen, bouton);
        event.addListener(bouton);
    }

    static void zoneEmi(
            Screen screen, java.util.function.Consumer<dev.emi.emi.api.widget.Bounds> zones) {
        var b = boutons.get(screen);
        if (b != null && b.visible)
            zones.accept(
                    new dev.emi.emi.api.widget.Bounds(
                            b.getX(), b.getY(), b.getWidth(), b.getHeight()));
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Pre event) {
        var b = boutons.get(event.getScreen());
        if (b == null) return;
        var screen = (AbstractContainerScreen<?>) event.getScreen();
        b.visible =
                vue != null
                        && vue.menu() == screen.getMenu()
                        && (!vue.retraits().isEmpty() || occupe());
        if (!b.visible) return;
        int largeur = Math.min(screen.getXSize(), 146);
        // La grille ME, sa recherche et le titre commencent sous ce bouton.
        int y = Math.max(0, screen.getGuiTop() - Theme.BOUTON - 2);
        b.zone(screen.getGuiLeft() + (screen.getXSize() - largeur) / 2, y, largeur);
        b.active = true;
        if (occupe()) {
            b.texte("Arrêter le retrait · " + recus);
            b.infobulle("Arrête après l’action en cours.");
        } else {
            long total = vue.retraits().stream().mapToLong(Lot::quantite).sum();
            b.texte("Prendre pour EMI · " + total);
            var lignes =
                    vue.retraits().stream()
                            .limit(8)
                            .map(l -> l.quantite() + " × " + l.pile().getHoverName().getString())
                            .toList();
            b.infobulle(String.join("\n", lignes) + (vue.retraits().size() > 8 ? "\n…" : ""));
        }
    }

    @SubscribeEvent
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() != ecran || !occupe()) return;
        var bouton = boutons.get(ecran);
        if (event.getButton() != 0
                || bouton == null
                || !bouton.isMouseOver(event.getMouseX(), event.getMouseY()))
            terminer("Retrait ME interrompu.");
    }

    private static void lancer(AbstractContainerScreen<?> screen) {
        if (occupe()) {
            terminer("Retrait ME arrêté.");
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.screen != screen
                || courant() != screen.getMenu()
                || mc.getConnection() == null
                || fr.assistantia.RetraitCoffre.occupe()
                || EnvoiMachine.occupe()) return;
        if (!screen.getMenu().getCarried().isEmpty()) {
            message("Pose d’abord l’objet tenu par la souris.");
            return;
        }
        try {
            Vue proposee = vue;
            actualiser();
            if (vue == null || vue.retraits().isEmpty() || vue != proposee) {
                message("Besoins EMI actualisés. Vérifie les quantités puis clique à nouveau.");
                return;
            }
            ecran = screen;
            menu = screen.getMenu();
            arbre = vue.arbre();
            connexion = mc.getConnection();
            retraits = vue.retraits();
            index = recus = avant = attendus = 0;
            destination = null;
            reste = retraits.get(0).quantite();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            terminer("Retrait ME indisponible.");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACCES == null) return;
        try {
            if (occupe()) avancer();
            if (++ticks % 10 == 0 || vue != null && vue.menu() != courant()) actualiser();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            if (occupe()) terminer("Retrait ME interrompu.");
            vue = null;
        }
    }

    private static void avancer() throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        Object tree = arbreCourant();
        if (mc.screen != ecran
                || courant() != menu
                || mc.getConnection() != connexion
                || tree == null
                || InstantaneArbreEmi.lire(tree) != arbre) {
            terminer("Retrait ME interrompu : terminal ou arbre modifié.");
            return;
        }
        var curseur = menu.getCarried();
        if (destination != null) {
            if (curseur.isEmpty()
                    && ItemStack.isSameItemSameTags(
                            destination.getItem(), retraits.get(index).pile())
                    && destination.getItem().getCount() == depotAvant + avant) {
                recus += avant;
                avant = 0;
                destination = null;
            } else {
                attendre();
                return;
            }
        }
        if (reste == 0 && avant == 0) {
            if (++index == retraits.size()) {
                terminer(recus + " objets récupérés dans le réseau ME.");
                return;
            }
            reste = retraits.get(index).quantite();
        }
        var lot = retraits.get(index);
        if (attendus > 0) {
            int volume = ItemStack.isSameItemSameTags(curseur, lot.pile()) ? curseur.getCount() : 0;
            if (volume == avant) {
                attendre();
                return;
            }
            int gain = volume - avant;
            if (gain < 1 || gain > attendus) {
                terminer("Retrait ME interrompu : le curseur a changé.");
                return;
            }
            reste -= gain;
            avant = volume;
            attendus = 0;
        }
        if (avant != curseur.getCount()
                || avant > 0 && !ItemStack.isSameItemSameTags(curseur, lot.pile())) {
            terminer("Retrait ME interrompu : le curseur a changé.");
            return;
        }
        Slot cible = null;
        int place = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != mc.player.getInventory()
                    || !slot.isActive()
                    || !slot.mayPlace(lot.pile())) continue;
            var ici = slot.getItem();
            if (!ici.isEmpty() && !ItemStack.isSameItemSameTags(ici, lot.pile())) continue;
            int libre = slot.getMaxStackSize(lot.pile()) - ici.getCount();
            if (libre <= place) continue;
            cible = slot;
            place = libre;
        }
        if (cible == null || place < avant) {
            terminer("Retrait ME arrêté : manque de place dans l’inventaire.");
            return;
        }
        echeance = System.nanoTime() + 3_000_000_000L;
        if (avant > 0 && (reste == 0 || avant == place)) {
            destination = cible;
            depotAvant = cible.getItem().getCount();
            // Pas de prédiction locale : attendre que le serveur confirme la case et le curseur.
            mc.getConnection()
                    .send(
                            new ServerboundContainerClickPacket(
                                    menu.containerId,
                                    menu.getStateId(),
                                    cible.index,
                                    0,
                                    ClickType.PICKUP,
                                    curseur.copy(),
                                    new Int2ObjectOpenHashMap<>()));
            return;
        }
        if (!(boolean) ACCES.powered().invoke(menu)) {
            terminer("Retrait ME arrêté : terminal hors tension.");
            return;
        }
        int taillePile = lot.pile().getMaxStackSize();
        boolean entier = avant == 0 && reste >= taillePile && place >= taillePile;
        attendus = entier ? taillePile : 1;
        ACCES.retirer().invoke(menu, lot.serial(), entier ? ACCES.pileEntiere() : ACCES.un());
    }

    private static void attendre() {
        if (System.nanoTime() >= echeance)
            terminer("Retrait ME interrompu : réponse du terminal absente. Vérifie le curseur.");
    }

    private static void terminer(String texte) {
        menu = null;
        ecran = null;
        connexion = null;
        arbre = null;
        destination = null;
        retraits = List.of();
        vue = null;
        ticks = 9;
        InventaireArbreEmi.invalider();
        message(texte);
    }

    private static void message(String texte) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(texte), true);
    }

    private static void signaler(Throwable cause) {
        if (erreur) return;
        erreur = true;
        LogUtils.getLogger().warn("Assistant : terminal ME indisponible", cause);
    }
}
