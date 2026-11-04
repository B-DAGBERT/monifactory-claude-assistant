"""Local EMI chest lookup: variants, dimensions, expiration and live chest changes."""

import ast
import copy
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from monifactory_assistant.game.emi_search import RecipeChestSearch, chest_search


class EmiSearchTests(unittest.TestCase):
    def setUp(self):
        self.request = {
            "n": 123,
            "actif": True,
            "fin": 1600,
            "monde": "server-a",
            "dimension": "minecraft:overworld",
            "recette": "test:craft",
            "objets": ["minecraft:oak_planks", "minecraft:birch_planks", "gtceu:iron_hammer"],
        }
        self.state = {"dimension": "minecraft:overworld", "x": 0, "y": 64, "z": 0}
        self.chests = [
            self.chest(30, "minecraft:oak_planks", 64),
            self.chest(4, "minecraft:birch_planks", 12),
            self.chest(1, "minecraft:diamond", 3),
            self.chest(2, "minecraft:oak_planks", 0),
        ]

    def chest(self, x, item, count, dimension="minecraft:overworld"):
        return {
            "x": x,
            "y": 64,
            "z": 0,
            "dimension": dimension,
            "objets": [{"item": item, "n": count}],
        }

    def find(self, request=None, chests=None, state=None, now=1000):
        return chest_search(
            self.request if request is None else request,
            self.chests if chests is None else chests,
            self.state if state is None else state,
            now,
        )

    def test_variants_tools_positive_counts_and_nearest_chests(self):
        self.chests.append(self.chest(8, "gtceu:iron_hammer", 1))
        result = self.find()
        self.assertEqual([c["x"] for c in result["coffres"]], [4, 8, 30])
        self.assertEqual([c["n"] for c in result["coffres"]], [12, 1, 64])
        self.assertEqual((result["n"], result["monde"]), (123, "server-a"))

    def test_reply_exposes_quantities_for_top_down_client_allocation(self):
        result = self.find()
        self.assertEqual(result["coffres"][0]["objets"], {"minecraft:birch_planks": 12})
        self.assertNotEqual(result["coffres"][0]["stock"], result["coffres"][1]["stock"])

    def test_double_chest_shares_stock_but_drawers_remain_independent(self):
        a = {**self.chest(1, "minecraft:oak_planks", 8), "bloc": "minecraft:chest"}
        b = {**a, "x": 2}
        result = self.find(chests=[a, b])["coffres"]
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["stock"], result[1]["stock"])
        result = self.find(chests=[{**c, "bloc": "functionalstorage:oak_1"} for c in [a, b]])[
            "coffres"
        ]
        self.assertNotEqual(result[0]["stock"], result[1]["stock"])

    def test_other_dimensions_never_have_beams(self):
        self.chests.append(self.chest(0, "minecraft:oak_planks", 100, "minecraft:the_nether"))
        self.assertEqual(self.find()["total_coffres"], 2)
        self.assertIsNone(self.find(state={**self.state, "dimension": "minecraft:the_nether"}))

    def test_stop_disconnect_and_expiration(self):
        self.assertIsNone(self.find(request={**self.request, "actif": False}))
        self.assertIsNone(self.find(now=1600))
        self.assertIsNone(chest_search(self.request, self.chests, None, 1000))
        self.assertIsNone(self.find(request={**self.request, "monde": ""}))

    def test_no_hits_are_reported_instead_of_silent_success(self):
        result = self.find(chests=[])
        self.assertEqual(result["coffres"], [])
        self.assertIn("Aucun ingrédient", result["message"])

    def test_all_matching_chests_are_returned_sorted_by_distance(self):
        result = self.find(chests=[self.chest(x, "minecraft:oak_planks", 1) for x in range(30)])
        self.assertEqual(len(result["coffres"]), 30)
        self.assertEqual(result["total_coffres"], 30)
        self.assertEqual(result["coffres"][-1]["x"], 29)

    def test_duplicate_positions_use_latest_observation_including_empty(self):
        old = {**self.chest(1, "minecraft:oak_planks", 64), "t": 100}
        newer = {**self.chest(1, "minecraft:oak_planks", 0), "t": 200}
        second = self.chest(2, "minecraft:oak_planks", 3)
        for records in ([old, newer, second], [newer, old, second]):
            self.assertEqual([c["x"] for c in self.find(chests=records)["coffres"]], [2])

    def test_reader_uses_all_physical_chests_even_when_totals_deduplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "recherche_emi.json"
            path.write_text(json.dumps(self.request))
            bridge = SimpleNamespace(dir=Path(directory), read=lambda _: self.request)
            storage = SimpleNamespace(containers=self.chests[:1], raw=self.chests)
            result = RecipeChestSearch().view(bridge, storage, self.state, 1000)
            self.assertEqual(result["total_coffres"], 2)

    def test_invalid_coordinates_and_counts_do_not_break_other_results(self):
        records = self.chests + [
            {"x": None},
            self.chest(float("nan"), "minecraft:oak_planks", 4),
            self.chest(8, "minecraft:oak_planks", float("nan")),
        ]
        self.assertEqual(self.find(chests=records)["total_coffres"], 2)

    def test_world_merge_keeps_adjacent_identical_chests_for_outlines(self):
        root = Path(__file__).resolve().parents[1]
        tree = ast.parse((root / "monifactory_assistant/app.py").read_text())
        nodes = [
            n for n in tree.body if isinstance(n, ast.ClassDef) and n.name in {"Storage", "World"}
        ]
        namespace = {}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "storage_test", "exec"), namespace)
        world = object.__new__(namespace["World"])
        a = {**self.chest(1, "minecraft:oak_planks", 3), "cle": "a", "bloc": "test:chest"}
        b = {**a, "cle": "b", "x": 2}
        world.blocks = {"a": a, "b": b}
        merged = world.merged(namespace["Storage"]([]))
        self.assertEqual(len(merged.containers), 1)  # Old totals behavior remains unchanged.
        self.assertEqual(len(merged.raw), 2)  # Both physical positions stay visible.
        self.assertEqual(self.find(chests=merged.raw)["total_coffres"], 2)

    def test_live_inventory_changes_and_no_mutations(self):
        before = copy.deepcopy(self.request)
        self.find()
        self.chests[0]["objets"].clear()
        self.assertEqual(self.find()["total_coffres"], 1)
        self.assertEqual(self.request, before)

    def test_malformed_or_unbounded_requests(self):
        for override in [
            {"fin": "NaN"},
            {"fin": 999999},
            {"n": None},
            {"objets": "minecraft:oak_planks"},
            {"objets": ["minecraft:*"]},
            {"objets": ["minecraft:oak_planks"] * 4097},
        ]:
            self.assertIsNone(self.find(request={**self.request, **override}))

    def test_reader_retries_partial_write_and_keeps_question_channel_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "recherche_emi.json"
            question = Path(directory) / "demande_mod.json"
            question.write_text('{"type":"question","texte":"en cours"}')
            original = question.read_bytes()

            def read(name):
                try:
                    return json.loads((Path(directory) / name).read_text())
                except (ValueError, OSError):
                    return None

            bridge = SimpleNamespace(dir=Path(directory), read=read)
            storage = SimpleNamespace(containers=self.chests)
            service = RecipeChestSearch()
            self.assertIsNone(service.view(bridge, storage, self.state, 1000))
            path.write_text(json.dumps(self.request))
            self.assertEqual(service.view(bridge, storage, self.state, 1000)["total_coffres"], 2)
            path.write_text('{"n":')
            self.assertIsNone(service.view(bridge, storage, self.state, 1001))
            path.write_text(json.dumps({**self.request, "n": 124, "objets": ["minecraft:diamond"]}))
            result = service.view(bridge, storage, self.state, 1001)
            self.assertEqual(result["n"], 124)
            self.assertEqual(result["coffres"][0]["n"], 3)
            self.assertEqual(question.read_bytes(), original)
