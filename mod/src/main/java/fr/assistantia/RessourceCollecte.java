package fr.assistantia;

/** Une pile à retirer, exprimée en objets ou en mB par contenant. */
public record RessourceCollecte(String id, int parObjet) {
    private static final String FLUIDE = "fluide|";

    public static String cleObjet(String id, Object donnees) {
        return donnees == null ? id : id + "|" + donnees;
    }

    /** Identifiant de registre reconnu par les relevés de coffres sans NBT. */
    public static String idObjet(String cle) {
        int separateur = cle.indexOf('|');
        return separateur < 0 ? cle : cle.substring(0, separateur);
    }

    public static String cleFluide(String id, Object donnees) {
        return FLUIDE + id + "|" + donnees;
    }

    public static boolean estFluide(String id) {
        return id.startsWith(FLUIDE);
    }

    int quantite(int nombre) {
        return (int) Math.min(Integer.MAX_VALUE, (long) nombre * parObjet);
    }

    int contenants(int quantite) {
        return (int) ((Math.max(0, (long) quantite) + parObjet - 1) / parObjet);
    }
}
