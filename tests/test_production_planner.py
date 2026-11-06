"""Offline production arithmetic, provenance and explicit uncertainty contracts."""

import copy
import json
import random
import unittest
from dataclasses import FrozenInstanceError

from monifactory_assistant.production.planner import ProductionLot, StockLedger, StockSource, plan
from monifactory_assistant.production.recipes import (
    Amount,
    Catalog,
    Ingredient,
    Output,
    Recipe,
    Resource,
)


def item(name, variant=""):
    return Resource("item", "test:" + name, "item", variant)


def fluid(name):
    return Resource("fluid", "test:" + name, "mB")


def ing(resource, quantity=1, *, reusable=False):
    return Ingredient((Amount(resource, quantity),), reusable)


def recipe(name, inputs, outputs, **kwargs):
    return Recipe(
        "test:" + name,
        "test:machine",
        tuple(inputs),
        tuple(Output(Amount(r, n)) for r, n in outputs),
        **kwargs,
    )


def stock(*contents, **kwargs):
    return StockLedger(
        sources=(StockSource("inventory", tuple(Amount(r, q) for r, q in contents)),), **kwargs
    )


def counts(amounts):
    return {a.resource: a.quantity for a in amounts}


class RecipeContractTests(unittest.TestCase):
    def setUp(self):
        self.recipe = recipe(
            "plate",
            [ing(item("ingot"))],
            [(item("plate"), 2)],
            machines=("gtceu:lv_bender",),
            duration_ticks=20,
            eut=16,
            circuit=1,
        )
        self.catalog = Catalog(1, (self.recipe,))

    def test_roundtrip_and_immutability(self):
        raw = self.catalog.to_dict()
        decoded = Catalog.from_dict(json.loads(json.dumps(raw)))
        self.assertEqual(decoded, self.catalog)
        raw["recipes"][0]["inputs"][0]["options"][0]["quantity"] = 99
        self.assertEqual(decoded.recipes[0].inputs[0].options[0].quantity, 1)
        with self.assertRaises(FrozenInstanceError):
            decoded.generation = 2
        source = [self.recipe]
        self.assertEqual(Catalog(1, source), self.catalog)
        source.clear()
        self.assertEqual(len(self.catalog.recipes), 1)

    def test_all_invalid_json_shapes_raise_value_error(self):
        good = self.catalog.to_dict()
        paths = [
            (),
            ("recipes",),
            ("recipes", 0),
            ("recipes", 0, "inputs"),
            ("recipes", 0, "inputs", 0),
            ("recipes", 0, "inputs", 0, "options"),
            ("recipes", 0, "inputs", 0, "options", 0),
            ("recipes", 0, "inputs", 0, "options", 0, "resource"),
            ("recipes", 0, "outputs"),
            ("recipes", 0, "outputs", 0),
            ("recipes", 0, "outputs", 0, "chance"),
        ]
        for path in paths:
            for invalid in (None, 2, True, "broken"):
                with self.subTest(path=path, invalid=invalid):
                    data = copy.deepcopy(good)
                    if path:
                        parent = data
                        for key in path[:-1]:
                            parent = parent[key]
                        parent[path[-1]] = invalid
                    else:
                        data = invalid
                    with self.assertRaises(ValueError):
                        Catalog.from_dict(data)
        for quantity in (0, -1, 1.5, True, "1", None):
            data = copy.deepcopy(good)
            data["recipes"][0]["inputs"][0]["options"][0]["quantity"] = quantity
            with self.subTest(quantity=quantity), self.assertRaises(ValueError):
                Catalog.from_dict(data)
        for generation in (0, -1, True, 1.0, "1"):
            with self.subTest(generation=generation), self.assertRaises(ValueError):
                Catalog.from_dict(dict(good, generation=generation))
        for version in (2, True, None, "1"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                Catalog.from_dict(dict(good, version=version))

    def test_unknown_semantics_and_ambiguous_identity_rejected(self):
        data = self.catalog.to_dict()
        data["recipes"][0]["temperature"] = 2000
        with self.assertRaises(ValueError):
            Catalog.from_dict(data)
        for kind, unit in (("item", "mB"), ("fluid", "item"), ("gas", "mB")):
            with self.assertRaises(ValueError):
                Resource(kind, "test:x", unit)
        with self.assertRaises(ValueError):
            Resource("item", "InvalidName", "item")
        with self.assertRaises(ValueError):
            Ingredient((Amount(item("x"), 1), Amount(item("x"), 2)))
        with self.assertRaises(ValueError):
            Catalog(1, (self.recipe, self.recipe))
        with self.assertRaises(ValueError):
            Output(Amount(item("x"), 1), 2, 1)
        self.assertEqual(Output(Amount(item("x"), 1), 50, 100).chance_denominator, 2)


class ProductionPlannerTests(unittest.TestCase):
    def test_exact_batch_rounding_large_integers_and_machine_metadata(self):
        ingot, plate = item("ingot"), item("plate")
        quantity = 2**60 + 3
        batches = (quantity + 1) // 2
        r = recipe(
            "bend",
            [ing(ingot, 3)],
            [(plate, 2)],
            machines=("gtceu:lv_bender",),
            duration_ticks=20,
            eut=16,
            circuit=1,
        )
        result = plan(Catalog(7, (r,)), Amount(plate, quantity), stock((ingot, batches * 3)))
        self.assertEqual(result.status, "complete")
        step = result.steps[0]
        self.assertEqual(
            (step.batches, step.duration_ticks, step.energy_eu, step.circuit),
            (batches, batches * 20, batches * 320, 1),
        )
        self.assertEqual(counts(step.inputs), {ingot: batches * 3})
        self.assertEqual(counts(result.surplus), {plate: 1})
        self.assertEqual(step.machines, ("gtceu:lv_bender",))
        self.assertNotIn("circuit", [a.resource.id for a in result.allocations])
        self.assertEqual(result.to_dict()["generation"], 7)
        self.assertEqual(result.to_dict()["execution_readiness"], "not_evaluated")
        self.assertTrue(result.steps[0].to_dict()["conditional"])
        json.dumps(result.to_dict(), allow_nan=False)

    def test_finished_item_prunes_ingredients_and_tools(self):
        goal, tool, ore = item("goal"), item("tool"), item("ore")
        r = recipe("goal", [ing(tool, reusable=True), ing(ore, 50)], [(goal, 1)])
        result = plan(Catalog(1, (r,)), Amount(goal, 2), stock((goal, 2)))
        self.assertEqual((result.steps, result.missing, result.waiting), ((), (), ()))
        self.assertEqual(result.status, "complete")
        self.assertEqual([(a.resource, a.quantity) for a in result.allocations], [(goal, 2)])

    def test_physical_reserved_expected_confirmed_are_counted_once(self):
        output = item("output")
        ledger = stock(
            (output, 3),
            reserved=(Amount(output, 1),),
            expected=(ProductionLot("batch", (Amount(output, 5),)),),
            confirmed=(ProductionLot("batch", (Amount(output, 2),)),),
        )
        result = plan(Catalog(1, ()), Amount(output, 6), ledger)
        self.assertEqual(counts(result.waiting), {output: 3})
        self.assertEqual(counts(result.missing), {output: 1})
        self.assertEqual(
            [(a.source_kind, a.quantity) for a in result.allocations],
            [("physical", 2), ("expected", 3)],
        )
        self.assertEqual(ledger.sources[0].contents[0].quantity, 3)
        # Receiving a promise then putting the result elsewhere does not create
        # a second usable inventory copy in a new production plan.
        received = StockLedger(
            expected=(ProductionLot("batch", (Amount(output, 5),)),),
            confirmed=(ProductionLot("batch", (Amount(output, 5),)),),
        )
        no_stock = plan(Catalog(1, ()), Amount(output, 1), received)
        self.assertEqual(no_stock.waiting, ())
        self.assertEqual(counts(no_stock.missing), {output: 1})

    def test_duplicate_snapshots_and_lots_deduplicated_conflicts_rejected(self):
        x = item("x")
        source = StockSource("same-chest", (Amount(x, 2),))
        lot = ProductionLot("lot", (Amount(x, 1),))
        ledger = StockLedger((source, source), expected=(lot, lot))
        result = plan(Catalog(1, ()), Amount(x, 4), ledger)
        self.assertEqual(counts(result.missing), {x: 1})
        self.assertEqual(counts(result.waiting), {x: 1})
        with self.assertRaises(ValueError):
            StockLedger((source, StockSource("same-chest", (Amount(x, 3),))))
        with self.assertRaises(ValueError):
            StockLedger(expected=(lot, ProductionLot("lot", (Amount(x, 2),))))
        with self.assertRaises(ValueError):
            StockLedger(expected=(lot,), confirmed=(ProductionLot("lot", (Amount(x, 2),)),))
        excessive = plan(Catalog(1, ()), Amount(x, 1), stock((x, 2), reserved=(Amount(x, 3),)))
        self.assertIn("reservation_shortage", [i.code for i in excessive.issues])
        self.assertEqual(excessive.allocations, ())

    def test_ambiguous_recipe_requires_explicit_choice_and_reports_candidates(self):
        a, b, goal = item("a"), item("b"), item("goal")
        first = recipe("first", [ing(a)], [(goal, 1)])
        second = recipe("second", [ing(b, 2)], [(goal, 1)])
        catalog = Catalog(1, (second, first))
        result = plan(catalog, Amount(goal, 2), stock((a, 10), (b, 4)))
        self.assertEqual(result.status, "partial")
        self.assertEqual(result.steps, ())
        self.assertEqual(result.issues[0].candidates, ("test:first", "test:second"))
        chosen = plan(
            catalog, Amount(goal, 2), stock((a, 10), (b, 4)), recipe_choices={goal: second.id}
        )
        self.assertEqual(chosen.status, "complete")
        self.assertEqual(counts(chosen.steps[0].inputs), {b: 4})
        self.assertFalse(any(allocation.resource == a for allocation in chosen.allocations))
        with self.assertRaises(ValueError):
            plan(catalog, Amount(goal, 1), recipe_choices={a: second.id})
        for invalid in ([], {}, 2, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                plan(catalog, Amount(goal, 1), recipe_choices={goal: invalid})

    def test_input_alternatives_exact_variants_and_atomic_ambiguity(self):
        oak, birch, ore, result = (
            item("wood", "oak"),
            item("wood", "birch"),
            item("ore"),
            item("result"),
        )
        r = recipe(
            "choice", [ing(ore), Ingredient((Amount(oak, 2), Amount(birch, 3)))], [(result, 1)]
        )
        catalog = Catalog(1, (r,))
        ledger = stock((ore, 1), (birch, 3))
        missing = plan(catalog, Amount(result, 1), ledger)
        self.assertEqual(missing.allocations, ())
        self.assertEqual(missing.issues[0].code, "ambiguous_ingredient")
        selected = plan(catalog, Amount(result, 1), ledger, ingredient_choices={r.id + "#1": birch})
        self.assertEqual(selected.status, "complete")
        self.assertEqual(counts(selected.steps[0].inputs), {ore: 1, birch: 3})
        wrong_variant = plan(Catalog(1, ()), Amount(oak, 1), stock((birch, 20)))
        self.assertEqual(counts(wrong_variant.missing), {oak: 1})
        with self.assertRaises(ValueError):
            plan(catalog, Amount(result, 1), ingredient_choices={r.id + "#2": oak})

    def test_items_and_fluids_have_separate_units_and_no_implicit_containers(self):
        metal, acid, result = item("metal"), fluid("acid"), item("result")
        r = recipe("fluid", [ing(metal), ing(acid, 750)], [(result, 2)])
        planned = plan(
            Catalog(1, (r,)),
            Amount(result, 3),
            stock((metal, 2), (acid, 1000), (item("acid_bucket"), 1)),
        )
        self.assertEqual(counts(planned.missing), {acid: 500})
        self.assertEqual(counts(planned.steps[0].inputs), {metal: 2, acid: 1500})
        self.assertFalse(any(a.resource == item("acid_bucket") for a in planned.allocations))

    def test_reusable_tools_share_across_sequential_branches_not_batches(self):
        tool, ore, a, b, goal = map(item, ("tool", "ore", "a", "b", "goal"))
        recipes = (
            recipe("a", [ing(tool, reusable=True), ing(ore)], [(a, 1)]),
            recipe("b", [ing(tool, reusable=True), ing(ore)], [(b, 1)]),
            recipe("goal", [ing(a, 3), ing(b, 2)], [(goal, 1)]),
        )
        result = plan(Catalog(1, recipes), Amount(goal, 1), stock((tool, 1), (ore, 5)))
        self.assertEqual(result.status, "complete")
        self.assertEqual(sum(a.quantity for a in result.allocations if a.resource == tool), 1)
        self.assertEqual(
            [counts(s.catalysts) for s in result.steps if s.catalysts], [{tool: 1}, {tool: 1}]
        )
        missing_tool = plan(Catalog(1, recipes), Amount(goal, 1), stock((ore, 5)))
        self.assertEqual(counts(missing_tool.missing), {tool: 1})

    def test_reusable_tool_cannot_also_pay_consumable_and_slots_add_within_recipe(self):
        tool, a, b, goal = map(item, ("tool", "a", "b", "goal"))
        recipes = (
            recipe("a", [ing(tool, reusable=True), ing(tool, reusable=True)], [(a, 1)]),
            recipe("b", [ing(tool)], [(b, 1)]),
            recipe("goal", [ing(a), ing(b)], [(goal, 1)]),
        )
        result = plan(Catalog(1, recipes), Amount(goal, 1), stock((tool, 2)))
        self.assertEqual(counts(result.missing), {tool: 1})
        self.assertEqual(counts(result.steps[0].catalysts), {tool: 2})
        self.assertEqual(sum(a.quantity for a in result.allocations if a.resource == tool), 2)

    def test_crafted_tool_dependency_is_preserved_for_every_consumer(self):
        tool, ore, a, b, goal = map(item, ("tool", "ore", "a", "b", "goal"))
        recipes = (
            recipe("tool", [ing(ore)], [(tool, 1)]),
            recipe("a", [ing(tool, reusable=True)], [(a, 1)]),
            recipe("b", [ing(tool, reusable=True)], [(b, 1)]),
            recipe("goal", [ing(a), ing(b)], [(goal, 1)]),
        )
        result = plan(Catalog(1, recipes), Amount(goal, 1), stock((ore, 1)))
        tool_step = next(s for s in result.steps if s.recipe_id == "test:tool")
        for step in result.steps:
            if step.recipe_id in ("test:a", "test:b"):
                self.assertIn(tool_step.id, step.dependencies)
        emitted = set()
        for step in result.steps:
            self.assertTrue(set(step.dependencies) <= emitted)
            emitted.add(step.id)

    def test_coproducts_reused_without_double_production(self):
        ore, a, b, goal = map(item, ("ore", "a", "b", "goal"))
        recipes = (
            recipe("split", [ing(ore)], [(a, 1), (b, 2)]),
            recipe("goal", [ing(a), ing(b)], [(goal, 1)]),
        )
        result = plan(Catalog(1, recipes), Amount(goal, 1), stock((ore, 1)))
        self.assertEqual(result.status, "complete")
        self.assertEqual([s.recipe_id for s in result.steps], ["test:split", "test:goal"])
        self.assertEqual(counts(result.surplus), {b: 1})
        self.assertEqual(result.steps[-1].dependencies, (result.steps[0].id,))

    def test_probabilistic_outputs_are_never_guaranteed_stock(self):
        ore, a, b = map(item, ("ore", "a", "b"))
        r = Recipe(
            "test:random",
            "test:machine",
            (ing(ore),),
            (Output(Amount(a, 1)), Output(Amount(b, 2), 1, 2)),
        )
        success = plan(Catalog(1, (r,)), Amount(a, 2), stock((ore, 2)))
        self.assertEqual(success.status, "complete")
        self.assertEqual(counts(success.steps[0].outputs), {a: 2})
        self.assertEqual(success.surplus, ())
        self.assertIn("probabilistic_coproduct", [i.code for i in success.issues])
        random_target = plan(Catalog(1, (r,)), Amount(b, 1), stock((ore, 100)))
        self.assertEqual(random_target.status, "partial")
        self.assertEqual(random_target.steps, ())
        self.assertEqual(random_target.issues[0].code, "probabilistic_target")
        uncertain = success.steps[0].probabilistic_outputs_per_batch
        self.assertEqual(uncertain, (Output(Amount(b, 2), 1, 2),))

    def test_unsupported_conditions_block_without_allocating_inputs(self):
        ore, goal = item("ore"), item("goal")
        r = recipe(
            "unknown", [ing(ore)], [(goal, 1)], unsupported=("GT tick inputs", "ingredient actions")
        )
        result = plan(Catalog(1, (r,)), Amount(goal, 1), stock((ore, 1)))
        self.assertEqual((result.steps, result.allocations), ((), ()))
        self.assertEqual(result.issues[0].code, "unsupported_recipe")
        self.assertEqual(counts(result.missing), {goal: 1})

    def test_cycles_and_depth_limits_are_partial_not_infinite_or_exact(self):
        a, b = item("a"), item("b")
        catalog = Catalog(1, (recipe("a", [ing(b)], [(a, 1)]), recipe("b", [ing(a)], [(b, 1)])))
        result = plan(catalog, Amount(a, 1))
        self.assertEqual(result.status, "partial")
        self.assertEqual(counts(result.missing), {a: 1})
        self.assertIn("cycle", [i.code for i in result.issues])
        seeded = plan(catalog, Amount(a, 1), stock((b, 1)))
        self.assertEqual(seeded.status, "complete")
        chain = Catalog(
            1,
            tuple(
                recipe(f"r{i}", [ing(item(f"x{i + 1}"))], [(item(f"x{i}"), 1)]) for i in range(140)
            ),
        )
        limited = plan(chain, Amount(item("x0"), 1))
        self.assertEqual(len(limited.steps), 128)
        self.assertIn("plan_limit", [i.code for i in limited.issues])
        self.assertEqual(limited.status, "partial")
        with self.assertRaises(ValueError):
            plan(chain, Amount(item("x0"), 1), max_depth=1000)

    def test_limits_machine_uncertainty_and_deterministic_serialization(self):
        a, b, ore, goal = map(item, ("a", "b", "ore", "goal"))
        recipes = (
            recipe("a", [ing(ore)], [(a, 1)]),
            recipe("b", [ing(ore)], [(b, 1)]),
            recipe("goal", [ing(a), ing(b)], [(goal, 1)]),
        )
        inventory = StockSource("inventory", (Amount(ore, 1),))
        chest = StockSource("chest", (Amount(ore, 1),))
        first = plan(Catalog(3, recipes), Amount(goal, 1), StockLedger((inventory, chest)))
        second = plan(
            Catalog(3, tuple(reversed(recipes))), Amount(goal, 1), StockLedger((chest, inventory))
        )
        self.assertEqual(first.to_dict(), second.to_dict())
        self.assertTrue(all(not i.blocking for i in first.issues if i.code == "machines_unknown"))
        self.assertTrue(all(not s.machines for s in first.steps))
        limited = plan(
            Catalog(3, recipes), Amount(goal, 1), StockLedger((inventory, chest)), max_steps=1
        )
        self.assertEqual(len(limited.steps), 1)
        self.assertEqual(counts(limited.missing), {a: 1, b: 1})

    def test_seeded_chain_reference_arithmetic(self):
        rng = random.Random(819)
        for _ in range(60):
            depth = rng.randrange(1, 8)
            target = rng.randrange(1, 100)
            needed = target
            recipes, expected_batches = [], {}
            for i in range(depth):
                output, consumption = rng.randrange(1, 6), rng.randrange(1, 5)
                batches = (needed + output - 1) // output
                expected_batches[f"test:r{i}"] = batches
                recipes.append(
                    recipe(
                        f"r{i}", [ing(item(f"x{i + 1}"), consumption)], [(item(f"x{i}"), output)]
                    )
                )
                needed = batches * consumption
            available = max(1, needed - rng.randrange(0, needed))
            result = plan(
                Catalog(1, tuple(recipes)),
                Amount(item("x0"), target),
                stock((item(f"x{depth}"), available)),
            )
            self.assertEqual({s.recipe_id: s.batches for s in result.steps}, expected_batches)
            self.assertEqual(
                counts(result.missing),
                {item(f"x{depth}"): needed - available} if needed > available else {},
            )
            physical = sum(a.quantity for a in result.allocations if a.source_kind == "physical")
            self.assertEqual(physical, available)


if __name__ == "__main__":
    unittest.main()
