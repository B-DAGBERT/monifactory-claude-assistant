package fr.assistantia;

import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Libelles;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.List;

public final class DispositionCoffreTest {
    /**
     * Libellés du panneau machine mesurés avec la police vanilla (Machine à vérifier, Envoyer…).
     */
    private static final Libelles MACHINE = new Libelles(92, 50, 101, true);

    /**
     * Dans l'écran, hors du coffre, et aucun élément ne déborde du cadre ni n'en recouvre un autre.
     * Les boutons et flèches ont la hauteur commune du thème.
     */
    private static void verifier(String cas, Disposition d, int l, int h, Zone coffre, int objets) {
        Zone c = d.cadre();
        assert c.x() >= 0 && c.y() >= 0 && c.droite() <= l && c.bas() <= h
                : cas + " hors écran " + d;
        assert d.chevauche() || !c.croise(coffre) : cas + " recouvre le coffre " + d;
        List<Zone> elements = new ArrayList<>(d.elements());
        for (int i = 0; i < Math.min(objets, d.parPage()); i++) elements.add(d.icone(i));
        for (int i = 0; i < elements.size(); i++) {
            Zone e = elements.get(i);
            assert e.x() >= c.x()
                            && e.y() >= c.y()
                            && e.droite() <= c.droite()
                            && e.bas() <= c.bas()
                    : cas + " élément hors du cadre " + e + " " + d;
            for (int j = i + 1; j < elements.size(); j++)
                assert !e.croise(elements.get(j)) : cas + " éléments superposés " + d;
        }
        assert d.pages() == (objets > d.parPage()) : cas + " pages " + d;
        assert d.parPage() >= 1 : cas + " aucune icône " + d;
        for (Zone bouton :
                d.pages()
                        ? List.of(d.prendre(), d.tout(), d.precedent(), d.suivant())
                        : List.of(d.prendre(), d.tout()))
            assert bouton.hauteur() == DispositionCoffre.BOUTON : cas + " hauteur bouton " + d;
        assert d.lignesTitre() >= 2 : cas + " titre sur une ligne " + d;
    }

    /** Dès 320 × 240 : titre et boutons gardent toute la largeur demandée, partout. */
    private static void entiers(String cas, Disposition d, Libelles libelles) {
        assert d.titre().largeur() >= libelles.titre() : cas + " titre rogné " + d;
        assert d.prendre().largeur() >= libelles.prendre() && d.tout().largeur() >= libelles.tout()
                : cas + " bouton rogné " + d;
    }

    /**
     * Mot le plus large du titre des coffres sur une ligne à lui : « Collecte », « prendre », «
     * Retrait… » (police vanilla).
     */
    private static final int MOT_COFFRE = 44;

    /**
     * Titre lisible : toute la largeur demandée, ou trois lignes dans un bloc de coffre plus
     * étroit, où « 64 à prendre » passe à la ligne. Autour d'une interface de 176 pixels, ce bloc
     * garde au moins trois colonnes et chaque mot du titre tient.
     */
    private static void titreLisible(
            String cas, Disposition d, Libelles libelles, boolean standard) {
        if (libelles.entiers() || !d.cote() || d.lignesTitre() == 2) {
            entiers(cas, d, libelles);
            return;
        }
        assert d.lignesTitre() == 3 : cas + " " + d;
        if (standard) {
            assert d.titre().largeur() >= MOT_COFFRE : cas + " mot du titre rogné " + d;
            assert d.prendre().largeur() >= libelles.prendre()
                            && d.tout().largeur() >= libelles.tout()
                    : cas + " bouton rogné " + d;
        }
    }

    public static void main(String[] args) {
        // Régression de la capture : FTB en haut à gauche ne doit pas décaler le panneau.
        Zone tonneau = new Zone(215, 43, 194, 240);
        Zone commandes = new Zone(2, 2, 76, 48);
        Zone onglet = new Zone(411, 48, 22, 25);
        Zone extension = new Zone(435, 48, 28, 50);
        Zone entouré = DispositionCoffre.entourage(tonneau, List.of(commandes, extension, onglet));
        assert entouré.equals(tonneau.union(onglet).union(extension));
        assert DispositionCoffre.entourage(tonneau, List.of(onglet, commandes, extension))
                        .equals(entouré)
                : "Indépendant de l'ordre d'ajout des widgets";
        Disposition panneau = DispositionCoffre.calculer(640, 360, entouré, 1);
        verifier("tonneau et boutons FTB", panneau, 640, 360, entouré, 1);
        assert panneau.cadre().x() == tonneau.x() && panneau.cadre().bas() < tonneau.y();
        assert !panneau.cadre().croise(commandes) : "Le panneau reste près du coffre";
        assert DispositionCoffre.entourage(tonneau, List.of(commandes)).equals(tonneau);

        // Coffre simple, place au-dessus : bandeau, comme avant.
        Zone petit = new Zone(152, 70, 176, 166);
        Disposition d = DispositionCoffre.calculer(480, 360, petit, 5);
        verifier("petit coffre", d, 480, 360, petit, 5);
        assert !d.cote() && d.cadre().bas() <= petit.y() : d;
        assert d.cadre().hauteur() <= 24 : "bandeau d'une seule ligne " + d;
        assert d.lignesTitre() == 2 && d.titre().largeur() >= Libelles.COFFRE.titre()
                : "« 64 à prendre » entier sous « Collecte » " + d;

        // Grand coffre qui touche presque le haut de l'écran : bloc large et bas sur le côté.
        Zone grand = new Zone(130, 4, 220, 262);
        for (int objets : new int[] {1, 7, 30}) {
            d = DispositionCoffre.calculer(480, 270, grand, objets);
            verifier("grand coffre " + objets, d, 480, 270, grand, objets);
            assert d.cote() && !d.chevauche() && d.colonnes() >= 4 && d.lignes() <= 2 : d;
            assert d.cadre().hauteur() <= 80 : "laisse la hauteur à EMI " + d;
            assert d.cadre().droite() <= grand.x() : "à gauche par défaut " + d;
            entiers("grand coffre " + objets, d, Libelles.COFFRE);
            assert d.lignesTitre() == 2 : "titre sur deux lignes dans un bloc large " + d;
        }
        d = DispositionCoffre.calculer(480, 270, grand, 30);
        assert d.pages();
        // Les flèches prennent les deux dernières cases de la grille, sous les icônes.
        assert d.parPage() == d.colonnes() * d.lignes() - 2 : d;
        assert d.grille().contient(d.precedent().x(), d.precedent().y())
                        && d.grille().contient(d.suivant().droite() - 1, d.suivant().bas() - 1)
                : d;

        // Côté gauche pris par les upgrades : le bloc passe à droite.
        Zone decale = new Zone(30, 4, 300, 262);
        d = DispositionCoffre.calculer(480, 270, decale, 12);
        verifier("à droite", d, 480, 270, decale, 12);
        assert d.cote() && d.cadre().x() >= decale.droite() : d;

        // Côté étroit : moins de colonnes, quelques lignes de plus, boutons l'un sous l'autre.
        Zone large = new Zone(56, 4, 368, 262);
        d = DispositionCoffre.calculer(480, 270, large, 12);
        verifier("côté étroit", d, 480, 270, large, 12);
        assert d.cote() && d.colonnes() == 2 && d.lignes() <= 4 : d;
        assert d.lignesTitre() == 3 : "titre trop large pour le bloc : une ligne de plus " + d;
        assert d.tout().y() > d.prendre().y() : "boutons l'un sous l'autre " + d;
        // Libellés entiers exigés : jamais de bloc plus étroit que ses boutons.
        d = DispositionCoffre.calculer(480, 270, large, 12, MACHINE);
        verifier("côté étroit machine", d, 480, 270, large, 12);
        assert d.chevauche() : d;
        entiers("côté étroit machine", d, MACHINE);

        // Beaucoup d'objets dans un bandeau : flèches et page de 9 au plus.
        d = DispositionCoffre.calculer(480, 360, petit, 40);
        verifier("bandeau paginé", d, 480, 360, petit, 40);
        assert d.pages() && d.parPage() <= 9 : d;

        // Écran minuscule : aucune place libre, le panneau reste dans l'écran.
        Zone plein = new Zone(2, 2, 316, 236);
        d = DispositionCoffre.calculer(320, 240, plein, 10);
        verifier("écran plein", d, 320, 240, plein, 10);
        assert d.chevauche() : d;

        // Libellés machine longs : entiers, sur toutes les positions et jusqu'à 320x240.
        for (int l = 320; l <= 640; l += 80)
            for (int h = 240; h <= 400; h += 40)
                for (int objets : new int[] {1, 5, 25})
                    for (Zone coffre :
                            List.of(
                                    new Zone((l - 176) / 2, 60, 176, 166),
                                    new Zone((l - 176) / 2, 4, 176, h - 8),
                                    new Zone(2, 2, l - 4, h - 4))) {
                        d = DispositionCoffre.calculer(l, h, coffre, objets, MACHINE);
                        verifier("machine avec libellé complet", d, l, h, coffre, objets);
                        entiers("En cours de craft et Machine à vérifier lisibles", d, MACHINE);
                        Disposition coffreSeul = DispositionCoffre.calculer(l, h, coffre, objets);
                        assert coffreSeul.equals(
                                                DispositionCoffre.calculer(
                                                        l,
                                                        h,
                                                        coffre,
                                                        objets,
                                                        new Libelles(0, 0, 0, false)))
                                        && coffreSeul.equals(
                                                DispositionCoffre.calculer(
                                                        l, h, coffre, objets, Libelles.COFFRE))
                                : "le panneau des coffres ne rétrécit jamais";
                        verifier("coffre", coffreSeul, l, h, coffre, objets);
                        titreLisible(
                                "« 1,2k à prendre » lisible",
                                coffreSeul,
                                Libelles.COFFRE,
                                coffre.largeur() == 176);
                    }

        // Tailles d'interface courantes : coffre simple, double, grand coffre moddé et machine.
        int[][] ecrans = {{320, 240}, {426, 240}, {480, 270}, {640, 360}};
        for (int[] e : ecrans)
            for (int[] taille : new int[][] {{176, 166}, {176, 222}, {230, 250}, {196, 186}})
                for (int objets : new int[] {1, 6, 9, 40})
                    for (Libelles libelles : List.of(Libelles.COFFRE, MACHINE)) {
                        int l = e[0], h = e[1], lc = Math.min(taille[0], l - 4);
                        int hc = Math.min(taille[1], h - 4);
                        Zone coffre = new Zone((l - lc) / 2, (h - hc) / 2, lc, hc);
                        String cas = l + "x" + h + " " + coffre + " " + objets + " " + libelles;
                        d = DispositionCoffre.calculer(l, h, coffre, objets, libelles);
                        verifier(cas, d, l, h, coffre, objets);
                        // Plus étroit, un bloc de coffre coupe ses boutons par « … » plutôt que
                        // de couvrir le coffre ; son titre passe alors sur trois lignes.
                        titreLisible(cas, d, libelles, lc == 176);
                    }

        // Balayage : jamais sur le coffre tant qu'une place existe.
        for (int l = 200; l <= 640; l += 40)
            for (int h = 160; h <= 400; h += 30)
                for (int lc = 120; lc <= Math.min(l - 4, 340); lc += 55)
                    for (int hc = 80; hc <= h - 4; hc += 45)
                        for (int objets : new int[] {1, 4, 9, 25}) {
                            Zone coffre = new Zone((l - lc) / 2, (h - hc) / 2, lc, hc);
                            verifier(
                                    l + "x" + h + " " + coffre,
                                    DispositionCoffre.calculer(l, h, coffre, objets),
                                    l,
                                    h,
                                    coffre,
                                    objets);
                        }
        System.out.println("DispositionCoffre OK");
    }
}
