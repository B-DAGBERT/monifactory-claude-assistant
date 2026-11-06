"""STOCK-01…15: observed sources and pure production accounting, without a live game.

The ledger cases attest its caller-supplied lot contract, not native acquisition of
reservations: fabrication_emi.json has no dated lot identities and is not that source.
"""

import itertools
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.production.acquisition import acquire
from monifactory_assistant.production.analysis import analyse_observed
from monifactory_assistant.production.catalog import CatalogueView
from monifactory_assistant.production.knowledge import (
    Freshness,
    SourceSnapshot,
    build_knowledge,
)
from monifactory_assistant.production.planner import ProductionLot, StockLedger, StockSource, plan
from monifactory_assistant.production.recipes import (
    Amount,
    Catalog,
    Ingredient,
    Output,
    Recipe,
    Resource,
)

NOW = 1_790_000_000_000
SCOPE = WorldScope("m1_" + "a" * 64, "b" * 32, "minecraft:overworld")
NETHER = WorldScope(SCOPE.world, SCOPE.session, "minecraft:the_nether")
IRON = Resource("item", "minecraft:iron_ingot", "item")
PLATE = Resource("item", "gtceu:iron_plate", "item")
WATER = Resource("fluid", "minecraft:water", "mB")
BUCKET = Resource("item", "minecraft:water_bucket", "item")
EMPTY = Catalog(1, ())


def capture(name, payload, *, scope=SCOPE, received=NOW):
    return SourceSnapshot(name, {**payload, **scope.fields()}, received_at_ms=received)


def inventory(amount, *, scope=SCOPE, stamp=NOW, **extra):
    return capture(
        "inventaire_joueur.json",
        {
            "t": stamp,
            "dimension": scope.dimension,
            "joueur": "Fixture",
            "actif": True,
            "synchronise": True,
            "inventaire": {IRON.id: amount},
            **extra,
        },
        scope=scope,
    )


def container(x, amount, *, block="minecraft:barrel", **extra):
    return {"p": [x, 64, 0], "b": block, "o": [[IRON.id, amount]], **extra}


def survey(*blocks, scope=SCOPE, stamp=NOW, received=NOW):
    return capture(
        "monde.json",
        {"t": stamp, "dimension": scope.dimension, "zone": [0, 0, 3, 3], "blocs": list(blocks)},
        scope=scope,
        received=received,
    )


def knowledge(*sources, scope=SCOPE):
    return build_knowledge(scope, NOW, sources)


def amounts(values):
    return {value.resource: value.quantity for value in values}


def physical(quantity, resource=PLATE, source="inventory"):
    return (StockSource(source, (Amount(resource, quantity),)),) if quantity else ()


def lot(quantity, *, name="project-a/session-1/lot-1", resource=PLATE):
    return ProductionLot(name, (Amount(resource, quantity),))


def allocation(result, kind, resource=PLATE):
    return sum(
        entry.quantity
        for entry in result.allocations
        if entry.source_kind == kind and entry.resource == resource
    )


class ProductionStockMatrixTests(unittest.TestCase):
    def observed_plan(self, snapshot, quantity, resource=IRON):
        return analyse_observed(
            CatalogueView("ready", "fixture", EMPTY),
            snapshot,
            Amount(resource, quantity),
            id_countable=(resource,),
        )

    def test_stock_01_inventory_count_retains_dated_source(self):
        snapshot = knowledge(inventory(3))
        result = self.observed_plan(snapshot, 5)
        self.assertEqual(allocation(result.plan, "physical", IRON), 3)
        self.assertEqual(amounts(result.plan.missing), {IRON: 2})
        proof = result.stock_proofs[0].evidence[0]
        self.assertEqual((proof.source, proof.observed_at_ms), ("inventaire_joueur.json", NOW))
        self.assertEqual((proof.world, proof.session), (SCOPE.world, SCOPE.session))

    def test_stock_02_backpack_total_replaces_pockets_without_adding_subtotal(self):
        pockets = capture(
            "etat.json",
            {
                "t": NOW,
                "joueur": "Fixture",
                "dimension": SCOPE.dimension,
                "inventaire": {IRON.id: 2},
            },
        )
        consolidated = inventory(10, backpacks={IRON.id: 8})
        snapshot = knowledge(pockets, consolidated, consolidated)
        result = self.observed_plan(snapshot, 12)
        self.assertEqual(allocation(result.plan, "physical", IRON), 10)
        self.assertEqual(amounts(result.plan.missing), {IRON: 2})
        self.assertEqual(len(result.stock_proofs), 1)
        self.assertTrue(snapshot.select("inventory")[0].value["includes_backpacks"])

    def test_stock_03_distinct_containers_same_capture_sum_once(self):
        frame = survey(container(0, 6), container(4, 7))
        result = self.observed_plan(knowledge(frame, frame), 15)
        self.assertEqual(allocation(result.plan, "physical", IRON), 13)
        self.assertEqual(amounts(result.plan.missing), {IRON: 2})
        self.assertEqual(len({proof.source_id for proof in result.stock_proofs}), 2)

    def test_stock_04_double_chest_pair_once_ambiguous_chain_not_allocated(self):
        pair = [container(x, 8, block="minecraft:chest") for x in (0, 1)]
        for ordering in itertools.permutations(pair):
            snapshot = knowledge(survey(*ordering))
            self.assertEqual(snapshot.availability(), {IRON.id: 8})
            self.assertEqual(len(snapshot.select("storage")[0].value["positions"]), 2)
        ambiguous = [*pair, container(2, 8, block="minecraft:chest")]
        for ordering in itertools.permutations(ambiguous):
            self.assertEqual(knowledge(survey(*ordering)).availability(), {})

    def test_stock_05_move_between_frames_or_into_backpack_never_doubles(self):
        old = survey(container(0, 10), stamp=NOW - 100, received=NOW - 100)
        new = survey(container(32, 10))
        for frames in ((old, new), (new, old)):
            self.assertEqual(knowledge(*frames).availability(), {IRON.id: 10})
        carried = inventory(10, backpacks={IRON.id: 10})
        self.assertEqual(knowledge(old, new, carried).availability(), {IRON.id: 10})

    def test_stock_06_late_observation_does_not_revive_stock(self):
        late = survey(container(0, 99), stamp=NOW + 100_000, received=NOW - 15_000)
        snapshot = knowledge(late, inventory(4, stamp=NOW - 2500))
        self.assertEqual(snapshot.availability(), {})
        self.assertTrue(
            all(row.evidence[0].freshness == Freshness.STALE for row in snapshot.observations)
        )
        current = survey(container(32, 6), stamp=NOW - 100_000)
        self.assertEqual(knowledge(late, current).availability(), {IRON.id: 6})

    def test_stock_07_unknown_inventory_is_not_a_complete_zero(self):
        unknown = {"p": [0, 64, 0], "b": "minecraft:barrel"}
        snapshot = knowledge(survey(unknown))
        self.assertEqual(snapshot.availability(), {})
        self.assertNotIn("items", snapshot.select("storage")[0].value)
        result = self.observed_plan(snapshot, 5)
        self.assertEqual(amounts(result.plan.missing), {IRON: 5})
        self.assertFalse(result.stock_universe_complete)
        self.assertTrue(any("stock total est inconnu" in limit for limit in result.limits))
        self.assertEqual(result.stock_proofs, ())

    def test_stock_08_reserved_objects_are_excluded_from_physical_allocations(self):
        result = plan(
            EMPTY, Amount(PLATE, 8), StockLedger(physical(8), reserved=(Amount(PLATE, 3),))
        )
        self.assertEqual(allocation(result, "physical"), 5)
        self.assertEqual(amounts(result.missing), {PLATE: 3})
        self.assertEqual(result.waiting, ())
        inconsistent = plan(
            EMPTY, Amount(PLATE, 1), StockLedger(physical(2), reserved=(Amount(PLATE, 3),))
        )
        self.assertIn("reservation_shortage", {issue.code for issue in inconsistent.issues})
        self.assertEqual(allocation(inconsistent, "physical"), 0)

    def test_stock_09_machine_content_and_working_status_do_not_invent_a_lot(self):
        for extra in ({"m": {"s": "WORKING", "pr": 1, "du": 10}}, {}):
            snapshot = knowledge(survey(container(0, 16, block="gtceu:lv_bender", **extra)))
            result = self.observed_plan(snapshot, 16)
            self.assertEqual(allocation(result.plan, "physical", IRON), 0)
            self.assertEqual(result.plan.waiting, ())
            self.assertEqual(amounts(result.plan.missing), {IRON: 16})

    def test_stock_10_expected_lot_is_waiting_not_available_and_duplicate_is_once(self):
        expected = lot(6)
        result = plan(EMPTY, Amount(PLATE, 8), StockLedger(expected=(expected, expected)))
        self.assertEqual(allocation(result, "physical"), 0)
        self.assertEqual(allocation(result, "expected"), 6)
        self.assertEqual(amounts(result.waiting), {PLATE: 6})
        self.assertEqual(amounts(result.missing), {PLATE: 2})

    def test_stock_11_partial_receipt_is_cumulative_and_never_creates_inventory(self):
        for received in (0, 2, 6):
            for present in (0, received):
                with self.subTest(received=received, present=present):
                    ledger = StockLedger(
                        physical(present),
                        expected=(lot(6),),
                        confirmed=(lot(received),) if received else (),
                    )
                    result = plan(EMPTY, Amount(PLATE, 6), ledger)
                    self.assertEqual(allocation(result, "physical"), present)
                    self.assertEqual(allocation(result, "expected"), 6 - received)
                    self.assertEqual(
                        amounts(result.missing),
                        {PLATE: received - present} if received > present else {},
                    )
                    self.assertEqual(plan(EMPTY, Amount(PLATE, 6), ledger), result)

    def test_stock_12_cancel_releases_only_remaining_promise_and_keeps_received_stock(self):
        # The caller removes the cancelled promise; its historical receipt is not physical stock.
        cancelled = StockLedger(physical(2), confirmed=(lot(2),))
        result = plan(EMPTY, Amount(PLATE, 6), cancelled)
        self.assertEqual(allocation(result, "physical"), 2)
        self.assertEqual(result.waiting, ())
        self.assertEqual(amounts(result.missing), {PLATE: 4})
        reacquired = plan(EMPTY, Amount(PLATE, 6), StockLedger(physical(6), confirmed=(lot(2),)))
        self.assertEqual(allocation(reacquired, "physical"), 6)
        self.assertEqual(reacquired.missing, ())

    def test_stock_13_virtual_circuit_is_a_setting_not_an_inventory_ingredient(self):
        recipe = Recipe(
            "fixture:plate",
            "gtceu:bender",
            (Ingredient((Amount(IRON, 1),)),),
            (Output(Amount(PLATE, 1)),),
            ("gtceu:lv_bender",),
            circuit=1,
        )
        result = plan(Catalog(1, (recipe,)), Amount(PLATE, 4), StockLedger(physical(4, IRON)))
        self.assertEqual(result.missing, ())
        self.assertEqual(result.steps[0].circuit, 1)
        self.assertEqual({entry.resource for entry in result.allocations}, {IRON, PLATE})

    def test_stock_14_fluids_use_mb_and_containers_do_not_pay_fluid_demands(self):
        ledger = StockLedger(
            sources=(*physical(1, BUCKET), *physical(400, WATER, "tank")),
            reserved=(Amount(WATER, 100),),
            expected=(lot(500, resource=WATER),),
            confirmed=(lot(200, resource=WATER),),
        )
        result = plan(EMPTY, Amount(WATER, 1000), ledger)
        self.assertEqual(allocation(result, "physical", WATER), 300)
        self.assertEqual(amounts(result.waiting), {WATER: 300})
        self.assertEqual(amounts(result.missing), {WATER: 400})
        self.assertNotIn(BUCKET, {entry.resource for entry in result.allocations})

    def test_stock_15_same_coordinates_in_two_dimensions_remain_separate(self):
        frames = (survey(container(0, 4)), survey(container(0, 99), scope=NETHER))
        for scope, expected in ((SCOPE, 4), (NETHER, 99)):
            snapshot = knowledge(*frames, scope=scope)
            self.assertEqual(snapshot.availability(), {IRON.id: expected})
            self.assertEqual(len(snapshot.select("storage")), 2)
            for row in snapshot.availability_observations():
                self.assertEqual(row.evidence[0].dimension, scope.dimension)

    def test_p4_receipts_settle_only_their_named_lot_in_sequential_cycles(self):
        first, second = lot(4, name="project/first"), lot(4, name="project/second")
        receipt = lot(4, name=first.id)
        ledger = StockLedger(physical(4), expected=(first, second), confirmed=(receipt, receipt))
        result = plan(EMPTY, Amount(PLATE, 8), ledger)
        self.assertEqual(allocation(result, "physical"), 4)
        self.assertEqual(amounts(result.waiting), {PLATE: 4})
        self.assertEqual(
            {entry.source_id for entry in result.allocations if entry.source_kind == "expected"},
            {second.id},
        )
        unrelated = lot(4, name="other-project/first")
        result = plan(
            EMPTY, Amount(PLATE, 8), StockLedger(expected=(first, second), confirmed=(unrelated,))
        )
        self.assertEqual(amounts(result.waiting), {PLATE: 8})
        self.assertEqual(allocation(result, "physical"), 0)

    def test_p4_conflicting_receipts_and_over_receipts_rejected(self):
        with self.assertRaises(ValueError):
            StockLedger(expected=(lot(6),), confirmed=(lot(2), lot(3)))
        with self.assertRaises(ValueError):
            StockLedger(expected=(lot(6),), confirmed=(lot(7),))
        with self.assertRaises(ValueError):
            StockLedger(expected=(lot(6),), confirmed=(lot(1, resource=IRON),))

    def test_p4_cancelled_cycle_does_not_credit_a_new_lot_with_same_output(self):
        old_receipt = lot(2, name="old-project/session-1/lot-1")
        new_expected = lot(6, name="new-project/session-2/lot-1")
        result = plan(
            EMPTY, Amount(PLATE, 6), StockLedger(expected=(new_expected,), confirmed=(old_receipt,))
        )
        self.assertEqual(amounts(result.waiting), {PLATE: 6})
        self.assertEqual(allocation(result, "physical"), 0)

    def test_p4_native_aggregate_progress_is_neither_stock_nor_production_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bridge = SimpleNamespace(dir=root, routing=SimpleNamespace(mode="legacy"))
            (root / "identite_monde.json").write_text(
                json.dumps(
                    {
                        "v": 1,
                        "monde": SCOPE.world,
                        "session": SCOPE.session,
                        "dimension": SCOPE.dimension,
                        "t": NOW,
                    }
                )
            )
            (root / "production_v2_inventaire.json").write_text(
                json.dumps(
                    {
                        **dict(inventory(2).data),
                        "inventaire": {IRON.id: 2},
                        "v": 1,
                        "capture": "stock-matrix",
                    }
                )
            )
            progress = root / "fabrication_emi.json"
            for project, obtained in (("current", 10), ("other", 1_000_000)):
                progress.write_text(
                    json.dumps(
                        {
                            **SCOPE.fields(),
                            "projet": project,
                            "version": "1",
                            "obtenus": {IRON.id: obtained},
                        }
                    )
                )
                with patch(
                    "monifactory_assistant.production.acquisition.time.time",
                    return_value=NOW / 1000,
                ):
                    snapshot = acquire(bridge, root, SCOPE, {"capture": "stock-matrix"}, NOW)
                result = self.observed_plan(snapshot, 5)
                self.assertEqual(allocation(result.plan, "physical", IRON), 2)
                self.assertEqual(amounts(result.plan.missing), {IRON: 3})
                self.assertEqual(result.plan.waiting, ())
                self.assertFalse(any(proof.source == progress.name for proof in snapshot.sources))


if __name__ == "__main__":
    unittest.main()
