package fr.assistantia;

import java.util.List;

public class EtatProductionTest {
    static EtatProduction.Contexte contexte(String monde, String session, String dimension) {
        return new EtatProduction.Contexte(
                "m1_" + monde.repeat(64), session.repeat(32), "minecraft:" + dimension);
    }

    static EtatProduction.Resultat resultat(long n, EtatProduction.Contexte contexte) {
        return new EtatProduction.Resultat(
                n,
                contexte,
                "pret",
                "Plan calculé",
                List.of("Manque un lingot"),
                List.of("Stock partiel"),
                List.of());
    }

    public static void main(String[] args) {
        var a = contexte("a", "a", "overworld");
        var b = contexte("a", "b", "overworld");
        var etat = new EtatProduction();
        etat.ouvrir();
        etat.observer(a, true, "", resultat(1, a), 1000);
        assert etat.resultat() == null : "No result without an explicit native click";
        assert etat.commencer(1000);
        assert !etat.commencer(1001) : "Double click must not submit twice";
        etat.envoye(7, 1002);
        etat.observer(a, true, "", resultat(8, a), 1003);
        assert etat.attente() && etat.resultat() == null : "Wrong receipt accepted";
        etat.observer(a, true, "", resultat(7, b), 1004);
        assert etat.attente() && etat.resultat() == null : "Foreign session accepted";
        etat.observer(a, true, "", resultat(7, a), 1005);
        assert !etat.attente() && etat.resultat() != null;
        var revoke =
                new EtatProduction.Resultat(
                        7, a, "erreur", "Catalogue indisponible", List.of(), List.of(), List.of());
        etat.observer(a, true, "", revoke, 1006);
        assert etat.resultat().etat().equals("erreur")
                : "A displayed result must honor its exact revocation";
        etat.observer(a, true, "", resultat(7, a), 1007);
        assert etat.resultat().etat().equals("erreur")
                : "A stale positive receipt revived a revoked result";
        etat.observer(a, true, "", resultat(7, a), 2000);
        assert etat.commencer(2000);
        etat.envoye(9, 2001);
        etat.fermer();
        etat.observer(a, true, "", resultat(9, a), 2002);
        etat.ouvrir();
        etat.observer(a, true, "", resultat(9, a), 3000);
        assert etat.resultat() == null && !etat.attente()
                : "Late result revived a closed operation";
        assert etat.commencer(3000);
        etat.envoye(10, 3001);
        etat.observer(a, true, "", resultat(10, a), 3001 + EtatProduction.DELAI);
        assert etat.resultat() == null && !etat.attente() : "Expired result accepted";
        assert etat.message().contains("Délai");
        for (var suivant :
                List.of(b, contexte("c", "a", "overworld"), contexte("a", "a", "the_nether"))) {
            var local = new EtatProduction();
            local.ouvrir();
            local.observer(a, true, "", null, 1000);
            assert local.commencer(1000);
            local.envoye(12, 1001);
            local.observer(suivant, true, "", resultat(12, a), 1002);
            assert local.resultat() == null && !local.attente();
        }
        etat.observer(a, false, "Assistant arrêté", null, 100_000);
        assert !etat.peutDemander(100_001) && etat.message().equals("Assistant arrêté");
        assert !new EtatProduction.Contexte("legacy", "", "").valide();
        assert a.valide();
    }
}
