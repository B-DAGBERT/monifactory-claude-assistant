package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.CoffreTampon;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanRetrait;
import fr.assistantia.RelachementSouris;
import fr.assistantia.Theme;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * L'inventaire ME est celui du menu AE2 synchronisé, valable le temps de son ouverture. Le retrait
 * passe par le même panneau que la Collecte des coffres (voir DispositionCoffre) : posé hors du
 * terminal, réservé auprès d'EMI, et dont l'appui comme le relâchement ne vont jamais au terminal.
 */
final class TerminalMe {
    private record Entree(long serial, Object cle, ItemStack pile, int quantite) {}

    private record Lot(long serial, ItemStack pile, int quantite) {}

    private record Acces(
            Class<?> menu,
            Class<?> ecran,
            Class<?> item,
            Method repo,
            Method powered,
            Method entrees,
            Method parIngredient,
            Method cle,
            Method quantite,
            Method serial,
            Method itemNatif,
            Method pile,
            Method retirer,
            Object un,
            Object pileEntiere,
            Object demiPile,
            Object pileDirecte) {}

    private record Vue(
            AbstractContainerMenu menu,
            InstantaneArbreEmi arbre,
            long inventaire,
            long tampon,
            int menuRevision,
            List<Entree> stock,
            List<PlanRetrait.Stock<ItemStack>> stockCalcul,
            long lots,
            List<PlanRetrait.Stock<ItemStack>> credits,
            List<PlanRetrait.Stock<EmiStack>> outils,
            List<Lot> retraits) {}

    private record Recherche(
            InstantaneArbreEmi arbre, Map<Object, List<EmiStack>> variantes, Ingredient filtre) {}

    private static final String NOM = "Réseau ME", RETRAIT = "Retrait… ";
    private static final String PRENDRE = "Prendre", TOUT = "Tout", ARRETER = "Arrêter";
    private static final Acces ACCES = charger();

    /** Terminal ouvert et son panneau : objets proposés, choix de Prendre, page. */
    private static AbstractContainerScreen<?> affiche;

    private static Disposition disposition;
    private static List<Lot> objets = List.of();
    private static ItemStack choisi = ItemStack.EMPTY;
    private static int page;
    private static long cleDisposition, placeLe;
    private static Vue vue;
    private static Recherche recherche;
    private static Class<?> indexIndisponible;
    private static AbstractContainerMenu menu;
    private static AbstractContainerScreen<?> ecran;
    private static InstantaneArbreEmi arbre;
    private static Object connexion;
    private static List<Lot> retraits = List.of();
    private static int index, reste, attendus, avant, recus, depotAvant;
    private static Slot destination;
    private static boolean direct;
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
                    methodeIndex(repo),
                    entree.getMethod("getWhat"),
                    entree.getMethod("getStoredAmount"),
                    entree.getMethod("getSerial"),
                    item.getMethod("getItem"),
                    item.getMethod("toStack"),
                    menu.getMethod("handleInteraction", long.class, action),
                    action.getField("PICKUP_SINGLE").get(null),
                    action.getField("PICKUP_OR_SET_DOWN").get(null),
                    actionOptionnelle(action, "SPLIT_OR_PLACE_SINGLE"),
                    actionOptionnelle(action, "SHIFT_CLICK"));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            signaler(incompatible);
            return null;
        }
    }

    private static Object actionOptionnelle(Class<?> actions, String nom)
            throws IllegalAccessException {
        try {
            return actions.getField(nom).get(null);
        } catch (NoSuchFieldException absente) {
            return null;
        }
    }

    private static Method methodeIndex(Class<?> repo) {
        try {
            return repo.getMethod("getByIngredient", Ingredient.class);
        } catch (NoSuchMethodException absent) {
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

    static List<PlanRetrait.Stock<ItemStack>> stock() {
        if (vue == null || vue.menu() != courant() || !InventaireJoueur.fabricationDisponible())
            return List.of();
        return vue.stockCalcul();
    }

    private static List<PlanRetrait.Stock<ItemStack>> stocks(List<Entree> entrees) {
        return entrees.stream()
                .map(e -> new PlanRetrait.Stock<>(e.pile(), e.quantite(), false))
                .toList();
    }

    private static boolean memeStock(List<Entree> avant, List<Entree> apres) {
        if (avant.size() != apres.size()) return false;
        for (int i = 0; i < avant.size(); i++) {
            var a = avant.get(i);
            var b = apres.get(i);
            if (a.serial() != b.serial()
                    || a.quantite() != b.quantite()
                    || !a.cle().equals(b.cle())) return false;
        }
        return true;
    }

    private static Recherche recherche(InstantaneArbreEmi arbre) {
        if (recherche != null && recherche.arbre() == arbre) return recherche;
        var variantes = new IdentityHashMap<Object, List<EmiStack>>();
        for (var pile : arbre.variantes())
            variantes.computeIfAbsent(pile.getKey(), k -> new ArrayList<>()).add(pile);
        // Le filtre natif sélectionne les types d'objet. ComparaisonEmi valide ensuite les
        // véritables variantes, les tags, l'usure et les autres règles propres aux recettes.
        var items =
                variantes.keySet().stream()
                        .filter(Item.class::isInstance)
                        .map(Item.class::cast)
                        .toArray(Item[]::new);
        recherche = new Recherche(arbre, variantes, Ingredient.of(items));
        return recherche;
    }

    private static Collection<?> entrees(Object repo, Ingredient filtre, boolean complet)
            throws ReflectiveOperationException {
        var index = ACCES.parIngredient();
        if (!complet && index != null && indexIndisponible != repo.getClass()) {
            try {
                if (index.invoke(repo, filtre) instanceof Collection<?> candidates)
                    return candidates;
            } catch (ReflectiveOperationException | RuntimeException incompatible) {
                // Une variante AE2 peut exposer la méthode sans prendre en charge son index.
            }
            indexIndisponible = repo.getClass();
        }
        return (Collection<?>) ACCES.entrees().invoke(repo);
    }

    private static List<Entree> lire(
            AbstractContainerMenu actif, InstantaneArbreEmi arbre, boolean complet)
            throws ReflectiveOperationException {
        if (!(boolean) ACCES.powered().invoke(actif)) return List.of();
        Object repo = ACCES.repo().invoke(actif);
        if (repo == null) return List.of();
        var recherche = recherche(arbre);
        var variantes = recherche.variantes();
        if (variantes.isEmpty()) return List.of();
        var result = new ArrayList<Entree>();
        var vus = new HashSet<Long>();
        for (Object e : entrees(repo, recherche.filtre(), complet)) {
            long n = (long) ACCES.quantite().invoke(e);
            if (n <= 0) continue;
            Object cle = ACCES.cle().invoke(e);
            if (!ACCES.item().isInstance(cle)) continue;
            var choix = variantes.get(ACCES.itemNatif().invoke(cle));
            if (choix == null) continue;
            long serial = (long) ACCES.serial().invoke(e);
            if (serial == -1 || !vus.add(serial)) continue;
            var pile = (ItemStack) ACCES.pile().invoke(cle);
            var emi = ComparaisonEmi.pourInventaire(EmiStack.of(pile));
            if (choix.stream().anyMatch(v -> ComparaisonEmi.meme(emi, v)))
                result.add(new Entree(serial, cle, pile, (int) Math.min(1_000_000L, n)));
        }
        result.sort(java.util.Comparator.comparingLong(Entree::serial));
        return List.copyOf(result);
    }

    private static Object arbreCourant() throws ReflectiveOperationException {
        return fr.assistantia.ArbreEmi.consulte();
    }

    private static void actualiser(boolean complet) throws ReflectiveOperationException {
        var actif = courant();
        Object tree = actif == null ? null : arbreCourant();
        if (tree == null || !InventaireJoueur.fabricationDisponible()) {
            if (vue != null) InventaireArbreEmi.invalider();
            vue = null;
            recherche = null;
            return;
        }
        var instantane = InstantaneArbreEmi.lire(tree);
        var stock = lire(actif, instantane, complet);
        long revision = InventaireJoueur.revisionFabrication(),
                revisionTampon = CoffreTampon.revision(),
                lots = MachinesEmi.revisionLotsProduction();
        var credits = CycleArbreEmi.credits();
        var outils = MachinesEmi.outilsInstalles();
        boolean stockIdentique =
                vue != null && vue.menu() == actif && memeStock(vue.stock(), stock);
        if (!complet
                && stockIdentique
                && vue.menu() == actif
                && vue.arbre() == instantane
                && vue.inventaire() == revision
                && vue.tampon() == revisionTampon
                && vue.menuRevision() == actif.getStateId()
                && vue.lots() == lots
                && vue.credits().equals(credits)
                && vue.outils().equals(outils)) return;
        var stockCalcul = stockIdentique ? vue.stockCalcul() : stocks(stock);
        var couverture =
                StockFabricationEmi.courant(
                        InventaireArbreEmi.physiques(InventaireArbreEmi.personnel()), stockCalcul);
        var retraits = new ArrayList<Lot>();
        for (var prise : couverture.retraitsReseau(instantane.machines(), instantane.lots())) {
            var entree = stock.get(prise.index());
            retraits.add(new Lot(entree.serial(), entree.pile(), prise.quantite()));
        }
        vue =
                new Vue(
                        actif,
                        instantane,
                        revision,
                        revisionTampon,
                        actif.getStateId(),
                        stock,
                        stockCalcul,
                        lots,
                        credits,
                        outils,
                        List.copyOf(retraits));
        InventaireArbreEmi.invalider();
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        boolean terminal = ACCES != null && ACCES.ecran().isInstance(event.getScreen());
        affiche = terminal ? (AbstractContainerScreen<?>) event.getScreen() : null;
        disposition = null;
        objets = List.of();
        page = 0;
    }

    static void zoneEmi(
            Screen screen, java.util.function.Consumer<dev.emi.emi.api.widget.Bounds> zones) {
        var d = disposition;
        if (d != null && screen == affiche && !objets.isEmpty())
            zones.accept(
                    new dev.emi.emi.api.widget.Bounds(
                            d.cadre().x(),
                            d.cadre().y(),
                            d.cadre().largeur(),
                            d.cadre().hauteur()));
    }

    /** Pendant un retrait, ses lots figés ; sinon ceux du relevé de ce terminal. */
    private static List<Lot> proposes(Screen screen) {
        if (occupe()) return screen == ecran ? retraits : List.of();
        return vue != null && affiche != null && vue.menu() == affiche.getMenu()
                ? vue.retraits()
                : List.of();
    }

    /** Ce qu'il reste à retirer du lot {@code n} pendant le retrait en cours. */
    private static int restant(int n) {
        if (!occupe() || n < index) return occupe() ? 0 : objets.get(n).quantite();
        return n == index ? reste : retraits.get(n).quantite();
    }

    private static long restantTotal() {
        long total = 0;
        for (int n = 0; n < objets.size(); n++) total += restant(n);
        return total;
    }

    private static int quantite(ItemStack item) {
        for (int n = 0; n < objets.size(); n++)
            if (ItemStack.isSameItemSameTags(objets.get(n).pile(), item)) return restant(n);
        return 0;
    }

    private static String aPrendre(long total) {
        return Theme.nombre(total) + " à prendre";
    }

    private static int derniere() {
        return (objets.size() - 1) / disposition.parPage();
    }

    /**
     * Liste et place tenues à jour avant le rendu ; la place ne bouge qu'avec le nombre d'objets.
     */
    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != affiche) return;
        objets = proposes(affiche);
        if (objets.isEmpty()) {
            disposition = null;
            PanneauAssistant.poser(affiche, null);
            return;
        }
        // Le choix de Prendre survit au retrait de Tout, qui ne montre que ses propres lots.
        if (!occupe()
                && objets.stream().noneMatch(l -> ItemStack.isSameItemSameTags(l.pile(), choisi)))
            choisi = objets.get(0).pile();
        long cle = (objets.size() * 31L + affiche.width) * 31L + affiche.height;
        long maintenant = System.currentTimeMillis();
        if (disposition == null || cle != cleDisposition || maintenant - placeLe >= 1000) {
            Font font = Minecraft.getInstance().font;
            disposition =
                    DispositionCoffre.calculer(
                            affiche.width,
                            affiche.height,
                            PanneauAssistant.occupee(affiche),
                            objets.size(),
                            PanneauAssistant.libelles(
                                    font,
                                    List.of(
                                            NOM,
                                            aPrendre(999_999),
                                            RETRAIT + Theme.nombre(999_999)),
                                    List.of(PRENDRE),
                                    List.of(TOUT, ARRETER),
                                    false));
            cleDisposition = cle;
            placeLe = maintenant;
        }
        page = Math.min(page, derniere());
        PanneauAssistant.poser(affiche, disposition.cadre());
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        Disposition d = disposition;
        if (d == null || event.getScreen() != affiche || objets.isEmpty()) return;
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        boolean occupe = occupe();
        PanneauAssistant.fond(g, d.cadre());
        long total = restantTotal();
        String detail = occupe ? RETRAIT + Theme.nombre(total) : aPrendre(total);
        PanneauAssistant.titre(g, font, d.titre(), NOM, detail);
        List<Component> infobulle = null;
        if (d.titre().contient(mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    NOM
                                            + " · "
                                            + (occupe ? RETRAIT + total : total + " à prendre")),
                            gris("Objets de l’arbre EMI à retirer du réseau AE2"),
                            gris(
                                    occupe
                                            ? "Un clic hors du panneau arrête aussi le retrait"
                                            : "Garde le curseur vide pendant le retrait"));
        for (int rang = 0; rang < d.parPage(); rang++) {
            int n = page * d.parPage() + rang;
            if (n >= objets.size()) break;
            var lot = objets.get(n);
            var z = d.icone(rang);
            int quantite = restant(n);
            PanneauAssistant.caseIcone(
                    g, z, !occupe && ItemStack.isSameItemSameTags(lot.pile(), choisi));
            ItemStack icone = lot.pile().copy();
            icone.setCount(1);
            g.renderItem(icone, z.x() + 1, z.y() + 1);
            PanneauAssistant.nombre(g, font, z, quantite);
            if (occupe && quantite == 0) PanneauAssistant.etat(g, z, Theme.VERT);
            if (z.contient(mx, my))
                infobulle =
                        occupe
                                ? List.of(
                                        lot.pile().getHoverName(),
                                        gris(quantite == 0 ? "Retiré" : quantite + " restants"))
                                : List.of(
                                        lot.pile().getHoverName(),
                                        gris(quantite + " à prendre"),
                                        Component.literal("Clic : choisir pour Prendre")
                                                .withStyle(
                                                        s -> s.withColor(PanneauAssistant.BLEU)));
        }
        if (d.pages()) {
            boolean avant =
                    PanneauAssistant.bouton(
                            g, font, d.precedent(), "‹", Theme.Genre.NORMAL, page > 0, mx, my);
            boolean apres =
                    PanneauAssistant.bouton(
                            g,
                            font,
                            d.suivant(),
                            "›",
                            Theme.Genre.NORMAL,
                            page < derniere(),
                            mx,
                            my);
            if (avant || apres)
                infobulle =
                        List.of(Component.literal("Page " + (page + 1) + "/" + (derniere() + 1)));
        }
        int choix = quantite(choisi);
        if (PanneauAssistant.bouton(
                g, font, d.prendre(), PRENDRE, Theme.Genre.NORMAL, !occupe && choix > 0, mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Prendre " + choix + " " + choisi.getHoverName().getString()));
        if (occupe) {
            if (PanneauAssistant.bouton(
                    g, font, d.tout(), ARRETER, Theme.Genre.DANGER, true, mx, my))
                infobulle = List.of(Component.literal("Arrêter après l’action en cours"));
        } else if (PanneauAssistant.bouton(
                g, font, d.tout(), TOUT, Theme.Genre.PRINCIPAL, total > 0, mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Prendre les " + total + " objets du réseau pour l’arbre EMI"));
        if (infobulle != null) g.renderComponentTooltip(font, infobulle, mx, my);
    }

    private static Component gris(String texte) {
        return Component.literal(texte).withStyle(s -> s.withColor(PanneauAssistant.GRIS));
    }

    private static boolean surPanneau(Screen screen, double mx, double my) {
        var d = disposition;
        return d != null && screen == affiche && !objets.isEmpty() && d.cadre().contient(mx, my);
    }

    /**
     * L'appui sur le panneau est consommé avec son relâchement : relâché au-dessus du terminal, il
     * y poserait ou jetterait l'objet que le retrait tient au curseur. Tout autre clic dans le
     * terminal arrête le retrait en cours.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        double mx = event.getMouseX(), my = event.getMouseY();
        if (event.isCanceled() || !surPanneau(event.getScreen(), mx, my)) {
            if (event.getScreen() == ecran && occupe()) terminer("Retrait ME interrompu.");
            return;
        }
        RelachementSouris.consommer(event);
        if (event.getButton() != 0) return;
        Disposition d = disposition;
        if (d.tout().contient(mx, my)) {
            PanneauAssistant.son();
            if (occupe()) terminer("Retrait ME arrêté.");
            else lancer(affiche, null);
        } else if (d.pages() && d.precedent().contient(mx, my)) tourner(-1);
        else if (d.pages() && d.suivant().contient(mx, my)) tourner(1);
        else if (occupe()) return;
        else if (d.prendre().contient(mx, my)) {
            PanneauAssistant.son();
            lancer(affiche, choisi.copy());
        } else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int n = page * d.parPage() + rang;
                if (n < objets.size() && d.icone(rang).contient(mx, my)) {
                    choisi = objets.get(n).pile();
                    return;
                }
            }
    }

    private static void tourner(int sens) {
        page = Math.max(0, Math.min(derniere(), page + sens));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (disposition.pages()) tourner(event.getScrollDelta() > 0 ? -1 : 1);
    }

    /**
     * Le clic relit le terminal et l'arbre : il retire ce qui manque à cet instant, jamais le plan
     * de l'image précédente. {@code selection} : un seul objet (Prendre), sinon tout (Tout).
     */
    private static void lancer(AbstractContainerScreen<?> screen, ItemStack selection) {
        var mc = Minecraft.getInstance();
        if (occupe()
                || screen == null
                || mc.screen != screen
                || courant() != screen.getMenu()
                || mc.getConnection() == null
                || fr.assistantia.RetraitCoffre.occupe()
                || EnvoiMachine.occupe()) return;
        if (!screen.getMenu().getCarried().isEmpty()) {
            message("Pose d’abord l’objet tenu par la souris.");
            return;
        }
        try {
            actualiser(true);
            var lots =
                    vue == null
                            ? List.<Lot>of()
                            : vue.retraits().stream()
                                    .filter(
                                            l ->
                                                    selection == null
                                                            || ItemStack.isSameItemSameTags(
                                                                    l.pile(), selection))
                                    .toList();
            if (lots.isEmpty()) {
                message("Rien à prendre dans le réseau ME pour l’arbre EMI.");
                return;
            }
            ecran = screen;
            menu = screen.getMenu();
            arbre = vue.arbre();
            connexion = mc.getConnection();
            retraits = lots;
            objets = lots;
            index = recus = avant = attendus = 0;
            destination = null;
            direct = false;
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
            if (++ticks % 10 == 0 || vue != null && vue.menu() != courant()) actualiser(false);
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
            int gain = destination.getItem().getCount() - depotAvant;
            if (curseur.isEmpty()
                    && ItemStack.isSameItemSameTags(
                            destination.getItem(), retraits.get(index).pile())
                    && (direct ? gain > 0 && gain <= attendus : gain == avant)) {
                if (direct) {
                    reste -= gain;
                    attendus = 0;
                    direct = false;
                }
                recus += gain;
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
        boolean deposerPile = false;
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
            deposerPile = attendus > 1;
            attendus = 0;
        }
        if (avant != curseur.getCount()
                || avant > 0 && !ItemStack.isSameItemSameTags(curseur, lot.pile())) {
            terminer("Retrait ME interrompu : le curseur a changé.");
            return;
        }
        var inventaire = mc.player.getInventory();
        int taillePile = lot.pile().getMaxStackSize();
        int emplacementNatif = -1;
        if (avant == 0 && reste >= taillePile && ACCES.pileDirecte() != null) {
            // Reprendre l'ordre du Maj-clic d'AE2. Il peut choisir une pile déjà entamée.
            emplacementNatif = inventaire.getSlotWithRemainingSpace(lot.pile());
            if (emplacementNatif < 0) emplacementNatif = inventaire.getFreeSlot();
        }
        Slot cible = null;
        Slot cibleDirecte = null;
        int place = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != inventaire || !slot.isActive() || !slot.mayPlace(lot.pile()))
                continue;
            var ici = slot.getItem();
            if (!ici.isEmpty() && !ItemStack.isSameItemSameTags(ici, lot.pile())) continue;
            int libre = slot.getMaxStackSize(lot.pile()) - ici.getCount();
            if (emplacementNatif >= 0
                    && emplacementNatif < 36
                    && slot.getContainerSlot() == emplacementNatif
                    && ici.isEmpty()
                    && libre >= taillePile) cibleDirecte = slot;
            if (libre <= place) continue;
            cible = slot;
            place = libre;
        }
        if (cible == null || place < avant) {
            terminer("Retrait ME arrêté : manque de place dans l’inventaire.");
            return;
        }
        echeance = System.nanoTime() + 3_000_000_000L;
        if (avant > 0 && (reste == 0 || avant == place || deposerPile)) {
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
        if (cibleDirecte != null) {
            destination = cibleDirecte;
            depotAvant = 0;
            direct = true;
            attendus = taillePile;
            ACCES.retirer().invoke(menu, lot.serial(), ACCES.pileDirecte());
            return;
        }
        int volume = volumeRetrait(reste, taillePile, place, avant);
        Object action = ACCES.un();
        if (volume == taillePile && taillePile > 1) action = ACCES.pileEntiere();
        else if (volume > 1 && ACCES.demiPile() != null) action = ACCES.demiPile();
        else volume = 1;
        attendus = volume;
        ACCES.retirer().invoke(menu, lot.serial(), action);
    }

    private static int volumeRetrait(int reste, int taillePile, int place, int curseur) {
        if (curseur > 0) return 1;
        int limite = Math.min(reste, place);
        if (limite >= taillePile) return taillePile;
        // AE2 arrondit la demi-pile vers le haut ; le stock réel peut réduire ce volume.
        int demi = (taillePile + 1) / 2;
        return limite >= demi ? demi : 1;
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
        ticks = 0;
        try { // Relevé refait tout de suite : le panneau passe au reste sans disparaître.
            actualiser(true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            vue = null;
        }
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
