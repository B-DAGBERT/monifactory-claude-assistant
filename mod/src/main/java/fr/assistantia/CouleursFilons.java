package fr.assistantia;

/**
 * Une couleur vive par matériau de minerai, la même quelle que soit la pierre autour (sulfur reste
 * sulfur dans la netherrack comme dans le basalte).
 *
 * <p>Point de départ : la couleur native GregTech du matériau, saturée et éclaircie. Les matériaux
 * gris (fer, étain, charbon…) reçoivent une teinte franche et stable tirée de leur nom. Le
 * contraste suit le fond dominant de la dimension : teintes claires sur pierre, deepslate et
 * netherrack, teintes profondes sur la pierre de l'End, très claire.
 */
final class CouleursFilons {
    /**
     * Teintes franches et bien séparées pour les matériaux sans couleur propre (sans jaune pâle).
     */
    private static final float[] TEINTES = {200, 285, 155, 320, 28, 235, 100, 180, 350, 260};

    private CouleursFilons() {}

    /** Luminance minimale sur fond sombre (pierre, deepslate, netherrack, basalte). */
    static final double LUMINANCE_MIN = 0.42;

    /** Luminance maximale sur la pierre de l'End, très claire. */
    static final double LUMINANCE_MAX = 0.2;

    /** Couleur ARGB opaque du matériau {@code cle} ({@code rgb} natif ou -1) dans la dimension. */
    static int couleur(String cle, int rgb, String dimension) {
        float h, s;
        if (rgb >= 0) {
            float[] hsv = hsv(rgb);
            h = hsv[0];
            s = hsv[1];
        } else {
            h = 0;
            s = 0;
        }
        if (rgb < 0 || s < 0.15f) {
            h = TEINTES[Math.floorMod(stable(cle), TEINTES.length)];
            s = 0.8f;
        } else {
            s = Math.max(0.8f, Math.min(1f, s));
        }
        boolean clair = dimension != null && dimension.endsWith("the_end");
        if (dimension != null && dimension.endsWith("the_nether") && (h >= 345 || h < 12))
            h = h >= 345 ? 330 : 22; // netherrack rouge sombre : éloigner les rouges de sa teinte
        int couleur = rgb(h, s, 1f);
        // Contraste garanti : éclaircir vers le blanc sur fond sombre, assombrir sur l'End.
        for (int pas = 1; pas <= 20; pas++) {
            double l = luminance(couleur);
            if (clair ? l <= LUMINANCE_MAX : l >= LUMINANCE_MIN) break;
            couleur = melanger(rgb(h, s, 1f), clair ? 0x000000 : 0xFFFFFF, pas / 20f);
        }
        return 0xFF000000 | couleur;
    }

    private static int melanger(int a, int b, float part) {
        int r = Math.round(((a >> 16) & 255) * (1 - part) + ((b >> 16) & 255) * part);
        int g = Math.round(((a >> 8) & 255) * (1 - part) + ((b >> 8) & 255) * part);
        int bl = Math.round((a & 255) * (1 - part) + (b & 255) * part);
        return (r << 16) | (g << 8) | bl;
    }

    /** Clé stable d'un bloc sans matériau GregTech : « gtceu:deepslate_coal_ore » → « coal ». */
    static String cleBloc(String bloc) {
        String nom = bloc.substring(bloc.indexOf(':') + 1);
        if (nom.endsWith("_ore")) nom = nom.substring(0, nom.length() - 4);
        for (String pierre :
                new String[] {
                    "deepslate_", "tuff_", "andesite_", "diorite_", "granite_", "red_granite_",
                    "marble_", "basalt_", "netherrack_", "endstone_", "end_stone_", "sand_",
                    "red_sand_", "gravel_", "blackstone_", "calcite_", "stone_", "nether_"
                }) if (nom.startsWith(pierre)) nom = nom.substring(pierre.length());
        return nom;
    }

    private static int stable(String cle) {
        int h = 0x811C9DC5;
        for (int i = 0; i < cle.length(); i++) h = (h ^ cle.charAt(i)) * 0x01000193;
        return h ^ (h >>> 15);
    }

    static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }

    static int rgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        float r, g, b;
        int secteur = (int) (h / 60) % 6;
        switch (secteur) {
            case 0 -> {
                r = c;
                g = x;
                b = 0;
            }
            case 1 -> {
                r = x;
                g = c;
                b = 0;
            }
            case 2 -> {
                r = 0;
                g = c;
                b = x;
            }
            case 3 -> {
                r = 0;
                g = x;
                b = c;
            }
            case 4 -> {
                r = x;
                g = 0;
                b = c;
            }
            default -> {
                r = c;
                g = 0;
                b = x;
            }
        }
        return (Math.round((r + m) * 255) << 16)
                | (Math.round((g + m) * 255) << 8)
                | Math.round((b + m) * 255);
    }

    /** Luminance relative WCAG d'une couleur RGB. */
    static double luminance(int rgb) {
        double r = canal((rgb >> 16) & 255), g = canal((rgb >> 8) & 255), b = canal(rgb & 255);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double canal(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Rapport de contraste WCAG entre deux couleurs RGB. */
    static double contraste(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
