package fr.assistantia;

import java.util.*;

public final class PlanMachinesTest {
    private static PlanMachines.Noeud<String, String> node(
            String item,
            int amount,
            int output,
            String recipe,
            List<PlanMachines.Noeud<String, String>> children) {
        return new PlanMachines.Noeud<>(item::equals, amount, output, false, recipe, children);
    }

    public static void main(String[] args) {
        var ore = node("ore", 3, 1, null, List.of());
        var plate = node("plate", 5, 2, "press", List.of(ore));
        var machine = node("machine", 1, 1, "assemble", List.of(plate));
        var empty = PlanMachines.calculer(machine, 2, List.<PlanRetrait.Stock<String>>of());
        assert empty.equals(
                List.of(
                        new PlanMachines.Operation<>("assemble", 2),
                        new PlanMachines.Operation<>("press", 5)));
        var ready =
                PlanMachines.calculer(
                        machine, 2, List.of(new PlanRetrait.Stock<>("plate", 10, true)));
        assert ready.equals(List.of(new PlanMachines.Operation<>("assemble", 2)))
                : "Un composant disponible coupe sa branche";
        assert PlanMachines.calculer(
                        machine, 2, List.of(new PlanRetrait.Stock<>("machine", 2, true)))
                .isEmpty();
        var partial =
                PlanMachines.calculer(
                        machine, 2, List.of(new PlanRetrait.Stock<>("plate", 7, true)));
        assert partial.get(1).fois() == 2 : "Arrondi au nombre de fabrications";
        var fluid = node("acid", 144, 1000, "reactor", List.of());
        assert PlanMachines.calculer(fluid, 8, List.<PlanRetrait.Stock<String>>of()).get(0).fois()
                == 2;
        var branches =
                node(
                        "root",
                        1,
                        1,
                        null,
                        List.of(
                                node("plate", 3, 1, "press", List.of()),
                                node("plate", 4, 1, "press", List.of())));
        assert PlanMachines.calculer(
                                        branches,
                                        1,
                                        List.of(new PlanRetrait.Stock<>("plate", 5, true)))
                                .get(0)
                                .fois()
                        == 2
                : "Même stock réservé une seule fois";
        var tool =
                new PlanMachines.Noeud<String, String>(
                        "mold"::equals, 1, 1, true, "mold_press", List.of());
        var twoTools = node("root", 1, 1, null, List.of(tool, tool));
        assert PlanMachines.calculer(twoTools, 1, List.of(new PlanRetrait.Stock<>("mold", 1, true)))
                        .isEmpty()
                : "Un moule reste réutilisable dans les branches";
        assert PlanMachines.calculer(twoTools, 1, List.of())
                        .equals(List.of(new PlanMachines.Operation<>("mold_press", 1)))
                : "un outil à fabriquer est partagé lui aussi";
        var deeper = node("other", 1, 1, "other_recipe", List.of(tool));
        var nested = node("root", 1, 1, null, List.of(tool, deeper));
        assert PlanMachines.calculer(nested, 1, List.of()).stream()
                                .filter(o -> o.recette().equals("mold_press"))
                                .mapToInt(PlanMachines.Operation::fois)
                                .sum()
                        == 1
                : "outil partagé à différentes profondeurs";
        var pairTool =
                new PlanMachines.Noeud<String, String>(
                        "mold"::equals, 1, 2, true, "pair_mold", List.of());
        var twoPairTools =
                new PlanMachines.Noeud<String, String>(
                        "mold"::equals, 2, 2, true, "pair_mold", List.of());
        assert PlanMachines.calculer(
                                node("root", 1, 1, null, List.of(pairTool, twoPairTools)),
                                1,
                                List.of())
                        .equals(List.of(new PlanMachines.Operation<>("pair_mold", 1)))
                : "le surplus d'une fabrication d'outils couvre la deuxième branche";
        assert PlanMachines.produit(Long.MAX_VALUE, Long.MAX_VALUE) == 1_000_000;
    }
}
