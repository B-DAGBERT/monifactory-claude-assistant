package fr.assistantia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Une recherche = une liste de courses. Les ingrédients validés ne sont jamais réactivés. */
public final class MemoireCollecte {
    public record Ingredient(Set<String> variantes, int quantite) {
        public Ingredient {
            variantes = Set.copyOf(variantes);
        }
    }

    private final List<Ingredient> ingredients;
    private final List<PlanRetrait.Besoin<String>> besoins;
    private final Map<String, Integer> observes = new LinkedHashMap<>();
    private final boolean[] valides;
    private final Set<String> tous;
    private Map<String, Integer> dernierInventaire = Map.of();
    private Set<String> restants;

    public MemoireCollecte(List<Ingredient> liste) {
        // Un même ingrédient répété dans plusieurs cases requiert la somme des quantités.
        Map<Set<String>, Integer> groupes = new LinkedHashMap<>();
        for (Ingredient i : liste)
            if (!i.variantes().isEmpty() && i.quantite() > 0)
                groupes.merge(
                        i.variantes(),
                        i.quantite(),
                        (a, b) -> (int) Math.min(1_000_000L, (long) a + b));
        ingredients =
                groupes.entrySet().stream()
                        .map(e -> new Ingredient(e.getKey(), e.getValue()))
                        .toList();
        besoins =
                ingredients.stream()
                        .map(
                                i ->
                                        new PlanRetrait.Besoin<String>(
                                                i.variantes()::contains, i.quantite()))
                        .toList();
        valides = new boolean[ingredients.size()];
        Set<String> ids = new LinkedHashSet<>();
        for (Ingredient i : ingredients) ids.addAll(i.variantes());
        tous = Set.copyOf(ids);
        restants = tous;
    }

    public Set<String> tous() {
        return tous;
    }

    public Set<String> actualiser(Map<String, Integer> inventaire) {
        if (inventaire.equals(dernierInventaire)) return restants;
        dernierInventaire = Map.copyOf(inventaire);
        // Crédit conservé pour ne pas réclamer à nouveau un objet déjà collecté puis consommé.
        for (String id : tous)
            observes.merge(id, Math.max(0, inventaire.getOrDefault(id, 0)), Math::max);
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        observes.forEach(
                (id, n) -> {
                    if (n > 0) stocks.add(new PlanRetrait.Stock<>(id, n, true));
                });
        var allocation = PlanRetrait.calculer(stocks, besoins);
        Set<String> manquants = new LinkedHashSet<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            valides[i] |= allocation.fournis().get(i) >= ingredient.quantite();
            if (!valides[i]) manquants.addAll(ingredient.variantes());
        }
        restants = Set.copyOf(manquants);
        return restants;
    }
}
