package fr.assistantia;

/** Une rangée mesurée sur le texte ; un seul accès compact quand elle ne tient pas. */
public record BarreOutilsEmi(Zone scan, Zone suivi, Zone sac, Zone menu) {
    public record Zone(int x, int y, int largeur, int hauteur) {}

    public static BarreOutilsEmi calculer(int x, int y, int place) {
        return calculer(x, y, place, 80, 42, 52, 48);
    }

    public static BarreOutilsEmi calculer(
            int x, int y, int place, int scan, int suivi, int sac, int menu) {
        int disponible = Math.max(0, place);
        scan = Math.max(20, scan);
        suivi = Math.max(20, suivi);
        sac = Math.max(20, sac);
        if (disponible < 20) return new BarreOutilsEmi(null, null, null, null);
        if ((long) scan + suivi + sac + 8 > disponible)
            return new BarreOutilsEmi(
                    null, null, null, new Zone(x, y, Math.min(disponible, Math.max(20, menu)), 20));
        return new BarreOutilsEmi(
                new Zone(x, y, scan, 20),
                new Zone(x + scan + 4, y, suivi, 20),
                new Zone(x + scan + suivi + 8, y, sac, 20),
                null);
    }
}
