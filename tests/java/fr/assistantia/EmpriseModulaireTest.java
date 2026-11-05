package fr.assistantia;

import fr.assistantia.DispositionCoffre.Zone;
import java.util.List;

public final class EmpriseModulaireTest {
    public record Widget(
            int getPositionX,
            int getPositionY,
            int getSizeWidth,
            int getSizeHeight,
            boolean isVisible) {}

    public record UI(List<Object> getFlatVisibleWidgetCollection) {}

    public record Menu(UI getModularUI) {}

    public static final class Casse {
        public boolean isVisible() {
            throw new IllegalStateException("widget indisponible");
        }
    }

    public static void main(String[] args) {
        Zone machine = new Zone(80, 60, 176, 166);
        Zone titre = new Zone(86, 38, 165, 10);
        var ancien = DispositionCoffre.calculer(320, 240, machine, 1, 46, 104);
        assert ancien.cadre().croise(titre) : "reproduire le bandeau sur le titre déporté";
        var menu =
                new Menu(
                        new UI(
                                List.of(
                                        new Widget(
                                                titre.x(),
                                                titre.y(),
                                                titre.largeur(),
                                                titre.hauteur(),
                                                true),
                                        new Widget(62, 64, 18, 40, true),
                                        new Widget(0, 0, 320, 240, true),
                                        new Widget(4, 2, 18, 18, false),
                                        new Widget(-30, 5, 40, 20, true),
                                        new Widget(300, 5, 40, 20, true),
                                        new Widget(4, 4, 0, 20, true),
                                        new Casse(),
                                        new Object())));
        Zone occupee = EmpriseModulaire.occupee(menu, machine, 320, 240);
        assert occupee.equals(machine.union(titre).union(new Zone(62, 64, 18, 40)));
        var panneau = DispositionCoffre.calculer(320, 240, occupee, 1, 46, 104);
        assert !panneau.cadre().croise(titre) && !panneau.cadre().croise(machine);
        assert panneau.cadre().bas() < titre.y() && panneau.cadre().y() >= 0;
        assert EmpriseModulaire.occupee(new Object(), machine, 320, 240).equals(machine);
        assert EmpriseModulaire.occupee(new Menu(null), machine, 320, 240).equals(machine);
        assert EmpriseModulaire.occupee(new Menu(new UI(List.of())), machine, 320, 240)
                .equals(machine);
        // Une lecture isolée en erreur n'empêche pas de protéger un titre lu ensuite.
        var partiel = new Menu(new UI(List.of(new Casse(), new Widget(86, 38, 165, 10, true))));
        assert EmpriseModulaire.occupee(partiel, machine, 320, 240).equals(machine.union(titre));
    }
}
