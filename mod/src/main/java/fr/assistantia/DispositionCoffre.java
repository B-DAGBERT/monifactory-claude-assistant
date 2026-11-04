package fr.assistantia;

import java.util.List;

/**
 * Place le panneau de retrait hors de l'interface du coffre, en largeur plutôt qu'en hauteur pour
 * laisser de la place à EMI : un bandeau d'une ligne au-dessus quand il y a la place, sinon un bloc
 * large et bas sur le côté le plus libre. Aucune dépendance à Minecraft.
 */
public final class DispositionCoffre {
    public static final int ICONE = 20, BARRE = 2, BORD = 3, ECART = 4, MARGE = 2;
    static final int FLECHE = 12, BOUTON = 14, PRENDRE = 46, TOUT = 34, TITRE = 52, LIGNE = 12;
    static final int COLONNES_MAX = 9, LIGNES_LARGE = 2, LIGNES_ETROIT = 4;

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

        Zone decaler(int dx, int dy) {
            return new Zone(x + dx, y + dy, largeur, hauteur);
        }
    }

    /**
     * Le cadre et ses éléments. {@code titre} sur deux lignes dans le bandeau, une seule sur le
     * côté. {@code chevauche} : l'écran est trop petit, le panneau passe sur l'interface.
     */
    public record Disposition(
            Zone cadre,
            Zone titre,
            Zone grille,
            int colonnes,
            int lignes,
            boolean cote,
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
                    grille.x() + (rang % colonnes) * ICONE,
                    grille.y() + (rang / colonnes) * ICONE,
                    18,
                    18);
        }

        public List<Zone> elements() {
            return pages()
                    ? List.of(titre, precedent, suivant, prendre, tout)
                    : List.of(titre, prendre, tout);
        }

        Disposition placer(int x, int y, boolean chevauche) {
            int dx = x - cadre.x(), dy = y - cadre.y();
            return new Disposition(
                    cadre.decaler(dx, dy),
                    titre.decaler(dx, dy),
                    grille.decaler(dx, dy),
                    colonnes,
                    lignes,
                    cote,
                    chevauche,
                    precedent == null ? null : precedent.decaler(dx, dy),
                    suivant == null ? null : suivant.decaler(dx, dy),
                    prendre.decaler(dx, dy),
                    tout.decaler(dx, dy));
        }
    }

    /**
     * @param occupee l'interface du coffre et tout ce qui la prolonge (upgrades, onglets)
     * @param objets nombre d'objets à proposer (au moins une case est prévue)
     */
    public static Disposition calculer(
            int largeurEcran, int hauteurEcran, Zone occupee, int objets) {
        objets = Math.max(1, objets);
        Disposition bandeau = bandeau(largeurEcran, objets);
        int x = Math.max(MARGE, Math.min(occupee.x(), largeurEcran - largeur(bandeau) - MARGE));
        int y = occupee.y() - ECART - hauteur(bandeau);
        if (y >= MARGE) return bandeau.placer(x, y, false);
        int gauche = occupee.x() - ECART - MARGE;
        int droite = largeurEcran - occupee.droite() - ECART - MARGE;
        boolean aGauche = colonnes(gauche) >= colonnes(droite);
        int colonnes = colonnes(aGauche ? gauche : droite);
        Disposition bloc = colonnes >= 2 ? bloc(hauteurEcran, objets, colonnes) : null;
        if (bloc != null) {
            int bx = aGauche ? occupee.x() - ECART - largeur(bloc) : occupee.droite() + ECART;
            int by = Math.max(MARGE, Math.min(occupee.y(), hauteurEcran - hauteur(bloc) - MARGE));
            return bloc.placer(bx, by, false);
        }
        // Aucune place libre : en haut de l'écran plutôt que pas du tout.
        return bandeau.placer(x, MARGE, true);
    }

    private static int largeur(Disposition d) {
        return d.cadre().largeur();
    }

    private static int hauteur(Disposition d) {
        return d.cadre().hauteur();
    }

    private static int colonnes(int largeur) {
        return Math.min(COLONNES_MAX, Math.max(0, (largeur - BARRE - 2 * BORD + 2) / ICONE));
    }

    /** Une ligne : titre sur deux lignes, icônes, flèches, Prendre et Tout. Construit en (0, 0). */
    private static Disposition bandeau(int largeurEcran, int objets) {
        int fixe = BARRE + BORD + TITRE + ECART + ECART + PRENDRE + 2 + TOUT + BORD;
        int place = largeurEcran - 2 * MARGE - fixe;
        int colonnes = Math.max(1, Math.min(Math.min(objets, COLONNES_MAX), place / ICONE));
        boolean pages = objets > colonnes;
        if (pages)
            colonnes = Math.max(1, Math.min(colonnes, (place - 2 * FLECHE - 2 - ECART) / ICONE));
        Zone titre = new Zone(BARRE + BORD, BORD, TITRE, 18);
        Zone grille = new Zone(titre.droite() + ECART, BORD, colonnes * ICONE, ICONE);
        int bx = grille.x() + colonnes * ICONE - 2 + ECART, by = BORD + 2;
        Zone precedent = null, suivant = null;
        if (pages) {
            precedent = new Zone(bx, by, FLECHE, BOUTON);
            suivant = new Zone(bx + FLECHE + 2, by, FLECHE, BOUTON);
            bx += 2 * FLECHE + 2 + ECART;
        }
        Zone prendre = new Zone(bx, by, PRENDRE, BOUTON);
        Zone tout = new Zone(prendre.droite() + 2, by, TOUT, BOUTON);
        return new Disposition(
                new Zone(0, 0, tout.droite() + BORD, BORD + 18 + BORD),
                titre,
                grille,
                colonnes,
                1,
                false,
                false,
                precedent,
                suivant,
                prendre,
                tout);
    }

    /**
     * Sur le côté : titre et flèches, deux lignes d'icônes au plus (quatre si le côté est étroit),
     * puis Prendre et Tout côte à côte quand la largeur le permet. Construit en (0, 0).
     */
    private static Disposition bloc(int hauteurEcran, int objets, int colonnes) {
        int interieur = colonnes * ICONE - 2;
        boolean cote = interieur >= PRENDRE + 2 + TOUT;
        int fixe = BORD + LIGNE + 1 + (cote ? BOUTON : 2 * BOUTON + 2) + BORD;
        int maximum =
                Math.min(
                        colonnes >= 4 ? LIGNES_LARGE : LIGNES_ETROIT,
                        (hauteurEcran - 2 * MARGE - fixe) / ICONE);
        if (maximum < 1) return null;
        int lignes = (objets + colonnes - 1) / colonnes;
        boolean pages = lignes > maximum;
        lignes = Math.min(lignes, maximum);
        int gx = BARRE + BORD, fleches = pages ? 2 * FLECHE + 2 : 0;
        Zone titre = new Zone(gx, BORD, interieur - (pages ? fleches + 2 : 0), LIGNE);
        Zone precedent = null, suivant = null;
        if (pages) {
            precedent = new Zone(gx + interieur - fleches, BORD, FLECHE, LIGNE);
            suivant = new Zone(gx + interieur - FLECHE, BORD, FLECHE, LIGNE);
        }
        Zone grille = new Zone(gx, BORD + LIGNE + 1, colonnes * ICONE, lignes * ICONE);
        int by = grille.y() + lignes * ICONE;
        Zone prendre, tout;
        if (cote) {
            prendre = new Zone(gx, by, interieur - TOUT - 2, BOUTON);
            tout = new Zone(gx + interieur - TOUT, by, TOUT, BOUTON);
        } else {
            prendre = new Zone(gx, by, interieur, BOUTON);
            tout = new Zone(gx, by + BOUTON + 2, interieur, BOUTON);
        }
        return new Disposition(
                new Zone(0, 0, gx + interieur + BORD, tout.bas() + BORD),
                titre,
                grille,
                colonnes,
                lignes,
                true,
                false,
                precedent,
                suivant,
                prendre,
                tout);
    }
}
