package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Style commun de toutes les interfaces du mod : couleurs, tailles et dessins de base.
 *
 * <p>Chaque écran, panneau ou bouton passe par ces constantes et ces méthodes plutôt que par des
 * valeurs écrites à la main : les interfaces restent ainsi identiques d'un écran à l'autre. Les
 * textes trop longs sont coupés par « … » et retrouvent leur version complète au survol.
 */
public final class Theme {
    private Theme() {}

    // ---------- Couleurs (ARGB) ----------

    /** Fond des grands panneaux, légèrement transparent sur le monde. */
    public static final int FOND = 0xE60D1013;

    /** Fond des panneaux posés sur une interface du jeu (coffres, machines, EMI). */
    public static final int FOND_PANNEAU = 0xEE0D1013;

    /** Ligne, carte ou case dans un panneau. */
    public static final int CARTE = 0xFF161B22;

    public static final int CARTE_SURVOL = 0xFF1F2630;

    /** Contour discret des cartes, boutons et champs. */
    public static final int BORDURE = 0xFF2A323E;

    public static final int BORDURE_SURVOL = 0xFF4A5566;

    /** Couleur de l'assistant : barre des panneaux, titres, sélection et survol. */
    public static final int ACCENT = 0xFFFFC94A;

    /** Fond doré très léger d'un élément choisi. */
    public static final int ACCENT_FOND = 0x33FFC94A;

    public static final int TEXTE = 0xFFF2F2F2;
    public static final int TEXTE_SECONDAIRE = 0xFF9AA3AE;
    public static final int TEXTE_INACTIF = 0xFF5F6773;

    public static final int VERT = 0xFF6BE36B;
    public static final int JAUNE = 0xFFFFE45C;
    public static final int ROUGE = 0xFFFF7A6B;
    public static final int BLEU = 0xFF8FC7FF;
    public static final int CYAN = 0xFF5FE3E3;

    /** Fonds des boutons selon leur genre. */
    private static final int BOUTON_FOND = 0xFF232A35,
            BOUTON_SURVOL = 0xFF2D3644,
            BOUTON_INACTIF = 0xFF181D25,
            PRINCIPAL_FOND = 0xFF2B6E33,
            PRINCIPAL_SURVOL = 0xFF358A3F,
            DANGER_FOND = 0xFF5C2626,
            DANGER_SURVOL = 0xFF7A3030;

    // ---------- Tailles ----------

    /** Hauteur unique des boutons, onglets et champs de saisie. */
    public static final int BOUTON = 16;

    /** Interligne du texte. */
    public static final int LIGNE = 11;

    /** Marge intérieure d'un panneau. */
    public static final int MARGE = 8;

    /** Marge entre un grand panneau et le bord de l'écran. */
    public static final int MARGE_ECRAN = 10;

    /** Espace entre deux éléments voisins. */
    public static final int ECART = 4;

    /** Largeur de la barre dorée à gauche des panneaux (même valeur que DispositionCoffre). */
    public static final int BARRE = DispositionCoffre.BARRE;

    /** Largeur de la barre de défilement. */
    public static final int DEFILEMENT = 4;

    /** Hauteur du bandeau de titre d'une fenêtre. */
    public static final int TITRE = 24;

    public static final String POINTS = "…";

    /** Rôle d'un bouton, qui fixe sa couleur. */
    public enum Genre {
        /** Action ordinaire. */
        NORMAL,
        /** Action principale de l'écran (Envoyer, Scanner, Appliquer…). */
        PRINCIPAL,
        /** Action qui efface ou annule. */
        DANGER,
        /** Onglet : texte seul, souligné en doré quand il est ouvert. */
        ONGLET
    }

    // ---------- Panneaux ----------

    /** Grand panneau : fond sombre, contour discret et barre dorée à gauche. */
    public static void panneau(GuiGraphics g, int x, int y, int largeur, int hauteur) {
        panneau(g, x, y, largeur, hauteur, FOND);
    }

    public static void panneau(GuiGraphics g, int x, int y, int largeur, int hauteur, int fond) {
        if (largeur <= 0 || hauteur <= 0) return;
        g.fill(x, y, x + largeur, y + hauteur, fond);
        g.renderOutline(x, y, largeur, hauteur, BORDURE);
        g.fill(x, y, x + BARRE, y + hauteur, ACCENT);
    }

    /** Carte ou ligne de liste, éclaircie au survol. */
    public static void carte(
            GuiGraphics g, int x, int y, int largeur, int hauteur, boolean survol) {
        if (largeur <= 0 || hauteur <= 0) return;
        g.fill(x, y, x + largeur, y + hauteur, survol ? CARTE_SURVOL : CARTE);
        g.renderOutline(x, y, largeur, hauteur, survol ? BORDURE_SURVOL : BORDURE);
    }

    /** Carte d'un élément choisi : fond et contour dorés. */
    public static void carteChoisie(GuiGraphics g, int x, int y, int largeur, int hauteur) {
        if (largeur <= 0 || hauteur <= 0) return;
        g.fill(x, y, x + largeur, y + hauteur, CARTE);
        g.fill(x, y, x + largeur, y + hauteur, ACCENT_FOND);
        g.renderOutline(x, y, largeur, hauteur, ACCENT);
    }

    /** Fine ligne horizontale entre deux sections. */
    public static void separateur(GuiGraphics g, int x, int y, int largeur) {
        if (largeur > 0) g.fill(x, y, x + largeur, y + 1, BORDURE);
    }

    /** Case d'une icône d'objet (18 × 18 pour une icône de 16), dorée quand elle est choisie. */
    public static void caseObjet(GuiGraphics g, int x, int y, int taille, boolean choisie) {
        g.fill(x, y, x + taille, y + taille, choisie ? 0x80FFC94A : 0x30FFFFFF);
        if (choisie) g.renderOutline(x, y, taille, taille, ACCENT);
    }

    /** Barre d'avancement : fond de carte, remplissage coloré selon la part faite (0 à 1). */
    public static void progression(
            GuiGraphics g, int x, int y, int largeur, int hauteur, double part, int couleur) {
        if (largeur <= 2 || hauteur <= 2) return;
        g.fill(x, y, x + largeur, y + hauteur, CARTE);
        g.renderOutline(x, y, largeur, hauteur, BORDURE);
        int plein = (int) Math.round((largeur - 2) * Math.max(0, Math.min(1, part)));
        if (plein > 0) g.fill(x + 1, y + 1, x + 1 + plein, y + hauteur - 1, couleur);
    }

    // ---------- Boutons ----------

    /** Dessine un bouton du thème. Le texte trop long est coupé par « … ». */
    public static void bouton(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int largeur,
            int hauteur,
            String texte,
            Genre genre,
            boolean actif,
            boolean survol,
            boolean choisi) {
        if (largeur <= 0 || hauteur <= 0) return;
        boolean chaud = actif && survol;
        int couleurTexte = actif ? TEXTE : TEXTE_INACTIF;
        if (genre == Genre.ONGLET) {
            if (choisi) {
                g.fill(x, y, x + largeur, y + hauteur, ACCENT_FOND);
                g.fill(x, y + hauteur - 2, x + largeur, y + hauteur, ACCENT);
            } else if (chaud) {
                g.fill(x, y, x + largeur, y + hauteur, CARTE_SURVOL);
                g.fill(x, y + hauteur - 1, x + largeur, y + hauteur, BORDURE_SURVOL);
            } else {
                g.fill(x, y + hauteur - 1, x + largeur, y + hauteur, BORDURE);
            }
            couleurTexte = !actif ? TEXTE_INACTIF : choisi || chaud ? TEXTE : TEXTE_SECONDAIRE;
        } else {
            int fond, contour;
            if (!actif) {
                fond = BOUTON_INACTIF;
                contour = BORDURE;
            } else if (genre == Genre.PRINCIPAL) {
                fond = chaud ? PRINCIPAL_SURVOL : PRINCIPAL_FOND;
                contour = chaud ? ACCENT : 0xFF3F9A48;
            } else if (genre == Genre.DANGER) {
                fond = chaud ? DANGER_SURVOL : DANGER_FOND;
                contour = chaud ? ACCENT : 0xFF8A3A3A;
            } else {
                fond = chaud ? BOUTON_SURVOL : BOUTON_FOND;
                contour = chaud ? ACCENT : BORDURE_SURVOL;
            }
            g.fill(x, y, x + largeur, y + hauteur, fond);
            if (choisi && actif) g.fill(x, y, x + largeur, y + hauteur, ACCENT_FOND);
            g.renderOutline(x, y, largeur, hauteur, choisi && actif ? ACCENT : contour);
        }
        String visible = couper(font, texte, largeur - 6);
        g.drawString(
                font,
                visible,
                x + (largeur - font.width(visible) + 1) / 2,
                y + (hauteur - 9) / 2 + 1,
                couleurTexte,
                false);
    }

    /** Largeur d'un bouton qui affiche tout son texte, bornée à {@code maximum}. */
    public static int largeurBouton(Font font, String texte, int maximum) {
        return Math.max(BOUTON, Math.min(maximum, font.width(texte) + 14));
    }

    // ---------- Défilement ----------

    /** Barre de défilement commune : rail discret, curseur doré au survol ou pendant le glisser. */
    public static void barre(GuiGraphics g, BarreDefilement barre, int mx, int my) {
        if (!barre.visible()) return;
        int x = barre.x(), y = barre.y();
        g.fill(x, y, x + DEFILEMENT, y + barre.hauteur(), 0x26FFFFFF);
        boolean survol =
                mx >= x - 3
                        && mx <= x + DEFILEMENT + 3
                        && my >= barre.curseur()
                        && my <= barre.curseur() + barre.taille();
        g.fill(
                x,
                barre.curseur(),
                x + DEFILEMENT,
                barre.curseur() + barre.taille(),
                barre.glisse() || survol ? ACCENT : BORDURE_SURVOL);
    }

    // ---------- Texte ----------

    /** Le texte s'il tient dans {@code largeur}, sinon son début suivi de « … ». */
    public static String couper(Font font, String texte, int largeur) {
        if (texte == null || texte.isEmpty() || largeur <= 0) return "";
        if (font.width(texte) <= largeur) return texte;
        int reste = largeur - font.width(POINTS);
        if (reste <= 0) return "";
        return font.plainSubstrByWidth(texte, reste).stripTrailing() + POINTS;
    }

    /** Vrai quand le texte ne tient pas dans la largeur et sera coupé. */
    public static boolean coupe(Font font, String texte, int largeur) {
        return texte != null && !texte.isEmpty() && font.width(texte) > largeur;
    }

    /**
     * Écrit une ligne coupée par « … » si besoin. Au survol d'un texte coupé, son contenu complet
     * apparaît en infobulle.
     *
     * @return la largeur réellement écrite
     */
    public static int texte(
            GuiGraphics g,
            Font font,
            String texte,
            int x,
            int y,
            int largeur,
            int couleur,
            int mx,
            int my) {
        String visible = couper(font, texte, largeur);
        if (visible.isEmpty()) return 0;
        g.drawString(font, visible, x, y, couleur, false);
        int l = font.width(visible);
        if (!visible.equals(texte) && mx >= x && mx < x + l && my >= y - 1 && my < y + 9)
            infobulle(font, Component.literal(texte));
        return l;
    }

    /** Comme {@link #texte} sans infobulle, pour un texte dont la souris n'est pas connue. */
    public static int texte(
            GuiGraphics g, Font font, String texte, int x, int y, int largeur, int couleur) {
        return texte(g, font, texte, x, y, largeur, couleur, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /** Texte centré sur {@code largeur}, coupé par « … » si besoin. */
    public static void texteCentre(
            GuiGraphics g, Font font, String texte, int x, int y, int largeur, int couleur) {
        String visible = couper(font, texte, largeur);
        g.drawString(font, visible, x + (largeur - font.width(visible)) / 2, y, couleur, false);
    }

    /** Titre doré d'un panneau ou d'une section. */
    public static int titre(GuiGraphics g, Font font, String texte, int x, int y, int largeur) {
        return texte(g, font, texte, x, y, largeur, ACCENT);
    }

    /**
     * Paragraphe coupé en lignes de {@code largeur}. Les lignes au-delà de {@code maxLignes} sont
     * remplacées par « … » sur la dernière.
     *
     * @return l'ordonnée sous le paragraphe
     */
    public static int paragraphe(
            GuiGraphics g,
            Font font,
            String texte,
            int x,
            int y,
            int largeur,
            int couleur,
            int maxLignes) {
        if (texte == null || texte.isEmpty() || largeur <= 0 || maxLignes <= 0) return y;
        var lignes = font.split(Component.literal(texte), largeur);
        int n = Math.min(lignes.size(), maxLignes);
        for (int i = 0; i < n; i++) {
            if (i == n - 1 && lignes.size() > n) {
                StringBuilder fin = new StringBuilder();
                lignes.get(i)
                        .accept(
                                (index, style, point) -> {
                                    fin.appendCodePoint(point);
                                    return true;
                                });
                g.drawString(font, couper(font, fin + POINTS, largeur), x, y, couleur, false);
            } else {
                g.drawString(font, lignes.get(i), x, y, couleur, false);
            }
            y += LIGNE;
        }
        return y;
    }

    /** Nombre de lignes qu'occupe un paragraphe sur {@code largeur}. */
    public static int lignes(Font font, String texte, int largeur) {
        if (texte == null || texte.isEmpty() || largeur <= 0) return 0;
        return font.split(Component.literal(texte), largeur).size();
    }

    /** Affiche une infobulle sur l'écran courant, après le rendu de ses widgets. */
    public static void infobulle(Font font, Component texte) {
        var ecran = Minecraft.getInstance().screen;
        if (ecran == null || texte == null) return;
        ecran.setTooltipForNextRenderPass(
                font.split(texte, Math.max(120, Math.min(300, ecran.width - 24))));
    }

    /** Nombre court : 999, 1,2k, 15k, 3,4M. */
    public static String nombre(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10_000) return String.format(java.util.Locale.FRANCE, "%.1fk", n / 1000.0);
        if (n < 1_000_000) return n / 1000 + "k";
        return String.format(java.util.Locale.FRANCE, "%.1fM", n / 1_000_000.0);
    }

    // ---------- Fenêtre standard ----------

    /**
     * Cadre d'un écran secondaire : centré, largeur bornée, titre doré dans un bandeau. Le contenu
     * se place entre {@link #contenuX()} et {@link #contenuDroite()}, sous {@link #contenuY()}.
     */
    public record Fenetre(int x, int y, int largeur, int hauteur) {
        /** Fenêtre centrée d'au plus {@code largeurMax} pixels, avec la marge d'écran autour. */
        public static Fenetre centree(int ecranLargeur, int ecranHauteur, int largeurMax) {
            int l = Math.max(120, Math.min(largeurMax, ecranLargeur - 2 * MARGE_ECRAN));
            int h = Math.max(80, ecranHauteur - 2 * MARGE_ECRAN);
            return new Fenetre(
                    Math.max(0, (ecranLargeur - l) / 2), Math.max(0, (ecranHauteur - h) / 2), l, h);
        }

        public int contenuX() {
            return x + BARRE + MARGE;
        }

        public int contenuY() {
            return y + TITRE;
        }

        public int contenuLargeur() {
            return largeur - BARRE - 2 * MARGE;
        }

        public int contenuDroite() {
            return x + largeur - MARGE;
        }

        public int bas() {
            return y + hauteur;
        }

        /** Ordonnée d'une rangée de boutons collée en bas de la fenêtre. */
        public int basBoutons() {
            return y + hauteur - MARGE - BOUTON;
        }

        /** Fond, titre doré et sous-titre gris facultatif, à droite du titre. */
        public void dessiner(
                GuiGraphics g, Font font, String titre, String sousTitre, int mx, int my) {
            panneau(g, x, y, largeur, hauteur);
            int tx = contenuX(), ty = y + (TITRE - 9) / 2 + 1, place = contenuLargeur();
            int l = Theme.texte(g, font, titre, tx, ty, place, ACCENT, mx, my);
            if (sousTitre != null && !sousTitre.isEmpty() && place - l > 30)
                Theme.texte(
                        g,
                        font,
                        "· " + sousTitre,
                        tx + l + 6,
                        ty,
                        place - l - 6,
                        TEXTE_SECONDAIRE,
                        mx,
                        my);
            separateur(g, x + BARRE, y + TITRE - 4, largeur - BARRE);
        }
    }
}
