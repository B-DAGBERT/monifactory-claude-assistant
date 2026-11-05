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
        // Une fabrication manuelle réserve un composant, pas ses matières ni ses outils.
        var manuel = new MemoireCollecte(machine, 1);
        manuel.actualiser(Map.of());
        manuel.reserver(Map.of("casing", 1));
        assert manuel.actualiser(Map.of()).equals(Set.of("machine", "casing"));
        assert manuel.dansCoffres(List.of(stock("iron", 64, false), stock("casing", 1, false)))
                        .equals(List.of(new PlanRetrait.Prise(1, 1)))
                : "résultat réservé toujours récupérable, aucune matière à reprendre";
        manuel.reserver(Map.of());
        assert manuel.actualiser(Map.of()).contains("iron")
                : "annulation : aucune acquisition fictive persistante";
        // Deux branches utilisent le même fer. Celui envoyé au boîtier ne couvre plus les vis.
        manuel = new MemoireCollecte(twoUses, 1);
        manuel.actualiser(Map.of("iron", 8));
        manuel.actualiser(Map.of());
        manuel.reserver(Map.of("casing", 1));
        manuel.actualiser(Map.of());
        assert manuel.dansCoffres(List.of(stock("iron", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 2)))
                : "matière réservée une seule fois";
        // Récupération partielle : stock réel + reste en machine, jamais double quantité.
        var trois = new MemoireCollecte(partial, 1);
        trois.actualiser(Map.of());
        trois.reserver(Map.of("casing", 2));
        trois.actualiser(Map.of());
        assert trois.dansCoffres(List.of(stock("iron", 64, false)))
                .equals(List.of(new PlanRetrait.Prise(0, 8)));
        trois.reserver(Map.of("casing", 1));
        trois.actualiser(Map.of("casing", 1));
        assert trois.dansCoffres(List.of(stock("iron", 64, false)))
                .equals(List.of(new PlanRetrait.Prise(0, 8)));
        // Changer une recette garde les acquisitions pertinentes et les lots encore présents.
        var change =
                new MemoireCollecte(
                        node(
                                "",
                                1,
                                1,
                                false,
                                node("casing", 4, 1, false, node("copper", 2, 1, false))),
                        1);
        change.reprendre(trois);
        change.reserver(Map.of("casing", 1));
        change.actualiser(Map.of());
        assert change.dansCoffres(List.of(stock("iron", 64, false), stock("copper", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(1, 4)))
                : "recette changée, nouvelles matières seulement";
        change.reserver(Map.of());
        change.actualiser(Map.of());
        assert change.dansCoffres(List.of(stock("copper", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 6)))
                : "la réservation n'est pas devenue un crédit";
        // Lot complet masqué dans EMI : seuls ses résultats restent recherchables.
        var plaques =
                new MemoireCollecte(node("plate", 200, 1, false, node("iron", 1, 1, false)), 1);
        plaques.reserver(Map.of("plate", 200));
        assert plaques.actualiser(Map.of()).equals(Set.of("plate"));
        assert plaques.dansCoffres(List.of(stock("iron", 1000, false))).isEmpty();
        var coffre = List.of(stock("plate", 64, false), stock("plate", 200, false));
        assert plaques.dansCoffres(coffre).stream().mapToInt(PlanRetrait.Prise::quantite).sum()
                == 200;
        // Réception partielle réelle : prendre seulement la suite et ne pas ressusciter le fer.
        plaques.reserver(Map.of("plate", 136));
        plaques.actualiser(Map.of("plate", 64));
        assert plaques.dansCoffres(coffre).stream().mapToInt(PlanRetrait.Prise::quantite).sum()
                == 136;
        plaques.reserver(Map.of());
        assert plaques.actualiser(Map.of("plate", 200)).isEmpty();
        assert plaques.dansCoffres(coffre).isEmpty();
        // Seulement 64 en machine sur 128 : le composant fini reste prioritaire dans tous les
        // coffres.
        var moitie =
                new MemoireCollecte(node("plate", 128, 1, false, node("iron", 1, 1, false)), 1);
        moitie.reserver(Map.of("plate", 64));
        moitie.actualiser(Map.of());
        var morceaux =
                moitie.dansCoffres(List.of(stock("plate", 64, false), stock("plate", 64, false)));
        assert morceaux.equals(List.of(new PlanRetrait.Prise(0, 64), new PlanRetrait.Prise(1, 64)))
                : "chaque stock alloué une seule fois, plusieurs coffres possibles";

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
        // Le retrait doit utiliser exactement l'allocation globale, même ouvert dans le drawer de
        // fer.
        var global = List.of(stock("iron", 64, false), stock("casing", 2, false));
        var selection = new java.util.HashMap<String, Integer>();
        for (var prise : suivi.dansCoffres(global))
            selection.merge(global.get(prise.index()).objet(), prise.quantite(), Integer::sum);
        assert MemoireCollecte.retraitSelection(List.of(stock("iron", 64, false)), selection)
                        .equals(List.of(new PlanRetrait.Prise(0, 8)))
                : "8 fers du scan, pas 24 du recalcul local";
        assert MemoireCollecte.retraitSelection(List.of(stock("iron", 64, false)), Map.of())
                .isEmpty();
        var crafted = node("part", 1, 1, false, hammer, node("iron", 2, 1, false));
        suivi = new MemoireCollecte(node("machine", 1, 1, false, crafted), 1);
        suivi.actualiser(Map.of("part", 1));
        suivi.actualiser(Map.of());
        assert suivi.dansCoffres(List.of(stock("hammer", 1, false), stock("iron", 64, false)))
                        .isEmpty()
                : "composant acquis : aucun outil ni matière pour le refaire";
        var plates =
                node("machine", 1, 1, false, node("plate", 4, 1, false, node("iron", 2, 1, false)));
        suivi = new MemoireCollecte(plates, 1);
        suivi.actualiser(Map.of());
        suivi.actualiser(Map.of("iron", 4));
        assert suivi.actualiser(Map.of("plate", 2)).contains("iron");
        assert suivi.dansCoffres(List.of(stock("iron", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 4)))
                : "4 lingots transformés ne sont plus 4 lingots disponibles";
        suivi.actualiser(Map.of("plate", 2, "iron", 4));
        assert !suivi.actualiser(Map.of("plate", 4)).contains("iron");
        assert suivi.dansCoffres(List.of(stock("iron", 64, false))).isEmpty();
        suivi = new MemoireCollecte(plates, 1);
        suivi.actualiser(Map.of("plate", 2));
        suivi.actualiser(Map.of());
        suivi.actualiser(Map.of("plate", 2));
        assert suivi.dansCoffres(List.of(stock("iron", 64, false))).get(0).quantite() == 4
                : "reprendre les deux mêmes plaques ne valide pas les quatre";
        suivi.actualiser(Map.of());
        assert !suivi.actualiser(Map.of("plate", 2), Map.of("plate", 2)).contains("iron")
                : "deux nouvelles plaques confirmées plus les deux rangées";
        // Deux transformations entre deux lectures : les plaques produites puis consommées
        // ne restent pas disponibles en plus de l'engrenage obtenu.
        var gears =
                node(
                        "machine",
                        1,
                        1,
                        false,
                        node(
                                "gear",
                                2,
                                1,
                                false,
                                node("plate", 2, 1, false, node("iron", 2, 1, false))));
        suivi = new MemoireCollecte(gears, 1);
        suivi.actualiser(Map.of("iron", 4));
        suivi.actualiser(Map.of("gear", 1), Map.of("plate", 2, "gear", 1));
        assert suivi.dansCoffres(List.of(stock("iron", 64, false)))
                        .equals(List.of(new PlanRetrait.Prise(0, 4)))
                : "chaîne rapide : matières et intermédiaires consommés sans double crédit";
        suivi.actualiser(Map.of("gear", 2), Map.of("plate", 2, "gear", 1));
        assert suivi.dansCoffres(List.of(stock("iron", 64, false))).isEmpty();
        System.out.println(
                "Branches acquises, quantités partielles, stocks partagés, outils et extinction durable : OK");
    }
}
