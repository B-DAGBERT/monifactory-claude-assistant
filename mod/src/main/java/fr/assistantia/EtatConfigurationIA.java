package fr.assistantia;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/** Brouillon local et accusé de réception exact, indépendants de Minecraft et de l'IA. */
public final class EtatConfigurationIA {
    private static final Pattern MODELE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+\\-]*(?:\\[[A-Za-z0-9]+\\])?");
    public static final long DELAI_REPONSE = 45_000;

    public record Valeurs(
            String modelQuestion, String effortQuestion, String modelPlan, String effortPlan) {}

    public record Configuration(
            String moteur,
            String nom,
            String revision,
            boolean modifiable,
            Valeurs valeurs,
            Valeurs defauts,
            List<String> efforts,
            String message) {
        public Configuration {
            efforts = List.copyOf(efforts);
        }
    }

    public record Reponse(long n, String etat, String message) {}

    private Configuration chargee, recente;
    private Valeurs brouillon;
    private final Map<String, Valeurs> brouillonsMoteur = new HashMap<>();
    private long requete, envoye, dernierClic;
    private boolean reponseRecue, expiree;
    private String message = "";

    public Configuration configuration() {
        return recente;
    }

    public Configuration chargee() {
        return chargee;
    }

    public Valeurs brouillon() {
        return brouillon;
    }

    public boolean disponible() {
        return chargee != null && recente != null;
    }

    public boolean attente() {
        return requete > 0 && !reponseRecue && !expiree;
    }

    public boolean perimee() {
        return disponible()
                && (!chargee.revision().equals(recente.revision())
                        || !chargee.moteur().equals(recente.moteur()));
    }

    public void observer(Configuration configuration, Reponse reponse, long maintenant) {
        recente = configuration;
        if (chargee == null && configuration != null) {
            chargee = configuration;
            brouillon = configuration.valeurs();
        }
        if (requete > 0
                && !reponseRecue
                && reponse != null
                && reponse.n() == requete
                && (reponse.etat().equals("appliquee") || reponse.etat().equals("refusee"))) {
            reponseRecue = true;
            expiree = false;
            boolean ok = reponse.etat().equals("appliquee");
            message =
                    (ok ? "Appliqué" : "Refusé")
                            + (reponse.message().isBlank() ? "." : " · " + reponse.message());
            // Le brouillon peut avoir été retouché après expiration : ne jamais l'écraser.
            if (ok && configuration != null && configuration.moteur().equals(chargee.moteur()))
                chargee = configuration;
        }
        if (attente() && maintenant - envoye >= DELAI_REPONSE) {
            expiree = true;
            message = "Aucune confirmation reçue. Vérifie l'assistant avant de réappliquer.";
        }
    }

    public void modifier(Valeurs valeurs) {
        if (attente()) return;
        brouillon = Objects.requireNonNull(valeurs);
        message = "";
    }

    public boolean interaction(long maintenant) {
        if (attente() || maintenant - dernierClic < 650) return false;
        dernierClic = maintenant;
        return true;
    }

    public void defauts(long maintenant) {
        if (!disponible() || !interaction(maintenant)) return;
        brouillon = recente.defauts();
        message = "Défauts dans le brouillon · clique sur Appliquer pour enregistrer.";
    }

    public void actualiser(long maintenant) {
        if (!disponible() || !interaction(maintenant)) return;
        if (!chargee.moteur().equals(recente.moteur())) {
            brouillonsMoteur.put(chargee.moteur(), brouillon);
            brouillon = brouillonsMoteur.getOrDefault(recente.moteur(), recente.valeurs());
            message = "Modèles de " + recente.nom() + " chargés · brouillon précédent conservé.";
        } else message = "Configuration actualisée · ton brouillon est conservé.";
        chargee = recente;
    }

    public String validation() {
        if (!disponible() || brouillon == null)
            return "Configuration indisponible · relance l'assistant à jour.";
        if (!modeleValide(brouillon.modelQuestion()) || !modeleValide(brouillon.modelPlan()))
            return "Nom de modèle requis (128 caractères max., sans espace).";
        if (!recente.efforts().contains(brouillon.effortQuestion())
                || !recente.efforts().contains(brouillon.effortPlan()))
            return "Choisis une réflexion proposée par ce moteur.";
        return "";
    }

    private static boolean modeleValide(String nom) {
        String valeur = nom.trim();
        return valeur.length() <= 128 && MODELE.matcher(valeur).matches();
    }

    public boolean peutAppliquer(boolean assistantActif, long maintenant) {
        return assistantActif
                && disponible()
                && recente.modifiable()
                && !perimee()
                && !attente()
                && maintenant - dernierClic >= 650
                && validation().isEmpty()
                && !normalisees().equals(recente.valeurs());
    }

    public Valeurs normalisees() {
        return new Valeurs(
                brouillon.modelQuestion().trim(),
                brouillon.effortQuestion(),
                brouillon.modelPlan().trim(),
                brouillon.effortPlan());
    }

    public void envoye(long n, long maintenant) {
        requete = n;
        envoye = dernierClic = maintenant;
        reponseRecue = expiree = false;
        message = "Enregistrement…";
    }

    public void echecEnvoi(long maintenant) {
        dernierClic = maintenant;
        message = "Échec de transmission · aucun enregistrement confirmé.";
    }

    public String message(boolean assistantActif) {
        String indisponible =
                !assistantActif
                        ? "Assistant arrêté · ton brouillon est conservé."
                        : !disponible()
                                ? validation()
                                : !recente.modifiable()
                                        ? (recente.message().isBlank()
                                                ? "Réglages momentanément indisponibles."
                                                : recente.message())
                                        : "";
        if (indisponible.isEmpty() && perimee())
            indisponible =
                    !chargee.moteur().equals(recente.moteur())
                            ? "Moteur changé · actualise pour charger ses modèles."
                            : "Configuration modifiée · actualise avant d'appliquer ton brouillon.";
        // Le motif actuel doit rester visible même dans les deux lignes du petit écran.
        String retour = attente() ? "Enregistrement…" : message;
        if (!indisponible.isEmpty()) return indisponible + (retour.isEmpty() ? "" : " · " + retour);
        if (!retour.isEmpty()) return retour;
        if (!validation().isEmpty()) return validation();
        return normalisees().equals(recente.valeurs())
                ? "Configuration actuelle · modifie un modèle ou sa réflexion."
                : "Brouillon modifié · clique sur Appliquer pour enregistrer.";
    }

    public static String nomEffort(String effort) {
        return switch (effort) {
            case "low" -> "Rapide";
            case "medium" -> "Équilibrée";
            case "high" -> "Approfondie";
            case "xhigh" -> "Très approfondie";
            case "max" -> "Maximale";
            default -> effort;
        };
    }
}
