package fr.assistantia;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArbreCollecteTest {
    private static ArbreCollecte.Noeud node(
            String id, long n, long out, boolean tool, ArbreCollecte.Noeud... children) {
        return new ArbreCollecte.Noeud(
                id.isEmpty() ? Set.of() : Set.of(id), n, out, tool, List.of(children));
    }

    private static int count(List<MemoireCollecte.Ingredient> list, String id) {
        return list.stream()
                .filter(i -> i.variantes().contains(id))
                .mapToInt(MemoireCollecte.Ingredient::quantite)
                .sum();
    }

    public static void main(String[] args) {
        var hammer = node("hammer", 1, 1, true);
        var motor =
                node(
                        "motor",
                        2,
                        2,
                        false,
                        node("wire", 3, 1, false, node("copper", 2, 1, false)),
                        hammer);
        var machine = node("machine", 2, 1, false, motor, node("plate", 3, 1, false, hammer));
        var root =
                node(
                        "",
                        1,
                        1,
                        false,
                        machine,
                        node("cable", 5, 4, false, node("rubber", 1, 1, false)));
        var result = ArbreCollecte.ingredients(root, 1);
        assert count(result, "machine") == 2;
        assert count(result, "motor") == 4;
        assert count(result, "wire") == 6;
        assert count(result, "copper") == 12 : "branches profondes";
        assert count(result, "plate") == 6;
        assert count(result, "cable") == 5;
        assert count(result, "rubber") == 2 : "arrondir au nombre de fabrications";
        assert count(result, "hammer") == 1 : "outil partagé entre branches";
        var many = ArbreCollecte.ingredients(root, 3);
        assert count(many, "machine") == 6 && count(many, "copper") == 36;
        assert count(many, "hammer") == 1;
        assert root.enfants().size() == 2 && machine.quantite() == 2 : "aucune mutation de l'arbre";
        var variant = new ArbreCollecte.Noeud(Set.of("oak", "birch"), 4, 1, false, List.of());
        var memory = new MemoireCollecte(ArbreCollecte.ingredients(variant, 2));
        assert !memory.actualiser(Map.of("oak", 7)).isEmpty();
        assert memory.actualiser(Map.of("oak", 7, "birch", 1)).isEmpty();
        assert memory.actualiser(Map.of()).isEmpty();
        assert count(
                        ArbreCollecte.ingredients(
                                node("ore", Long.MAX_VALUE, 1, false), Long.MAX_VALUE),
                        "ore")
                == 1_000_000;
        var deep = node("leaf", 1, 1, false);
        for (int i = 0; i < 130; i++) deep = node("", 1, 1, false, deep);
        try {
            ArbreCollecte.ingredients(deep, 1);
            throw new AssertionError("profondeur non bornée");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println(
                "Arbre complet, intermédiaires, lots, variantes, outils et limites : OK");
    }
}
