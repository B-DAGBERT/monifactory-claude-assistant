package fr.assistantia;

import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Retrait de l'arbre EMI courant dans un coffre. Le panneau se place hors de l'interface du coffre
 * (voir DispositionCoffre), se réserve sa place auprès d'EMI et se coupe dans les réglages.
 */
public final class AideCoffre {
    private static AbstractContainerScreen<?> ecran;
    private static Zone occupee;
    private static Disposition disposition;
    private static RetraitCoffre.Plan plan = new RetraitCoffre.Plan(List.of(), 0);
    private static List<ItemStack> objets = List.of();
    private static ItemStack choisi = ItemStack.EMPTY;
    private static long actualise;
    private static int page;

    private AideCoffre() {}

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        ecran = null;
        vider();
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !RetraitCoffre.estCoffre(screen.getMenu())) return;
        ecran = screen;
        occupee = occupee(screen);
    }

    /** L'interface, ses cases (upgrades comprises) et les widgets ajoutés autour par son mod. */
    private static Zone occupee(AbstractContainerScreen<?> screen) {
        int gauche = screen.getGuiLeft(), haut = screen.getGuiTop();
        Zone zone = new Zone(gauche, haut, screen.getXSize(), screen.getYSize());
        for (Slot s : screen.getMenu().slots)
            if (s.isActive())
                zone = ajouter(screen, zone, new Zone(gauche + s.x - 1, haut + s.y - 1, 18, 18));
        for (GuiEventListener enfant : screen.children())
            zone = ajouter(screen, zone, zone(enfant));
        return zone;
    }

    /** Ignore ce qui est caché hors de l'écran ou couvre la moitié de l'écran. */
    private static Zone ajouter(Screen screen, Zone zone, Zone morceau) {
        if (morceau == null
                || morceau.largeur() <= 0
                || morceau.hauteur() <= 0
                || morceau.x() < 0
                || morceau.y() < 0
                || morceau.droite() > screen.width
                || morceau.bas() > screen.height
                || morceau.largeur() * morceau.hauteur() > screen.width * screen.height / 2)
            return zone;
        return zone.union(morceau);
    }

    private static Zone zone(GuiEventListener enfant) {
        if (enfant instanceof LayoutElement e)
            return new Zone(e.getX(), e.getY(), e.getWidth(), e.getHeight());
        try { // Onglets de Sophisticated Storage : mêmes accesseurs, sans l'interface vanilla.
            Class<?> type = enfant.getClass();
            return new Zone(
                    (Integer) type.getMethod("getX").invoke(enfant),
                    (Integer) type.getMethod("getY").invoke(enfant),
                    (Integer) type.getMethod("getWidth").invoke(enfant),
                    (Integer) type.getMethod("getHeight").invoke(enfant));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static void vider() {
        plan = new RetraitCoffre.Plan(List.of(), 0);
        objets = List.of();
        choisi = ItemStack.EMPTY;
        disposition = null;
        page = 0;
        actualise = 0;
    }

    /** La place du panneau affiché, pour qu'EMI n'y pose pas ses listes d'objets. */
    public static Zone zoneEmi(Screen screen) {
        return screen == ecran && disposition != null ? disposition.cadre() : null;
    }

    /** Coffre ouvert, réglage actif et arbre EMI en cours ; sinon le panneau disparaît. */
    private static boolean actif(Screen screen) {
        if (screen != ecran || ecran == null) return false;
        if (Pont.retraitCoffres() && Emi.arbreActif()) return true;
        if (disposition != null || !objets.isEmpty()) vider();
        return false;
    }

    private static void actualiser() {
        plan = Emi.retraitArbre(ecran);
        objets = plan.objets();
        if (objets.stream().noneMatch(i -> ItemStack.isSameItemSameTags(i, choisi)))
            choisi = objets.isEmpty() ? ItemStack.EMPTY : objets.get(0);
        disposition =
                objets.isEmpty()
                        ? null
                        : DispositionCoffre.calculer(
                                ecran.width, ecran.height, occupee, objets.size());
        page = disposition == null ? 0 : Math.min(page, derniere());
        actualise = System.currentTimeMillis();
    }

    private static int derniere() {
        return (objets.size() - 1) / disposition.parPage();
    }

    private static void agir(boolean tous) {
        if (RetraitCoffre.occupe() || ecran == null) return;
        // Le clic utilise l'arbre et les slots actuels, jamais le plan mis en cache pour le rendu.
        ItemStack selection = choisi.copy();
        actualiser();
        RetraitCoffre.Plan retrait = tous ? plan : plan.pour(selection);
        if (retrait.total() > 0) RetraitCoffre.lancer(ecran, retrait);
    }

    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (!actif(event.getScreen())) return;
        if (!RetraitCoffre.occupe() && System.currentTimeMillis() - actualise >= 400) actualiser();
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (!actif(event.getScreen()) || disposition == null) return;
        Disposition d = disposition;
        Zone c = d.cadre();
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        boolean occupe = RetraitCoffre.occupe();
        // Même habillage que le panneau du HUD : fond sombre et barre dorée à gauche.
        g.fill(c.x(), c.y(), c.droite(), c.bas(), 0xC80D1013);
        g.fill(c.x(), c.y(), c.x() + DispositionCoffre.BARRE, c.bas(), 0xFFFFC94A);
        titre(g, font, d.titre(), occupe);
        List<Component> infobulle = null;
        for (int rang = 0; rang < d.parPage(); rang++) {
            int n = page * d.parPage() + rang;
            if (n >= objets.size()) break;
            ItemStack item = objets.get(n);
            Zone z = d.icone(rang);
            boolean selection = ItemStack.isSameItemSameTags(item, choisi);
            g.fill(z.x(), z.y(), z.droite(), z.bas(), selection ? 0x90FFC94A : 0x30FFFFFF);
            ItemStack icone = item.copy();
            icone.setCount(1);
            int quantite = plan.pour(item).total();
            g.renderItem(icone, z.x() + 1, z.y() + 1);
            g.renderItemDecorations(font, icone, z.x() + 1, z.y() + 1, nombre(quantite));
            if (z.contient(mx, my))
                infobulle =
                        List.of(
                                item.getHoverName(),
                                Component.literal(quantite + " à prendre · Clic : choisir")
                                        .withStyle(s -> s.withColor(Hud.GRIS)));
        }
        if (d.pages()) {
            bouton(g, font, d.precedent(), "‹", !occupe && page > 0, false, mx, my);
            bouton(g, font, d.suivant(), "›", !occupe && page < derniere(), false, mx, my);
            if (d.precedent().contient(mx, my) || d.suivant().contient(mx, my))
                infobulle =
                        List.of(Component.literal("Page " + (page + 1) + "/" + (derniere() + 1)));
        }
        int choix = plan.pour(choisi).total(), tout = plan.total();
        bouton(g, font, d.prendre(), "Prendre", !occupe && choix > 0, false, mx, my);
        bouton(g, font, d.tout(), "Tout", !occupe && tout > 0, true, mx, my);
        if (d.prendre().contient(mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Prendre " + choix + " " + choisi.getHoverName().getString()));
        else if (d.tout().contient(mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Prendre les " + tout + " objets de l’arbre présents ici"));
        if (infobulle != null) g.renderComponentTooltip(font, infobulle, mx, my);
    }

    /** Titre doré et quantité en gris : sur deux lignes dans le bandeau, une sur le côté. */
    private static void titre(GuiGraphics g, Font font, Zone z, boolean occupe) {
        String nom = "Arbre EMI", detail = occupe ? "Transfert…" : plan.total() + " à prendre";
        if (z.hauteur() >= 18) {
            g.drawString(
                    font, font.plainSubstrByWidth(nom, z.largeur()), z.x(), z.y(), Hud.OR, false);
            g.drawString(
                    font,
                    font.plainSubstrByWidth(detail, z.largeur()),
                    z.x(),
                    z.y() + 10,
                    Hud.GRIS,
                    false);
            return;
        }
        int y = z.y() + (z.hauteur() - 8) / 2;
        String debut = font.plainSubstrByWidth(nom, z.largeur());
        g.drawString(font, debut, z.x(), y, Hud.OR, false);
        int reste = z.largeur() - font.width(debut);
        String suite = font.plainSubstrByWidth(" · " + detail, Math.max(0, reste));
        g.drawString(font, suite, z.x() + font.width(debut), y, Hud.GRIS, false);
    }

    private static String nombre(int n) {
        return n < 1000 ? String.valueOf(n) : n / 1000 + "k";
    }

    /** Boutons des réglages : vert pour l'action principale, gris sinon, contour au survol. */
    private static void bouton(
            GuiGraphics g,
            Font font,
            Zone z,
            String texte,
            boolean actif,
            boolean principal,
            int mx,
            int my) {
        boolean survol = actif && z.contient(mx, my);
        int fond =
                !actif
                        ? 0xFF2A2A2A
                        : principal
                                ? (survol ? 0xFF3FA845 : 0xFF2E8B36)
                                : (survol ? 0xFF5A5A5A : 0xFF3C3C3C);
        g.fill(z.x(), z.y(), z.droite(), z.bas(), fond);
        g.renderOutline(
                z.x(),
                z.y(),
                z.largeur(),
                z.hauteur(),
                survol ? 0xFFFFFFFF : actif ? 0x60FFFFFF : 0x30FFFFFF);
        String t = font.plainSubstrByWidth(texte, z.largeur() - 4);
        g.drawString(
                font,
                t,
                z.x() + (z.largeur() - font.width(t)) / 2,
                z.y() + (z.hauteur() - 8) / 2 + 1,
                actif ? 0xFFFFFF : 0x808080,
                false);
    }

    private static void tourner(int sens) {
        page = Math.max(0, Math.min(derniere(), page + sens));
    }

    private static boolean surPanneau(Screen screen, double mx, double my) {
        return actif(screen) && disposition != null && disposition.cadre().contient(mx, my);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        double mx = event.getMouseX(), my = event.getMouseY();
        if (!surPanneau(event.getScreen(), mx, my)) return;
        event.setCanceled(true); // jamais de clic qui traverse vers le coffre ou EMI
        if (event.getButton() != 0 || RetraitCoffre.occupe()) return;
        Disposition d = disposition;
        if (d.prendre().contient(mx, my)) {
            son();
            agir(false);
        } else if (d.tout().contient(mx, my)) {
            son();
            agir(true);
        } else if (d.pages() && d.precedent().contient(mx, my)) tourner(-1);
        else if (d.pages() && d.suivant().contient(mx, my)) tourner(1);
        else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int n = page * d.parPage() + rang;
                if (n < objets.size() && d.icone(rang).contient(mx, my)) {
                    choisi = objets.get(n);
                    return;
                }
            }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void relache(ScreenEvent.MouseButtonReleased.Pre event) {
        if (surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY()))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (disposition.pages() && !RetraitCoffre.occupe())
            tourner(event.getScrollDelta() > 0 ? -1 : 1);
    }

    private static void son() {
        Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
