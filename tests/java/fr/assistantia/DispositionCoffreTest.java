package fr.assistantia;

import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.List;

public final class DispositionCoffreTest {
    /**
     * Dans l'écran, hors du coffre, et aucun élément ne déborde du cadre ni n'en recouvre un autre.
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
    }

    public static void main(String[] args) {
        // Coffre simple, place au-dessus : bandeau, comme avant.
        Zone petit = new Zone(152, 70, 176, 166);
        Disposition d = DispositionCoffre.calculer(480, 360, petit, 5);
        verifier("petit coffre", d, 480, 360, petit, 5);
        assert !d.vertical() && d.cadre().bas() <= petit.y() : d;

        // Grand coffre qui touche presque le haut de l'écran : colonne sur le côté.
        Zone grand = new Zone(130, 4, 220, 262);
        for (int objets : new int[] {1, 7, 30}) {
            d = DispositionCoffre.calculer(480, 270, grand, objets);
            verifier("grand coffre " + objets, d, 480, 270, grand, objets);
            assert d.vertical() && !d.chevauche() && d.colonnes() >= 2 : d;
            assert d.cadre().droite() <= grand.x() : "à gauche par défaut " + d;
        }
        assert DispositionCoffre.calculer(480, 270, grand, 30).pages();

        // Côté gauche pris par les upgrades : la colonne passe à droite.
        Zone decale = new Zone(30, 4, 300, 262);
        d = DispositionCoffre.calculer(480, 270, decale, 12);
        verifier("à droite", d, 480, 270, decale, 12);
        assert d.vertical() && d.cadre().x() >= decale.droite() : d;

        // Beaucoup d'objets dans un bandeau : flèches et page de 9 au plus.
        d = DispositionCoffre.calculer(480, 360, petit, 40);
        verifier("bandeau paginé", d, 480, 360, petit, 40);
        assert d.pages() && d.parPage() <= 9 : d;

        // Écran minuscule : aucune place libre, le panneau reste dans l'écran.
        Zone plein = new Zone(2, 2, 316, 236);
        d = DispositionCoffre.calculer(320, 240, plein, 10);
        verifier("écran plein", d, 320, 240, plein, 10);
        assert d.chevauche() : d;

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
