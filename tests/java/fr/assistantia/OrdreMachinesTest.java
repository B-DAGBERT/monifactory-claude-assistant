package fr.assistantia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public final class OrdreMachinesTest {
    private static PlanMachines.Noeud<String, String> node(String recipe, String... children) {
        return node(
                recipe, java.util.Arrays.stream(children).map(c -> node(c, List.of())).toList());
    }

    private static PlanMachines.Noeud<String, String> node(
            String recipe, List<PlanMachines.Noeud<String, String>> children) {
        return new PlanMachines.Noeud<>(s -> s.equals(recipe), 1, 1, false, recipe, children);
    }

    private static List<PlanMachines.Operation<String>> ops(String... recipes) {
        return java.util.Arrays.stream(recipes)
                .map(r -> new PlanMachines.Operation<>(r, 1))
                .toList();
    }

    private static OrdreMachines.Regle<String> need(String id, int amount) {
        return new OrdreMachines.Regle<>(id::equals, amount, false, id);
    }

    private static OrdreMachines.Regle<String> tool(String id) {
        return new OrdreMachines.Regle<>(id::equals, 1, true, id, id);
    }

    private static OrdreMachines.StockSource<String> stock(String id, int amount) {
        return stock(id, amount, OrdreMachines.Origine.POCHES);
    }

    private static OrdreMachines.StockSource<String> stock(
            String id, int amount, OrdreMachines.Origine origin) {
        return new OrdreMachines.StockSource<>(new PlanRetrait.Stock<>(id, amount, true), origin);
    }

    private static OrdreMachines.Resultat<String> order(
            PlanMachines.Noeud<String, String> tree,
            List<PlanMachines.Operation<String>> ops,
            Map<String, List<OrdreMachines.Regle<String>>> needs,
            List<OrdreMachines.StockSource<String>> stocks) {
        return order(tree, ops, needs, stocks, r -> OrdreMachines.Condition.prete(), true);
    }

    private static OrdreMachines.Resultat<String> order(
            PlanMachines.Noeud<String, String> tree,
            List<PlanMachines.Operation<String>> ops,
            Map<String, List<OrdreMachines.Regle<String>>> needs,
            List<OrdreMachines.StockSource<String>> stocks,
            Function<String, OrdreMachines.Condition> conditions,
            boolean sync) {
        return OrdreMachines.calculer(
                tree, ops, r -> needs.getOrDefault(r, List.of()), stocks, conditions, sync);
    }

    private static List<String> names(OrdreMachines.Resultat<String> result) {
        return result.lignes().stream().map(l -> l.operation().recette()).toList();
    }

    private static OrdreMachines.Ligne<String> line(
            OrdreMachines.Resultat<String> result, String r) {
        return result.lignes().stream()
                .filter(l -> l.operation().recette().equals(r))
                .findFirst()
                .orElseThrow();
    }

    private static void group(
            OrdreMachines.Resultat<String> result, String r, OrdreMachines.Groupe expected) {
        assert line(result, r).groupe() == expected : r + ": " + line(result, r);
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "01" -> chain();
            case "02" -> independent();
            case "03" -> competing();
            case "04" -> owned();
            case "05" -> chest();
            case "06" -> backpack();
            case "07" -> circuit();
            case "08" -> reusable();
            case "09" -> fluids();
            case "10" -> pending();
            case "11" -> partial();
            case "12" -> branches();
            case "13" -> cycles();
            case "14" -> shared();
            case "15" -> unknown();
            case "16" -> filter();
            case "17" -> immutable();
            case "18" -> paused();
            case "19" -> reconnect();
            case "20" -> large();
            case "variants" -> variants();
            case "priority" -> priority();
            case "machine" -> machine();
            case "limits" -> limits();
            case "pathological" -> pathological();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void chain() {
        var tree = node("C", List.of(node("B", "A")));
        var rules =
                Map.of(
                        "A",
                        List.of(need("ore", 1)),
                        "B",
                        List.of(need("A", 1)),
                        "C",
                        List.of(need("B", 1)));
        var result = order(tree, ops("C", "B", "A"), rules, List.of(stock("ore", 1)));
        assert names(result).equals(List.of("A", "B", "C")) : names(result);
        group(result, "A", OrdreMachines.Groupe.PRET);
        group(result, "B", OrdreMachines.Groupe.ETAPES);
        assert line(result, "B").dependances().equals(List.of("A"));
        var next = order(tree, ops("C", "B"), rules, List.of(stock("A", 1)));
        assert names(next).equals(List.of("B", "C"));
        group(next, "B", OrdreMachines.Groupe.PRET);
    }

    private static void independent() {
        var result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        Map.of("A", List.of(need("iron", 3)), "B", List.of(need("copper", 2))),
                        List.of(stock("iron", 3), stock("copper", 2)));
        assert result.lignes().stream().allMatch(l -> l.groupe() == OrdreMachines.Groupe.PRET);
        assert names(result).equals(List.of("A", "B"));
    }

    private static void competing() {
        var rules = Map.of("A", List.of(need("iron", 3)), "B", List.of(need("iron", 3)));
        var result = order(node(null, "A", "B"), ops("A", "B"), rules, List.of(stock("iron", 4)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        group(result, "B", OrdreMachines.Groupe.ETAPES);
        assert line(result, "B").conflit();
        assert line(result, "B").foisPretes() == 0;
        result = order(node(null, "A", "B"), ops("A", "B"), rules, List.of(stock("iron", 6)));
        assert result.lignes().stream().allMatch(l -> l.foisPretes() == 1);
    }

    private static void owned() {
        var plate =
                new PlanMachines.Noeud<String, String>(
                        "plate"::equals, 3, 1, false, "press", List.of(node(null, List.of())));
        var tree = node("assemble", List.of(plate));
        var physical = new PlanRetrait.Stock<>("plate", 3, true);
        var remaining = PlanMachines.calculer(tree, 1, List.of(physical));
        assert remaining.equals(ops("assemble"));
        var result =
                order(
                        tree,
                        remaining,
                        Map.of("assemble", List.of(need("plate", 3))),
                        List.of(
                                new OrdreMachines.StockSource<>(
                                        physical, OrdreMachines.Origine.POCHES)));
        assert names(result).equals(List.of("assemble"));
        assert line(result, "assemble").dependances().isEmpty();
        group(result, "assemble", OrdreMachines.Groupe.PRET);
    }

    private static void chest() {
        var result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("iron", 3))),
                        List.of(stock("iron", 3, OrdreMachines.Origine.COFFRE)));
        group(result, "A", OrdreMachines.Groupe.PREPARER);
        assert line(result, "A").foisPretes() == 0;
    }

    private static void backpack() {
        var result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("iron", 3))),
                        List.of(stock("iron", 3, OrdreMachines.Origine.SAC)));
        group(result, "A", OrdreMachines.Groupe.PREPARER);
    }

    private static void circuit() {
        var result =
                order(
                        node("bend"),
                        ops("bend"),
                        Map.of("bend", List.of(need("iron", 1))),
                        List.of(stock("iron", 1)),
                        r -> OrdreMachines.Condition.preparer("Circuit à régler : 4"),
                        true);
        group(result, "bend", OrdreMachines.Groupe.PREPARER);
        assert line(result, "bend").raison().equals("Circuit à régler : 4");
        // Le circuit virtuel n'est pas injecté dans les besoins d'objets.
        result =
                order(
                        node("bend"),
                        ops("bend"),
                        Map.of("bend", List.of(need("iron", 1))),
                        List.of(stock("iron", 1)));
        group(result, "bend", OrdreMachines.Groupe.PRET);
    }

    private static void reusable() {
        var rules =
                Map.of(
                        "A",
                        List.of(need("iron", 1), tool("mold")),
                        "B",
                        List.of(need("iron", 1), tool("mold")),
                        "C",
                        List.of(need("mold", 1)));
        var result =
                order(
                        node(null, "A", "B", "C"),
                        ops("A", "B", "C"),
                        rules,
                        List.of(stock("iron", 2), stock("mold", 1)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        group(result, "B", OrdreMachines.Groupe.PRET);
        group(result, "C", OrdreMachines.Groupe.ETAPES);
        assert line(result, "C").conflit()
                : "le moule réutilisable ne peut pas aussi être consommé";
        Predicate<String> either = s -> s.equals("red") || s.equals("blue");
        result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        Map.of(
                                "A",
                                List.of(new OrdreMachines.Regle<>(either, 1, true, "tool", "tool")),
                                "B",
                                List.of(need("red", 1))),
                        List.of(stock("red", 1), stock("blue", 1)));
        assert result.lignes().stream().allMatch(l -> l.groupe() == OrdreMachines.Groupe.PRET)
                : "réaffecter le catalyseur bleu au lieu de bloquer le consommable rouge";
    }

    private static void fluids() {
        var rules = Map.of("A", List.of(need("fluid:water:mB", 144)));
        var result =
                order(
                        node("A"),
                        ops("A"),
                        rules,
                        List.of(stock("item:water_bucket", 1), stock("fluid:water:mB", 143)));
        group(result, "A", OrdreMachines.Groupe.ETAPES);
        result = order(node("A"), ops("A"), rules, List.of(stock("fluid:water:mB", 144)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        Map.of("A", rules.get("A"), "B", List.of(need("fluid:water:mB", 100))),
                        List.of(stock("fluid:water:mB", 200)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        assert line(result, "B").conflit();
    }

    private static void pending() {
        var tree = node("B", "A");
        var remaining =
                PlanMachines.calculer(tree, 1, List.of(new PlanRetrait.Stock<>("A", 1, true)));
        assert remaining.equals(ops("B"));
        var result = order(tree, remaining, Map.of("B", List.of(need("A", 1))), List.of());
        group(result, "B", OrdreMachines.Groupe.ETAPES);
        assert names(result).equals(List.of("B")) : "aucune refabrication du lot déjà couvert";
        result =
                order(
                        tree,
                        ops("B", "A"),
                        Map.of("B", List.of(need("A", 1))),
                        List.of(),
                        r ->
                                r.equals("A")
                                        ? new OrdreMachines.Condition(
                                                OrdreMachines.Mode.EN_COURS, "Sortie attendue")
                                        : OrdreMachines.Condition.prete(),
                        true);
        group(result, "A", OrdreMachines.Groupe.EN_COURS);
        group(result, "B", OrdreMachines.Groupe.ETAPES);
    }

    private static void partial() {
        var tree =
                new PlanMachines.Noeud<String, String>(
                        "result"::equals, 10, 2, false, "A", List.of());
        var remaining =
                PlanMachines.calculer(tree, 1, List.of(new PlanRetrait.Stock<>("result", 3, true)));
        assert remaining.get(0).fois() == 4;
        var result =
                order(
                        tree,
                        remaining,
                        Map.of("A", List.of(need("ore", 1))),
                        List.of(stock("ore", 1)));
        assert line(result, "A").operation().fois() == 4;
        assert line(result, "A").foisPretes() == 1
                : "un lancement garanti ne promet pas quatre lots";
    }

    private static void branches() {
        var first =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("ore", 1))),
                        List.of(stock("ore", 1)));
        var next =
                order(
                        node("B"),
                        ops("B"),
                        Map.of("B", List.of(need("other", 1))),
                        List.of(stock("ore", 1)));
        assert names(first).equals(List.of("A"));
        assert names(next).equals(List.of("B"));
        group(next, "B", OrdreMachines.Groupe.ETAPES);
        var stale = order(node("B"), ops("A"), Map.of(), List.of());
        assert line(stale, "A").mode() == OrdreMachines.Mode.INCONNU;
        assert line(stale, "A").raison().contains("absente");
    }

    private static void cycles() {
        var aChildren = new ArrayList<PlanMachines.Noeud<String, String>>();
        var a = node("A", aChildren);
        var b = node("B", List.of(a));
        aChildren.add(b);
        var result =
                order(
                        node(null, List.of(a, node("independent"))),
                        ops("A", "B", "independent"),
                        Map.of(),
                        List.of());
        assert result.cycle();
        assert line(result, "A").cycle() && line(result, "B").cycle();
        group(result, "A", OrdreMachines.Groupe.ETAPES);
        group(result, "independent", OrdreMachines.Groupe.PRET);
        var selfChildren = new ArrayList<PlanMachines.Noeud<String, String>>();
        var self = node("self", selfChildren);
        selfChildren.add(self);
        assert order(self, ops("self"), Map.of(), List.of()).cycle();
    }

    private static void shared() {
        var c = node("C");
        var tree = node("final", List.of(node("A", List.of(c)), node("B", List.of(c))));
        var result =
                order(
                        tree,
                        ops("final", "A", "B", "C"),
                        Map.of(
                                "C",
                                List.of(need("ore", 1)),
                                "A",
                                List.of(need("C", 1)),
                                "B",
                                List.of(need("C", 1)),
                                "final",
                                List.of(need("A", 1), need("B", 1))),
                        List.of(stock("ore", 1)));
        assert names(result).equals(List.of("C", "A", "B", "final")) : names(result);
        assert line(result, "A").dependances().equals(List.of("C"));
        assert line(result, "B").dependances().equals(List.of("C"));
        assert !result.cycle();
    }

    private static void unknown() {
        var result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of(),
                        List.of(stock("iron", 1)),
                        r -> {
                            throw new AssertionError(
                                    "ne pas consulter une précondition d'un contexte périmé");
                        },
                        false);
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
        result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        Map.of("A", List.of(need("iron", 1)), "B", List.of(need("iron", 1))),
                        List.of(stock("iron", 1)),
                        r ->
                                r.equals("A")
                                        ? new OrdreMachines.Condition(
                                                OrdreMachines.Mode.INCONNU, "Machine inconnue")
                                        : OrdreMachines.Condition.prete(),
                        true);
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
        group(result, "B", OrdreMachines.Groupe.PRET);
        result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("copper", 2))),
                        List.of(stock("iron", 1)),
                        r ->
                                new OrdreMachines.Condition(
                                        OrdreMachines.Mode.INCONNU, "Machine inconnue"),
                        true);
        assert line(result, "A").mode() == OrdreMachines.Mode.BLOQUE;
        assert line(result, "A").raison().contains("copper");
        result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("copper", 2))),
                        List.of(stock("copper", 2)),
                        r ->
                                new OrdreMachines.Condition(
                                        OrdreMachines.Mode.INCONNU, "Machine inconnue"),
                        true);
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
        assert line(result, "A").raison().equals("Machine inconnue");
    }

    private static void filter() {
        var result =
                order(
                        node("C", List.of(node("B", "A"))),
                        ops("C", "B", "A"),
                        Map.of(
                                "A",
                                List.of(need("ore", 1)),
                                "B",
                                List.of(need("A", 1)),
                                "C",
                                List.of(need("B", 1))),
                        List.of(stock("ore", 1)));
        assert result.lignes().stream()
                .filter(l -> !l.operation().recette().equals("B"))
                .map(l -> l.operation().recette())
                .toList()
                .equals(List.of("A", "C"));
    }

    private static void immutable() {
        var operations = ops("C", "B", "A");
        var stocks = List.of(stock("ore", 1));
        var result =
                order(
                        node("C", List.of(node("B", "A"))),
                        operations,
                        Map.of(
                                "A",
                                List.of(need("ore", 1)),
                                "B",
                                List.of(need("A", 1)),
                                "C",
                                List.of(need("B", 1))),
                        stocks);
        assert operations.equals(ops("C", "B", "A"));
        assert stocks.equals(List.of(stock("ore", 1)));
        assert line(result, "A").operation() == operations.get(2)
                : "identité stable pour la sélection";
        try {
            result.lignes().clear();
            throw new AssertionError("résultat modifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void paused() {
        var result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("iron", 1))),
                        List.of(stock("iron", 1)),
                        r ->
                                new OrdreMachines.Condition(
                                        OrdreMachines.Mode.BLOQUE, "Objectif en pause"),
                        true);
        group(result, "A", OrdreMachines.Groupe.ETAPES);
        assert line(result, "A").foisPretes() == 0;
        assert line(result, "A").raison().equals("Objectif en pause");
    }

    private static void reconnect() {
        var rules = Map.of("A", List.of(need("iron", 1)));
        group(
                order(node("A"), ops("A"), rules, List.of(stock("iron", 1))),
                "A",
                OrdreMachines.Groupe.PRET);
        var result = order(node("A"), ops("A"), rules, List.of());
        group(result, "A", OrdreMachines.Groupe.ETAPES);
        result =
                order(
                        node("A"),
                        ops("A"),
                        rules,
                        List.of(stock("iron", 1)),
                        r -> OrdreMachines.Condition.prete(),
                        false);
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
    }

    private static void large() {
        int count = 1500;
        var children = new ArrayList<PlanMachines.Noeud<String, String>>();
        var operations = new ArrayList<PlanMachines.Operation<String>>();
        var rules = new LinkedHashMap<String, List<OrdreMachines.Regle<String>>>();
        var stocks = new ArrayList<OrdreMachines.StockSource<String>>();
        for (int i = 0; i < count; i++) {
            String r = "recipe" + i;
            children.add(node(r));
            operations.add(new PlanMachines.Operation<>(r, 1));
            rules.put(r, List.of(need("ore" + i, 1)));
            stocks.add(stock("ore" + i, 1));
        }
        var tree = node(null, children);
        long begin = System.nanoTime();
        var result = order(tree, operations, rules, stocks);
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        assert result.lignes().size() == count;
        assert result.lignes().stream().allMatch(l -> l.groupe() == OrdreMachines.Groupe.PRET);
        assert names(result)
                .equals(operations.stream().map(PlanMachines.Operation::recette).toList());
        assert names(result).equals(names(order(tree, operations, rules, stocks)));
        System.out.println("ORDER20 branches=" + count + " ms=" + elapsed);
        for (int i = 0; i < count; i++) rules.put("recipe" + i, List.of(need("shared", 1)));
        begin = System.nanoTime();
        result = order(tree, operations, rules, List.of(stock("shared", count)));
        elapsed = (System.nanoTime() - begin) / 1_000_000;
        assert result.lignes().stream().allMatch(l -> l.groupe() == OrdreMachines.Groupe.PRET);
        System.out.println("ORDER20 competing=" + count + " ms=" + elapsed);
    }

    private static void variants() {
        Predicate<String> either = s -> s.equals("red") || s.equals("blue");
        var rules =
                Map.of(
                        "A",
                        List.of(new OrdreMachines.Regle<>(either, 1, false, "either")),
                        "B",
                        List.of(need("red", 1)));
        var result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        rules,
                        List.of(stock("red", 1), stock("blue", 1)));
        assert result.lignes().stream().allMatch(l -> l.groupe() == OrdreMachines.Groupe.PRET);
        result =
                order(
                        node(null, "A", "B"),
                        ops("A", "B"),
                        rules,
                        List.of(stock("red", 1), stock("blue", 1, OrdreMachines.Origine.SAC)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        group(result, "B", OrdreMachines.Groupe.ETAPES);
        assert line(result, "B").conflit()
                : "ne pas déplacer au sac la ressource d'une ligne déjà prête";
        result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("tool:nbt:blue", 1))),
                        List.of(stock("tool:nbt:red", 1)));
        group(result, "A", OrdreMachines.Groupe.ETAPES);
    }

    private static void priority() {
        var tree = node(null, List.of(node("leaf"), node("parent", "unlock")));
        var result =
                order(
                        tree,
                        ops("leaf", "parent", "unlock"),
                        Map.of(
                                "leaf",
                                List.of(need("iron", 1)),
                                "unlock",
                                List.of(need("iron", 1)),
                                "parent",
                                List.of(need("unlock", 1))),
                        List.of(stock("iron", 1)));
        assert names(result).get(0).equals("unlock") : names(result);
        assert line(result, "leaf").conflit();
        result =
                order(
                        node(null, "prepare", "ready"),
                        ops("prepare", "ready"),
                        Map.of(
                                "prepare",
                                List.of(need("iron", 1)),
                                "ready",
                                List.of(need("iron", 1))),
                        List.of(stock("iron", 1)),
                        r ->
                                r.equals("prepare")
                                        ? OrdreMachines.Condition.preparer("Circuit à régler")
                                        : OrdreMachines.Condition.prete(),
                        true);
        group(result, "ready", OrdreMachines.Groupe.PRET);
        assert line(result, "prepare").conflit();
    }

    private static void machine() {
        var result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("iron", 1), tool("mold"))),
                        List.of(stock("iron", 1), stock("mold", 1, OrdreMachines.Origine.MACHINE)));
        group(result, "A", OrdreMachines.Groupe.PRET);
        result =
                order(
                        node("A"),
                        ops("A"),
                        Map.of("A", List.of(need("iron", 1))),
                        List.of(stock("iron", 1, OrdreMachines.Origine.MACHINE)),
                        r ->
                                new OrdreMachines.Condition(
                                        OrdreMachines.Mode.INCONNU, "Entrées non vérifiées"),
                        true);
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
    }

    private static void pathological() {
        int count = 200;
        var children = new ArrayList<PlanMachines.Noeud<String, String>>();
        var operations = new ArrayList<PlanMachines.Operation<String>>();
        var rules = new LinkedHashMap<String, List<OrdreMachines.Regle<String>>>();
        var stocks = new ArrayList<OrdreMachines.StockSource<String>>();
        for (int i = 0; i < count; i++) {
            final int limit = i;
            String recipe = "r" + i;
            children.add(node(recipe));
            operations.add(new PlanMachines.Operation<>(recipe, 1));
            rules.put(
                    recipe,
                    List.of(
                            new OrdreMachines.Regle<>(
                                    x -> Integer.parseInt(x.substring(3)) <= limit,
                                    1,
                                    false,
                                    "metal")));
            stocks.add(stock("ore" + i, 1));
        }
        long begin = System.nanoTime();
        var result = order(node(null, children), operations, rules, stocks);
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        assert result.lignes().size() == count;
        assert result.lignes().stream()
                        .allMatch(
                                l ->
                                        l.mode() == OrdreMachines.Mode.INCONNU
                                                && l.foisPretes() == 0
                                                && l.raison().contains("variantes"))
                : "aucune recommandation positive partielle après dépassement de coût";
        System.out.println("ORDER20 alternatives=" + count + " bounded_ms=" + elapsed);
    }

    private static void limits() {
        var result = order(null, ops("A"), Map.of(), List.of());
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
        var children = new ArrayList<PlanMachines.Noeud<String, String>>();
        for (int i = 0; i < 4096; i++) children.add(node(null, List.of()));
        result = order(node("A", children), ops("A"), Map.of(), List.of());
        assert line(result, "A").mode() == OrdreMachines.Mode.INCONNU;
    }
}
