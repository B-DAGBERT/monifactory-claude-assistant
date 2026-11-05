package fr.assistantia;

import java.util.List;

public final class ReceptionLotsTest {
    private static PlanRetrait.Stock<String> s(String id, int n) {
        return new PlanRetrait.Stock<>(id, n, true);
    }

    public static void main(String[] args) {
        var envoye = new LotFabrication<>(List.of(s("plate", 64)), String::equals);
        var surplus = new LotFabrication<>(List.of(s("plate", 136)), String::equals);
        var lots =
                List.of(
                        new ReceptionLots.Source<>(
                                envoye,
                                (java.util.function.Predicate<String>) i -> !i.equals("fluid")),
                        new ReceptionLots.Source<>(
                                surplus, (java.util.function.Predicate<String>) i -> true));
        var receptions = new ReceptionManuelle<String>(String::equals);
        receptions.initialiser(List.of());
        var premier = receptions.actualiser(List.of(s("plate", 64)), List.of());
        var resultat = ReceptionLots.repartir(premier, lots);
        assert resultat.change() && envoye.etat() == LotFabrication.Etat.TERMINE;
        assert surplus.totalRecu() == 0 && surplus.restants().get(0).quantite() == 136;
        // Même contenu dans d'autres poches : aucune nouvelle réception.
        var sac = receptions.actualiser(List.of(s("plate", 32), s("plate", 32)), List.of());
        assert !ReceptionLots.repartir(sac, lots).change();
        var suite = receptions.actualiser(List.of(s("plate", 128)), List.of());
        ReceptionLots.repartir(suite, lots);
        assert surplus.totalRecu() == 64 && surplus.etat() != LotFabrication.Etat.TERMINE;
        var fin = receptions.actualiser(List.of(s("plate", 200)), List.of());
        ReceptionLots.repartir(fin, lots);
        assert surplus.etat() == LotFabrication.Etat.TERMINE;
        assert envoye.totalRecu() + surplus.totalRecu() == 200;
        receptions.actualiser(List.of(), List.of());
        var retours = receptions.actualiser(List.of(s("plate", 200)), List.of());
        assert !ReceptionLots.repartir(retours, lots).change();
        // « Tout le lot » agrandit la même ligne, sans perdre la réception du premier envoi.
        var agrandi = new LotFabrication<>(List.of(s("plate", 64)), String::equals);
        agrandi.recevoir(List.of(s("plate", 16)));
        assert agrandi.ajouter(List.of(s("plate", 136)));
        assert agrandi.total() == 200 && agrandi.totalRecu() == 16;
        assert agrandi.restants().get(0).quantite() == 184;
        agrandi.recevoir(List.of(s("plate", 184)));
        assert agrandi.etat() == LotFabrication.Etat.TERMINE;
        var annule = new LotFabrication<>(List.of(s("plate", 20)), String::equals);
        annule.annuler();
        var reste =
                ReceptionLots.repartir(
                        List.of(s("plate", 7)),
                        List.of(new ReceptionLots.Source<>(annule, i -> true)));
        assert reste.restants().equals(List.of(s("plate", 7))) && annule.totalRecu() == 0;
    }
}
