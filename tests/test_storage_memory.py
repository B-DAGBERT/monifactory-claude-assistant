"""Legacy client stock de-duplication: newest half, exact order and bounded comparisons."""

import ast
import copy
import random
import tempfile
import unittest
from collections import defaultdict
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]


def storage_class():
    tree = ast.parse((ROOT / "monifactory_assistant/app.py").read_text())
    cls = next(
        node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Storage"
    )
    cls.body = [
        node
        for node in cls.body
        if isinstance(node, ast.FunctionDef)
        and node.name in {"__init__", "load", "twins", "totals"}
    ]
    namespace = {"defaultdict": defaultdict}
    exec(compile(ast.Module(body=[cls], type_ignores=[]), "app.py", "exec"), namespace)
    return namespace["Storage"]


Storage = storage_class()


def chest(x, *, t=10, large=True, count=1, **extra):
    return {
        "v": 2,
        "nom": "Large Chest" if large else "Oak Chest",
        "bloc": "minecraft:chest",
        "dimension": "minecraft:overworld",
        "x": x,
        "y": 64,
        "z": 0,
        "t": t,
        "objets": [{"item": "minecraft:iron_ingot", "n": count}],
        **extra,
    }


def original(containers):
    kept = []
    for candidate in sorted(containers, key=lambda c: -c["t"]):
        if not any(Storage.twins(candidate, other) for other in kept):
            kept.append(candidate)
    return kept


class StorageMemoryTests(unittest.TestCase):
    def load(self, containers, machines=()):
        bridge = SimpleNamespace(
            read=Mock(return_value={"conteneurs": containers, "machines": machines})
        )
        return Storage.load(bridge)

    def test_newest_large_half_wins_even_when_inventory_changed(self):
        older, newer = chest(0, t=10, count=64), chest(1, t=20, count=7)
        result = self.load([older, newer])
        self.assertEqual(result.containers, [newer])
        self.assertIs(result.containers[0], newer)
        self.assertEqual(result.raw, [older, newer])
        self.assertEqual(result.totals(), {"minecraft:iron_ingot": 7})

    def test_name_rule_remains_asymmetric(self):
        small = chest(0, t=20, large=False)
        large = chest(1, t=10)
        self.assertEqual(self.load([large, small]).containers, [small])
        small["t"], large["t"] = 10, 20
        self.assertEqual(self.load([small, large]).containers, [large, small])
        self.assertEqual(
            self.load([chest(0, large=False), chest(1, large=False)]).totals(),
            {"minecraft:iron_ingot": 2},
        )

    def test_equal_dates_keep_input_order_and_do_not_merge_same_position(self):
        a, b = chest(0), chest(1)
        self.assertEqual(self.load([a, b]).containers, [a])
        self.assertEqual(self.load([b, a]).containers, [b])
        duplicate = chest(0, count=8)
        self.assertEqual(self.load([a, duplicate]).containers, [a, duplicate])

    def test_three_neighbor_chain_preserves_legacy_kept_only_comparisons(self):
        left, middle, right = chest(0, t=30), chest(1, t=20), chest(2, t=10)
        self.assertEqual(self.load([left, middle, right]).containers, [left, right])
        middle["t"] = 40
        self.assertEqual(self.load([left, middle, right]).containers, [middle])

    def test_all_four_neighbors_and_block_height_dimension_boundaries(self):
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            with self.subTest(dx=dx, dz=dz):
                newer = chest(0, t=20)
                older = chest(dx, z=dz, t=10)
                self.assertEqual(self.load([older, newer]).containers, [newer])
        neighbors = [
            chest(1, bloc="minecraft:trapped_chest"),
            chest(1, dimension="minecraft:the_nether"),
            chest(1, y=65),
            chest(1, z=1),
        ]
        data = [chest(0, t=20), *neighbors]
        self.assertEqual(self.load(data).containers, data)

    def test_nonstandard_coordinates_preserve_original_predicate(self):
        cases = [
            [chest(0.5, t=20), chest(1.5, t=10)],
            [chest(False, t=20), chest(1, t=10)],
            [chest(0, t=20), chest(True, t=10)],
            [chest(0, dimension=None, t=20), chest(1, dimension=None, t=10)],
        ]
        for data in cases:
            with self.subTest(data=data):
                self.assertEqual(self.load(data).containers, original(data))

    def test_version_filter_machines_raw_order_and_input_are_preserved(self):
        data = [chest(2, v=1), chest(3, t=20), chest(0, t=10)]
        before = copy.deepcopy(data)
        machines = [{"v": 1, "bloc": "old"}, {"v": 2, "bloc": "current"}]
        result = self.load(data, machines)
        self.assertEqual(result.raw, data[1:])
        self.assertEqual(result.machines, machines[1:])
        self.assertEqual(data, before)

    def test_missing_memory_is_empty_but_partial_existing_file_is_not(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = SimpleNamespace(dir=Path(directory), read=Mock(return_value=None))
            self.assertEqual(Storage.load(bridge).containers, [])
            (bridge.dir / "stockage.json").write_text('{"conteneurs":')
            self.assertIsNone(Storage.load(bridge))

    def test_index_matches_historical_order_for_random_mixed_storage(self):
        rng = random.Random(541)
        for _ in range(25):
            data = [
                chest(
                    rng.randrange(-8, 9),
                    z=rng.randrange(-8, 9),
                    y=rng.randrange(64, 66),
                    t=rng.randrange(1, 6),
                    large=rng.choice([True, False]),
                    count=rng.randrange(1, 65),
                    bloc=rng.choice(
                        ["minecraft:chest", "sophisticatedstorage:chest", "minecraft:barrel"]
                    ),
                    dimension=rng.choice(["minecraft:overworld", "minecraft:the_nether"]),
                )
                for _ in range(200)
            ]
            self.assertEqual(self.load(data).containers, original(data))

    def test_ten_thousand_observations_do_not_compare_all_kept_chests(self):
        twins = Storage.twins
        for large in (False, True):
            with self.subTest(large=large):
                data = [chest(i * 3, large=large) for i in range(10_000)]
                comparisons = 0

                def bounded_twins(a, b):
                    nonlocal comparisons
                    comparisons += 1
                    self.assertLessEqual(
                        comparisons, 4 * len(data), "quadratic scan of kept chests"
                    )
                    return twins(a, b)

                with patch.object(Storage, "twins", side_effect=bounded_twins):
                    result = self.load(data)
                self.assertEqual(result.containers, data)
                self.assertEqual(result.totals(), {"minecraft:iron_ingot": len(data)})
