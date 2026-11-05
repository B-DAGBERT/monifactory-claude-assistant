package fr.assistantia;

import fr.assistantia.DispositionCoffre.Zone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;

/**
 * Habillage commun des panneaux posés à côté d'une interface (coffres, machines) : celui du HUD,
 * avec les boutons des réglages. Les dispositions viennent de DispositionCoffre.
 */
public final class PanneauAssistant {
    /** Texte secondaire (quantités, infobulles), visible hors du paquet. */
    public static final int GRIS = Hud.GRIS;

    private PanneauAssistant() {}

    /** Fond sombre et barre dorée à gauche, comme le panneau du HUD. */
    public static void fond(GuiGraphics g, Zone c) {
        g.fill(c.x(), c.y(), c.droite(), c.bas(), 0xC80D1013);
        g.fill(c.x(), c.y(), c.x() + DispositionCoffre.BARRE, c.bas(), 0xFFFFC94A);
    }

    /** Nom doré et détail gris : sur deux lignes dans un bandeau, à la suite sur le côté. */
    public static void titre(GuiGraphics g, Font font, Zone z, String nom, String detail) {
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
        String suite = font.plainSubstrByWidth(" · " + detail, z.largeur());
        String debut = font.plainSubstrByWidth(nom, Math.max(0, z.largeur() - font.width(suite)));
        g.drawString(font, debut, z.x(), y, Hud.OR, false);
        g.drawString(font, suite, z.x() + font.width(debut), y, Hud.GRIS, false);
    }

    /** Case d'une icône, dorée quand elle est choisie. */
    public static void caseIcone(GuiGraphics g, Zone z, boolean choisie) {
        g.fill(z.x(), z.y(), z.droite(), z.bas(), choisie ? 0x90FFC94A : 0x30FFFFFF);
    }

    /** Nombre en bas à droite d'une icône, à la place de la quantité d'une pile. */
    public static void nombre(GuiGraphics g, Font font, Zone z, int n) {
        String texte = n < 1000 ? String.valueOf(n) : n / 1000 + "k";
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.drawString(font, texte, z.x() + 18 - font.width(texte), z.y() + 10, 0xFFFFFF, true);
        g.pose().popPose();
    }

    /**
     * Boutons des réglages : vert pour l'action principale, gris sinon, contour blanc au survol.
     */
    public static void bouton(
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

    public static void son() {
        Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** L'interface, ses cases (upgrades comprises) et les widgets ajoutés autour par son mod. */
    public static Zone occupee(AbstractContainerScreen<?> screen) {
        int gauche = screen.getGuiLeft(), haut = screen.getGuiTop();
        Zone zone = new Zone(gauche, haut, screen.getXSize(), screen.getYSize());
        List<Zone> widgets = new ArrayList<>();
        for (Slot s : screen.getMenu().slots)
            if (s.isActive())
                ajouter(screen, widgets, new Zone(gauche + s.x - 1, haut + s.y - 1, 18, 18));
        for (GuiEventListener enfant : screen.children()) ajouter(screen, widgets, zone(enfant));
        Zone externe = DispositionCoffre.entourage(zone, widgets);
        // Les titres et onglets LDLib peuvent être déportés sans toucher le rectangle vanilla.
        // Ils appartiennent bien au conteneur ; les commandes FTB isolées restent exclues.
        return externe.union(
                EmpriseModulaire.occupee(screen.getMenu(), zone, screen.width, screen.height));
    }

    /** Ignore les widgets cachés hors écran et les grandes racines transparentes. */
    private static void ajouter(Screen screen, List<Zone> widgets, Zone morceau) {
        if (morceau == null
                || morceau.largeur() <= 0
                || morceau.hauteur() <= 0
                || morceau.x() < 0
                || morceau.y() < 0
                || morceau.droite() > screen.width
                || morceau.bas() > screen.height
                || (long) morceau.largeur() * morceau.hauteur()
                        > (long) screen.width * screen.height / 2) return;
        widgets.add(morceau);
    }

    private static Zone zone(GuiEventListener enfant) {
        if (enfant instanceof AbstractWidget widget && !widget.visible) return null;
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
}
