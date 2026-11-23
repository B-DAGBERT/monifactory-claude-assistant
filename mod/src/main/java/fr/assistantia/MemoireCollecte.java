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

    // Une pile peut satisfaire plusieurs comparaisons EMI. Elle conserve une seule quantité
    // physique, que PlanRetrait répartit entre les branches éligibles.
    private final Map<String, Set<String>> groupesParVariante = new LinkedHashMap<>();
    private final Map<Set<String>, String> groupes = new LinkedHashMap<>();
    private final Map<String, Set<String>> variantesParObjet = new LinkedHashMap<>();

    public String groupe(Set<String> variantes) {
        if (variantes.isEmpty()) return "";
        String premiere = variantes.iterator().next();
        if (variantes.size() == 1) return premiere;
        String connu = groupes.get(variantes);
        if (connu != null) return connu;
        var cle = new StringBuilder(RessourceCollecte.idObjet(premiere)).append("|&");
        variantes.stream().sorted().forEach(v -> cle.append(v.length()).append(':').append(v));
        String groupe = cle.toString();
        groupes.put(Set.copyOf(variantes), groupe);
        for (String v : variantes)
            groupesParVariante.computeIfAbsent(v, k -> new LinkedHashSet<>()).add(groupe);
        return groupe;
    }

    Set<String> pilesAcceptees(Set<String> variantes) {
        if (groupesParVariante.isEmpty()) return variantes;
        Set<String> piles = new LinkedHashSet<>(variantes);
        for (String v : variantes) piles.addAll(groupesParVariante.getOrDefault(v, Set.of()));
        return piles;
    }

    boolean accepte(Set<String> variantes, String pile) {
        if (variantes.contains(pile)) return true;
        if (groupesParVariante.isEmpty()) return false;
        for (String v : variantes)
            if (groupesParVariante.getOrDefault(v, Set.of()).contains(pile)) return true;
        return false;
    }

    Set<String> variantesObjet(String id) {
        return variantesParObjet.getOrDefault(id, Set.of());
    }

    private ArbreCollecte.Noeud arbre;
    private long lots, version;
    private final List<Ingredient> ingredients;
    private final List<PlanRetrait.Besoin<String>> besoins;
    private final Map<String, Integer> observes = new LinkedHashMap<>();
    private final Map<String, Integer> prouves = new LinkedHashMap<>();
    private final boolean[] valides;
    private final Set<String> tous;
    private Map<String, Integer> dernierInventaire = Map.of();
    private Set<String> restants;
    private Map<String, Integer> reserves = Map.of(), outilsMachines = Map.of();
    private boolean reservationModifiee;

    /** Recalcul d'un arbre modifié : conserver les acquisitions encore pertinentes. */
    public void reprendre(MemoireCollecte ancienne) {
        if (ancienne == null) return;
        var correspondances = new LinkedHashMap<String, String>();
        for (String cle : tous) correspondances.put(cle, cle);
        ancienne.groupes.forEach(
                (variantes, ancienneCle) -> {
                    var restantes = new LinkedHashSet<>(variantes);
                    restantes.retainAll(tous);
                    if (!restantes.isEmpty()) correspondances.put(ancienneCle, groupe(restantes));
                });
        reprendre(ancienne.observes, observes, correspondances);
        reprendre(ancienne.prouves, prouves, correspondances);
        dernierInventaire = null;
    }

    private static void reprendre(
            Map<String, Integer> source,
            Map<String, Integer> cible,
            Map<String, String> correspondances) {
        source.forEach(
                (id, n) -> {
                    String cle = correspondances.get(id);
                    if (cle != null) cible.merge(cle, n, (a, b) -> Math.min(1_000_000, a + b));
                });
    }

    public void reserver(Map<String, Integer> quantites) {
        Map<String, Integer> nouvelles = new LinkedHashMap<>();
        quantites.forEach(
                (id, n) -> {
                    if (accepte(tous, id) && n > 0) nouvelles.put(id, Math.min(n, 1_000_000));
                });
        if (!reserves.equals(nouvelles)) {
            reserves = Map.copyOf(nouvelles);
            reservationModifiee = true;
        }
    }

    /** Disponibilité temporaire des outils installés, distincte d'un résultat à récupérer. */
    public void outilsMachines(Map<String, Integer> quantites) {
        var nouveaux = new LinkedHashMap<String, Integer>();
        quantites.forEach(
                (id, n) -> {
                    if (accepte(tous, id) && n > 0) nouveaux.put(id, Math.min(n, 1_000_000));
                });
        if (!outilsMachines.equals(nouveaux)) {
            outilsMachines = Map.copyOf(nouveaux);
            reservationModifiee = true;
        }
    }

    private List<PlanRetrait.Stock<String>> stocksPlanifies() {
        return stocksPlanifies(Map.of(), Map.of());
    }

    private List<PlanRetrait.Stock<String>> stocksPlanifies(
            Map<String, Integer> portes, Map<String, Integer> tampons) {
        var credits = new LinkedHashMap<>(observes);
        // Un objet rangé dans un tampon a pu être déjà observé dans les poches. Seule
        // la partie qui dépasse les poches actuelles peut recouvrir ce stock physique.
        tampons.forEach(
                (id, n) ->
                        credits.put(
                                id,
                                Math.max(
                                        portes.getOrDefault(id, 0),
                                        credits.getOrDefault(id, 0) - n)));
        outilsMachines.forEach((id, n) -> credits.merge(id, n, Math::max));
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
        long n =
                pilesAcceptees(node.variantes()).stream()
                        .mapToLong(id -> reserves.getOrDefault(id, 0))
                        .sum();
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
        for (String cle : tous)
            if (!RessourceCollecte.estFluide(cle))
                variantesParObjet
                        .computeIfAbsent(RessourceCollecte.idObjet(cle), k -> new LinkedHashSet<>())
                        .add(cle);
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

    /** Une source que le joueur retire du calcul exige une nouvelle observation du stock. */
    void reprendreStockCourant() {
        observes.clear();
        observes.putAll(prouves);
        java.util.Arrays.fill(valides, false);
        dernierInventaire = null;
        restants = tous;
    }

    public Set<String> tous() {
        return tous;
    }

    private Set<String> outils;

    /**
     * Variantes des ingrédients qui ne se consomment pas (moules, presses, outils) : une fois
     * repérés, ils sont surlignés dans les coffres ouverts comme les autres ingrédients.
     */
    public Set<String> outils() {
        if (outils == null) {
            Set<String> ids = new LinkedHashSet<>();
            if (arbre != null)
                collecterOutils(
                        arbre,
                        ids,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
            outils = Set.copyOf(ids);
        }
        return outils;
    }

    private static void collecterOutils(
            ArbreCollecte.Noeud noeud, Set<String> ids, Set<ArbreCollecte.Noeud> vus) {
        if (!vus.add(noeud) || vus.size() > 4096) return;
        if (noeud.reutilisable()) ids.addAll(noeud.variantes());
        for (var enfant : noeud.enfants()) collecterOutils(enfant, ids, vus);
    }

    /** Réserves connues : composants d'abord, sans créditer un objet simplement repéré. */
    public List<PlanRetrait.Prise> dansCoffres(List<PlanRetrait.Stock<String>> coffres) {
        return dansCoffres(coffres, 0, Map.of());
    }

    /** Les premières piles sont des tampons physiques à allouer avant les autres coffres. */
    List<PlanRetrait.Prise> dansCoffres(
            List<PlanRetrait.Stock<String>> coffres,
            int nombreTampons,
            Map<String, Integer> portes) {
        var stocks = prevoirAvecTampons(coffres.subList(0, nombreTampons), portes);
        int offset = stocks.size();
        int finPrioritaire = nombreTampons == 0 ? 0 : offset + nombreTampons;
        stocks.addAll(coffres);
        var prises =
                arbre == null
                        ? PlanRetrait.calculer(stocks, besoins, finPrioritaire).prises()
                        : PlanArbre.calculer(
                                        arbre,
                                        lots,
                                        stocks,
                                        ids -> pilesAcceptees(ids)::contains,
                                        finPrioritaire)
                                .prises();
        // Allocation physique unique : la part du lot attendu utilise seulement ce qui
        // n'a pas déjà été retenu pour les branches encore à fabriquer.
        Map<Integer, Integer> retenus = new LinkedHashMap<>();
        for (var prise : prises) {
            int i = prise.index() - offset;
            if (i >= 0 && i < coffres.size()) retenus.merge(i, prise.quantite(), Integer::sum);
        }
        if (!reserves.isEmpty()) {
            List<PlanRetrait.Stock<String>> disponibles = new ArrayList<>();
            for (int i = 0; i < coffres.size(); i++) {
                var stock = coffres.get(i);
                disponibles.add(
                        new PlanRetrait.Stock<>(
                                stock.objet(),
                                Math.max(0, stock.quantite() - retenus.getOrDefault(i, 0)),
                                stock.inventaire()));
            }
            var resultats =
                    reserves.entrySet().stream()
                            .map(
                                    e ->
                                            new PlanRetrait.Besoin<String>(
                                                    e.getKey()::equals, e.getValue()))
                            .toList();
            for (var prise : PlanRetrait.calculer(disponibles, resultats, nombreTampons).prises())
                retenus.merge(prise.index(), prise.quantite(), Integer::sum);
        }
        return retenus.entrySet().stream()
                .map(e -> new PlanRetrait.Prise(e.getKey(), e.getValue()))
                .toList();
    }

    private ArrayList<PlanRetrait.Stock<String>> prevoirAvecTampons(
            List<PlanRetrait.Stock<String>> piles, Map<String, Integer> portes) {
        Map<String, Integer> tampons = new LinkedHashMap<>();
        for (var stock : piles)
            tampons.merge(
                    stock.objet(),
                    stock.quantite(),
                    (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        return new ArrayList<>(stocksPlanifies(portes, tampons));
    }

    /** Les tampons couvrent d'abord les besoins, sans devenir des coffres ordinaires à chercher. */
    Set<String> manquantsApresTampons(
            List<PlanRetrait.Stock<String>> tampons, Map<String, Integer> portes) {
        if (arbre == null || tampons.isEmpty()) return restants;
        var stocks = prevoirAvecTampons(tampons, portes);
        stocks.addAll(tampons);
        return manquantsArbre(stocks);
    }

    private Set<String> manquantsArbre(List<PlanRetrait.Stock<String>> stocks) {
        var allocation =
                PlanArbre.calculer(arbre, lots, stocks, ids -> pilesAcceptees(ids)::contains);
        Set<String> manquants = new LinkedHashSet<>();
        for (var ingredient : allocation.manquants()) manquants.addAll(ingredient.variantes());
        return avecFabrications(manquants);
    }

    /** Les résultats attendus restent recherchables, même si leurs branches EMI sont pliées. */
    public boolean enFabrication(String id) {
        return reserves.getOrDefault(id, 0) > 0;
    }

    private Set<String> avecFabrications(Set<String> manque) {
        Set<String> result = new LinkedHashSet<>(manque);
        result.addAll(reserves.keySet());
        return Set.copyOf(result);
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
                pilesAcceptees(node.variantes()).stream()
                        .filter(id -> !credites.contains(id))
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
        for (String id : pilesAcceptees(node.variantes()))
            if (credites.add(id)) crediter(id, actuel, preuves);
    }

    private void crediter(String id, Map<String, Integer> actuel, Map<String, Integer> preuves) {
        if (RessourceCollecte.estFluide(id)) {
            observes.put(id, Math.max(0, actuel.getOrDefault(id, 0)));
            return;
        }
        int gain = Math.max(0, preuves.getOrDefault(id, 0));
        int credit = (int) Math.min(1_000_000L, (long) observes.getOrDefault(id, 0) + gain);
        observes.put(id, Math.max(credit, Math.max(0, actuel.getOrDefault(id, 0))));
        if (gain > 0)
            prouves.merge(id, Math.min(1_000_000, gain), (a, b) -> Math.min(1_000_000, a + b));
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
        for (String id : pilesAcceptees(node.variantes())) {
            int ancien = credits.getOrDefault(id, 0), reel = actuel.getOrDefault(id, 0);
            int retire = Math.min(reste, Math.max(0, ancien - reel));
            credits.put(id, ancien - retire);
            if (credits == observes && retire > 0) {
                // Une preuve déjà consommée ne couvre plus un besoin après une bascule.
                prouves.computeIfPresent(id, (cle, n) -> n > retire ? n - retire : null);
            }
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
        for (String id : pilesAcceptees(tous))
            if (!credites.contains(id)) crediter(id, inventaire, preuves);
        List<PlanRetrait.Stock<String>> stocks = stocksPlanifies();
        if (arbre != null) {
            restants = manquantsArbre(stocks);
            return restants;
        }
        var allocation = PlanRetrait.calculer(stocks, besoins);
        Set<String> manquants = new LinkedHashSet<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            valides[i] |= allocation.fournis().get(i) >= ingredient.quantite();
            if (!valides[i]) manquants.addAll(ingredient.variantes());
        }
        restants = avecFabrications(manquants);
        return restants;
    }
}
