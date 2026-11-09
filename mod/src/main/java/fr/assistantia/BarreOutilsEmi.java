package fr.assistantia;

/**
 * Une rangée mesurée sur le texte ; un seul accès compact quand elle ne tient pas. Les boutons ont
 * la hauteur commune du thème ({@link #HAUTEUR}, égale à {@code Theme.BOUTON}) : l'appelant centre
 * la rangée sur celle d'EMI.
 */
public record BarreOutilsEmi(Zone scan, Zone suivi, Zone sac, Zone menu) {
    public static final int HAUTEUR = 16;
    private static final int ECART = 4, MINIMUM = 20;

    public record Zone(int x, int y, int largeur, int hauteur) {}

    public static BarreOutilsEmi calculer(int x, int y, int place) {
        return calculer(x, y, place, 80, 42, 52, 48);
    }

    public static BarreOutilsEmi calculer(
            int x, int y, int place, int scan, int suivi, int sac, int menu) {
        int disponible = Math.max(0, place);
        scan = Math.max(MINIMUM, scan);
        suivi = Math.max(MINIMUM, suivi);
        sac = Math.max(MINIMUM, sac);
        if (disponible < MINIMUM) return new BarreOutilsEmi(null, null, null, null);
        if ((long) scan + suivi + sac + 2 * ECART > disponible)
            return new BarreOutilsEmi(
                    null,
                    null,
                    null,
                    new Zone(x, y, Math.min(disponible, Math.max(MINIMUM, menu)), HAUTEUR));
        return new BarreOutilsEmi(
                new Zone(x, y, scan, HAUTEUR),
                new Zone(x + scan + ECART, y, suivi, HAUTEUR),
                new Zone(x + scan + suivi + 2 * ECART, y, sac, HAUTEUR),
                null);
    }
}
