package fr.assistantia;

import java.util.List;
import fr.assistantia.EtatConfigurationIA.*;

public final class EtatConfigurationIATest {
    static final Valeurs BASE = new Valeurs("gpt-fast", "low", "gpt-plan", "medium");
    static final Valeurs AUTRE = new Valeurs("gpt-next", "high", "gpt-plan", "max");

    static Configuration config(String revision, boolean modifiable, Valeurs valeurs) {
        return new Configuration(
                "codex",
                "Codex",
                revision,
                modifiable,
                valeurs,
                BASE,
                List.of("low", "medium", "high", "xhigh", "max"),
                "Assistant occupé");
    }

    static EtatConfigurationIA etat() {
        var e = new EtatConfigurationIA();
        e.observer(config("a", true, BASE), null, 1000);
        return e;
    }

    public static void main(String[] args) {
        var e = etat();
        assert e.brouillon().equals(BASE);
        assert !e.peutAppliquer(true, 1000) : "configuration inchangée";
        e.modifier(AUTRE);
        assert e.peutAppliquer(true, 1000);
        assert !e.peutAppliquer(false, 1000) : "assistant arrêté";
        e.envoye(1001, 1001);
        e.modifier(BASE);
        assert e.brouillon().equals(AUTRE) : "édition bloquée pendant enregistrement";
        assert e.attente() && !e.peutAppliquer(true, 2000);
        e.observer(config("a", true, BASE), new Reponse(1000, "appliquee", ""), 2000);
        assert e.attente() : "ancien accusé ignoré";
        e.observer(config("b", true, AUTRE), new Reponse(1001, "appliquee", "ok"), 2100);
        assert !e.attente() && !e.perimee() && e.message(true).startsWith("Appliqué");
        assert !e.peutAppliquer(true, 3000);
        e.observer(config("b", true, AUTRE), new Reponse(1001, "refusee", "ancien"), 3100);
        assert e.message(true).startsWith("Appliqué") : "accusé déjà consommé";

        e.observer(config("c", true, AUTRE), null, 3200);
        assert e.message(true).startsWith("Configuration modifiée")
                        && e.message(true).contains("Appliqué")
                : "une ancienne réussite ne masque pas une nouvelle version";
        assert e.message(false).startsWith("Assistant arrêté")
                        && e.message(false).contains("Appliqué")
                : "l'arrêt actuel est visible avant l'ancien accusé";
        e.observer(config("c", false, AUTRE), null, 3300);
        assert e.message(true).startsWith("Assistant occupé")
                : "l'indisponibilité actuelle reste prioritaire";
        e.observer(null, null, 3400);
        assert e.message(true).startsWith("Configuration indisponible");

        e = etat();
        e.modifier(AUTRE);
        e.envoye(2001, 2001);
        e.observer(config("b", true, BASE), new Reponse(2001, "refusee", "version périmée"), 2100);
        assert e.message(true).contains("Refusé") && e.brouillon().equals(AUTRE) && e.perimee();
        assert !e.peutAppliquer(true, 3000);
        e.actualiser(3000);
        assert !e.perimee() && e.brouillon().equals(AUTRE) : "rebase sans écrasement";
        assert !e.peutAppliquer(true, 3100) : "anti double clic";
        assert e.peutAppliquer(true, 3650);
        e.observer(config("b", false, BASE), null, 4000);
        assert !e.peutAppliquer(true, 4000);
        e = etat();
        e.observer(config("a", false, BASE), null, 1001);
        assert e.message(true).equals("Assistant occupé");

        e = etat();
        e.modifier(AUTRE);
        e.envoye(3001, 3001);
        e.observer(config("a", true, BASE), null, 48000);
        assert e.attente();
        e.observer(config("a", true, BASE), null, 48001);
        assert !e.attente() && e.message(true).contains("Aucune confirmation");
        e.modifier(new Valeurs("gpt-newer", "low", "gpt-plan", "medium"));
        e.observer(config("b", true, AUTRE), new Reponse(3001, "appliquee", ""), 48002);
        assert e.brouillon().modelQuestion().equals("gpt-newer")
                : "accusé retardé conserve nouvel essai";

        e = etat();
        e.modifier(AUTRE);
        e.defauts(2000);
        assert e.brouillon().equals(BASE) && !e.attente()
                : "défauts ne soumet aucun enregistrement";
        e.modifier(AUTRE);
        e.echecEnvoi(3000);
        assert !e.attente() && e.brouillon().equals(AUTRE) && e.message(true).startsWith("Échec");
        for (String model :
                List.of(
                        "",
                        " ",
                        "-model",
                        "name with space",
                        "name\nother",
                        "bad;command",
                        "x".repeat(129),
                        "claude[]",
                        "claude[1 m]")) {
            e = etat();
            e.modifier(new Valeurs(model, "low", "model", "high"));
            assert !e.validation().isEmpty() : model;
        }
        for (String model :
                List.of(
                        "model",
                        "openai/gpt-5.1",
                        "claude-sonnet[1m]",
                        "o1+test",
                        "a".repeat(128))) {
            e = etat();
            e.modifier(new Valeurs(model, "low", "model", "high"));
            assert e.validation().isEmpty() : model;
        }
        e = etat();
        e.modifier(new Valeurs("model", "invalid", "model", "high"));
        assert !e.validation().isEmpty();
        e = etat();
        e.modifier(AUTRE);
        e.observer(
                new Configuration(
                        "claude",
                        "Claude",
                        "a",
                        true,
                        BASE,
                        BASE,
                        List.of("low", "medium", "high", "max"),
                        ""),
                null,
                2000);
        assert e.perimee() && !e.peutAppliquer(true, 3000) : "moteur modifié à révision identique";
        e.actualiser(3000);
        assert e.brouillon().equals(BASE) : "charger les modèles du nouveau moteur";
        e.observer(config("b", true, BASE), null, 4000);
        e.actualiser(4000);
        assert e.brouillon().equals(AUTRE) : "retrouver le brouillon du moteur précédent";
        e.observer(null, null, 5000);
        assert !e.peutAppliquer(true, 5000) && e.brouillon().equals(AUTRE)
                : "trame manquante conserve le brouillon";
    }
}
