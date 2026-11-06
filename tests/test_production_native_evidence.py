"""Native stock/lot decoder contracts, using click-bound synthetic captures only."""

import copy
import unittest
from dataclasses import FrozenInstanceError

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.production.knowledge import Freshness
from monifactory_assistant.production.native_evidence import (
    MAX_AMOUNTS,
    MAX_LOTS,
    MAX_QUANTITY,
    decode_native_evidence,
)
from monifactory_assistant.production.planner import plan
from monifactory_assistant.production.recipes import Amount, Catalog, Resource

NOW = 1_790_000_000_000
SCOPE = WorldScope("m1_" + "a" * 64, "b" * 32, "minecraft:overworld")
IRON = Resource("item", "minecraft:iron_ingot", "item")
WATER = Resource("fluid", "minecraft:water", "mB")


def amounts(quantity, resource=IRON):
    return [Amount(resource, quantity).to_dict()] if quantity else []


def lot(number=1, *, total=8, received=2, cancelled=0, resource=IRON, state="en_cours"):
    return {
        "id": f"emi-lot/{SCOPE.session}/{number}",
        "recette": "gtceu:smelting/iron",
        "origine": "envoi_machine",
        "etat": state,
        "total": amounts(total, resource),
        "recus": amounts(received, resource),
        "restants": amounts(total - received - cancelled, resource),
        "annules": amounts(cancelled, resource),
        "machine_key": f"{SCOPE.dimension}|1|64|-2",
    }


def fixture(*, personal=2, lots=None, menu=False):
    binding = {
        **SCOPE.fields(),
        "capture": "capture-native-1",
        "projet": "project-a",
        "revision_projet": "3:7:1",
        "catalogue_source": "emi/fixture",
        "catalogue_generation": 1,
        "catalogue_sha256": "c" * 64,
    }
    data = {
        "v": 2,
        "t": NOW,
        **binding,
        "actif": True,
        "synchronise": True,
        "inventaire": {IRON.id: personal} if personal else {},
        "stock": {"v": 1, "disponible": False, "mode": "menu_serveur", "limites": []},
        "suivi": {
            "v": 3,
            "disponible": True,
            "revision": 5,
            "empreinte": "d" * 64,
            "lots": [lot()] if lots is None else lots,
            "limites": [],
        },
    }
    if menu:
        data["stock"].update(
            disponible=True,
            revision="full-packet-1",
            observe_ms=NOW - 10,
            capture_ms=NOW,
            coherence="paquet_complet_sans_mutation",
            container_id=2,
            state_id=12,
            sources=[
                {"id": "coffre", "nature": "coffre_ouvert", "items": {IRON.id: 3}},
                {"id": "inventaire", "nature": "inventaire_36", "items": {IRON.id: 2}},
                {"id": "curseur", "nature": "curseur", "items": {IRON.id: 1}},
            ],
        )
    return data, {"type": "production_v2", "preuves_natives": 2, **binding}


def decode(data, request):
    return decode_native_evidence(
        data, scope=SCOPE, request=request, now_ms=NOW, received_at_ms=NOW
    )


def quantities(values):
    return {entry.resource: entry.quantity for entry in values}


class NativeEvidenceTests(unittest.TestCase):
    def test_personal_and_synchronized_backpack_total_counted_only_once(self):
        data, request = fixture(personal=10, lots=[])
        data["backpacks"] = {IRON.id: 8}
        result = decode(data, request)
        self.assertEqual(result.stock_mode, "personnel")
        self.assertEqual(len(result.ledger.sources), 1)
        self.assertEqual(result.ledger.sources[0].id, "native:personnel")
        self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 10})
        self.assertFalse(result.stock_complete)
        self.assertTrue(result.stock_available)
        self.assertEqual(result.observations[0].evidence[0].capture_id, request["capture"])
        self.assertEqual(result.observations[0].evidence[0].world, SCOPE.world)

    def test_open_menu_packet_uses_three_disjoint_sources_not_personal_cache(self):
        data, request = fixture(personal=999, menu=True, lots=[])
        result = decode(data, request)
        self.assertEqual(result.stock_mode, "menu_serveur")
        self.assertEqual(len(result.ledger.sources), 3)
        self.assertEqual(sum(a.quantity for s in result.ledger.sources for a in s.contents), 6)
        self.assertEqual(result.stock_revision, "full-packet-1")
        self.assertEqual(result.observations[0].evidence[0].observed_at_ms, NOW - 10)
        self.assertEqual(
            [source.id for source in result.ledger.sources],
            ["native:0-inventaire", "native:1-curseur", "native:2-coffre"],
        )
        self.assertEqual(
            {row.key for row in result.observations},
            {source.id for source in result.ledger.sources},
        )

    def test_menu_allocates_inventory_then_cursor_before_chest(self):
        data, request = fixture(menu=True, lots=[])
        for needed, expected in (
            (3, [("native:0-inventaire", 2), ("native:1-curseur", 1)]),
            (5, [("native:0-inventaire", 2), ("native:1-curseur", 1), ("native:2-coffre", 2)]),
        ):
            with self.subTest(needed=needed):
                result = decode(data, request)
                planned = plan(Catalog(1, ()), Amount(IRON, needed), result.ledger)
                self.assertEqual(
                    [
                        (allocation.source_id, allocation.quantity)
                        for allocation in planned.allocations
                    ],
                    expected,
                )
        data["stock"]["sources"][0]["items"] = {IRON.id: 5}
        data["stock"]["sources"][1]["items"] = {IRON.id: 8}
        data["stock"]["sources"][2]["items"] = {}
        planned = plan(Catalog(1, ()), Amount(IRON, 8), decode(data, request).ledger)
        self.assertEqual(
            [(allocation.source_id, allocation.quantity) for allocation in planned.allocations],
            [("native:0-inventaire", 8)],
        )

    def test_menu_packet_cannot_also_credit_a_personal_lot_promise(self):
        data, request = fixture(personal=999, menu=True)
        result = decode(data, request)
        planned = plan(Catalog(1, ()), Amount(IRON, 8), result.ledger)
        self.assertEqual(quantities(planned.missing), {IRON: 2})
        self.assertEqual(planned.waiting, ())
        self.assertFalse(result.lots_available)
        self.assertTrue(any("preuve commune" in message for message in result.limitations))
        self.assertEqual(result.observations[-1].evidence[0].freshness, Freshness.UNAVAILABLE)

    def test_partial_receipt_settles_promise_without_adding_physical_stock(self):
        for present, missing in ((2, {}), (0, {IRON: 2})):
            with self.subTest(present=present):
                data, request = fixture(personal=present)
                result = decode(data, request)
                planned = plan(Catalog(1, ()), Amount(IRON, 8), result.ledger)
                self.assertEqual(quantities(planned.waiting), {IRON: 6})
                self.assertEqual(quantities(planned.missing), missing)
                self.assertEqual(result.ledger.reserved, ())
                self.assertEqual(result.lots_fingerprint, "d" * 64)

    def test_cancelled_lot_does_not_leave_pending_output_or_remove_received_stock(self):
        data, request = fixture(lots=[lot(cancelled=6, state="annule")])
        result = decode(data, request)
        planned = plan(Catalog(1, ()), Amount(IRON, 8), result.ledger)
        self.assertEqual(result.ledger.expected, ())
        self.assertEqual(quantities(result.ledger.confirmed[0].outputs), {IRON: 2})
        self.assertEqual(quantities(planned.missing), {IRON: 6})
        self.assertEqual(planned.waiting, ())

    def test_completed_receipt_stays_completed_when_item_leaves_inventory(self):
        data, request = fixture(personal=0, lots=[lot(received=8, state="termine")])
        result = decode(data, request)
        planned = plan(Catalog(1, ()), Amount(IRON, 8), result.ledger)
        self.assertEqual(planned.waiting, ())
        self.assertEqual(quantities(planned.missing), {IRON: 8})
        self.assertEqual(result.observations[-1].value["state"], "termine")

    def test_exact_duplicate_lot_deduplicated_and_conflicting_lot_rejected(self):
        data, request = fixture(lots=[lot(), lot()])
        self.assertEqual(len(decode(data, request).ledger.expected), 1)
        data["suivi"]["lots"][1] = lot(received=3)
        result = decode(data, request)
        self.assertFalse(result.lots_available)
        self.assertEqual(result.ledger.expected, ())
        self.assertTrue(result.stock_available)

    def test_distinct_lots_same_output_do_not_share_receipts(self):
        data, request = fixture(
            personal=8, lots=[lot(received=8, state="termine"), lot(2, received=0)]
        )
        result = decode(data, request)
        planned = plan(Catalog(1, ()), Amount(IRON, 16), result.ledger)
        self.assertEqual(quantities(planned.waiting), {IRON: 8})
        self.assertEqual(planned.missing, ())
        self.assertEqual(
            {a.source_id for a in planned.allocations if a.source_kind == "expected"},
            {lot(2)["id"]},
        )

    def test_fluid_unit_and_exact_nbt_identity_are_preserved(self):
        variant = Resource("item", IRON.id, "item", '{"custom":1b}')
        data, request = fixture(lots=[lot(resource=WATER), lot(2, resource=variant)])
        result = decode(data, request)
        self.assertEqual(
            {a.resource for pending in result.ledger.expected for a in pending.outputs},
            {WATER, variant},
        )
        self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 2})
        planned = plan(Catalog(1, ()), Amount(variant, 8), result.ledger)
        self.assertEqual(quantities(planned.missing), {variant: 2})
        self.assertEqual(quantities(planned.waiting), {variant: 6})

    def test_manual_declaration_has_no_invented_machine_position(self):
        declared = lot()
        declared["origine"] = "declaration_joueur"
        del declared["machine_key"]
        data, request = fixture(lots=[declared])
        self.assertIsNone(decode(data, request).observations[-1].value["machine_key"])
        data["suivi"]["lots"][0]["machine_key"] = f"{SCOPE.dimension}|0|64|0"
        self.assertFalse(decode(data, request).lots_available)

    def test_unknown_lot_followup_and_unsynchronized_inventory_never_credit(self):
        data, request = fixture()
        data["suivi"]["disponible"] = False
        result = decode(data, request)
        self.assertEqual(result.ledger.expected, ())
        self.assertFalse(result.lots_available)
        data["synchronise"] = False
        data["suivi"]["disponible"] = True
        result = decode(data, request)
        self.assertEqual(result.ledger.sources, ())
        self.assertEqual(result.ledger.expected, ())
        self.assertFalse(result.stock_available)

    def test_all_click_project_catalogue_and_scope_bindings_are_exact(self):
        for key, changed in (
            ("capture", "old-click"),
            ("projet", "other-project"),
            ("revision_projet", "old-revision"),
            ("catalogue_source", "emi/old"),
            ("catalogue_generation", 2),
            ("catalogue_generation", True),
            ("catalogue_sha256", "e" * 64),
            ("monde_actif", "m1_" + "e" * 64),
            ("session_monde", "e" * 32),
            ("dimension_monde", "minecraft:the_nether"),
        ):
            with self.subTest(key=key, changed=changed):
                data, request = fixture()
                data[key] = changed
                with self.assertRaises(ValueError):
                    decode(data, request)

    def test_stale_future_and_legacy_captures_cannot_be_relabelled_current(self):
        for changed in ({"v": 1}, {"v": True}, {"t": NOW - 2501}, {"t": NOW + 1}, {"t": True}):
            data, request = fixture()
            data.update(changed)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                decode(data, request)
        data, request = fixture()
        request.pop("preuves_natives")
        with self.assertRaises(ValueError):
            decode(data, request)

    def test_malformed_shared_envelope_is_rejected_even_when_both_copies_agree(self):
        for key, value in (
            ("catalogue_sha256", int("1" * 64)),
            ("catalogue_generation", True),
            ("catalogue_generation", 10**400),
            ("capture", "a/b\nrequest"),
            ("catalogue_source", "history/catalogue"),
        ):
            data, request = fixture()
            data[key] = request[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                decode(data, request)

    def test_menu_requires_whole_packet_revision_and_recent_observation(self):
        for changed in (
            {"coherence": "same-click"},
            {"observe_ms": NOW - 60001},
            {"observe_ms": NOW + 1},
            {"capture_ms": NOW - 1},
            {"state_id": True},
            {"container_id": -1},
            {"revision": ""},
        ):
            data, request = fixture(menu=True)
            data["stock"].update(changed)
            with self.subTest(changed=changed):
                result = decode(data, request)
                self.assertEqual(result.stock_mode, "personnel")
                self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 2})

    def test_duplicate_missing_or_extra_menu_source_rejected(self):
        for action in ("duplicate", "missing", "extra", "wrong-nature"):
            data, request = fixture(menu=True)
            sources = data["stock"]["sources"]
            if action == "duplicate":
                sources[2] = copy.deepcopy(sources[0])
            elif action == "missing":
                sources.pop()
            elif action == "extra":
                sources.append({"id": "other", "nature": "coffre_ouvert", "items": {}})
            else:
                sources[0]["nature"] = "drawers-controller"
            with self.subTest(action=action):
                result = decode(data, request)
                self.assertEqual(result.stock_mode, "personnel")
                self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 2})

    def test_lot_conservation_and_state_must_agree(self):
        for changed in (
            {"recus": amounts(9)},
            {"restants": amounts(5)},
            {"etat": "termine"},
            {"etat": "annule"},
            {"annules": amounts(1), "restants": amounts(5)},
            {"etat": "unknown"},
            {"origine": "ai"},
            {"machine_key": "minecraft:the_nether|1|64|1"},
            {"id": "emi-lot/" + "f" * 32 + "/1"},
        ):
            data, request = fixture()
            data["suivi"]["lots"][0].update(changed)
            with self.subTest(changed=changed):
                result = decode(data, request)
                self.assertFalse(result.lots_available)
                self.assertTrue(result.stock_available)
                self.assertEqual(result.ledger.expected, ())

    def test_limits_and_numeric_types_reject_unbounded_or_malformed_payloads(self):
        for quantity in (True, 0, -1, 1.5, float("nan"), MAX_QUANTITY + 1, 10**400):
            data, request = fixture()
            data["inventaire"][IRON.id] = quantity
            with self.subTest(quantity=quantity):
                result = decode(data, request)
                self.assertFalse(result.stock_available)
                self.assertFalse(result.lots_available)
                self.assertEqual(result.ledger.sources, ())
        data, request = fixture(lots=[lot()] * (MAX_LOTS + 1))
        self.assertFalse(decode(data, request).lots_available)
        data, request = fixture()
        data["suivi"]["lots"][0]["total"] = amounts(1) * (MAX_AMOUNTS + 1)
        self.assertFalse(decode(data, request).lots_available)

    def test_missing_or_malformed_lots_do_not_discard_verified_stock(self):
        for malformed in (None, [], {"v": True}, {"v": 1, "lots": []}):
            data, request = fixture(personal=7)
            data["suivi"] = malformed
            with self.subTest(malformed=malformed):
                result = decode(data, request)
                self.assertTrue(result.stock_available)
                self.assertFalse(result.lots_available)
                self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 7})
                self.assertEqual(result.lots_fingerprint, "")

    def test_obsolete_lot_contract_cannot_credit_its_ambiguous_receipts(self):
        for version in (1, 2):
            with self.subTest(version=version):
                data, request = fixture(personal=7, lots=[lot(received=3)])
                data["suivi"]["v"] = version
                result = decode(data, request)
                self.assertTrue(result.stock_available)
                self.assertFalse(result.lots_available)
                self.assertEqual(result.ledger.expected, ())
                self.assertEqual(result.ledger.confirmed, ())

    def test_missing_requested_chest_has_explicit_personal_fallback(self):
        data, request = fixture(personal=7)
        data.pop("stock")
        data["coffre_demande"] = True
        result = decode(data, request)
        self.assertTrue(result.stock_available)
        self.assertEqual(result.stock_mode, "personnel")
        self.assertTrue(any("Relevé du coffre" in limit for limit in result.limitations))

    def test_malformed_followup_keeps_valid_menu_stock_despite_unknown_personal_cache(self):
        data, request = fixture(personal=0, menu=True)
        data["synchronise"] = False
        data["suivi"] = None
        result = decode(data, request)
        self.assertTrue(result.stock_available)
        self.assertFalse(result.lots_available)
        self.assertEqual(result.stock_mode, "menu_serveur")
        self.assertEqual(sum(a.quantity for s in result.ledger.sources for a in s.contents), 6)

    def test_missing_stock_projection_falls_back_only_to_current_personal_capture(self):
        data, request = fixture(personal=7, lots=[])
        data.pop("stock")
        result = decode(data, request)
        self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 7})
        data.pop("inventaire")
        result = decode(data, request)
        self.assertFalse(result.stock_available)
        self.assertEqual(result.ledger.sources, ())

    def test_decoded_data_is_immutable_and_independent_of_the_raw_capture(self):
        data, request = fixture()
        result = decode(data, request)
        data["inventaire"][IRON.id] = 100
        data["suivi"]["lots"][0]["recus"][0]["quantity"] = 100
        self.assertEqual(quantities(result.ledger.sources[0].contents), {IRON: 2})
        self.assertEqual(quantities(result.ledger.confirmed[0].outputs), {IRON: 2})
        with self.assertRaises(FrozenInstanceError):
            result.stock_available = False
        with self.assertRaises(TypeError):
            result.observations[0].value["nature"] = "other"


if __name__ == "__main__":
    unittest.main()
