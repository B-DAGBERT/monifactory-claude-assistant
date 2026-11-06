package fr.assistantia;

import java.util.List;
import java.util.Objects;

/** Lecture seule : une réponse appartient à un clic, un monde et une version de projet. */
public final class EtatProduction {
    public static final long DELAI = 45_000;

    public record Contexte(
            String monde, String session, String dimension, String projet, String revision) {
        public boolean valide() {
            return monde != null
                    && monde.matches("m1_[0-9a-f]{64}")
                    && session != null
                    && session.matches("[0-9a-f]{32}")
                    && dimension != null
                    && dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    && projet != null
                    && revision != null
                    && !revision.isBlank();
        }
    }

    public record Alternative(String id, String texte) {}

    public record Resultat(
            long n,
            Contexte contexte,
            String etat,
            String titre,
            List<String> lignes,
            List<String> limites,
            List<Alternative> alternatives) {
        public Resultat {
            lignes = List.copyOf(lignes);
            limites = List.copyOf(limites);
            alternatives = List.copyOf(alternatives);
        }
    }

    private Contexte contexte;
    private Resultat resultat;
    private long n, debut, dernierClic = Long.MIN_VALUE / 2, revision;
    private boolean preparation, disponible, ouvert;
    private String message = "Choisis une vue, puis lance sa lecture.";

    public void ouvrir() {
        ouvert = true;
    }

    public void fermer() {
        ouvert = false;
        invalider("Lecture fermée · aucune réponse tardive ne sera affichée.");
    }

    public void observer(
            Contexte actuel, boolean pret, String indisponible, Resultat reponse, long temps) {
        if (!ouvert) return;
        if (!Objects.equals(contexte, actuel)) {
            boolean ancien = contexte != null;
            contexte = actuel;
            invalider(
                    ancien
                            ? "Monde ou projet changé · relance la lecture."
                            : "Choisis une vue, puis lance sa lecture.");
        }
        disponible = pret && contexte != null && contexte.valide();
        if (!disponible) {
            String motif =
                    indisponible == null || indisponible.isBlank()
                            ? "Assistant ou contexte indisponible."
                            : indisponible;
            if (attente() || resultat != null || !message.equals(motif)) invalider(motif);
            return;
        }
        if (attente() && temps - debut >= DELAI) {
            invalider(
                    "Délai dépassé · aucune réponse confirmée. Relance la lecture si nécessaire.");
            return;
        }
        // Python peut révoquer le rapport déjà consulté si son catalogue n'est plus attesté.
        // Cette révocation ne permet jamais à un autre succès de revenir sans nouveau clic.
        if (resultat != null
                && resultat.etat().equals("pret")
                && reponse != null
                && reponse.etat().equals("erreur")
                && reponse.n() == resultat.n()
                && contexte.equals(reponse.contexte())) {
            resultat = reponse;
            message = "Résultat révoqué · relance la lecture.";
            revision++;
        }
        if (n > 0 && reponse != null && reponse.n() == n && contexte.equals(reponse.contexte())) {
            if (reponse.etat().equals("pret") || reponse.etat().equals("erreur")) {
                resultat = reponse;
                n = 0;
                preparation = false;
                message =
                        reponse.etat().equals("pret")
                                ? "Résultat daté · lecture seule"
                                : "Lecture refusée ou incomplète";
                revision++;
            }
        }
    }

    public boolean commencer(long temps) {
        if (!peutDemander(temps)) return false;
        resultat = null;
        preparation = true;
        debut = dernierClic = temps;
        message = "Préparation du catalogue EMI…";
        revision++;
        return true;
    }

    public void envoye(long numero, long temps) {
        if (!ouvert || !preparation || numero <= 0) return;
        preparation = false;
        n = numero;
        debut = temps;
        message = "Calcul local en cours…";
        revision++;
    }

    public void invalider(String motif) {
        n = 0;
        preparation = false;
        resultat = null;
        message = motif;
        revision++;
    }

    public boolean peutDemander(long temps) {
        return ouvert && disponible && !attente() && temps - dernierClic >= 650;
    }

    public boolean attente() {
        return preparation || n > 0;
    }

    public boolean preparation() {
        return preparation;
    }

    public Contexte contexte() {
        return contexte;
    }

    public Resultat resultat() {
        return resultat;
    }

    public String message() {
        return message;
    }

    public long revision() {
        return revision;
    }
}
