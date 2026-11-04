package fr.assistantia;

import java.util.*;

public class LotFabricationTest {
    static PlanRetrait.Stock<String> s(String id, int n) {
        return new PlanRetrait.Stock<>(id, n, true);
    }

    public static void main(String[] args) {
        var a = new LotFabrication<>(List.of(s("plate", 4)), String::equals);
        var b = new LotFabrication<>(List.of(s("plate", 2)), String::equals);
        assert a.etat() == LotFabrication.Etat.EN_MACHINE;
        a.disponible(true);
        assert a.etat() == LotFabrication.Etat.A_RECUPERER;
        var reste = a.recevoir(List.of(s("plate", 3)));
        assert reste.isEmpty() && a.totalRecu() == 3 && a.restants().get(0).quantite() == 1;
        reste = b.recevoir(a.recevoir(List.of(s("plate", 2))));
        assert a.etat() == LotFabrication.Etat.TERMINE && b.totalRecu() == 1 && reste.isEmpty();
        assert !a.annulable();
        b.annuler();
        assert b.restants().isEmpty() && !b.annulable() && b.totalRecu() == 1;
        assert b.recevoir(List.of(s("plate", 1))).size() == 1
                : "annuler ne mange pas une réception ultérieure";
        var multi = new LotFabrication<>(List.of(s("dust", 2), s("plate", 1)), String::equals);
        multi.recevoir(List.of(s("dust", 2)));
        assert multi.etat() != LotFabrication.Etat.TERMINE;
        multi.recevoir(List.of(s("plate", 1)));
        assert multi.etat() == LotFabrication.Etat.TERMINE;
        var sorties = new ReceptionSortie<String>(String::equals);
        assert sorties.actualiser(List.of(), List.of(s("acid", 1000))).isEmpty();
        assert sorties.actualiser(List.of(), List.of(s("acid", 500))).isEmpty();
        assert sorties.actualiser(List.of(s("acid", 500)), List.of(s("acid", 500)))
                        .equals(List.of(s("acid", 500)))
                : "cuve et contenant synchronisés séparément";
        assert sorties.actualiser(List.of(s("acid", 500)), List.of(s("acid", 500))).isEmpty();
        sorties.actualiser(List.of(), List.of());
        for (int i = 0; i < 22; i++) sorties.actualiser(List.of(), List.of());
        assert sorties.actualiser(List.of(s("acid", 500)), List.of()).isEmpty()
                : "une sortie ancienne ne crédite pas un retrait sans rapport";
    }
}
