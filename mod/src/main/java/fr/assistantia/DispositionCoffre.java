package fr.assistantia;

import java.util.ArrayList;
import java.util.List;

/**
 * Place le panneau posé à côté d'une interface (coffre, machine, établi) hors de cette interface,
 * en largeur plutôt qu'en hauteur pour laisser de la place à EMI : un bandeau d'une ligne au-dessus
 * quand il y a la place, sinon un bloc large et bas sur le côté le plus libre. Aucune dépendance à
 * Minecraft.
 *
 * <p>Les boutons ont la hauteur commune du thème ({@link #BOUTON}, égale à {@code Theme.BOUTON}).
 * Le titre occupe deux lignes, trois dans un bloc plus étroit que lui, avec la largeur demandée par
 * les {@link Libelles} : dès 320 × 240, les libellés fixes du panneau tiennent en entier.
 */
public final class DispositionCoffre {
    public static final int ICONE = 20, BARRE = 2, BORD = 3, ECART = 4, MARGE = 2;

    /** Hauteur des boutons (Theme.BOUTON) et pas des lignes du titre (Font.lineHeight). */
    public static final int BOUTON = 16, LIGNE = 9;

    static final int FLECHE = 12, PRENDRE = 50, TOUT = 34, TITRE = 76, TITRE_MIN = 40;
    static final int COLONNES_MAX = 9, LIGNES_LARGE = 2, LIGNES_ETROIT = 4;

    /** Deux flèches de page et l'écart qui les suit, dans le bandeau. */
    private static final int FLECHES = 2 * FLECHE + 2 + ECART;

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
     * Largeurs que le panneau réserve à ses textes, mesurées avec la police du jeu : chaque ligne
     * du titre, le bouton de gauche et celui de droite. Elles ne descendent jamais sous celles des
     * coffres. {@code entiers} : aucun bloc latéral plus étroit que ses boutons ; sinon un côté
     * étroit, aux libellés coupés par « … », reste préférable à l'interface recouverte.
     */
    public record Libelles(int titre, int prendre, int tout, boolean entiers) {
        public static final Libelles COFFRE = new Libelles(TITRE, PRENDRE, TOUT, false);

        public Libelles {
            titre = Math.max(TITRE, titre);
            prendre = Math.max(PRENDRE, prendre);
            tout = Math.max(TOUT, tout);
        }
    }

    /**
     * Le cadre et ses éléments. {@code titre} s'écrit sur {@code titre.hauteur() / LIGNE} lignes,
     * arrondi au-dessus. {@code cote} : bloc latéral, dont les flèches de page occupent les deux
     * dernières cases de la grille. {@code chevauche} : l'écran est trop petit, le panneau passe
     * sur l'interface.
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
        /** Icônes par page : toute la grille, moins les cases des flèches d'un bloc paginé. */
        public int parPage() {
            return colonnes * lignes - (cote && pages() ? 2 : 0);
        }

        public boolean pages() {
            return precedent != null;
        }

        /** Nombre de lignes de texte du titre. */
        public int lignesTitre() {
            return (titre.hauteur() + 1) / LIGNE;
        }

        /** Case de l'icône {@code rang} dans la page (16 px d'objet + 1 px de bord). */
        public Zone icone(int rang) {
            return cellule(grille, colonnes, rang);
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

    private static Zone cellule(Zone grille, int colonnes, int rang) {
        return new Zone(
                grille.x() + (rang % colonnes) * ICONE,
                grille.y() + (rang / colonnes) * ICONE,
                18,
                18);
    }

    /** Inclut les prolongements reliés au conteneur, jamais les commandes globales isolées. */
    public static Zone entourage(Zone conteneur, List<Zone> widgets) {
        List<Zone> relies = new ArrayList<>();
        relies.add(conteneur);
        List<Zone> restants = new ArrayList<>(widgets);
        Zone resultat = conteneur;
        boolean ajout;
        do {
            ajout = false;
            for (int i = restants.size() - 1; i >= 0; i--) {
                Zone candidat = restants.get(i);
                if (relies.stream().noneMatch(z -> voisins(z, candidat))) continue;
                relies.add(candidat);
                resultat = resultat.union(candidat);
                restants.remove(i);
                ajout = true;
            }
        } while (ajout);
        return resultat;
    }

    private static boolean voisins(Zone a, Zone b) {
        return a.x() <= b.droite() + ECART
                && b.x() <= a.droite() + ECART
                && a.y() <= b.bas() + ECART
                && b.y() <= a.bas() + ECART;
    }

    /**
     * Panneau des coffres : « Collecte », Prendre et Tout.
     *
     * @param occupee l'interface du coffre et tout ce qui la prolonge (upgrades, onglets)
     * @param objets nombre d'objets à proposer (au moins une case est prévue)
     */
    public static Disposition calculer(
            int largeurEcran, int hauteurEcran, Zone occupee, int objets) {
        return calculer(largeurEcran, hauteurEcran, occupee, objets, Libelles.COFFRE);
    }

    /**
     * Boutons plus larges que ceux des coffres : ils restent entiers dans toutes les dispositions.
     */
    public static Disposition calculer(
            int largeurEcran,
            int hauteurEcran,
            Zone occupee,
            int objets,
            int largeurPrendre,
            int largeurTout) {
        var libelles = new Libelles(TITRE, largeurPrendre, largeurTout, false);
        if (!libelles.equals(Libelles.COFFRE))
            libelles = new Libelles(TITRE, largeurPrendre, largeurTout, true);
        return calculer(largeurEcran, hauteurEcran, occupee, objets, libelles);
    }

    public static Disposition calculer(
            int largeurEcran, int hauteurEcran, Zone occupee, int objets, Libelles libelles) {
        objets = Math.max(1, objets);
        Disposition bandeau = bandeau(largeurEcran, objets, libelles);
        int x = Math.max(MARGE, Math.min(occupee.x(), largeurEcran - largeur(bandeau) - MARGE));
        int y = occupee.y() - ECART - hauteur(bandeau);
        if (y >= MARGE) return bandeau.placer(x, y, false);
        int gauche = occupee.x() - ECART - MARGE;
        int droite = largeurEcran - occupee.droite() - ECART - MARGE;
        boolean aGauche = colonnes(gauche) >= colonnes(droite);
        int colonnes = colonnes(aGauche ? gauche : droite);
        int minimum = libelles.entiers() ? Math.max(libelles.prendre(), libelles.tout()) : 0;
        Disposition bloc =
                colonnes >= 2 && colonnes * ICONE - 2 >= minimum
                        ? bloc(hauteurEcran, objets, colonnes, libelles)
                        : null;
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

    /**
     * Une ligne : titre sur deux lignes, icônes, flèches, puis les deux boutons. Le titre garde la
     * largeur demandée ; seul un écran plus étroit que 320 pixels la réduit pour garder une icône.
     * Construit en (0, 0).
     */
    private static Disposition bandeau(int largeurEcran, int objets, Libelles l) {
        int fixe = BARRE + BORD + ECART + ECART + l.prendre() + 2 + l.tout() + BORD;
        int place = largeurEcran - 2 * MARGE - fixe; // titre, icônes et flèches éventuelles
        int largeurTitre = l.titre();
        int colonnes = Math.min(Math.min(objets, COLONNES_MAX), libre(place - largeurTitre));
        boolean pages = objets > colonnes;
        if (pages) colonnes = Math.min(colonnes, libre(place - largeurTitre - FLECHES));
        if (colonnes < 1) {
            colonnes = 1;
            pages = objets > 1;
            largeurTitre = Math.max(TITRE_MIN, place - ICONE - (pages ? FLECHES : 0));
        }
        Zone titre = new Zone(BARRE + BORD, BORD, largeurTitre, 2 * LIGNE);
        Zone grille = new Zone(titre.droite() + ECART, BORD, colonnes * ICONE, ICONE);
        int bx = grille.x() + colonnes * ICONE - 2 + ECART, by = BORD + (18 - BOUTON) / 2;
        Zone precedent = null, suivant = null;
        if (pages) {
            precedent = new Zone(bx, by, FLECHE, BOUTON);
            suivant = new Zone(bx + FLECHE + 2, by, FLECHE, BOUTON);
            bx += FLECHES;
        }
        Zone prendre = new Zone(bx, by, l.prendre(), BOUTON);
        Zone tout = new Zone(prendre.droite() + 2, by, l.tout(), BOUTON);
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

    private static int libre(int largeur) {
        return Math.max(0, largeur) / ICONE;
    }

    /**
     * Sur le côté : titre, deux lignes d'icônes au plus (quatre si le côté est étroit) dont les
     * deux dernières cases deviennent les flèches quand il y a plusieurs pages, puis les boutons
     * côte à côte quand la largeur le permet. Construit en (0, 0).
     */
    private static Disposition bloc(int hauteurEcran, int objets, int colonnes, Libelles l) {
        int interieur = colonnes * ICONE - 2;
        boolean cote = interieur >= l.prendre() + 2 + l.tout();
        int hauteurTitre = (interieur >= l.titre() ? 2 : 3) * LIGNE - 1;
        int fixe = BORD + hauteurTitre + 1 + (cote ? BOUTON : 2 * BOUTON + 2) + BORD;
        int maximum =
                Math.min(
                        colonnes >= 4 ? LIGNES_LARGE : LIGNES_ETROIT,
                        (hauteurEcran - 2 * MARGE - fixe) / ICONE);
        if (maximum < 1) return null;
        int lignes = (objets + colonnes - 1) / colonnes;
        boolean pages = lignes > maximum;
        // Au moins une icône à côté des deux flèches.
        if (pages && colonnes * maximum < 3) return null;
        lignes = Math.min(lignes, maximum);
        int gx = BARRE + BORD;
        Zone titre = new Zone(gx, BORD, interieur, hauteurTitre);
        Zone grille = new Zone(gx, titre.bas() + 1, colonnes * ICONE, lignes * ICONE);
        Zone precedent = null, suivant = null;
        if (pages) {
            Zone a = cellule(grille, colonnes, colonnes * lignes - 2);
            Zone b = cellule(grille, colonnes, colonnes * lignes - 1);
            precedent = new Zone(a.x() + 1, a.y() + 1, BOUTON, BOUTON);
            suivant = new Zone(b.x() + 1, b.y() + 1, BOUTON, BOUTON);
        }
        int by = grille.y() + lignes * ICONE;
        Zone prendre, tout;
        if (cote) {
            prendre = new Zone(gx, by, interieur - l.tout() - 2, BOUTON);
            tout = new Zone(gx + interieur - l.tout(), by, l.tout(), BOUTON);
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
