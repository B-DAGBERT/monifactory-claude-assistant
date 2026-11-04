package fr.assistantia;

import java.util.List;

/**
 * Place le panneau de retrait hors de l'interface du coffre : en bandeau au-dessus quand il y a la
 * place, sinon en colonne sur le côté le plus libre. Aucune dépendance à Minecraft.
 */
public final class DispositionCoffre {
    public static final int ICONE = 20, TITRE = 12, BOUTON = 14, BORD = 3, ECART = 4, MARGE = 2;
    static final int FLECHE = 12, PRENDRE = 48, TOUT = 34, COLONNES_BANDEAU = 9;
    static final int COLONNES_COTE = 4, LIGNES_COTE = 6;

    private DispositionCoffre() {}

    public record Zone(int x, int y, int largeur, int hauteur) {
        public int droite() {
            return x + largeur;
        }

        public int bas() {
            return y + hauteur;
        }

        public boolean contient(double px, double py) {
            return px >= x && px < droite() && py >= y && py < bas();
        }

        public boolean croise(Zone autre) {
            return x < autre.droite() && autre.x < droite() && y < autre.bas() && autre.y < bas();
        }

        public Zone union(Zone autre) {
            int gauche = Math.min(x, autre.x), haut = Math.min(y, autre.y);
            return new Zone(
                    gauche,
                    haut,
                    Math.max(droite(), autre.droite()) - gauche,
                    Math.max(bas(), autre.bas()) - haut);
        }
    }

    /**
     * Le cadre et ses éléments. {@code chevauche} : l'écran est trop petit, le panneau passe sur
     * l'interface faute de mieux.
     */
    public record Disposition(
            Zone cadre,
            int colonnes,
            int lignes,
            boolean vertical,
            boolean chevauche,
            Zone precedent,
            Zone suivant,
            Zone prendre,
            Zone tout) {
        public int parPage() {
            return colonnes * lignes;
        }

        public boolean pages() {
            return precedent != null;
        }

        /** Case de l'icône {@code rang} dans la page (16 px d'objet + 1 px de bord). */
        public Zone icone(int rang) {
            return new Zone(
                    cadre.x() + BORD + (rang % colonnes) * ICONE,
                    cadre.y() + BORD + TITRE + (rang / colonnes) * ICONE,
                    18,
                    18);
        }

        public List<Zone> elements() {
            return pages() ? List.of(precedent, suivant, prendre, tout) : List.of(prendre, tout);
        }
    }

    /**
     * @param occupee l'interface du coffre et tout ce qui la prolonge (upgrades, onglets)
     * @param objets nombre d'objets à proposer (au moins une case est prévue)
     */
    public static Disposition calculer(
            int largeurEcran, int hauteurEcran, Zone occupee, int objets) {
        objets = Math.max(1, objets);
        Disposition bandeau = bandeau(largeurEcran, occupee, objets, occupee.y() - ECART);
        if (bandeau.cadre().y() >= MARGE) return bandeau;
        int gauche = occupee.x() - ECART - MARGE;
        int droite = largeurEcran - occupee.droite() - ECART - MARGE;
        boolean aGauche = colonnes(gauche) >= 3 || colonnes(gauche) >= colonnes(droite);
        int place = aGauche ? gauche : droite;
        if (colonnes(place) >= 2) {
            Disposition colonne = colonne(hauteurEcran, occupee, objets, colonnes(place));
            if (colonne != null) {
                Zone c = colonne.cadre();
                int x = aGauche ? occupee.x() - ECART - c.largeur() : occupee.droite() + ECART;
                return deplacer(colonne, x - c.x(), 0);
            }
        }
        // Aucune place libre : en haut de l'écran, comme avant, plutôt que pas du tout.
        Disposition secours =
                bandeau(largeurEcran, occupee, objets, MARGE + BORD + TITRE + ICONE + BORD);
        return new Disposition(
                secours.cadre(),
                secours.colonnes(),
                secours.lignes(),
                false,
                true,
                secours.precedent(),
                secours.suivant(),
                secours.prendre(),
                secours.tout());
    }

    private static int colonnes(int largeur) {
        return Math.min(COLONNES_COTE, Math.max(0, (largeur - 2 * BORD) / ICONE));
    }

    /**
     * Titre, puis une ligne d'icônes suivie des flèches et des deux boutons ; bas à {@code bas}.
     */
    private static Disposition bandeau(int largeurEcran, Zone occupee, int objets, int bas) {
        int hauteur = BORD + TITRE + ICONE + BORD;
        int boutons = ECART + PRENDRE + 2 + TOUT + BORD;
        int place = largeurEcran - 2 * MARGE - BORD - boutons;
        int colonnes = Math.max(1, Math.min(Math.min(objets, COLONNES_BANDEAU), place / ICONE));
        boolean pages = objets > colonnes;
        if (pages) colonnes = Math.max(1, Math.min(colonnes, (place - 2 * FLECHE - 4) / ICONE));
        int largeur = BORD + colonnes * ICONE + boutons + (pages ? 2 * FLECHE + 4 : 0);
        int x = Math.max(MARGE, Math.min(occupee.x(), largeurEcran - largeur - MARGE));
        int y = bas - hauteur;
        int bx = x + BORD + colonnes * ICONE + ECART, by = y + BORD + TITRE + 2;
        Zone precedent = null, suivant = null;
        if (pages) {
            precedent = new Zone(bx, by, FLECHE, BOUTON);
            suivant = new Zone(bx + FLECHE + 2, by, FLECHE, BOUTON);
            bx += 2 * FLECHE + 4;
        }
        return new Disposition(
                new Zone(x, y, largeur, hauteur),
                colonnes,
                1,
                false,
                false,
                precedent,
                suivant,
                new Zone(bx, by, PRENDRE, BOUTON),
                new Zone(bx + PRENDRE + 2, by, TOUT, BOUTON));
    }

    /** Titre, grille d'icônes, flèches si besoin, puis les deux boutons l'un sous l'autre. */
    private static Disposition colonne(int hauteurEcran, Zone occupee, int objets, int colonnes) {
        int fixe = BORD + TITRE + 2 + 2 * (BOUTON + 2) + BORD;
        int lignes = (objets + colonnes - 1) / colonnes;
        int maximum = Math.min(LIGNES_COTE, (hauteurEcran - 2 * MARGE - fixe) / ICONE);
        boolean pages = lignes > maximum;
        if (pages)
            maximum = Math.min(LIGNES_COTE, (hauteurEcran - 2 * MARGE - fixe - BOUTON - 2) / ICONE);
        if (maximum < 1) return null;
        lignes = Math.min(lignes, maximum);
        int largeur = 2 * BORD + colonnes * ICONE;
        int hauteur = fixe + lignes * ICONE + (pages ? BOUTON + 2 : 0);
        int y = Math.max(MARGE, Math.min(occupee.y(), hauteurEcran - hauteur - MARGE));
        int by = y + BORD + TITRE + lignes * ICONE + 2, interieur = largeur - 2 * BORD;
        Zone precedent = null, suivant = null;
        if (pages) {
            precedent = new Zone(BORD, by, FLECHE, BOUTON);
            suivant = new Zone(largeur - BORD - FLECHE, by, FLECHE, BOUTON);
            by += BOUTON + 2;
        }
        return new Disposition(
                new Zone(0, y, largeur, hauteur),
                colonnes,
                lignes,
                true,
                false,
                precedent,
                suivant,
                new Zone(BORD, by, interieur, BOUTON),
                new Zone(BORD, by + BOUTON + 2, interieur, BOUTON));
    }

    private static Disposition deplacer(Disposition d, int dx, int dy) {
        return new Disposition(
                decaler(d.cadre(), dx, dy),
                d.colonnes(),
                d.lignes(),
                d.vertical(),
                d.chevauche(),
                decaler(d.precedent(), dx, dy),
                decaler(d.suivant(), dx, dy),
                decaler(d.prendre(), dx, dy),
                decaler(d.tout(), dx, dy));
    }

    private static Zone decaler(Zone z, int dx, int dy) {
        return z == null ? null : new Zone(z.x() + dx, z.y() + dy, z.largeur(), z.hauteur());
    }
}
