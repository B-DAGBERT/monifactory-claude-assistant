package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Bouton Minecraft dessiné avec le {@link Theme} : mêmes couleurs, même hauteur et texte coupé par
 * « … » partout. Un texte coupé sans infobulle propre s'affiche en entier au survol.
 *
 * <p>Il reste un {@link Button} : clics, son, focus clavier, narration et infobulles fonctionnent
 * comme pour les boutons vanilla.
 */
public class BoutonTheme extends Button {
    private Theme.Genre genre = Theme.Genre.NORMAL;
    private boolean choisi;

    /** Texte de l'infobulle posée par {@link #infobulle}, pour ne pas la recréer à l'identique. */
    private String infobulle;

    public BoutonTheme(int x, int y, int largeur, int hauteur, Component texte, OnPress action) {
        super(x, y, largeur, hauteur, texte, action, DEFAULT_NARRATION);
    }

    /** Bouton de hauteur standard, à placer ensuite avec {@link #zone}. */
    public static BoutonTheme creer(String texte, OnPress action) {
        return new BoutonTheme(0, 0, 60, Theme.BOUTON, Component.literal(texte), action);
    }

    /** Position et taille ; la hauteur standard est {@link Theme#BOUTON}. */
    public BoutonTheme zone(int x, int y, int largeur, int hauteur) {
        setX(x);
        setY(y);
        setWidth(largeur);
        this.height = hauteur;
        return this;
    }

    public BoutonTheme zone(int x, int y, int largeur) {
        return zone(x, y, largeur, Theme.BOUTON);
    }

    public BoutonTheme genre(Theme.Genre genre) {
        this.genre = genre;
        return this;
    }

    public Theme.Genre genre() {
        return genre;
    }

    /** Onglet ouvert ou option active : le bouton reste cliquable, souligné en doré. */
    public BoutonTheme choisi(boolean choisi) {
        this.choisi = choisi;
        return this;
    }

    public boolean choisi() {
        return choisi;
    }

    /**
     * Infobulle propre du bouton. Appelée à chaque image, elle ne recrée l'infobulle que si son
     * texte change : Minecraft garde ainsi ses lignes déjà découpées.
     */
    public BoutonTheme infobulle(String texte) {
        String valeur = texte == null ? "" : texte;
        if (valeur.equals(infobulle)) return this;
        super.setTooltip(valeur.isEmpty() ? null : Tooltip.create(Component.literal(valeur)));
        infobulle = valeur;
        return this;
    }

    /** Une infobulle posée directement remplace celle de {@link #infobulle}. */
    @Override
    public void setTooltip(Tooltip tooltip) {
        infobulle = null;
        super.setTooltip(tooltip);
    }

    /** Change le texte du bouton ; sans effet s'il est déjà affiché (appel à chaque image). */
    public BoutonTheme texte(String texte) {
        if (!texte.equals(getMessage().getString())) setMessage(Component.literal(texte));
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float partialTick) {
        Font font = Minecraft.getInstance().font;
        String texte = getMessage().getString();
        Theme.bouton(
                g,
                font,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                texte,
                genre,
                this.active,
                isHoveredOrFocused(),
                choisi);
        if (isHovered() && getTooltip() == null && Theme.coupe(font, texte, getWidth() - 6))
            Theme.infobulle(font, getMessage());
    }
}
