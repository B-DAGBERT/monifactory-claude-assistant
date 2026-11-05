package fr.assistantia;

import fr.assistantia.DispositionCoffre.Zone;

/** Limites des widgets natifs LDLib, sans dépendance obligatoire à GregTech ni à son GUI. */
public final class EmpriseModulaire {
    private EmpriseModulaire() {}

    public static Zone occupee(Object menu, Zone conteneur, int largeur, int hauteur) {
        Zone resultat = conteneur;
        try {
            Object ui = menu.getClass().getMethod("getModularUI").invoke(menu);
            if (ui == null) return resultat;
            Object collection =
                    ui.getClass().getMethod("getFlatVisibleWidgetCollection").invoke(ui);
            if (!(collection instanceof Iterable<?> widgets)) return resultat;
            for (Object widget : widgets) {
                Zone zone = zone(widget, largeur, hauteur);
                if (zone != null) resultat = resultat.union(zone);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Les écrans vanilla et les autres mods conservent leurs limites habituelles.
        }
        return resultat;
    }

    private static Zone zone(Object widget, int largeur, int hauteur) {
        if (widget == null) return null;
        try {
            Class<?> type = widget.getClass();
            if (!(boolean) type.getMethod("isVisible").invoke(widget)) return null;
            Zone zone =
                    new Zone(
                            (int) type.getMethod("getPositionX").invoke(widget),
                            (int) type.getMethod("getPositionY").invoke(widget),
                            (int) type.getMethod("getSizeWidth").invoke(widget),
                            (int) type.getMethod("getSizeHeight").invoke(widget));
            if (zone.largeur() <= 0
                    || zone.hauteur() <= 0
                    || zone.x() < 0
                    || zone.y() < 0
                    || zone.droite() > largeur
                    || zone.bas() > hauteur
                    || (long) zone.largeur() * zone.hauteur() > (long) largeur * hauteur / 2)
                return null;
            return zone;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
