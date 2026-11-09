package fr.assistantia;

import java.util.Arrays;

/**
 * Table d'association long → int sans objet par entrée : adressage ouvert et sondage linéaire.
 *
 * <p>Les clés sont des positions compactées ({@link FilonMemoire#pos}). {@link Long#MIN_VALUE}
 * marque une case vide : cette valeur correspond à x = -33 554 432, hors de toute bordure de monde.
 */
final class TableLong {
    static final long VIDE = Long.MIN_VALUE;
    static final int ABSENT = -1;

    private long[] cles;
    private int[] valeurs;
    private int taille, masque;

    TableLong() {
        this(16);
    }

    TableLong(int capacite) {
        int n = Integer.highestOneBit(Math.max(4, capacite * 2 - 1)) << 1;
        cles = new long[n];
        valeurs = new int[n];
        Arrays.fill(cles, VIDE);
        masque = n - 1;
    }

    private static int melange(long cle) {
        long h = cle * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h ^= h >>> 16;
        return (int) h;
    }

    int taille() {
        return taille;
    }

    int lire(long cle) {
        for (int i = melange(cle) & masque; ; i = (i + 1) & masque) {
            long c = cles[i];
            if (c == cle) return valeurs[i];
            if (c == VIDE) return ABSENT;
        }
    }

    boolean contient(long cle) {
        return lire(cle) != ABSENT;
    }

    /** Remplace la valeur d'une clé connue ou l'ajoute. */
    void ecrire(long cle, int valeur) {
        if (cle == VIDE) throw new IllegalArgumentException("Clé réservée");
        if ((taille + 1) * 4 > cles.length * 3) agrandir();
        for (int i = melange(cle) & masque; ; i = (i + 1) & masque) {
            long c = cles[i];
            if (c == cle) {
                valeurs[i] = valeur;
                return;
            }
            if (c == VIDE) {
                cles[i] = cle;
                valeurs[i] = valeur;
                taille++;
                return;
            }
        }
    }

    /** Ajoute {@code delta} à la valeur (0 si absente) et retire la clé quand elle tombe à 0. */
    int ajouter(long cle, int delta) {
        int avant = lire(cle);
        int apres = (avant == ABSENT ? 0 : avant) + delta;
        if (apres <= 0) retirer(cle);
        else ecrire(cle, apres);
        return Math.max(0, apres);
    }

    /** Retrait par décalage arrière : aucune pierre tombale, la table reste compacte. */
    int retirer(long cle) {
        int i = melange(cle) & masque;
        while (true) {
            long c = cles[i];
            if (c == VIDE) return ABSENT;
            if (c == cle) break;
            i = (i + 1) & masque;
        }
        int ancienne = valeurs[i];
        taille--;
        int trou = i;
        for (int j = (trou + 1) & masque; cles[j] != VIDE; j = (j + 1) & masque) {
            int ideal = melange(cles[j]) & masque;
            // La case j peut combler le trou si son emplacement idéal n'est pas entre trou et j.
            boolean deplacable =
                    trou <= j ? ideal <= trou || ideal > j : ideal <= trou && ideal > j;
            if (deplacable) {
                cles[trou] = cles[j];
                valeurs[trou] = valeurs[j];
                trou = j;
            }
        }
        cles[trou] = VIDE;
        return ancienne;
    }

    void vider() {
        Arrays.fill(cles, VIDE);
        taille = 0;
    }

    private void agrandir() {
        long[] anciennesCles = cles;
        int[] anciennesValeurs = valeurs;
        cles = new long[anciennesCles.length * 2];
        valeurs = new int[cles.length];
        Arrays.fill(cles, VIDE);
        masque = cles.length - 1;
        taille = 0;
        for (int i = 0; i < anciennesCles.length; i++)
            if (anciennesCles[i] != VIDE) ecrire(anciennesCles[i], anciennesValeurs[i]);
    }

    /** Parcours sans allocation : appelle {@code action} pour chaque couple. */
    interface Visite {
        void voir(long cle, int valeur);
    }

    void parcourir(Visite action) {
        for (int i = 0; i < cles.length; i++) if (cles[i] != VIDE) action.voir(cles[i], valeurs[i]);
    }
}
