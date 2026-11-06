"""Offline qualification of the existing file schemas, without reading a player's data."""

import copy
import itertools
import unittest
from dataclasses import FrozenInstanceError

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.production.knowledge import (
    Completeness,
    Freshness,
    KnowledgeStatus,
    SourceSnapshot,
    build_knowledge,
)

NOW = 1_790_000_000_000
DIM = "minecraft:overworld"
NETHER = "minecraft:the_nether"
SCOPE = WorldScope("m1_" + "a" * 64, "b" * 32, DIM)
OTHER = WorldScope("m1_" + "c" * 64, "d" * 32, DIM)
IRON = "minecraft:iron_ingot"
TIN = "gtceu:tin_ingot"


def source(name, payload, *, scope=SCOPE, received=NOW, **kw):
    if scope is not None:
        payload = {**payload, **scope.fields()}
    return SourceSnapshot(name, payload, received_at_ms=received, **kw)


def state(items=None, **kw):
    return {
        "t": NOW,
        "joueur": "Damien",
        "dimension": DIM,
        "inventaire": {IRON: 2} if items is None else items,
        **kw,
    }


def native(items=None, **kw):
    return {**state(items), "actif": True, "synchronise": True, "backpacks": {IRON: 8}, **kw}


def chest(x=0, *, count=10, block="minecraft:barrel", date=NOW - 500, **kw):
    return {
        "v": 2,
        "x": x,
        "y": 64,
        "z": 0,
        "dimension": DIM,
        "bloc": block,
        "nom": "Barrel",
        "t": date,
        "objets": [{"item": IRON, "n": count}],
        **kw,
    }


def storage(*containers, **kw):
    return SourceSnapshot(
        "stockage.json", {"conteneurs": list(containers), "machines": []}, world=SCOPE.world, **kw
    )


def frame(*blocks, **kw):
    return {"t": NOW, "dimension": DIM, "zone": [0, 0, 3, 3], "blocs": list(blocks), **kw}


def block(x=0, count=10, **kw):
    return {"p": [x, 64, 0], "b": "minecraft:barrel", "o": [[IRON, count]], **kw}


def view(*sources, scope=SCOPE, now=NOW, **kw):
    return build_knowledge(scope, now, sources, **kw)


class ProductionKnowledgeTests(unittest.TestCase):
    def test_native_inventory_replaces_pockets_and_backpack_subtotal_is_not_added(self):
        result = view(
            source("etat.json", state()), source("inventaire_joueur.json", native({IRON: 10}))
        )
        self.assertEqual(result.availability(), {IRON: 10})
        self.assertEqual(len(result.select("inventory")), 1)
        inventory = result.select("inventory")[0]
        self.assertTrue(inventory.value["includes_backpacks"])
        self.assertEqual(inventory.evidence[0].completeness, Completeness.COMPLETE)
        self.assertEqual(len(result.history), 1)
        self.assertFalse(result.stock_universe_complete)
        self.assertEqual(result.version, 1)

    def test_unsynchronized_or_stale_native_inventory_keeps_partial_pockets(self):
        for values in ({"synchronise": False}, {"t": NOW - 2500}, {"t": NOW + 1}, {"actif": False}):
            with self.subTest(values=values):
                result = view(
                    source("etat.json", state()),
                    source("inventaire_joueur.json", native({IRON: 20}, **values)),
                )
                self.assertEqual(result.availability(), {IRON: 2})
                self.assertEqual(
                    result.select("inventory")[0].evidence[0].completeness, Completeness.PARTIAL
                )

    def test_missing_inventory_is_unknown_but_synchronized_empty_is_observed(self):
        data = state()
        del data["inventaire"]
        missing = view(source("etat.json", data))
        self.assertEqual(missing.select("inventory"), ())
        self.assertEqual(missing.availability(), {})
        empty = view(source("etat.json", state()), source("inventaire_joueur.json", native({})))
        self.assertEqual(empty.availability(), {})
        self.assertEqual(len(empty.availability_observations()), 1)
        self.assertEqual(
            empty.select("inventory")[0].evidence[0].completeness, Completeness.COMPLETE
        )

    def test_foreign_player_native_inventory_does_not_add_to_personal_stock(self):
        result = view(
            source("etat.json", state()),
            source("inventaire_joueur.json", native({IRON: 99}, joueur="Camille")),
        )
        self.assertEqual(result.availability(), {IRON: 2})
        self.assertTrue(any("autre joueur" in item for item in result.limitations))

    def test_data_and_nested_observations_are_immutable_copies(self):
        data = native({IRON: 10})
        captured = source("inventaire_joueur.json", data)
        data["inventaire"][IRON] = 99
        result = view(captured)
        self.assertEqual(result.availability(), {IRON: 10})
        with self.assertRaises(TypeError):
            result.select("inventory")[0].value["items"][IRON] = 7
        with self.assertRaises(FrozenInstanceError):
            result.version = 2
        with self.assertRaises(FrozenInstanceError):
            result.sources[0].freshness = Freshness.LIVE

    def test_foreign_world_and_scope_partition_conflict_fail_closed(self):
        for captured in (
            source("inventaire_joueur.json", native(), scope=OTHER),
            source("inventaire_joueur.json", native(), world=OTHER.world),
            SourceSnapshot("stockage.json", {"conteneurs": [chest()]}, world=OTHER.world),
        ):
            with self.subTest(source=captured.name):
                result = view(captured)
                self.assertEqual(result.observations, ())
                self.assertEqual(result.sources[0].freshness, Freshness.UNAVAILABLE)

    def test_old_session_and_other_dimension_stay_historical(self):
        old = WorldScope(SCOPE.world, "c" * 32, DIM)
        other_dim = WorldScope(SCOPE.world, SCOPE.session, NETHER)
        for scope, data in ((old, native()), (other_dim, native(dimension=NETHER))):
            with self.subTest(scope=scope):
                result = view(source("inventaire_joueur.json", data, scope=scope))
                self.assertEqual(result.availability(), {})
                self.assertEqual(
                    result.select("inventory")[0].evidence[0].freshness, Freshness.HISTORICAL
                )

    def test_legacy_data_not_assigned_to_active_world_and_explicit_history_after_disconnect(self):
        legacy = SourceSnapshot("stockage.json", {"conteneurs": [chest()]})
        self.assertEqual(view(legacy).observations, ())
        unscoped = view(legacy, scope=None)
        self.assertEqual(len(unscoped.select("storage")), 1)
        self.assertIsNone(unscoped.select("storage")[0].evidence[0].world)
        disconnected = view(storage(chest()), scope=None, history_world=SCOPE.world)
        self.assertEqual(len(disconnected.select("storage")), 1)
        self.assertEqual(disconnected.availability(), {})

    def test_restart_memory_never_becomes_live_due_to_fresh_receive_time(self):
        old = chest(etat={"s": "WORKING", "pr": 1, "du": 2}, bloc="gtceu:lp_steam_alloy_smelter")
        result = view(
            SourceSnapshot(
                "monde_memoire.json", {"blocs": {"any": old}}, world=SCOPE.world, received_at_ms=NOW
            )
        )
        machine = result.select("machine")[0]
        self.assertEqual(machine.evidence[0].freshness, Freshness.HISTORICAL)
        self.assertIsNone(machine.evidence[0].observed_at_ms)
        self.assertEqual(machine.evidence[0].received_at_ms, NOW - 500)
        self.assertIsNone(machine.evidence[0].session)

    def test_optional_server_absent_keeps_client_history_without_zero_stock_claim(self):
        result = view(storage(chest(count=12)), SourceSnapshot("monde.json", None))
        self.assertEqual(result.select("storage")[0].value["items"], {IRON: 12})
        self.assertEqual(result.availability(), {})
        self.assertEqual(result.sources[-1].freshness, Freshness.UNAVAILABLE)

    def test_server_and_client_same_physical_stock_replace_instead_of_sum(self):
        result = view(storage(chest(count=90)), source("monde.json", frame(block(count=12))))
        self.assertEqual(result.availability(), {IRON: 12})
        self.assertEqual(len(result.select("storage")), 1)
        self.assertEqual(result.history[0].value["items"], {IRON: 90})
        self.assertEqual(result.select("storage")[0].evidence[0].source, "monde.json")

    def test_historical_client_date_cannot_override_live_server(self):
        result = view(
            storage(chest(count=90, date=NOW + 1000)), source("monde.json", frame(block(count=12)))
        )
        self.assertEqual(result.availability(), {IRON: 12})

    def test_empty_observed_inventory_clears_prior_count_but_missing_o_is_unknown(self):
        empty = view(storage(chest(count=90)), source("monde.json", frame(block(count=0))))
        self.assertEqual(empty.select("storage")[0].value["items"], {})
        self.assertEqual(empty.select("storage")[0].evidence[0].completeness, Completeness.COMPLETE)
        unknown = view(
            storage(chest(count=90)),
            source("monde.json", frame({"p": [0, 64, 0], "b": "minecraft:barrel"})),
        )
        self.assertNotIn("items", unknown.select("storage")[0].value)
        self.assertEqual(
            unknown.select("storage")[0].evidence[0].completeness, Completeness.PARTIAL
        )
        self.assertEqual(unknown.availability(), {})
        self.assertEqual(unknown.availability_observations(), ())

    def test_machine_items_do_not_become_free_stock_or_invent_energy(self):
        result = view(
            source(
                "monde.json",
                frame(
                    block(b="gtceu:lp_steam_alloy_smelter", m={"s": "WORKING", "pr": 3, "du": 4})
                ),
            )
        )
        machine = result.select("machine")[0]
        self.assertEqual(machine.value["state"]["s"], "WORKING")
        self.assertNotIn("energy", machine.value)
        self.assertEqual(result.availability(), {})

    def test_double_chests_deduplicated_but_independent_barrels_and_drawers_not(self):
        for item, expected in (
            ("minecraft:chest", 10),
            ("minecraft:barrel", 20),
            ("storagedrawers:oak_full_drawers_1", 20),
        ):
            with self.subTest(item=item):
                result = view(source("monde.json", frame(block(b=item), block(1, b=item))))
                self.assertEqual(result.availability(), {IRON: expected})
                if expected == 10:
                    entry = result.select("storage")[0]
                    self.assertEqual(len(entry.value["positions"]), 2)
                    self.assertTrue(entry.value["shared_identity_inferred"])
                    self.assertEqual(entry.evidence[-1].status, KnowledgeStatus.DERIVED)

    def test_dedup_compares_whole_inventory_and_keeps_ambiguous_chests_unallocated(self):
        result = view(
            source(
                "monde.json",
                frame(
                    block(b="minecraft:chest"),
                    block(1, b="minecraft:chest"),
                    block(2, b="minecraft:chest"),
                ),
            )
        )
        self.assertEqual(result.availability(), {})
        self.assertEqual(len(result.select("storage")), 3)
        different = view(
            source(
                "monde.json",
                frame(
                    block(b="minecraft:chest"),
                    block(1, b="minecraft:chest", o=[[IRON, 10], [TIN, 1]]),
                ),
            )
        )
        self.assertEqual(different.availability(), {IRON: 20, TIN: 1})

    def test_ambiguous_double_chests_are_order_independent(self):
        chests = [block(x, b="minecraft:chest") for x in range(4)]
        for ordering in itertools.permutations(chests):
            result = view(source("monde.json", frame(*ordering)))
            self.assertEqual(result.availability(), {})
            self.assertEqual(len(result.select("storage")), 4)
            self.assertTrue(
                all(row.value["identity_ambiguous"] for row in result.select("storage"))
            )

    def test_legacy_large_chest_keeps_newest_half_even_when_contents_changed(self):
        result = view(
            storage(
                chest(0, block="minecraft:chest", nom="Large Chest", count=80, date=NOW - 1000),
                chest(1, block="minecraft:chest", nom="Large Chest", count=3),
            )
        )
        self.assertEqual(len(result.select("storage")), 1)
        self.assertEqual(result.select("storage")[0].value["items"], {IRON: 3})

    def test_incomplete_coverage_cannot_remove_history(self):
        for extra in ({}, {"chunks_complets": [[1, 1]]}):
            result = view(storage(chest()), source("monde.json", frame(**extra)))
            self.assertEqual(result.select("absence"), ())
            self.assertEqual(len(result.select("storage")), 1)
            self.assertEqual(result.availability(), {})

    def test_complete_coverage_proves_local_absence_with_its_own_reference(self):
        result = view(storage(chest()), source("monde.json", frame(chunks_complets=[[0, 0]])))
        self.assertEqual(result.select("storage"), ())
        absence = result.select("absence")[0]
        self.assertEqual(absence.evidence[0].status, KnowledgeStatus.OBSERVED)
        self.assertEqual(absence.evidence[0].completeness, Completeness.COMPLETE)
        self.assertEqual(absence.value["absence_scope"], "authorized_survey")
        self.assertIn("#absence/", absence.evidence[0].reference)
        self.assertEqual(result.history[0].value["items"], {IRON: 10})

    def test_permission_failure_or_malformed_block_invalidates_coverage_not_history(self):
        for captured in (
            source("monde.json", frame(chunks_complets=[[0, 0]]), authorized=False),
            source("monde.json", frame(chunks_complets=[[0, 0]]), coherent=False),
            source(
                "monde.json",
                frame(
                    {"p": [2, 64, 0], "b": "minecraft:barrel", "o": [[IRON, -1]]},
                    chunks_complets=[[0, 0]],
                ),
            ),
            source("monde.json", frame(chunks_complets=[[99, 99]])),
        ):
            result = view(storage(chest()), captured)
            self.assertEqual(result.select("absence"), ())
            self.assertEqual(len(result.select("storage")), 1)
            self.assertEqual(result.sources[-1].freshness, Freshness.UNAVAILABLE)

    def test_server_freshness_uses_local_receipt_and_never_relabels_old_session(self):
        result = view(source("monde.json", frame(block(), t=NOW + 100_000), received=NOW))
        self.assertEqual(result.availability(), {IRON: 10})
        proof = result.select("storage")[0].evidence[0]
        self.assertEqual(proof.observed_at_ms, NOW + 100_000)
        self.assertEqual(proof.received_at_ms, NOW)
        for date in (NOW - 15_000, NOW + 1, None):
            stale = view(source("monde.json", frame(block()), received=date))
            self.assertEqual(stale.availability(), {})
            self.assertEqual(stale.select("storage")[0].evidence[0].freshness, Freshness.STALE)

    def test_other_players_positions_retained_across_dimensions(self):
        player = {"nom": "Camille", "x": 5, "y": 64, "z": 7, "dimension": NETHER}
        result = view(source("monde.json", frame(joueurs=[player])))
        position = result.select("player")[0]
        self.assertEqual(position.position, (5, 64, 7))
        self.assertEqual(position.evidence[0].dimension, NETHER)
        self.assertEqual(position.evidence[0].freshness, Freshness.LIVE)
        disconnected = view(
            source("monde.json", frame(joueurs=[player])), scope=None, history_world=SCOPE.world
        )
        self.assertEqual(disconnected.select("player")[0].position, (5, 64, 7))
        self.assertEqual(
            disconnected.select("player")[0].evidence[0].freshness, Freshness.HISTORICAL
        )

    def test_conflicting_same_generation_is_unknown_and_not_order_dependent(self):
        captures = [
            source("monde.json", frame(block(count=n)), reference=f"frame-{n}") for n in (3, 9, 3)
        ]
        for ordered in (captures, list(reversed(captures))):
            result = view(*ordered)
            self.assertEqual(result.availability(), {})
            self.assertTrue(
                any(
                    "contradictoires" in message or "Captures serveur différentes" in message
                    for message in result.limitations
                )
            )
            self.assertEqual(
                result.select("storage")[0].evidence[0].status, KnowledgeStatus.UNKNOWN
            )

    def test_duplicate_source_and_inventory_have_no_effect_on_totals(self):
        captured = source("monde.json", frame(block()))
        inventory = source("inventaire_joueur.json", native({IRON: 2}))
        result = view(captured, captured, inventory, inventory, stock_frame_coherent=True)
        self.assertEqual(result.availability(), {IRON: 12})
        self.assertEqual(len(result.availability_observations()), 2)

    def test_independent_personal_and_chest_frames_are_not_summed_by_default(self):
        inventory = source("inventaire_joueur.json", native({IRON: 10}))
        chest_frame = source("monde.json", frame(block(count=10)), received=NOW - 100)
        # The ten items may have moved from the chest to the player between the two exports.
        self.assertEqual(view(inventory, chest_frame).availability(), {IRON: 10})
        self.assertEqual(view(chest_frame).availability(), {IRON: 10})
        self.assertEqual(
            view(inventory, chest_frame, stock_frame_coherent=True).availability(), {IRON: 20}
        )

    def test_successive_server_surveys_do_not_count_moved_items_twice(self):
        older = source("monde.json", frame(block(0), t=NOW - 100), received=NOW - 100)
        newer = source("monde.json", frame(block(32)), received=NOW)
        for coherent in (False, True):
            result = view(older, newer, stock_frame_coherent=coherent)
            self.assertEqual(result.availability(), {IRON: 10})
            self.assertEqual(len(result.select("storage")), 2)
            self.assertEqual(
                sum(
                    row.evidence[0].freshness == Freshness.LIVE for row in result.select("storage")
                ),
                1,
            )

    def test_distinct_server_contents_with_identical_dates_cannot_share_a_stock_frame(self):
        first = source("monde.json", frame(block(0)), reference="first")
        second = source("monde.json", frame(block(32)), reference="second")
        for captures in ((first, second), (second, first)):
            for coherent in (False, True):
                result = view(*captures, stock_frame_coherent=coherent)
                self.assertEqual(result.availability(), {})
                self.assertEqual(len(result.select("storage")), 2)
                self.assertTrue(
                    all(
                        row.evidence[0].status == KnowledgeStatus.UNKNOWN
                        for row in result.select("storage")
                    )
                )
                self.assertEqual(
                    len({row.evidence[0].capture_id for row in result.select("storage")}), 2
                )
                self.assertTrue(all(proof.freshness == Freshness.STALE for proof in result.sources))

    def test_missing_server_clock_does_not_crash_a_tied_local_receipt(self):
        data = frame(block(0))
        del data["t"]
        invalid = source("monde.json", data)
        valid = source("monde.json", frame(block(32)))
        for captures in ((invalid, valid), (valid, invalid)):
            result = view(*captures)
            self.assertEqual(result.availability(), {IRON: 10})
            self.assertEqual(result.availability_observations()[0].position, (32, 64, 0))
            self.assertEqual(
                {proof.freshness for proof in result.sources},
                {Freshness.UNAVAILABLE, Freshness.LIVE},
            )

    def test_unserializable_frame_metadata_cannot_escape_the_source_boundary(self):
        invalid = source("monde.json", {**frame(block()), 1: "not a JSON object key"})
        result = view(invalid)
        self.assertEqual(result.observations, ())
        self.assertEqual(result.sources[0].freshness, Freshness.UNAVAILABLE)

    def test_same_local_receipt_uses_server_order_without_depending_on_input_order(self):
        first = source("monde.json", frame(block(0), t=NOW - 10))
        second = source("monde.json", frame(block(32), t=NOW))
        for captures in ((first, second), (second, first)):
            result = view(*captures)
            self.assertEqual(result.availability(), {IRON: 10})
            self.assertEqual(result.availability_observations()[0].position, (32, 64, 0))

    def test_server_clock_offset_does_not_keep_old_stock_when_new_survey_omits_it(self):
        older = source("monde.json", frame(block(), t=NOW + 100_000), received=NOW - 1000)
        newer = source("monde.json", frame(t=NOW + 101_000, chunks_complets=[[0, 0]]), received=NOW)
        result = view(older, newer)
        self.assertEqual(result.availability(), {})
        self.assertEqual(result.select("absence")[0].value["absence_scope"], "authorized_survey")

    def test_generic_machine_or_network_inventory_is_not_free_storage(self):
        for identifier in (
            "gtceu:lv_bender",
            "functionalstorage:framed_controller_extension",
            "sophisticatedstorage:oak_storage_connector",
            "other:inventory",
        ):
            result = view(source("monde.json", frame(block(b=identifier))))
            self.assertEqual(result.availability(), {})
            self.assertEqual(result.select("block")[0].value["items"], {IRON: 10})
        for identifier in (
            "functionalstorage:oak_1",
            "sophisticatedstorage:limited_1_barrel",
            "sophisticatedstorage:netherite_chest",
        ):
            result = view(source("monde.json", frame(block(b=identifier))))
            self.assertEqual(result.availability(), {IRON: 10})

    def test_veins_only_known_gregtech_deposits_and_zero_scan_is_not_exhausted(self):
        deposit = {
            "id": "vein-1",
            "definition": "gtceu:coal_vein",
            "dimension": DIM,
            "x": 1,
            "y": -20,
            "z": 3,
            "composition": ["gtceu:coal_ore"],
            "epuise": False,
        }
        payload = {
            "v": 1,
            "source": "gregtech",
            "monde": "legacy-id-not-canonical",
            "t": NOW,
            "gisements": [deposit],
            "blocs": [],
        }
        result = view(source("filons_mod.json", payload))
        vein = result.select("vein")[0]
        self.assertEqual(vein.value["observed_blocks"], 0)
        self.assertIsNone(vein.value["remaining_total"])
        self.assertNotIn("exhausted", vein.value)
        self.assertIsNone(vein.evidence[0].observed_at_ms)
        self.assertEqual(vein.evidence[0].freshness, Freshness.HISTORICAL)
        self.assertEqual(vein.evidence[0].world, SCOPE.world)
        self.assertEqual(result.select("vein_exhaustion"), ())

    def test_vein_export_does_not_refresh_blocks_and_manual_exhaustion_is_distinct(self):
        deposit = {
            "id": "v",
            "dimension": DIM,
            "x": 0,
            "y": 64,
            "z": 0,
            "composition": ["gtceu:coal_ore"],
            "epuise_manuel": True,
        }
        ore = {
            "filon": "v",
            "bloc": "gtceu:coal_ore",
            "dimension": DIM,
            "x": 0,
            "y": 64,
            "z": 0,
            "t": NOW - 900_000,
            "mine": False,
        }
        payload = {"v": 1, "source": "gregtech", "t": NOW, "gisements": [deposit], "blocs": [ore]}
        result = view(source("filons_mod.json", payload))
        self.assertEqual(
            result.select("vein")[0].value["latest_block_observed_at_ms"], NOW - 900_000
        )
        self.assertEqual(
            result.select("vein_exhaustion")[0].evidence[0].status, KnowledgeStatus.USER_CONFIRMED
        )

    def test_malformed_counts_boolean_nonfinite_and_unsupported_versions_are_not_zero(self):
        for bad in (-1, True, float("nan"), float("inf"), 1.5, 10**400):
            with self.subTest(bad=str(bad)):
                result = view(source("inventaire_joueur.json", native({IRON: bad})))
                self.assertEqual(result.select("inventory"), ())
                self.assertEqual(result.sources[0].freshness, Freshness.UNAVAILABLE)
        bad = copy.deepcopy(chest())
        bad["v"] = True
        self.assertEqual(view(storage(bad)).observations, ())

    def test_integral_rhino_numbers_and_unknown_future_sources(self):
        result = view(
            source(
                "monde.json",
                frame(
                    block(count=10.0),
                    zone=[0.0, 0.0, 3.0, 3.0],
                    chunks_complets=[[0.0, 0.0]],
                    t=float(NOW),
                ),
            )
        )
        self.assertEqual(result.availability(), {IRON: 10})
        self.assertEqual(result.sources[0].observed_at_ms, NOW)
        unknown = view(source("future.json", {"inventaire": {IRON: 1000}}))
        self.assertEqual(unknown.observations, ())
        self.assertEqual(unknown.sources[0].status, KnowledgeStatus.UNKNOWN)


if __name__ == "__main__":
    unittest.main()
