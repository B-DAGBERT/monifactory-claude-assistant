package fr.assistantia;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Champ de saisie Minecraft avec le contour du {@link Theme} : doré quand il a le focus, plus clair
 * au survol. Le reste (curseur, sélection, copier-coller) est celui d'EditBox.
 */
public class ChampTheme extends EditBox {
    /** EditBox ne publie pas son état de contour en 1.20.1 : on le suit ici. */
    private boolean bordure = true;

    public ChampTheme(Font font, int x, int y, int largeur, int hauteur, Component nom) {
        super(font, x, y, largeur, hauteur, nom);
        setTextColor(Theme.TEXTE & 0xFFFFFF);
        setTextColorUneditable(Theme.TEXTE_SECONDAIRE & 0xFFFFFF);
    }

    /** Champ de hauteur standard. */
    public ChampTheme(Font font, int x, int y, int largeur, Component nom) {
        this(font, x, y, largeur, Theme.BOUTON, nom);
    }

    /**
     * Champ dont le contour occupe exactement la zone donnée, haute de {@link Theme#BOUTON} : il
     * s'aligne sur les boutons de la même rangée (EditBox dessine son contour autour du champ).
     */
    public static ChampTheme dans(Font font, int x, int y, int largeur, Component nom) {
        return new ChampTheme(font, x + 1, y + 1, Math.max(1, largeur - 2), Theme.BOUTON - 2, nom);
    }

    /** Replace le champ pour que son contour occupe la zone donnée, comme {@link #dans}. */
    public ChampTheme zone(int x, int y, int largeur) {
        setX(x + 1);
        setY(y + 1);
        setWidth(Math.max(1, largeur - 2));
        return this;
    }

    @Override
    public void setBordered(boolean bordure) {
        super.setBordered(bordure);
        this.bordure = bordure;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float partialTick) {
        super.renderWidget(g, mx, my, partialTick);
        if (!isVisible() || !bordure) return;
        // EditBox dessine son contour sur un pixel autour du champ : on le recolore.
        g.renderOutline(
                getX() - 1,
                getY() - 1,
                getWidth() + 2,
                getHeight() + 2,
                isFocused() ? Theme.ACCENT : isHovered() ? Theme.BORDURE_SURVOL : Theme.BORDURE);
    }
}
