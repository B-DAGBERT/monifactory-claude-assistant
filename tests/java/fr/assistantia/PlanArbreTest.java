package fr.assistantia;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlanArbreTest {
    private static ArbreCollecte.Noeud node(
            String id, int amount, int output, boolean tool, ArbreCollecte.Noeud... children) {
        return new ArbreCollecte.Noeud(
                id.isEmpty() ? Set.of() : Set.of(id), amount, output, tool, List.of(children));
    }

    private static PlanRetrait.Stock<String> stock(String id, int amount, boolean inventory) {
        return new PlanRetrait.Stock<>(id, amount, inventory);
    }

    private static PlanArbre.Resultat plan(
            ArbreCollecte.Noeud root, PlanRetrait.Stock<String>... stocks) {
        return PlanArbre.calculer(root, 1, List.of(stocks), ids -> ids::contains);
    }

    private static int manque(PlanArbre.Resultat p, String id) {
        return p.manquants().stream()
                .filter(i -> i.variantes().contains(id))
                .mapToInt(MemoireCollecte.Ingredient::quantite)
                .sum();
    }

    public static void main(String[] args) {
        var iron = node("iron", 8, 1, false);
        var casing = node("casing", 1, 1, false, iron);
        var machine = node("machine", 1, 1, false, casing);
        var p = plan(machine, stock("casing", 1, true));
        assert manque(p, "machine") == 1 && manque(p, "casing") == 0 && manque(p, "iron") == 0;
        p = plan(machine, stock("casing", 1, false), stock("iron", 64, false));
        assert p.prises().equals(List.of(new PlanRetrait.Prise(0, 1)))
                : "prendre le boîtier, pas le fer qui le refabriquerait";
        assert manque(p, "iron") == 0;
        var partial = node("", 1, 1, false, node("casing", 3, 1, false, iron));
        p =
                plan(
                        partial,
                        stock("casing", 1, true),
                        stock("casing", 1, false),
                        stock("iron", 64, false));
        assert p.prises().equals(List.of(new PlanRetrait.Prise(1, 1), new PlanRetrait.Prise(2, 8)));
        assert manque(p, "casing") == 1 && manque(p, "iron") == 0;
        p = plan(machine, stock("machine", 1, true), stock("iron", 64, false));
        assert p.prises().isEmpty() && p.manquants().isEmpty();
        var twoUses =
                node(
                        "",
                        1,
                        1,
                        false,
                        casing,
                        node("screw", 1, 1, false, node("iron", 2, 1, false)));
        p = plan(twoUses, stock("casing", 1, true));
        assert manque(p, "iron") == 2 : "garder le fer encore nécessaire dans une autre branche";
        var choices =
                node(
                        "",
                        1,
                        1,
                        false,
                        new ArbreCollecte.Noeud(Set.of("oak", "birch"), 1, 1, false, List.of()),
                        node("oak", 1, 1, false));
        assert plan(choices, stock("oak", 1, true), stock("birch", 1, false)).manquants().isEmpty();
        assert manque(plan(node("", 1, 1, false, casing, casing), stock("casing", 1, true)), "iron")
                        == 8
                : "un composant ne couvre pas deux branches";
        var multiOutput = node("casing", 5, 4, false, node("iron", 2, 1, false));
        assert manque(plan(multiOutput, stock("casing", 1, true)), "iron") == 2
                : "arrondi sur le manque, pas sur le total";
        var hammer = node("hammer", 1, 1, true, node("iron", 6, 1, false));
        p = plan(node("", 1, 1, false, hammer, hammer), stock("iron", 64, false));
        assert p.prises().equals(List.of(new PlanRetrait.Prise(0, 6)))
                : "outil partagé fabriqué une seule fois";
        var suivi = new MemoireCollecte(machine, 1);
        assert suivi.actualiser(Map.of()).contains("iron");
        assert !suivi.actualiser(Map.of("casing", 1)).contains("iron");
        assert !suivi.actualiser(Map.of()).contains("iron")
                : "drawer éteint même après consommation du boîtier";
        assert suivi.actualiser(Map.of("machine", 1)).isEmpty();
        assert suivi.actualiser(Map.of()).isEmpty();
        assert new MemoireCollecte(machine, 1)
                .actualiser(Map.of("casing", 1))
                .equals(Set.of("machine"));
        // Le drawer de fer proche ne doit pas gagner sur le boîtier d'un coffre éloigné.
        suivi = new MemoireCollecte(machine, 1);
        suivi.actualiser(Map.of());
        var drawers = List.of(stock("iron", 64, false), stock("casing", 1, false));
        assert suivi.dansCoffres(drawers).equals(List.of(new PlanRetrait.Prise(1, 1)));
        assert suivi.dansCoffres(List.of(stock("iron", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 8)))
                : "un composant seulement repéré ne doit pas être enregistré comme récupéré";
        suivi.actualiser(Map.of("casing", 1));
        assert suivi.dansCoffres(drawers).isEmpty();
        suivi.actualiser(Map.of());
        assert suivi.dansCoffres(drawers).isEmpty() : "pas de retour du drawer après consommation";
        suivi = new MemoireCollecte(partial, 1);
        suivi.actualiser(Map.of());
        assert suivi.dansCoffres(List.of(stock("iron", 64, false), stock("casing", 2, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 8), new PlanRetrait.Prise(1, 2)))
                : "stocks de tous les coffres, matières premières seulement pour le troisième";
        System.out.println(
                "Branches acquises, quantités partielles, stocks partagés, outils et extinction durable : OK");
    }
}
