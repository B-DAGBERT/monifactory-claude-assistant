package fr.assistantia;

import java.util.*;

public class ReceptionManuelleTest {
    static PlanRetrait.Stock<String> s(String id, int n) {
        return new PlanRetrait.Stock<>(id, n, true);
    }

    public static void main(String[] args) {
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
        second.annuler();
        assert second.restants().isEmpty();
        assert second.recevoir(List.of(s("plate", 1))).equals(List.of(s("plate", 1)));
    }
}
