package fr.assistantia;

import java.util.*;

public class ReceptionManuelleTest {
    static PlanRetrait.Stock<String> s(String id, int n) {
        return new PlanRetrait.Stock<>(id, n, true);
    }

    public static void main(String[] args) {
        var contexte = new ReceptionManuelle<String>(String::equals);
        contexte.initialiser(List.of());
        assert contexte.actualiser(List.of(s("plate", 1)), List.of())
                .equals(List.of(s("plate", 1)));
        contexte.actualiser(List.of(), List.of());
        contexte.vider();
        contexte.initialiser(List.of());
        assert contexte.actualiser(List.of(s("plate", 1)), List.of()).equals(List.of(s("plate", 1)))
                : "un nouveau suivi ne reprend pas les dettes d'un ancien monde";

        var reception = new ReceptionManuelle<String>(String::equals);
        reception.initialiser(List.of(s("plate", 4)));
        var lot = new LotFabrication<>(List.of(s("plate", 6)), String::equals);
        lot.recevoir(reception.actualiser(List.of(s("plate", 4)), List.of()));
        assert lot.totalRecu() == 0 : "le stock antérieur ne valide pas la déclaration";
        // Même stock, redistribué entre plusieurs poches et sacs.
        lot.recevoir(reception.actualiser(List.of(s("plate", 1), s("plate", 3)), List.of()));
        assert lot.totalRecu() == 0 : "déplacer dans le sac n'est pas une nouvelle fabrication";
        lot.recevoir(reception.actualiser(List.of(s("plate", 6)), List.of()));
        assert lot.totalRecu() == 2 && lot.restants().equals(List.of(s("plate", 4)));
        lot.recevoir(reception.actualiser(List.of(s("plate", 9)), List.of(s("plate", 3))));
        assert lot.totalRecu() == 2
                : "une réception automatique ne crédite pas aussi un lot manuel";
        var second = new LotFabrication<>(List.of(s("plate", 2)), String::equals);
        var restants = lot.recevoir(reception.actualiser(List.of(s("plate", 14)), List.of()));
        second.recevoir(restants);
        assert lot.etat() == LotFabrication.Etat.TERMINE && second.totalRecu() == 1;
        second.recevoir(reception.actualiser(List.of(s("plate", 14)), List.of()));
        assert second.totalRecu() == 1 : "une réception n'est appliquée qu'une fois";
        assert reception
                        .soustraire(List.of(s("plate", 2), s("plate", 3)), List.of(s("plate", 1)))
                        .equals(List.of(s("plate", 4)))
                : "preuves réparties dans plusieurs piles";
        // Le gain peut précéder le paquet de sortie : le lot automatique reste prioritaire.
        reception.initialiser(List.of());
        var manuel = new LotFabrication<>(List.of(s("plate", 2)), String::equals);
        manuel.recevoir(
                reception.actualiser(List.of(s("plate", 2)), List.of(), List.of(s("plate", 2))));
        assert manuel.totalRecu() == 0 : "réception automatique encore non confirmée";
        manuel.recevoir(
                reception.actualiser(List.of(s("plate", 2)), List.of(s("plate", 2)), List.of()));
        assert manuel.totalRecu() == 0 : "preuve tardive sans double crédit manuel";
        manuel.recevoir(reception.actualiser(List.of(s("plate", 4)), List.of()));
        assert manuel.totalRecu() == 2 : "la réception manuelle suivante reste disponible";
        reception.initialiser(List.of());
        assert reception
                        .actualiser(
                                List.of(s("plate", 5)),
                                List.of(s("plate", 2)),
                                List.of(s("plate", 2)))
                        .equals(List.of(s("plate", 3)))
                : "les preuves et réservations d'un même lot ne s'additionnent pas";
        var reprises = new ReceptionManuelle<String>(String::equals);
        reprises.initialiser(List.of());
        var suiviReprises = new LotFabrication<>(List.of(s("plate", 2)), String::equals);
        suiviReprises.recevoir(reprises.actualiser(List.of(s("plate", 1)), List.of()));
        suiviReprises.recevoir(reprises.actualiser(List.of(), List.of()));
        suiviReprises.recevoir(reprises.actualiser(List.of(s("plate", 1)), List.of()));
        assert suiviReprises.totalRecu() == 1
                : "ranger puis reprendre le même objet ne termine pas un lot manuel de deux";
        // Deux fabrications successives sont reconnues même si elles ne sont jamais détenues
        // ensemble.
        var successifs = new ReceptionManuelle<String>(String::equals);
        successifs.initialiser(List.of());
        var trois = new LotFabrication<>(List.of(s("plate", 3)), String::equals);
        for (int i = 0; i < 3; i++) {
            trois.recevoir(
                    successifs.actualiser(
                            List.of(s("plate", 1)), List.of(), List.of(), List.of(s("plate", 1))));
            assert trois.totalRecu() == i + 1 : "chaque nouveau craft confirmé est reçu";
            trois.recevoir(successifs.actualiser(List.of(), List.of()));
        }
        assert trois.etat() == LotFabrication.Etat.TERMINE;
        // La preuve de sortie peut suivre le gain et doit conserver la dette de l'ancien objet.
        var tardifs = new ReceptionManuelle<String>(String::equals);
        tardifs.initialiser(List.of());
        var tardif = new LotFabrication<>(List.of(s("plate", 3)), String::equals);
        tardif.recevoir(tardifs.actualiser(List.of(s("plate", 1)), List.of()));
        tardif.recevoir(tardifs.actualiser(List.of(), List.of()));
        tardif.recevoir(tardifs.actualiser(List.of(s("plate", 1)), List.of()));
        assert tardif.totalRecu() == 1;
        tardif.recevoir(
                tardifs.actualiser(
                        List.of(s("plate", 1)), List.of(), List.of(), List.of(s("plate", 1))));
        assert tardif.totalRecu() == 2;
        tardif.recevoir(
                tardifs.actualiser(
                        List.of(s("plate", 1)), List.of(), List.of(), List.of(s("plate", 1))));
        assert tardif.totalRecu() == 2 : "la confirmation est consommée une seule fois";
        tardif.recevoir(tardifs.actualiser(List.of(s("plate", 2)), List.of()));
        assert tardif.totalRecu() == 2
                : "reprendre l'ancien objet après preuve tardive ne crédite rien";
        // Ajouter une nouvelle déclaration ne supprime pas les dettes des objets déjà rangés.
        tardif.recevoir(tardifs.actualiser(List.of(), List.of()));
        tardifs.initialiser(List.of());
        var suivant = new LotFabrication<>(List.of(s("plate", 2)), String::equals);
        suivant.recevoir(tardifs.actualiser(List.of(s("plate", 2)), List.of()));
        assert suivant.totalRecu() == 0 : "nouveau lot, pas nouvel historique des mêmes objets";
        // Plusieurs preuves tardives consomment d'abord les gains déjà comptés.
        var partiels = new ReceptionManuelle<String>(String::equals);
        partiels.initialiser(List.of());
        partiels.actualiser(List.of(s("plate", 1)), List.of());
        partiels.actualiser(List.of(), List.of());
        assert partiels.actualiser(List.of(s("plate", 2)), List.of())
                .equals(List.of(s("plate", 1)));
        assert partiels.actualiser(
                        List.of(s("plate", 2)), List.of(), List.of(), List.of(s("plate", 1)))
                .isEmpty();
        assert partiels.actualiser(
                        List.of(s("plate", 2)), List.of(), List.of(), List.of(s("plate", 1)))
                .equals(List.of(s("plate", 1)));
        assert partiels.actualiser(List.of(s("plate", 3)), List.of()).isEmpty();
        var expire = new ReceptionManuelle<String>(String::equals);
        expire.initialiser(List.of(s("plate", 1)));
        expire.actualiser(List.of(), List.of());
        expire.actualiser(List.of(s("plate", 1)), List.of());
        for (int i = 0; i < 22; i++) expire.actualiser(List.of(s("plate", 1)), List.of());
        assert expire.actualiser(
                                List.of(s("plate", 1)),
                                List.of(),
                                List.of(),
                                List.of(s("plate", 1)))
                        .isEmpty()
                : "un ancien retour ne reçoit pas une confirmation sans rapport";
        second.annuler();
        assert second.restants().isEmpty();
        assert second.recevoir(List.of(s("plate", 1))).equals(List.of(s("plate", 1)));
    }
}
