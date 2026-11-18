package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;

/** Un réglage distant en cours, avec la valeur publiée comme seule valeur affichable. */
final class EtatReglages {
    static final long DELAI_REPONSE = 45_000;
    private static final long DELAI_DOUBLE_CLIC = 350;

    record Contexte(String monde, Object connexion, Object niveau) {}

    private record Demande(String cle, boolean valeur, long n, String id, long debut) {}

    private String monde = "";
    private WeakReference<Object> connexion = new WeakReference<>(null);
    private WeakReference<Object> niveau = new WeakReference<>(null);
    private Demande attente;
    private String information = "", dernierClic = "";
    private long clicLe;

    boolean enAttente() {
        return attente != null;
    }

    String information() {
        return enAttente() ? "Réglage en attente de confirmation…" : information;
    }

    boolean cliquer(String cle, long maintenant) {
        if (enAttente() || cle.equals(dernierClic) && maintenant - clicLe < DELAI_DOUBLE_CLIC)
            return false;
        dernierClic = cle;
        clicLe = maintenant;
        information = "";
        return true;
    }

    void envoyer(String cle, boolean valeur, long n, String id, long maintenant) {
        attente = new Demande(cle, valeur, n, id, maintenant);
    }

    void echecEnvoi() {
        information = "Envoi du réglage impossible.";
    }

    void observer(Contexte courant, boolean assistantActif, JsonObject ecran, long maintenant) {
        if (!courant.monde().equals(monde)
                || courant.connexion() != connexion.get()
                || courant.niveau() != niveau.get()) {
            monde = courant.monde();
            connexion = new WeakReference<>(courant.connexion());
            niveau = new WeakReference<>(courant.niveau());
            information =
                    enAttente() ? "Connexion ou dimension changée. Réglage non confirmé." : "";
            attente = null;
            dernierClic = "";
        }
        if (!enAttente()) return;
        if (!assistantActif) {
            terminer("Assistant arrêté. Réglage non confirmé.");
            return;
        }

        JsonObject recu = objet(objet(ecran, "demandes"), "derniere");
        var n = recu.get("n");
        String id = Pont.texte(recu, "id");
        if (n != null
                && n.isJsonPrimitive()
                && n.getAsJsonPrimitive().isNumber()
                && n.getAsLong() == attente.n()
                && id.equals(attente.id())) {
            String message =
                    switch (Pont.texte(recu, "etat")) {
                        case "refusee" -> "Réglage refusé.";
                        case "expiree" -> "Demande de réglage expirée.";
                        case "interrompue" -> "Réglage interrompu. Vérifie sa valeur.";
                        default -> "";
                    };
            if (!message.isEmpty()) {
                terminer(message);
                return;
            }
        }

        // La file ne publie que son dernier accusé. La vue appliquée fait aussi foi.
        Boolean appliquee = valeurPubliee(ecran, attente.cle());
        if (appliquee != null && appliquee == attente.valeur()) {
            terminer("");
            return;
        }
        if (maintenant - attente.debut() >= DELAI_REPONSE)
            terminer("Aucune confirmation du réglage reçue.");
    }

    static Boolean valeurPubliee(JsonObject ecran, String cle) {
        for (JsonElement ligne : Pont.liste(ecran, "reglages")) {
            if (!(ligne instanceof JsonObject r) || !cle.equals(Pont.texte(r, "cle"))) continue;
            var valeur = r.get("valeur");
            return valeur != null
                            && valeur.isJsonPrimitive()
                            && valeur.getAsJsonPrimitive().isBoolean()
                    ? valeur.getAsBoolean()
                    : null;
        }
        return null;
    }

    private void terminer(String message) {
        attente = null;
        information = message;
    }

    private static JsonObject objet(JsonObject parent, String cle) {
        var champ = parent.get(cle);
        return champ != null && champ.isJsonObject() ? champ.getAsJsonObject() : new JsonObject();
    }
}
