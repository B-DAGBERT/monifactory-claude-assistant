package fr.assistantia;

/** Contraste doux et budget de rendu indépendant de la taille du relevé. */
public record StyleFilons(int maximum, int intensite, boolean surface) {
    public StyleFilons {
        maximum = Math.max(1, Math.min(4096, maximum));
        intensite = Math.max(0, Math.min(2, intensite));
    }

    public StyleFilons(int maximum, int intensite) {
        this(maximum, intensite, true);
    }

    public StyleFilons vueSuivante() {
        return new StyleFilons(maximum, intensite, !surface);
    }

    public StyleFilons budgetSuivant() {
        for (int n : new int[] {64, 192, 512, 1024, 4096})
            if (n > maximum) return new StyleFilons(n, intensite, surface);
        return new StyleFilons(64, intensite, surface);
    }

    public static StyleFilons defaut() {
        return new StyleFilons(192, 0);
    }

    public float alpha(double distance2, boolean visible) {
        if (distance2 < 0 || distance2 > 64 * 64 || (!visible && surface)) return 0;
        double distance = Math.sqrt(distance2);
        float attenuation = (float) Math.max(.08, 1 - distance / 64);
        float force =
                switch (intensite) {
                    case 1 -> .4F;
                    case 2 -> .65F;
                    default -> .22F;
                };
        return force * attenuation * (visible ? 1F : .6F);
    }

    public String nom() {
        return switch (intensite) {
            case 1 -> "Normal";
            case 2 -> "Soutenu";
            default -> "Discret";
        };
    }
}
