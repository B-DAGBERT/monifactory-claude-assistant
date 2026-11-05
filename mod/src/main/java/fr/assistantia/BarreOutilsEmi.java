package fr.assistantia;

/**
 * Trois boutons près de Recipe Tree ; repli sur plusieurs rangées si la recherche manque de place.
 */
public record BarreOutilsEmi(Zone scan, Zone suivi, Zone sac) {
    public record Zone(int x, int y, int largeur, int hauteur) {}

    public static BarreOutilsEmi calculer(int x, int y, int place) {
        int room = Math.max(20, place);
        if (room >= 134) {
            int scan = Math.min(102, room - 112);
            return new BarreOutilsEmi(
                    new Zone(x, y, scan, 20),
                    new Zone(x + scan + 2, y, 48, 20),
                    new Zone(x + scan + 52, y, 58, 20));
        }
        int largeur = Math.min(102, room);
        if (room >= 108)
            return new BarreOutilsEmi(
                    new Zone(x, y, largeur, 20),
                    new Zone(x, y - 22, 48, 20),
                    new Zone(x + 50, y - 22, 58, 20));
        return new BarreOutilsEmi(
                new Zone(x, y, largeur, 20),
                new Zone(x, y - 22, Math.min(48, room), 20),
                new Zone(x, y - 44, Math.min(58, room), 20));
    }
}
