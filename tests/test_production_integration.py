"""Replay a small production chain across catalogue, knowledge, planning and diagnosis."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.production.analysis import analyse_observed
from monifactory_assistant.production.catalog import CatalogueReader, catalogue_document
from monifactory_assistant.production.knowledge import SourceSnapshot, build_knowledge
from monifactory_assistant.production.recipes import (
    Amount,
    Catalog,
    Ingredient,
    Output,
    Recipe,
    Resource,
)

NOW = 1_790_000_000_000
SCOPE = WorldScope("m1_" + "1" * 64, "a" * 32, "minecraft:overworld")
COPPER, TIN, BRONZE, PLATE = (
    Resource("item", "fixture:" + name, "item") for name in ("copper", "tin", "bronze", "plate")
)
BENDER = "gtceu:lv_bender"
SMELT = Recipe(
    "fixture:bronze_alloy",
    "gtceu:alloy_smelter",
    (Ingredient((Amount(COPPER, 3),)), Ingredient((Amount(TIN, 1),))),
    (Output(Amount(BRONZE, 4)),),
    ("gtceu:lp_steam_alloy_smelter",),
)
BEND = Recipe(
    "fixture:bronze_plate",
    "gtceu:bender",
    (Ingredient((Amount(BRONZE, 1),)),),
    (Output(Amount(PLATE, 1)),),
    (BENDER,),
    10,
    16,
    1,
)


class ProductionIntegrationTests(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.directory = Path(workspace.name)
        self.catalogue = self.directory / "catalogue.json"
        self.request = self.directory / "request.json"
        atomic_json(
            self.catalogue,
            catalogue_document("test-catalogue", Catalog(1, (BEND, SMELT)).to_dict()),
        )

    def test_reliable_stock_and_explainable_machine_observation_from_one_snapshot(self):
        identity = {**SCOPE.fields(), "dimension": SCOPE.dimension, "t": NOW - 10}
        state = SourceSnapshot(
            "etat.json", {**identity, "joueur": "Test", "inventaire": {COPPER.id: 6, PLATE.id: 1}}
        )
        consolidated = SourceSnapshot(
            "inventaire_joueur.json",
            {
                **identity,
                "joueur": "Test",
                "actif": True,
                "synchronise": True,
                "inventaire": {COPPER.id: 6, TIN.id: 2, PLATE.id: 2, BRONZE.id: 1},
            },
        )
        server = SourceSnapshot(
            "monde.json",
            {
                **identity,
                "zone": [0, 0, 0, 0],
                "blocs": [
                    {"p": [1, 64, 1], "b": BENDER, "m": {"s": "WAITING", "r": "Inputs"}},
                ],
                "joueurs": [
                    {"nom": "Autre", "dimension": "minecraft:the_nether", "x": 3, "y": 64, "z": -1}
                ],
            },
            received_at_ms=NOW - 10,
        )
        snapshot = build_knowledge(SCOPE, NOW, (state, consolidated, server))
        view = CatalogueReader(self.catalogue, "test-catalogue").refresh()
        analysis = analyse_observed(
            view,
            snapshot,
            Amount(PLATE, 9),
            id_countable=(COPPER, TIN, BRONZE, PLATE),
            diagnose_recipe=BEND.id,
        )
        self.assertEqual(snapshot.select("player")[0].position, (3, 64, -1))
        self.assertEqual(
            [(step.recipe_id, step.batches) for step in analysis.plan.steps],
            [(SMELT.id, 2), (BEND.id, 7)],
        )
        self.assertEqual(
            sum(
                a.quantity
                for a in analysis.plan.allocations
                if a.resource == COPPER and a.source_kind == "physical"
            ),
            6,
        )
        self.assertFalse(analysis.stock_universe_complete)
        self.assertEqual(analysis.stock_proofs[0].evidence[0].source, "inventaire_joueur.json")
        self.assertTrue(
            any(finding.code == "machine_waiting" for finding in analysis.diagnosis.findings)
        )
        self.assertFalse(
            any(finding.code == "circuit_mismatch" for finding in analysis.diagnosis.findings)
        )

        anonymous = analyse_observed(view, snapshot, Amount(PLATE, 9), diagnose_recipe=BEND.id)
        self.assertEqual(anonymous.plan.status, "partial")
        self.assertFalse(anonymous.stock_proofs)
        self.assertIn(COPPER, {entry.resource for entry in anonymous.plan.missing})

    def test_explicit_command_outputs_the_same_result_and_rejects_another_source(self):
        data = {
            "schema": "monifactory.production.request",
            "version": 1,
            "target": Amount(PLATE, 8).to_dict(),
            "sources": [
                {
                    "id": "coffre-1",
                    "contents": [Amount(COPPER, 6).to_dict(), Amount(TIN, 2).to_dict()],
                }
            ],
        }
        atomic_json(self.request, data)
        command = [
            sys.executable,
            "-B",
            "-m",
            "monifactory_assistant.commands.plan_production",
            str(self.catalogue),
            str(self.request),
            "--source",
        ]
        first = subprocess.run(
            [*command, "test-catalogue"], capture_output=True, text=True, check=True
        )
        second = subprocess.run(
            [*command, "test-catalogue"], capture_output=True, text=True, check=True
        )
        self.assertEqual(first.stdout, second.stdout)
        result = json.loads(first.stdout)
        self.assertEqual(result["status"], "complete")
        self.assertEqual([row["batches"] for row in result["steps"]], [2, 8])
        self.assertEqual(result["catalogue"]["status"], "ready")
        rejected = subprocess.run([*command, "other-catalogue"], capture_output=True, text=True)
        self.assertEqual(rejected.returncode, 2)
        self.assertFalse(rejected.stdout)

    def test_default_plan_does_not_double_count_a_stock_moved_between_relevés(self):
        identity = {**SCOPE.fields(), "dimension": SCOPE.dimension, "t": NOW - 10}
        chest_before_move = SourceSnapshot(
            "monde.json",
            {
                **identity,
                "zone": [0, 0, 0, 0],
                "blocs": [{"p": [1, 64, 1], "b": "minecraft:barrel", "o": [[PLATE.id, 8]]}],
            },
            received_at_ms=NOW - 30,
        )
        player_after_move = SourceSnapshot(
            "etat.json", {**identity, "joueur": "Test", "inventaire": {PLATE.id: 8}}
        )
        observations = (chest_before_move, player_after_move)
        view = CatalogueReader(self.catalogue, "test-catalogue").refresh()
        cautious = analyse_observed(
            view,
            build_knowledge(SCOPE, NOW, observations),
            Amount(PLATE, 15),
            id_countable=(PLATE,),
        )
        self.assertEqual(cautious.plan.status, "partial")
        self.assertEqual(
            sum(row.quantity for row in cautious.plan.allocations if row.source_kind == "physical"),
            8,
        )
        self.assertEqual(len(cautious.stock_proofs), 1)

        # A separate fixture represents two disjoint locations captured together.
        independent_chest = SourceSnapshot(
            "monde.json", chest_before_move.data, received_at_ms=NOW - 10
        )
        attested = analyse_observed(
            view,
            build_knowledge(
                SCOPE, NOW, (independent_chest, player_after_move), stock_frame_coherent=True
            ),
            Amount(PLATE, 15),
            id_countable=(PLATE,),
        )
        self.assertEqual(attested.plan.status, "complete")
        self.assertEqual(len(attested.stock_proofs), 2)
