package fr.assistantia;

/** État conservé à la fermeture du menu, indépendant de Minecraft pour être testable. */
final class EtatDiscussion {
    private String brouillon = "", question = "", information = "";
    private long demande;
    private int position, maximum;
    private boolean suivre = true;

    String brouillon() {
        return brouillon;
    }

    void brouillon(String texte) {
        brouillon = texte;
        if (!enAttente()) information = "";
    }

    boolean enAttente() {
        return demande != 0;
    }

    String question() {
        return question;
    }

    String information() {
        return information;
    }

    boolean peutEnvoyer(boolean actif, boolean occupe) {
        return actif && !occupe && !enAttente() && !brouillon.isBlank();
    }

    void envoye(long numero) {
        demande = numero;
        question = brouillon.trim();
        information = "";
        derniers();
    }

    void echecEnvoi() {
        information = "Envoi impossible : ton brouillon est conservé.";
    }

    void confirmer(long numero, String etat, long derniereDemande) {
        if (!enAttente()) return;
        if (numero == demande) {
            if ("acceptee".equals(etat)) {
                brouillon = "";
                information = "";
            } else {
                information =
                        "expiree".equals(etat)
                                ? "Envoi expiré : ton brouillon est conservé."
                                : "Assistant occupé : ton brouillon est conservé.";
            }
        } else if (derniereDemande > demande) {
            information = "Question non reçue : ton brouillon est conservé.";
        } else return;
        demande = 0;
        question = "";
    }

    void ajuster(int hauteur, int visible) {
        maximum = Math.max(0, hauteur - Math.max(0, visible));
        position = suivre ? maximum : Math.min(position, maximum);
    }

    void retirerEnTete(int hauteur) {
        position = Math.max(0, position - hauteur);
    }

    void position(int valeur) {
        position = Math.max(0, Math.min(maximum, valeur));
        suivre = position == maximum;
    }

    void defiler(int delta) {
        position(position + delta);
    }

    void derniers() {
        suivre = true;
        position = maximum;
    }

    int position() {
        return position;
    }

    int maximum() {
        return maximum;
    }

    boolean suitDerniers() {
        return suivre;
    }
}
