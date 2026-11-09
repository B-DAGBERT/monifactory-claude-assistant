package fr.assistantia;

/**
 * Réglages de la surbrillance des filons : budget de blocs, vue Surface/Total, épaisseur, intensité
 * et remplissage des faces. Le budget de rendu ne change ni le scan ni les relevés.
 */
public record StyleFilons(
        int maximum, int intensite, boolean surface, int epaisseur, boolean remplissage) {
    /** Blocs surlignés simultanément, cycle du bouton « Blocs ». */
    public static final int[] BUDGETS = {64, 192, 512, 1024, 4096};

    /** Épaisseurs des contours en pixels pour un écran de 1920 pixels de large. */
    public static final float[] EPAISSEURS = {1.5f, 2.5f, 4f, 6f};

    public static final String[] NOMS_EPAISSEUR = {"Fine", "Normale", "Épaisse", "Très épaisse"};

    /** Opacité des contours ; « Maximale » ajoute un liseré sombre sous chaque contour. */
    public static final float[] INTENSITES = {0.5f, 0.75f, 0.95f, 1f};

    public static final String[] NOMS_INTENSITE = {"Douce", "Normale", "Forte", "Maximale"};

    /** Portée de la surbrillance autour du joueur, en blocs. */
    public static final int RAYON = 96;

    public static final int EPAISSEUR_DEFAUT = 1, INTENSITE_DEFAUT = 1;

    public StyleFilons {
        maximum = Math.max(1, Math.min(4096, maximum));
        intensite = Math.max(0, Math.min(INTENSITES.length - 1, intensite));
        epaisseur = Math.max(0, Math.min(EPAISSEURS.length - 1, epaisseur));
    }

    public StyleFilons(int maximum, int intensite) {
        this(maximum, intensite, true);
    }

    public StyleFilons(int maximum, int intensite, boolean surface) {
        this(maximum, intensite, surface, EPAISSEUR_DEFAUT, false);
    }

    public static StyleFilons defaut() {
        return new StyleFilons(512, INTENSITE_DEFAUT, true, EPAISSEUR_DEFAUT, false);
    }

    public StyleFilons vueSuivante() {
        return new StyleFilons(maximum, intensite, !surface, epaisseur, remplissage);
    }

    public StyleFilons budgetSuivant() {
        for (int n : BUDGETS)
            if (n > maximum) return new StyleFilons(n, intensite, surface, epaisseur, remplissage);
        return new StyleFilons(BUDGETS[0], intensite, surface, epaisseur, remplissage);
    }

    public StyleFilons epaisseurSuivante() {
        return new StyleFilons(
                maximum, intensite, surface, (epaisseur + 1) % EPAISSEURS.length, remplissage);
    }

    public StyleFilons intensiteSuivante() {
        return new StyleFilons(
                maximum, (intensite + 1) % INTENSITES.length, surface, epaisseur, remplissage);
    }

    public StyleFilons remplissageInverse() {
        return new StyleFilons(maximum, intensite, surface, epaisseur, !remplissage);
    }

    /**
     * Opacité d'un contour : atténuation douce avec la distance (60 % à la portée maximale),
     * contour caché moins appuyé que le visible, rien hors de portée ou en vue Surface pour un
     * caché.
     */
    public float alpha(double distance2, boolean visible) {
        if (distance2 < 0 || distance2 > (double) RAYON * RAYON || (!visible && surface)) return 0;
        double part = Math.min(1, Math.sqrt(distance2) / RAYON);
        float attenuation = (float) (1 - 0.4 * part * part);
        return INTENSITES[intensite] * attenuation * (visible ? 1F : .6F);
    }

    /** Opacité du remplissage des faces, discrète pour garder le relief lisible. */
    public float alphaFaces(double distance2, boolean visible) {
        return remplissage ? alpha(distance2, visible) * 0.22F : 0;
    }

    /** Épaisseur en pixels, proportionnelle à la largeur de la fenêtre au-delà de 1920 pixels. */
    public float largeur(int largeurFenetre) {
        return EPAISSEURS[epaisseur] * Math.max(1F, largeurFenetre / 1920F);
    }

    /** Liseré sombre sous les contours (intensité maximale) : lisible sur tout fond. */
    public boolean lisere() {
        return intensite == INTENSITES.length - 1;
    }

    public String nom() {
        return NOMS_INTENSITE[intensite];
    }

    public String nomEpaisseur() {
        return NOMS_EPAISSEUR[epaisseur];
    }
}
