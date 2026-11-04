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

    private ArbreCollecte.Noeud arbre;
    private long lots, version;
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

    public MemoireCollecte(ArbreCollecte.Noeud arbre, long lots) {
        this(ArbreCollecte.ingredients(arbre, lots));
        this.arbre = arbre;
        this.lots = lots;
        dernierInventaire = null; // Calculer même avec un inventaire vide au premier appel.
    }

    public long version() {
        return version;
    }

    public Set<String> tous() {
        return tous;
    }

    /** Réserves connues : composants d'abord, sans créditer un objet simplement repéré. */
    public List<PlanRetrait.Prise> dansCoffres(List<PlanRetrait.Stock<String>> coffres) {
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        observes.forEach(
                (id, n) -> {
                    if (n > 0) stocks.add(new PlanRetrait.Stock<>(id, n, true));
                });
        int offset = stocks.size();
        stocks.addAll(coffres);
        var prises =
                arbre == null
                        ? PlanRetrait.calculer(stocks, besoins).prises()
                        : PlanArbre.calculer(arbre, lots, stocks, ids -> ids::contains).prises();
        return prises.stream()
                .map(p -> new PlanRetrait.Prise(p.index() - offset, p.quantite()))
                .toList();
    }

    /**
     * Retrait borné par la sélection globale du scan, jamais par un nouvel arbre local au coffre.
     */
    public static List<PlanRetrait.Prise> retraitSelection(
            List<PlanRetrait.Stock<String>> stocks, Map<String, Integer> selection) {
        var besoins =
                selection.entrySet().stream()
                        .map(e -> new PlanRetrait.Besoin<String>(e.getKey()::equals, e.getValue()))
                        .toList();
        return PlanRetrait.calculer(stocks, besoins).prises();
    }

    public Set<String> actualiser(Map<String, Integer> inventaire) {
        if (inventaire.equals(dernierInventaire)) return restants;
        dernierInventaire = Map.copyOf(inventaire);
        version++;
        // Crédit conservé pour ne pas réclamer à nouveau un objet déjà collecté puis consommé.
        for (String id : tous)
            observes.merge(id, Math.max(0, inventaire.getOrDefault(id, 0)), Math::max);
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        observes.forEach(
                (id, n) -> {
                    if (n > 0) stocks.add(new PlanRetrait.Stock<>(id, n, true));
                });
        if (arbre != null) {
            var allocation = PlanArbre.calculer(arbre, lots, stocks, ids -> ids::contains);
            Set<String> manquants = new LinkedHashSet<>();
            for (var ingredient : allocation.manquants()) manquants.addAll(ingredient.variantes());
            // Une branche validée ne revient pas après consommation des composants.
            manquants.retainAll(restants);
            restants = Set.copyOf(manquants);
            return restants;
        }
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
