package fr.assistantia;

import fr.assistantia.DispositionCoffre.Libelles;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;

/**
 * Habillage commun des panneaux posés à côté d'une interface (coffres, machines, établi), dessiné
 * avec le {@link Theme}. Les dispositions viennent de DispositionCoffre.
 *
 * <p>Ces panneaux sont dessinés après l'écran : leurs infobulles passent par {@code
 * renderComponentTooltip} à la fin du panneau, jamais par l'infobulle différée du thème, que le
 * rendu suivant recouvrirait.
 */
public final class PanneauAssistant {
    /** Couleur RGB du texte secondaire des infobulles (quantités, aide au clic). */
    public static final int GRIS = Theme.TEXTE_SECONDAIRE & 0xFFFFFF;

    /** Couleur RGB d'une indication d'action dans une infobulle. */
    public static final int BLEU = Theme.BLEU & 0xFFFFFF;

    /** Espace entre le texte d'un bouton de panneau et chacun de ses bords. */
    private static final int MARGE_BOUTON = 4;

    private PanneauAssistant() {}

    /** Fond sombre, contour discret et barre dorée à gauche. */
    public static void fond(GuiGraphics g, Zone c) {
        Theme.panneau(g, c.x(), c.y(), c.largeur(), c.hauteur(), Theme.FOND_PANNEAU);
    }

    /**
     * Largeurs à réserver, mesurées avec la police du jeu : la plus longue ligne de titre et le
     * plus long libellé possible de chaque bouton, pour une disposition stable quand l'état change.
     */
    public static Libelles libelles(
            Font font,
            List<String> titres,
            List<String> gauche,
            List<String> droite,
            boolean entiers) {
        return new Libelles(
                plusLarge(font, titres),
                plusLarge(font, gauche) + 2 * MARGE_BOUTON,
                plusLarge(font, droite) + 2 * MARGE_BOUTON,
                entiers);
    }

    private static int plusLarge(Font font, List<String> textes) {
        int largeur = 0;
        for (String texte : textes) largeur = Math.max(largeur, font.width(texte));
        return largeur;
    }

    /**
     * Nom doré sur la première ligne, détail gris sur les suivantes, avec passage à la ligne entre
     * les mots ; un texte trop long finit par « … ». Sur une seule ligne : « nom · détail ».
     *
     * @return vrai si une partie du texte est coupée : le panneau la montre alors en infobulle
     */
    public static boolean titre(GuiGraphics g, Font font, Zone z, String nom, String detail) {
        int pas = DispositionCoffre.LIGNE, largeur = z.largeur();
        int lignes = Math.max(1, (z.hauteur() + 1) / pas);
        if (lignes == 1) {
            int y = z.y() + (z.hauteur() - 8) / 2;
            String suite = detail.isEmpty() ? "" : " · " + detail;
            boolean coupe = Theme.coupe(font, nom + suite, largeur);
            int place = coupe ? Math.max(largeur / 2, largeur - font.width(suite)) : largeur;
            int l = Theme.texte(g, font, nom, z.x(), y, place, Theme.ACCENT);
            Theme.texte(g, font, suite, z.x() + l, y, largeur - l, Theme.TEXTE_SECONDAIRE);
            return coupe;
        }
        int y = z.y() + Math.max(0, (z.hauteur() - (lignes * pas - 1)) / 2);
        boolean coupe = Theme.coupe(font, nom, largeur);
        Theme.texte(g, font, nom, z.x(), y, largeur, Theme.ACCENT);
        String reste = detail;
        for (int i = 1; i < lignes && !reste.isEmpty(); i++) {
            y += pas;
            String ligne = i == lignes - 1 ? reste : debut(font, reste, largeur);
            if (ligne.isEmpty()) ligne = reste; // un seul mot trop long : coupé ici
            coupe |= Theme.coupe(font, ligne, largeur);
            Theme.texte(g, font, ligne, z.x(), y, largeur, Theme.TEXTE_SECONDAIRE);
            reste = reste.substring(ligne.length()).stripLeading();
        }
        return coupe;
    }

    /** Les premiers mots de {@code texte} qui tiennent dans {@code largeur}, sans couper un mot. */
    static String debut(Font font, String texte, int largeur) {
        if (font.width(texte) <= largeur) return texte;
        int coupure = -1;
        for (int i = texte.indexOf(' '); i > 0; i = texte.indexOf(' ', i + 1)) {
            if (font.width(texte.substring(0, i)) > largeur) break;
            coupure = i;
        }
        return coupure > 0 ? texte.substring(0, coupure) : "";
    }

    /** Case d'une icône d'objet, dorée quand elle est choisie. */
    public static void caseIcone(GuiGraphics g, Zone z, boolean choisie) {
        Theme.caseObjet(g, z.x(), z.y(), z.largeur(), choisie);
    }

    /** Nombre court en bas à droite d'une icône, à la place de la quantité d'une pile. */
    public static void nombre(GuiGraphics g, Font font, Zone z, long n) {
        String texte = Theme.nombre(n);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.drawString(font, texte, z.x() + 18 - font.width(texte), z.y() + 10, Theme.TEXTE, true);
        g.pose().popPose();
    }

    /** Fin d'une case d'icône soulignée par la couleur de son état. */
    public static void etat(GuiGraphics g, Zone z, int couleur) {
        g.fill(z.x(), z.bas() - 2, z.droite(), z.bas(), couleur);
    }

    /**
     * Bouton du thème dessiné dans un panneau : même rendu que {@link BoutonTheme}, libellé coupé
     * par « … » s'il ne tient pas.
     *
     * @return vrai s'il est survolé : le panneau affiche alors son infobulle
     */
    public static boolean bouton(
            GuiGraphics g,
            Font font,
            Zone z,
            String texte,
            Theme.Genre genre,
            boolean actif,
            int mx,
            int my) {
        boolean survol = z.contient(mx, my);
        Theme.bouton(
                g,
                font,
                z.x(),
                z.y(),
                z.largeur(),
                z.hauteur(),
                texte,
                genre,
                actif,
                survol,
                false);
        return survol;
    }

    /** Variante historique : action principale en vert, ordinaire sinon. */
    public static void bouton(
            GuiGraphics g,
            Font font,
            Zone z,
            String texte,
            boolean actif,
            boolean principal,
            int mx,
            int my) {
        bouton(
                g,
                font,
                z,
                texte,
                principal ? Theme.Genre.PRINCIPAL : Theme.Genre.NORMAL,
                actif,
                mx,
                my);
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
