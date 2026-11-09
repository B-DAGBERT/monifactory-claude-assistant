package fr.assistantia.compat.emi;

/**
 * Décision C du joueur : jamais de « machine pas assez puissante / passe au tier supérieur ». Une
 * étape qui demande plus de tension que les machines possédées le dit sans nommer de machine plus
 * puissante, et aucune machine électrique au-delà de la plus forte tension possédée n'est proposée.
 * Règles pures.
 */
public final class DecisionTension {
    private DecisionTension() {}

    /**
     * Raison affichée pour un poste GregTech absent.
     *
     * @param possedee machine du même type possédée mais trop faible, ou null
     * @param nommable atelier qu'on peut nommer sans monter de tier, ou null
     */
    public static String raison(String possedee, String nommable) {
        if (possedee != null) return "Tension insuffisante (" + possedee + " possédé)";
        if (nommable == null) return "Tension insuffisante (au-dessus de tes machines)";
        return "Machine manquante : " + nommable;
    }

    /**
     * Atelier qu'on peut nommer ou proposer : poste hors GregTech (capacité 0), machine à vapeur,
     * ou machine électrique dans les tensions déjà possédées.
     */
    public static boolean proposable(long capacite, boolean vapeur, long tensionMax) {
        return capacite == 0 || vapeur || capacite <= tensionMax;
    }

    /** Plus forte tension électrique possédée ; vapeur et multiblocs n'ouvrent aucun tier. */
    public static long tensionMax(long[] capacites, boolean[] vapeurs, boolean[] multiblocs) {
        long max = 0;
        for (int i = 0; i < capacites.length; i++)
            if (!vapeurs[i] && !multiblocs[i]) max = Math.max(max, capacites[i]);
        return max;
    }
}
