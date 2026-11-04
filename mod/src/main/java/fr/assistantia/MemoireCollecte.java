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
    private Map<String, Integer> reserves = Map.of();
    private boolean reservationModifiee;

    /** Recalcul d'un arbre modifié : conserver les acquisitions encore pertinentes. */
    public void reprendre(MemoireCollecte ancienne) {
        if (ancienne == null) return;
        ancienne.observes.forEach(
                (id, n) -> {
                    if (tous.contains(id)) observes.put(id, n);
                });
        dernierInventaire = null;
    }

    public void reserver(Map<String, Integer> quantites) {
        Map<String, Integer> nouvelles = new LinkedHashMap<>();
        quantites.forEach(
                (id, n) -> {
                    if (tous.contains(id) && n > 0) nouvelles.put(id, Math.min(n, 1_000_000));
                });
        if (!reserves.equals(nouvelles)) {
            reserves = Map.copyOf(nouvelles);
            reservationModifiee = true;
        }
    }

    private List<PlanRetrait.Stock<String>> stocksPlanifies() {
        var credits = new LinkedHashMap<>(observes);
        if (arbre != null && !reserves.isEmpty())
            deduireReservations(arbre, credits, new LinkedHashSet<>());
        reserves.forEach(
                (id, n) ->
                        credits.merge(id, n, (a, b) -> (int) Math.min(1_000_000L, (long) a + b)));
        return credits.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new PlanRetrait.Stock<>(e.getKey(), e.getValue(), true))
                .toList();
    }

    private void deduireReservations(
            ArbreCollecte.Noeud node, Map<String, Integer> credits, Set<Set<String>> visites) {
        for (var child : node.enfants()) deduireReservations(child, credits, visites);
        if (node.variantes().isEmpty() || !visites.add(node.variantes())) return;
        long n = node.variantes().stream().mapToLong(id -> reserves.getOrDefault(id, 0)).sum();
        long production = Math.max(1, node.production());
        long fois = (n + production - 1) / production;
        // Les matières déjà envoyées ne peuvent pas aussi servir à une autre branche.
        if (fois > 0)
            for (var child : node.enfants())
                if (!child.reutilisable())
                    consommerHistorique(
                            child,
                            fois,
                            dernierInventaire == null ? Map.of() : dernierInventaire,
                            0,
                            credits);
    }

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
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>(stocksPlanifies());
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

    private void convertir(
            ArbreCollecte.Noeud node,
            Map<String, Integer> actuel,
            Set<Set<String>> visites,
            Set<String> credites,
            Map<String, Integer> preuves) {
        // Les recettes intermédiaires avant leurs parents, pour les fabrications en chaîne.
        for (var child : node.enfants()) convertir(child, actuel, visites, credites, preuves);
        if (node.variantes().isEmpty() || !visites.add(node.variantes())) return;
        int arrive =
                node.variantes().stream()
                        .mapToInt(
                                id ->
                                        Math.max(
                                                0,
                                                Math.max(
                                                        preuves.getOrDefault(id, 0),
                                                        actuel.getOrDefault(id, 0)
                                                                - observes.getOrDefault(id, 0))))
                        .sum();
        long fois = (arrive + Math.max(1, node.production()) - 1) / Math.max(1, node.production());
        for (var child : node.enfants())
            if (!child.reutilisable()) consommerHistorique(child, fois, actuel, 0, observes);
        // Rendre ce résultat disponible avant de visiter son parent : une chaîne peut être
        // entièrement fabriquée entre deux relevés, sans garder ses intermédiaires.
        for (String id : node.variantes()) if (credites.add(id)) crediter(id, actuel, preuves);
    }

    private void crediter(String id, Map<String, Integer> actuel, Map<String, Integer> preuves) {
        int credit =
                (int)
                        Math.min(
                                1_000_000L,
                                (long) observes.getOrDefault(id, 0)
                                        + Math.max(0, preuves.getOrDefault(id, 0)));
        observes.put(id, Math.max(credit, Math.max(0, actuel.getOrDefault(id, 0))));
    }

    private void consommerHistorique(
            ArbreCollecte.Noeud node,
            long fois,
            Map<String, Integer> actuel,
            int depth,
            Map<String, Integer> credits) {
        if (depth > 128) return;
        int besoin =
                (int)
                        Math.min(
                                1_000_000L,
                                Math.min(1_000_000L, node.quantite()) * Math.min(1_000_000L, fois));
        int reste = besoin;
        for (String id : node.variantes()) {
            int ancien = credits.getOrDefault(id, 0), reel = actuel.getOrDefault(id, 0);
            int retire = Math.min(reste, Math.max(0, ancien - reel));
            credits.put(id, ancien - retire);
            reste -= retire;
        }
        // Une conversion peut traverser plusieurs recettes entre deux relevés.
        if (reste > 0) {
            long production = Math.max(1, node.production());
            long lots = (reste + production - 1) / production;
            for (var child : node.enfants())
                if (!child.reutilisable())
                    consommerHistorique(child, lots, actuel, depth + 1, credits);
        }
    }

    public Set<String> actualiser(Map<String, Integer> inventaire) {
        return actualiser(inventaire, Map.of());
    }

    public Set<String> actualiser(Map<String, Integer> inventaire, Map<String, Integer> preuves) {
        if (inventaire.equals(dernierInventaire) && preuves.isEmpty() && !reservationModifiee)
            return restants;
        reservationModifiee = false;
        Set<String> credites = new LinkedHashSet<>();
        if (arbre != null && dernierInventaire != null)
            convertir(arbre, inventaire, new LinkedHashSet<>(), credites, preuves);
        dernierInventaire = Map.copyOf(inventaire);
        version++;
        // Le crédit est un stock encore utile, pas la somme de toutes les formes d'un matériau.
        for (String id : tous) if (!credites.contains(id)) crediter(id, inventaire, preuves);
        List<PlanRetrait.Stock<String>> stocks = stocksPlanifies();
        if (arbre != null) {
            var allocation = PlanArbre.calculer(arbre, lots, stocks, ids -> ids::contains);
            Set<String> manquants = new LinkedHashSet<>();
            for (var ingredient : allocation.manquants()) manquants.addAll(ingredient.variantes());
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
