package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import fr.assistantia.ArbreEmi;
import fr.assistantia.BoutonTheme;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.RechercheEmi;
import fr.assistantia.Theme;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Un seul interrupteur près de Recipe Tree. Aucune mutation de l'arbre ni des favoris EMI. Les
 * boutons suivent le thème commun : scan actif en doré ({@link BoutonTheme#choisi}).
 */
public final class CoffresArbreEmi {
    private static final String ID = "assistantia:coffres_arbre";
    private static final Map<Screen, BoutonTheme> boutons = new WeakHashMap<>();
    private static final Map<Screen, BoutonTheme> suivis = new WeakHashMap<>();
    private static final Map<Screen, BoutonTheme> menus = new WeakHashMap<>();
    private static Field boutonArbre, barreRecherche;
    private static boolean charge;
    private static java.lang.reflect.Method desactive;
    private static boolean erreurSignalee;
    private static long dernierClic;
    private static int ticks;
    private static InstantaneArbreEmi dernierArbre;

    private static void appliquer(InstantaneArbreEmi vue) {
        RechercheEmi.recalculer(
                new MemoireCollecte(vue.collecte(), vue.lots()),
                vue::accepte,
                dernierArbre != null && vue.cible().equals(dernierArbre.cible()));
        dernierArbre = vue;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!RechercheEmi.selectionnee(ID)) {
            dernierArbre = null;
            return;
        }
        if (++ticks % 10 != 0 || EnvoiMachine.occupe() || fr.assistantia.RetraitCoffre.occupe())
            return;
        try {
            Object tree = ArbreEmi.actif();
            if (tree == null) return;
            var vue = InstantaneArbreEmi.lire(tree);
            if (!vue.memeCollecte(dernierArbre)) appliquer(vue);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RechercheEmi.arreter(); // Ne pas proposer les ingrédients de l'ancien arbre.
            signaler(e);
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(
                        Component.literal("Arbre illisible · scan arrêté. Rouvre Recipe Tree."),
                        true);
        }
    }

    private static void charger() throws ReflectiveOperationException {
        if (charge) return;
        Class<?> manager = Class.forName("dev.emi.emi.screen.EmiScreenManager");
        boutonArbre = manager.getField("tree");
        barreRecherche = manager.getField("search");
        desactive = manager.getMethod("isDisabled");
        charge = true;
    }

    public static Object arbreActif() {
        try {
            charger();
            return ArbreEmi.actif();
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            return null;
        }
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!(screen instanceof AbstractContainerScreen<?>)
                && !name.equals("dev.emi.emi.screen.RecipeScreen")
                && !name.equals("dev.emi.emi.screen.BoMScreen")) return;
        var scan = outil("Coffres OFF", b -> basculer());
        var suivi =
                outil("Suivi", b -> ouvrirSuivi(screen))
                        .infobulle("Suivre les fabrications, recalculer ou annuler un lot");
        var menu =
                outil("Aide…", b -> ouvrirDepuisCoffre(screen, new Menu(screen)))
                        .infobulle(
                                "Outils EMI : recherche dans les coffres et suivi des fabrications");
        boutons.put(screen, scan);
        suivis.put(screen, suivi);
        menus.put(screen, menu);
        for (var bouton : List.of(scan, suivi, menu)) event.addListener(bouton);
    }

    /** Bouton de la barre : thème commun, infobulle après un court survol, caché jusqu'au rendu. */
    private static BoutonTheme outil(String texte, Button.OnPress action) {
        var bouton = BoutonTheme.creer(texte, action);
        bouton.setTooltipDelay(150);
        bouton.visible = false;
        return bouton;
    }

    private static List<Map<Screen, BoutonTheme>> cartes() {
        return List.of(boutons, suivis, menus);
    }

    private static void ouvrirSuivi(Screen parent) {
        ouvrirDepuisCoffre(parent, new SuiviEmiEcran(parent));
    }

    private static void ouvrirDepuisCoffre(Screen parent, Screen suivant) {
        if (!fr.assistantia.StockProduction.ouvrirSuivi(parent, suivant))
            Minecraft.getInstance().setScreen(suivant);
    }

    private static void masquer(Screen screen) {
        for (var carte : cartes()) {
            var b = carte.get(screen);
            if (b != null) b.visible = false;
        }
    }

    private static void reglerScan(BoutonTheme bouton) throws ReflectiveOperationException {
        boolean selection = RechercheEmi.selectionnee(ID);
        boolean attend = selection && !fr.assistantia.InventaireJoueur.synchronise();
        bouton.active = selection || ArbreEmi.consulte() != null;
        bouton.texte(attend ? "Coffres…" : selection ? "Coffres ON" : "Coffres OFF")
                .choisi(selection)
                .infobulle(
                        attend
                                ? "Lecture du backpack en cours · cliquer pour arrêter le scan"
                                : selection
                                        ? "Scan actif · cliquer pour arrêter la recherche dans les coffres"
                                        : bouton.active
                                                ? "Chercher les ingrédients de l’arbre EMI dans les coffres"
                                                : "Choisis d’abord une recette dans Recipe Tree");
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        BoutonTheme scan = boutons.get(screen);
        if (scan == null) return;
        try {
            charger();
            AbstractWidget tree = (AbstractWidget) boutonArbre.get(null);
            AbstractWidget search = (AbstractWidget) barreRecherche.get(null);
            if (tree == null || !tree.visible || (boolean) desactive.invoke(null)) {
                masquer(screen);
                return;
            }
            // Rangée d'EMI : les boutons du thème, moins hauts, sont centrés sur Recipe Tree.
            int x = tree.getX() + tree.getWidth() + Theme.ECART, haut = tree.getY();
            int y = haut + (tree.getHeight() - fr.assistantia.BarreOutilsEmi.HAUTEUR) / 2;
            int place = screen.width - x - 2;
            if (search != null
                    && search.visible
                    && search.getX() >= x
                    && search.getY() < haut + tree.getHeight()
                    && search.getY() + search.getHeight() > haut)
                place = Math.min(place, search.getX() - x - Theme.ECART);
            var font = Minecraft.getInstance().font;
            var disposition =
                    fr.assistantia.BarreOutilsEmi.calculer(
                            x,
                            y,
                            place,
                            Math.max(largeur(font, "Coffres OFF"), largeur(font, "Coffres ON")),
                            largeur(font, "Suivi"),
                            largeur(font, "Aide…"));
            BoutonTheme suivi = suivis.get(screen);
            BoutonTheme menu = menus.get(screen);
            placer(scan, disposition.scan());
            placer(suivi, disposition.suivi());
            placer(menu, disposition.menu());
            reglerScan(scan);
            suivi.active = ArbreEmi.actif() != null || MachinesEmi.dernierTermine();
            // Accès compact : doré tant que le scan des coffres, rangé dans le menu, est actif.
            menu.texte(menu.getWidth() >= largeur(font, "Aide…") ? "Aide…" : "…")
                    .choisi(RechercheEmi.selectionnee(ID));
        } catch (ReflectiveOperationException | RuntimeException e) {
            masquer(screen);
            signaler(e);
        }
    }

    private static int largeur(net.minecraft.client.gui.Font font, String texte) {
        return Theme.largeurBouton(font, texte, 200);
    }

    private static void placer(BoutonTheme bouton, fr.assistantia.BarreOutilsEmi.Zone zone) {
        if (bouton == null) return;
        bouton.visible = zone != null;
        if (zone != null) bouton.zone(zone.x(), zone.y(), zone.largeur());
    }

    /** Le bouton visible de la barre sous la souris, grisé compris. */
    private static BoutonTheme sous(Screen screen, double x, double y) {
        for (var carte : cartes()) {
            BoutonTheme b = carte.get(screen);
            if (b != null
                    && b.visible
                    && x >= b.getX()
                    && x < b.getX() + b.getWidth()
                    && y >= b.getY()
                    && y < b.getY() + b.getHeight()) return b;
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        BoutonTheme bouton = sous(event.getScreen(), event.getMouseX(), event.getMouseY());
        if (bouton == null) return;
        // Ni EMI ni le conteneur ne prennent ce clic, même sur un bouton grisé, ni son relâchement
        // :
        // le conteneur jetterait au sol l'objet tenu au curseur.
        fr.assistantia.RelachementSouris.consommer(event);
        if (bouton.active)
            bouton.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
        // Le relâchement est retenu par RelachementSouris, pas selon sa position : un glisser
        // commencé dans le conteneur (répartition, barre du créatif, widget GT) doit s'y terminer.
    }

    public static fr.assistantia.DispositionCoffre.Zone zoneSuivi(Screen screen) {
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, droite = 0, bas = 0;
        for (var carte : cartes()) {
            var b = carte.get(screen);
            if (b == null || !b.visible) continue;
            x = Math.min(x, b.getX());
            y = Math.min(y, b.getY());
            droite = Math.max(droite, b.getX() + b.getWidth());
            bas = Math.max(bas, b.getY() + b.getHeight());
        }
        return x == Integer.MAX_VALUE
                ? null
                : new fr.assistantia.DispositionCoffre.Zone(x, y, droite - x, bas - y);
    }

    /** Repli unique : aucune rangée supplémentaire ne recouvre les contrôles des autres mods. */
    private static final class Menu extends Screen {
        private static final String AIDE = "Coffres et fabrications";
        private final Screen parent;
        private final Object connexionOuverture, mondeOuverture;
        private BoutonTheme scan, suivi;
        private Theme.Fenetre fenetre;

        Menu(Screen parent) {
            super(Component.literal("Outils EMI"));
            this.parent = parent;
            var mc = Minecraft.getInstance();
            connexionOuverture = mc.getConnection();
            mondeOuverture = mc.level;
        }

        @Override
        protected void init() {
            int pas = Theme.BOUTON + Theme.ECART;
            int hauteur =
                    Theme.TITRE
                            + Theme.LIGNE
                            + Theme.ECART
                            + 2 * pas
                            + 2 * Theme.MARGE
                            + Theme.BOUTON;
            fenetre = Theme.Fenetre.centree(width, height, 300, hauteur);
            int x = fenetre.contenuX(), largeur = fenetre.contenuLargeur();
            int y = fenetre.contenuY() + Theme.LIGNE + Theme.ECART;
            scan =
                    addRenderableWidget(
                            BoutonTheme.creer(
                                            "",
                                            b -> {
                                                basculer();
                                                onClose();
                                            })
                                    .zone(x, y, largeur));
            suivi =
                    addRenderableWidget(
                            BoutonTheme.creer("Suivi des fabrications", b -> ouvrirSuivi(parent))
                                    .zone(x, y + pas, largeur));
            addRenderableWidget(
                    BoutonTheme.creer("Retour à EMI", b -> onClose())
                            .zone(x, fenetre.basBoutons(), largeur));
            tick();
        }

        @Override
        public void tick() {
            try {
                reglerScan(scan);
                suivi.active = ArbreEmi.actif() != null || MachinesEmi.dernierTermine();
            } catch (ReflectiveOperationException | RuntimeException e) {
                scan.active = suivi.active = false;
                scan.texte("Coffres indisponibles")
                        .choisi(false)
                        .infobulle("Arbre EMI illisible · retourne dans Recipe Tree");
                signaler(e);
            }
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            renderBackground(g);
            fenetre.dessiner(g, font, title.getString(), null, mx, my);
            Theme.texte(
                    g,
                    font,
                    AIDE,
                    fenetre.contenuX(),
                    fenetre.contenuY(),
                    fenetre.contenuLargeur(),
                    Theme.TEXTE_SECONDAIRE,
                    mx,
                    my);
            super.render(g, mx, my, delta);
        }

        @Override
        public void onClose() {
            if (minecraft == null) return;
            boolean contexte =
                    connexionOuverture == minecraft.getConnection()
                            && mondeOuverture == minecraft.level;
            boolean menu =
                    !(parent instanceof AbstractContainerScreen<?> conteneur)
                            || minecraft.player != null
                                    && minecraft.player.containerMenu == conteneur.getMenu();
            minecraft.setScreen(contexte && menu ? parent : null);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    public static boolean recalculer() {
        if (!RechercheEmi.actif()) return true;
        try {
            Object tree = ArbreEmi.actif();
            if (tree == null) return false;
            InstantaneArbreEmi.invalider();
            appliquer(InstantaneArbreEmi.lire(tree));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            return false;
        }
    }

    private static void basculer() {
        long now = System.nanoTime();
        if (now - dernierClic < 650_000_000L) return;
        dernierClic = now;
        if (RechercheEmi.selectionnee(ID)) {
            RechercheEmi.arreter();
            return;
        }
        try {
            charger();
            Object tree = ArbreEmi.consulte();
            if (tree == null) return;
            var vue = InstantaneArbreEmi.lire(tree);
            if (ArbreEmi.actif() == null) RechercheEmi.arreter();
            if (!ArbreEmi.activer()) return;
            RechercheEmi.basculer(
                    ID, new MemoireCollecte(vue.collecte(), vue.lots()), vue::accepte);
            dernierArbre = vue;
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(
                        Component.literal(
                                "Arbre EMI indisponible. Rouvre Recipe Tree puis réessaie."),
                        true);
        }
    }

    /** Les quantités réservées par la recherche active, sans reconstruire l'arbre. */
    public static fr.assistantia.RetraitCoffre.Plan planTransfert(
            AbstractContainerScreen<?> screen) {
        return RechercheEmi.retrait(screen.getMenu());
    }

    private static void signaler(Exception e) {
        if (!erreurSignalee) {
            LogUtils.getLogger().warn("Assistant : recherche de l'arbre EMI indisponible", e);
            erreurSignalee = true;
        }
    }
}
