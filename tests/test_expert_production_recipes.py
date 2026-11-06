"""Replay selected recipes captured from the loaded Monifactory Expert 0.13.8 registry."""

import json
import unittest
from pathlib import Path

from monifactory_assistant.production.planner import StockLedger, StockSource, plan
from monifactory_assistant.production.recipes import Amount, Catalog, Resource

FIXTURE = Path(__file__).parent / "fixtures" / "production_v2" / "expert_recipes.json"
ALLOY = "gtceu:alloy_smelter/copper_ingot_and_tin_ingot_into_bronze"
BENDER = "gtceu:bender/bend_bronze_to_plate"
HAMMER = "gtceu:forge_hammer/hammer_bronze_to_plate"
CUTTER = "gtceu:cutter/cut_bronze_block_to_plate_water"
MOULD = "gtceu:fluid_solidifier/solidify_bronze_to_plate"
WIRE = "gtceu:wiremill/mill_copper_wire_2"
MACERATOR = "gtceu:macerator/macerate_gilded_blackstone"
PLATE = Resource("item", "gtceu:bronze_plate", "item")
COPPER = Resource("item", "minecraft:copper_ingot", "item")
TIN = Resource("item", "gtceu:tin_ingot", "item")


class ExpertRecipeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        captured = Catalog.from_dict(json.loads(FIXTURE.read_text(encoding="utf-8")))
        cls.recipes = {recipe.id: recipe for recipe in captured.recipes}

    def catalogue(self, *ids):
        return Catalog(1, tuple(self.recipes[id_] for id_ in ids))

    def test_eight_plates_match_both_real_emi_trees(self):
        for process, batches, copper, tin in ((BENDER, [2, 8], 6, 2), (HAMMER, [3, 4], 9, 3)):
            with self.subTest(process=process):
                catalogue = self.catalogue(ALLOY, process)
                missing = plan(catalogue, Amount(PLATE, 8))
                self.assertEqual([step.batches for step in missing.steps], batches)
                self.assertEqual(
                    {a.resource: a.quantity for a in missing.missing}, {COPPER: copper, TIN: tin}
                )
                stock = StockLedger(
                    sources=(StockSource("native", (Amount(COPPER, copper), Amount(TIN, tin))),)
                )
                supplied = plan(catalogue, Amount(PLATE, 8), stock)
                self.assertEqual(supplied.status, "complete")
                self.assertEqual(supplied.missing, ())
                self.assertEqual(supplied.steps[-1].circuit, 1 if process == BENDER else None)

    def test_cutter_preserves_all_native_options_and_water_millibuckets(self):
        recipe = self.recipes[CUTTER]
        block_slot = next(
            i for i, ingredient in enumerate(recipe.inputs) if len(ingredient.options) > 1
        )
        options = recipe.inputs[block_slot].options
        self.assertEqual(len(options), 8)
        bronze_block = next(a.resource for a in options if a.resource.id == "gtceu:bronze_block")
        catalogue = self.catalogue(CUTTER)
        unresolved = plan(catalogue, Amount(PLATE, 9))
        self.assertIn("ambiguous_ingredient", {issue.code for issue in unresolved.issues})
        selected = plan(
            catalogue, Amount(PLATE, 9), ingredient_choices={f"{CUTTER}#{block_slot}": bronze_block}
        )
        self.assertEqual(len(selected.steps), 1)
        self.assertEqual(
            {(a.resource.id, a.resource.unit, a.quantity) for a in selected.steps[0].inputs},
            {("minecraft:water", "mB", 57), ("gtceu:bronze_block", "item", 1)},
        )

    def test_casting_mould_and_circuit_are_distinct(self):
        casting = plan(self.catalogue(MOULD), Amount(PLATE, 8)).steps[0]
        self.assertEqual(
            [(a.resource.id, a.resource.unit, a.quantity) for a in casting.inputs],
            [("gtceu:bronze", "mB", 1152)],
        )
        self.assertEqual(
            [(a.resource.id, a.quantity) for a in casting.catalysts],
            [("gtceu:plate_casting_mold", 1)],
        )
        double_wire = Resource("item", "gtceu:copper_double_wire", "item")
        wiring = plan(self.catalogue(WIRE), Amount(double_wire, 4)).steps[0]
        self.assertEqual(wiring.circuit, 2)
        self.assertEqual(wiring.inputs, (Amount(COPPER, 4),))
        self.assertEqual(wiring.catalysts, ())

    def test_real_gold_coproduct_is_not_guaranteed(self):
        target = Resource("item", "gtceu:blackstone_dust", "item")
        result = plan(self.catalogue(MACERATOR), Amount(target, 1))
        step = result.steps[0]
        self.assertEqual(step.outputs, (Amount(target, 1),))
        (gold,) = step.probabilistic_outputs_per_batch
        self.assertEqual(
            (gold.amount.resource.id, gold.chance_numerator, gold.chance_denominator),
            ("gtceu:gold_dust", 1, 4),
        )
        self.assertEqual(result.surplus, ())
