package fr.assistantia;

import java.util.ArrayList;
import java.util.List;

/**
 * Numéros, étoile et marques des machines tracés en segments, comme un afficheur : ils passent par
 * le même rendu de lignes que les contours rouges, toujours visible, au lieu de la police du jeu
 * (son texte dans le monde ne s'affichait pas chez le joueur). Calcul pur : un dessin de hauteur 1,
 * origine en bas à gauche.
 */
public final class TraitsReperes {
    private TraitsReperes() {}

    /** Un segment, en unités de hauteur de texte. */
    public record Trait(float x1, float y1, float x2, float y2) {}

    /** Les segments d'une étiquette et sa largeur totale (sa hauteur vaut 1). */
    public record Dessin(List<Trait> traits, float largeur) {
        public Dessin {
            traits = List.copyOf(traits);
        }
    }

    public static final char ETOILE = '★', PRET = '✓', EN_COURS = '…';
    static final float CHIFFRE = 0.6F, ECART = 0.25F;

    // Segments d'afficheur : a haut, b haut droit, c bas droit, d bas, e bas gauche, f haut
    // gauche, g milieu.
    private static final String[] AFFICHEUR = {
        "abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"
    };

    /**
     * Un drawer (Functional Storage et autres mods de tiroirs), d'après l'identifiant de son bloc :
     * on y affiche le nombre d'objets à sortir, faute de bouton qui les prend tout seul comme dans
     * un coffre. Jamais un contrôleur ni une extension, qui ne contiennent rien eux-mêmes.
     */
    public static boolean tiroir(String idBloc) {
        if (idBloc == null) return false;
        int deuxPoints = idBloc.indexOf(':');
        String espace = deuxPoints < 0 ? "" : idBloc.substring(0, deuxPoints);
        String nom = idBloc.substring(deuxPoints + 1);
        if (nom.contains("controller") || nom.contains("extension")) return false;
        if (nom.contains("drawer")) return true;
        return espace.equals("functionalstorage") && nom.matches(".*_[124]");
    }

    /**
     * Une machine qui garde ses outils installés (inscriber d'AE2 et ses presses), d'après
     * l'identifiant de son bloc : son contenu est déjà en place, jamais un coffre où aller
     * chercher.
     */
    public static boolean machineOutils(String idBloc) {
        if (idBloc == null) return false;
        return idBloc.substring(idBloc.indexOf(':') + 1).contains("inscriber");
    }

    /** « ★ 3 · prêt » devient « ★3✓ », « 3 · en cours » devient « 3… ». */
    public static String symboles(String texte) {
        StringBuilder sortie = new StringBuilder();
        if (texte.contains("★")) sortie.append(ETOILE);
        for (char c : texte.toCharArray()) if (c >= '0' && c <= '9') sortie.append(c);
        if (texte.contains("prêt")) sortie.append(PRET);
        else if (texte.contains("en cours")) sortie.append(EN_COURS);
        return sortie.toString();
    }

    /** Les caractères inconnus sont ignorés. */
    public static Dessin dessiner(String symboles) {
        List<Trait> traits = new ArrayList<>();
        float x = 0;
        for (char c : symboles.toCharArray()) {
            float largeur = largeur(c);
            if (largeur <= 0) continue;
            if (!traits.isEmpty()) x += ECART;
            glyphe(c, x, traits);
            x += largeur;
        }
        return new Dessin(traits, x);
    }

    /** Largeur d'un glyphe, 0 s'il est inconnu. */
    static float largeur(char c) {
        if (c >= '0' && c <= '9') return CHIFFRE;
        return switch (c) {
            case ETOILE -> 1;
            case PRET -> 0.8F;
            case EN_COURS -> 0.72F;
            default -> 0;
        };
    }

    /** Ajoute le glyphe à partir de {@code x}. */
    private static void glyphe(char c, float x, List<Trait> traits) {
        if (c >= '0' && c <= '9') {
            for (char s : AFFICHEUR[c - '0'].toCharArray()) segment(s, x, traits);
            return;
        }
        if (c == ETOILE) {
            // Étoile à cinq branches, pointe en haut, dans un carré de côté 1.
            float[] px = new float[10], py = new float[10];
            for (int i = 0; i < 10; i++) {
                double angle = Math.PI / 2 + i * Math.PI / 5;
                double rayon = i % 2 == 0 ? 0.5 : 0.21;
                px[i] = x + 0.5F + (float) (Math.cos(angle) * rayon);
                py[i] = 0.5F + (float) (Math.sin(angle) * rayon);
            }
            for (int i = 0; i < 10; i++)
                traits.add(new Trait(px[i], py[i], px[(i + 1) % 10], py[(i + 1) % 10]));
        } else if (c == PRET) {
            traits.add(new Trait(x, 0.45F, x + 0.28F, 0.1F));
            traits.add(new Trait(x + 0.28F, 0.1F, x + 0.8F, 0.95F));
        } else if (c == EN_COURS) {
            for (int i = 0; i < 3; i++)
                traits.add(new Trait(x + i * 0.3F, 0.05F, x + i * 0.3F + 0.12F, 0.05F));
        }
    }

    private static void segment(char s, float x, List<Trait> traits) {
        float l = CHIFFRE;
        traits.add(
                switch (s) {
                    case 'a' -> new Trait(x, 1, x + l, 1);
                    case 'b' -> new Trait(x + l, 1, x + l, 0.5F);
                    case 'c' -> new Trait(x + l, 0.5F, x + l, 0);
                    case 'd' -> new Trait(x, 0, x + l, 0);
                    case 'e' -> new Trait(x, 0, x, 0.5F);
                    case 'f' -> new Trait(x, 0.5F, x, 1);
                    default -> new Trait(x, 0.5F, x + l, 0.5F);
                });
    }
}
