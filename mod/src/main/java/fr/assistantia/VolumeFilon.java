package fr.assistantia;

/**
 * Boîte où le générateur GregTech peut placer les minerais d'un gisement, calculée d'après les
 * paramètres natifs de son générateur (GTCEu 7.5). Les bornes sont inclusives.
 *
 * <p>Pour un générateur inconnu, la boîte est un cube autour du centre ; le scan y ajoute une marge
 * où seuls les minerais reliés au filon (trous de {@link #TROU} blocs au plus) sont retenus.
 */
public record VolumeFilon(
        Genre genre, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int marge) {
    /** Trous tolérés entre deux minerais d'un même filon hors des bornes connues. */
    public static final int TROU = 2;

    /** Au-delà, le volume est refusé (mémoire et durée du scan bornées). */
    public static final long MAX_POSITIONS = 2_500_000;

    public enum Genre {
        COUCHES,
        VEINE,
        DYKE,
        CUBOIDE,
        CLASSIQUE,
        STANDARD,
        GEODE,
        INCONNU;

        static Genre lire(String nom) {
            for (Genre g : values()) if (g.name().equalsIgnoreCase(nom)) return g;
            return INCONNU;
        }
    }

    public VolumeFilon {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Vide");
        marge = Math.max(0, Math.min(32, marge));
    }

    // ---------- Générateurs GTCEu ----------

    /** Sphère de couches : demi-taille ⌈taille/2⌉ autour du centre (LayeredVeinGenerator). */
    public static VolumeFilon couches(int cx, int cy, int cz, int tailleMax) {
        int r = demi(tailleMax);
        return cube(Genre.COUCHES, cx, cy, cz, r);
    }

    /** Veine de bruit : cube centre ± ⌈taille/2⌉ (VeinedVeinGenerator). */
    public static VolumeFilon veine(int cx, int cy, int cz, int tailleMax) {
        return cube(Genre.VEINE, cx, cy, cz, demi(tailleMax));
    }

    /**
     * Dyke : colonne de rayon √(2·⌈taille/2⌉) dont l'axe est tiré dans le chunk du centre, entre
     * les hauteurs du générateur (DikeVeinGenerator).
     */
    public static VolumeFilon dyke(int cx, int cz, int tailleMax, int basY, int hautY) {
        int rayon = (int) Math.floor(Math.sqrt(2.0 * demi(tailleMax)));
        int chunkX = Math.floorDiv(cx, 16) * 16, chunkZ = Math.floorDiv(cz, 16) * 16;
        return new VolumeFilon(
                Genre.DYKE,
                chunkX - rayon,
                Math.min(basY, hautY),
                chunkZ - rayon,
                chunkX + 15 + rayon,
                Math.max(basY, hautY),
                chunkZ + 15 + rayon,
                0);
    }

    /**
     * Cuboïde : de x - (taille/2 - 1) à x + 16 + taille/2 - 2, bas tiré dans [minY - 1, maxY - 7],
     * puis {@code couches} + 1 couches (CuboidVeinGenerator).
     */
    public static VolumeFilon cuboide(
            int cx, int cy, int cz, int tailleMax, int minY, int maxY, int couches) {
        int demi = Math.max(1, tailleMax / 2);
        int bas = minY - 1, haut = Math.max(bas, maxY - 7) + Math.max(0, couches);
        return new VolumeFilon(
                Genre.CUBOIDE,
                cx - (demi - 1),
                bas,
                cz - (demi - 1),
                cx + 16 + demi - 2,
                haut,
                cz + 16 + demi - 2,
                0);
    }

    /** Ellipsoïde GT5 : ± taille/2 à l'horizontale, ± min(taille/2, yRadius) (Classic). */
    public static VolumeFilon classique(int cx, int cy, int cz, int tailleMax, int rayonY) {
        int a = Math.max(1, tailleMax / 2);
        int b = Math.max(0, Math.min(a, rayonY));
        return new VolumeFilon(Genre.CLASSIQUE, cx - a, cy - b, cz - a, cx + a, cy + b, cz + a, 0);
    }

    /** Amas façon minerai vanilla (StandardVeinGenerator) : boîte de doPlace. */
    public static VolumeFilon standard(int cx, int cy, int cz, int tailleMax) {
        double g = tailleMax / 8.0;
        int n = (int) Math.ceil((tailleMax / 16.0 * 2 + 1) / 2);
        int h = (int) Math.ceil(g) + n;
        return new VolumeFilon(
                Genre.STANDARD, cx - h, cy - 2 - n, cz - h, cx + h, cy + 2 + n, cz + h, 0);
    }

    /**
     * Géode : décalages minGenOffset à maxGenOffset sur chaque axe, plus une couche de sécurité.
     */
    public static VolumeFilon geode(int cx, int cy, int cz, int decalageMin, int decalageMax) {
        int a = Math.min(decalageMin, decalageMax) - 1, b = Math.max(decalageMin, decalageMax) + 1;
        return new VolumeFilon(Genre.GEODE, cx + a, cy + a, cz + a, cx + b, cy + b, cz + b, 0);
    }

    /**
     * Générateur inconnu : cube du noyau, plus une marge où le scan suit les minerais reliés au
     * filon par des trous de {@link #TROU} blocs au plus.
     */
    public static VolumeFilon inconnu(int cx, int cy, int cz, int rayon, int marge) {
        int r = Math.max(4, Math.min(48, rayon));
        return new VolumeFilon(
                Genre.INCONNU, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, marge);
    }

    private static VolumeFilon cube(Genre genre, int cx, int cy, int cz, int r) {
        return new VolumeFilon(genre, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, 0);
    }

    private static int demi(int taille) {
        return Math.max(1, (taille + 1) / 2);
    }

    // ---------- Géométrie ----------

    /** Volume réellement parcouru, marge comprise. */
    public int bordMinX() {
        return minX - marge;
    }

    public int bordMaxX() {
        return maxX + marge;
    }

    public int bordMinY() {
        return minY - marge;
    }

    public int bordMaxY() {
        return maxY + marge;
    }

    public int bordMinZ() {
        return minZ - marge;
    }

    public int bordMaxZ() {
        return maxZ + marge;
    }

    public long positions() {
        return (long) (bordMaxX() - bordMinX() + 1)
                * (bordMaxY() - bordMinY() + 1)
                * (bordMaxZ() - bordMinZ() + 1);
    }

    public boolean acceptable() {
        return positions() <= MAX_POSITIONS;
    }

    /** Dans les bornes du générateur (sans la marge). */
    public boolean contient(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contient(long p) {
        return contient(FilonMemoire.x(p), FilonMemoire.y(p), FilonMemoire.z(p));
    }

    /** Dans les bornes ou leur marge. */
    public boolean parcourt(int x, int y, int z) {
        return x >= bordMinX()
                && x <= bordMaxX()
                && y >= bordMinY()
                && y <= bordMaxY()
                && z >= bordMinZ()
                && z <= bordMaxZ();
    }

    public boolean croise(VolumeFilon autre) {
        return autre.bordMinX() <= bordMaxX()
                && autre.bordMaxX() >= bordMinX()
                && autre.bordMinY() <= bordMaxY()
                && autre.bordMaxY() >= bordMinY()
                && autre.bordMinZ() <= bordMaxZ()
                && autre.bordMaxZ() >= bordMinZ();
    }

    /**
     * Profondeur relative dans la boîte : 0 au centre, 1 sur une face, plus au-delà. Départage deux
     * gisements dont les boîtes se chevauchent : le minerai revient au filon où il est le plus au
     * centre.
     */
    public double profondeur(int x, int y, int z) {
        double hx = Math.max(1, (maxX - minX) / 2.0),
                hy = Math.max(1, (maxY - minY) / 2.0),
                hz = Math.max(1, (maxZ - minZ) / 2.0);
        double dx = Math.abs(x - (minX + maxX) / 2.0) / hx,
                dy = Math.abs(y - (minY + maxY) / 2.0) / hy,
                dz = Math.abs(z - (minZ + maxZ) / 2.0) / hz;
        return Math.max(dx, Math.max(dy, dz));
    }

    /** Colonnes de chunks parcourues, codées par {@link #colonne}. */
    public long[] colonnes() {
        int ax = Math.floorDiv(bordMinX(), 16), bx = Math.floorDiv(bordMaxX(), 16);
        int az = Math.floorDiv(bordMinZ(), 16), bz = Math.floorDiv(bordMaxZ(), 16);
        long[] resultat = new long[(bx - ax + 1) * (bz - az + 1)];
        int n = 0;
        for (int cx = ax; cx <= bx; cx++)
            for (int cz = az; cz <= bz; cz++) resultat[n++] = colonne(cx, cz);
        return resultat;
    }

    public static long colonne(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int colonneX(long c) {
        return (int) (c >> 32);
    }

    public static int colonneZ(long c) {
        return (int) c;
    }

    /** Positions de la boîte (marge comprise) dans une colonne, hauteurs du monde comprises. */
    public long positionsColonne(long c, int mondeMinY, int mondeMaxY) {
        int cx = colonneX(c) * 16, cz = colonneZ(c) * 16;
        int ax = Math.max(bordMinX(), cx), bx = Math.min(bordMaxX(), cx + 15);
        int az = Math.max(bordMinZ(), cz), bz = Math.min(bordMaxZ(), cz + 15);
        int ay = Math.max(bordMinY(), mondeMinY), by = Math.min(bordMaxY(), mondeMaxY);
        if (ax > bx || az > bz || ay > by) return 0;
        return (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
    }

    // ---------- Sauvegarde ----------

    public int[] valeurs() {
        return new int[] {genre.ordinal(), minX, minY, minZ, maxX, maxY, maxZ, marge};
    }

    public static VolumeFilon lire(int[] v) {
        if (v == null || v.length < 7) return null;
        try {
            Genre genre = v[0] >= 0 && v[0] < Genre.values().length ? Genre.values()[v[0]] : null;
            if (genre == null) return null;
            var volume =
                    new VolumeFilon(
                            genre, v[1], v[2], v[3], v[4], v[5], v[6], v.length > 7 ? v[7] : 0);
            return volume.acceptable() ? volume : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String nom() {
        return switch (genre) {
            case COUCHES -> "couches";
            case VEINE -> "veine";
            case DYKE -> "dyke";
            case CUBOIDE -> "cuboïde";
            case CLASSIQUE -> "classique";
            case STANDARD -> "amas";
            case GEODE -> "géode";
            case INCONNU -> "générateur inconnu";
        };
    }
}
