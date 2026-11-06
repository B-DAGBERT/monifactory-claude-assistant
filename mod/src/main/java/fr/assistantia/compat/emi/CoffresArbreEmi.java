package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import fr.assistantia.ArbreEmi;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.RechercheEmi;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Un seul interrupteur près de Recipe Tree. Aucune mutation de l'arbre ni des favoris EMI. */
public final class CoffresArbreEmi {
    private static final String ID = "assistantia:coffres_arbre";
    private static final Map<Screen, Button> boutons = new WeakHashMap<>();
    private static final Map<Screen, Button> suivis = new WeakHashMap<>();
    private static final Map<Screen, Button> sacs = new WeakHashMap<>();
    private static final Map<Screen, Button> menus = new WeakHashMap<>();
    private static long lectureSacDate;
    private static int typesSac;
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
        var scan = new BoutonOutilEmi(Component.literal("Coffres OFF"), b -> basculer());
        var suivi =
                new BoutonOutilEmi(
                        Component.literal("Suivi"),
                        b -> Minecraft.getInstance().setScreen(new SuiviEmiEcran(screen)));
        var sac =
                new BoutonOutilEmi(
                        Component.literal("Sac"),
                        b ->
                                Minecraft.getInstance()
                                        .setScreen(new fr.assistantia.EcranSac(screen)));
        var menu =
                new BoutonOutilEmi(
                        Component.literal("Aide…"),
                        b -> Minecraft.getInstance().setScreen(new Menu(screen)));
        boutons.put(screen, scan);
        suivis.put(screen, suivi);
        sacs.put(screen, sac);
        menus.put(screen, menu);
        for (var bouton : List.of(scan, suivi, sac, menu)) {
            bouton.visible = false;
            event.addListener(bouton);
        }
        suivi.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Suivre les fabrications, recalculer ou annuler un lot")));
        menu.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Outils EMI : recherche dans les coffres, suivi et backpack")));
    }

    private static List<Map<Screen, Button>> cartes() {
        return List.of(boutons, suivis, sacs, menus);
    }

    private static void masquer(Screen screen) {
        for (var carte : cartes()) {
            var b = carte.get(screen);
            if (b != null) b.visible = false;
        }
    }

    private static void actualiserSac() {
        long maintenant = System.currentTimeMillis();
        if (!fr.assistantia.InventaireJoueur.disponible()) {
            typesSac = 0;
            lectureSacDate = 0;
        } else if (maintenant - lectureSacDate > 500) {
            typesSac =
                    fr.assistantia.ListeSac.lire(fr.assistantia.InventaireJoueur.sacs(), "").size();
            lectureSacDate = maintenant;
        }
    }

    private static String nomSac() {
        return fr.assistantia.InventaireJoueur.disponible()
                ? "Sac " + (typesSac > 99 ? "99+" : typesSac)
                : "Sac…";
    }

    private static void reglerScan(Button bouton) throws ReflectiveOperationException {
        boolean selection = RechercheEmi.selectionnee(ID);
        boolean attend = selection && !fr.assistantia.InventaireJoueur.synchronise();
        bouton.active = selection || ArbreEmi.consulte() != null;
        bouton.setMessage(
                Component.literal(attend ? "Coffres…" : selection ? "Coffres ON" : "Coffres OFF"));
        bouton.setTooltip(
                Tooltip.create(
                        Component.literal(
                                attend
                                        ? "Lecture du backpack en cours · cliquer pour arrêter le scan"
                                        : selection
                                                ? "Scan actif · cliquer pour arrêter la recherche dans les coffres"
                                                : bouton.active
                                                        ? "Chercher les ingrédients de l’arbre EMI dans les coffres"
                                                        : "Choisis d’abord une recette dans Recipe Tree")));
        if (bouton instanceof BoutonOutilEmi outil) outil.accent(selection ? 0x55D88C : 0);
    }

    private static void reglerSac(Button bouton) {
        bouton.setMessage(Component.literal(nomSac()));
        bouton.setTooltip(
                Tooltip.create(
                        Component.literal(
                                fr.assistantia.InventaireJoueur.disponible()
                                        ? typesSac
                                                + " types d’objets dans tes backpacks · déjà sur toi et comptés dans EMI"
                                        : "Lecture du backpack en cours · ouvrir la liste des objets portés")));
        if (bouton instanceof BoutonOutilEmi outil) outil.accent(typesSac > 0 ? 0x65C7F3 : 0);
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        Button scan = boutons.get(screen);
        if (scan == null) return;
        try {
            charger();
            AbstractWidget tree = (AbstractWidget) boutonArbre.get(null);
            AbstractWidget search = (AbstractWidget) barreRecherche.get(null);
            if (tree == null || !tree.visible || (boolean) desactive.invoke(null)) {
                masquer(screen);
                return;
            }
            int x = tree.getX() + tree.getWidth() + 4, y = tree.getY();
            int place = screen.width - x - 2;
            if (search != null
                    && search.visible
                    && search.getX() >= x
                    && search.getY() < y + 20
                    && search.getY() + search.getHeight() > y)
                place = Math.min(place, search.getX() - x - 4);
            var font = Minecraft.getInstance().font;
            var disposition =
                    fr.assistantia.BarreOutilsEmi.calculer(
                            x,
                            y,
                            place,
                            Math.max(font.width("Coffres OFF"), font.width("Coffres ON")) + 12,
                            font.width("Suivi") + 12,
                            Math.max(font.width("Sac 99+"), font.width("Sac…")) + 12,
                            font.width("Aide…") + 12);
            Button suivi = suivis.get(screen), sac = sacs.get(screen), menu = menus.get(screen);
            placer(scan, disposition.scan());
            placer(suivi, disposition.suivi());
            placer(sac, disposition.sac());
            placer(menu, disposition.menu());
            actualiserSac();
            reglerScan(scan);
            reglerSac(sac);
            suivi.active = ArbreEmi.actif() != null || MachinesEmi.dernierTermine();
            menu.setMessage(
                    Component.literal(menu.getWidth() >= font.width("Aide…") + 12 ? "Aide…" : "…"));
            if (menu instanceof BoutonOutilEmi outil)
                outil.accent(RechercheEmi.selectionnee(ID) ? 0x55D88C : 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            masquer(screen);
            signaler(e);
        }
    }

    private static void placer(Button bouton, fr.assistantia.BarreOutilsEmi.Zone zone) {
        if (bouton == null) return;
        bouton.visible = zone != null;
        if (zone == null) return;
        bouton.setX(zone.x());
        bouton.setY(zone.y());
        bouton.setWidth(zone.largeur());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        for (var carte : cartes()) {
            Button bouton = carte.get(event.getScreen());
            if (bouton == null
                    || !bouton.visible
                    || !bouton.isMouseOver(event.getMouseX(), event.getMouseY())) continue;
            if (bouton.active)
                bouton.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
            // EMI ne doit pas également prendre ce clic pour un objet placé derrière.
            event.setCanceled(true);
            return;
        }
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
        private final Screen parent;
        private Button scan, suivi, sac;

        Menu(Screen parent) {
            super(Component.literal("Outils EMI"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int largeur = Math.min(280, width - 24), x = (width - largeur) / 2;
            scan =
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(""),
                                            b -> {
                                                basculer();
                                                onClose();
                                            })
                                    .bounds(x, 58, largeur, 20)
                                    .build());
            suivi =
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal("Suivi des fabrications"),
                                            b -> minecraft.setScreen(new SuiviEmiEcran(parent)))
                                    .bounds(x, 86, largeur, 20)
                                    .build());
            sac =
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(""),
                                            b ->
                                                    minecraft.setScreen(
                                                            new fr.assistantia.EcranSac(parent)))
                                    .bounds(x, 114, largeur, 20)
                                    .build());
            addRenderableWidget(
                    Button.builder(Component.literal("Retour à EMI"), b -> onClose())
                            .bounds(x, height - 28, largeur, 20)
                            .build());
            tick();
        }

        @Override
        public void tick() {
            actualiserSac();
            reglerSac(sac);
            try {
                reglerScan(scan);
                suivi.active = ArbreEmi.actif() != null || MachinesEmi.dernierTermine();
            } catch (ReflectiveOperationException | RuntimeException e) {
                scan.active = suivi.active = false;
                scan.setMessage(Component.literal("Coffres indisponibles"));
                scan.setTooltip(
                        Tooltip.create(
                                Component.literal(
                                        "Arbre EMI illisible · retourne dans Recipe Tree")));
                signaler(e);
            }
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            renderBackground(g);
            g.drawCenteredString(font, title, width / 2, 14, 0xFFFFC94A);
            g.drawCenteredString(
                    font, "Coffres, fabrications et backpack", width / 2, 33, 0xAAAAAA);
            super.render(g, mx, my, delta);
        }

        @Override
        public void onClose() {
            if (minecraft != null) minecraft.setScreen(parent);
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
