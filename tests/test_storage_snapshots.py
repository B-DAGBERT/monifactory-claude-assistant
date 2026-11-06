"""Revisions used by stock monitoring and EMI search."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.game.emi_search import RecipeChestSearch
from monifactory_assistant.game.storage import Storage, StorageMonitor


class StorageSnapshotTests(unittest.TestCase):
    def test_partial_write_keeps_previous_stock_and_atomic_replacement_is_seen(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stockage.json"

            def read(_name):
                try:
                    return json.loads(path.read_text())
                except (OSError, ValueError):
                    return None

            bridge = SimpleNamespace(dir=path.parent, read=Mock(side_effect=read))
            world = SimpleNamespace(
                last_t=0, blocks={"server": True}, merged=Mock(side_effect=lambda s: s)
            )
            monitor = StorageMonitor()
            path.write_text('{"conteneurs":[]}')
            first = monitor.refresh(bridge, world)
            self.assertIs(monitor.refresh(bridge, world), first)
            self.assertEqual(bridge.read.call_count, 1)
            self.assertEqual(world.merged.call_count, 1)
            path.write_text('{"conteneurs":')
            self.assertIs(monitor.refresh(bridge, world), first)
            path.write_text('{"conteneurs":[]}')
            before = path.stat()
            second = monitor.refresh(bridge, world)
            new = path.with_suffix(".new")
            new.write_text(path.read_text())
            os.utime(new, ns=(before.st_atime_ns, before.st_mtime_ns))
            new.replace(path)
            self.assertIsNot(monitor.refresh(bridge, world), second)

    def test_stock_export_keeps_legacy_text_and_only_changes_at_the_next_minute(self):
        chest = {
            "x": 1,
            "y": 64,
            "z": 3,
            "dimension": "test:world",
            "bloc": "minecraft:chest",
            "nom": "Chest",
            "t": 1_790_000_040_000,
            "objets": [{"item": "test:plate", "n": 4}],
        }
        first = Storage([chest])
        same_minute = Storage([{**chest, "t": chest["t"] + 1000}])
        later = Storage([{**chest, "t": chest["t"] + 60_000}])
        date = datetime.fromtimestamp(chest["t"] / 1000).strftime("%d/%m %H:%M")
        self.assertEqual(
            first.as_text(),
            f"test:plate | 4 | 1 64 3 | test:world | minecraft:chest (Chest) | {date}\n",
        )
        self.assertEqual(same_minute.export_key(), first.export_key())
        self.assertNotEqual(later.export_key(), first.export_key())

    def test_emi_movement_resorts_and_replacement_invalidates_hits(self):
        with tempfile.TemporaryDirectory() as directory:
            request = {
                "actif": True,
                "n": 1,
                "fin": 600,
                "monde": "test",
                "dimension": "test:world",
                "objets": ["test:plate"],
            }
            path = Path(directory) / "recherche_emi.json"
            path.write_text(json.dumps(request))
            bridge = SimpleNamespace(dir=path.parent, read=lambda _: json.loads(path.read_text()))
            contents = [{"item": "test:plate", "n": 4}]
            a = {"dimension": "test:world", "x": 1, "y": 64, "z": 0, "objets": contents}
            b = {**a, "x": 10}
            storage = Storage([a, b])
            service = RecipeChestSearch()
            from monifactory_assistant.game.emi_search import _matching_chests

            with patch(
                "monifactory_assistant.game.emi_search._matching_chests", wraps=_matching_chests
            ) as scan:
                self.assertEqual(service.view(bridge, storage, a, 0)["coffres"][0]["x"], 1)
                self.assertEqual(service.view(bridge, storage, b, 1)["coffres"][0]["x"], 10)
                self.assertEqual(scan.call_count, 1)
                self.assertEqual(service.view(bridge, Storage([b]), a, 2)["coffres"][0]["x"], 10)
                self.assertEqual(scan.call_count, 2)
                self.assertIsNone(service.view(bridge, storage, b, 601))

    def test_server_truncation_invalidates_only_affected_chunks(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis pour exécuter le script KubeJS simulé")
        server = Path(__file__).resolve().parents[1] / "serveur/assistant_ia_serveur.js"
        source = server.read_text().replace("const TAILLE_MAX = 600000", "const TAILLE_MAX = 1100")
        harness = (
            """
        Java = {loadClass: () => class {}};
        NetworkEvents = {dataReceived() {}};
        ServerEvents = {tick() {}};
        """
            + source
            + """
        presents.test = Date.now();
        const p = {
            getName: () => ({getString: () => 'test'}), getX: () => 0, getZ: () => 0,
            server: {players: []},
            sendData: (_, data) => console.log(data.p0),
            level: {
                dimension: 'test:world',
                getChunk: (x, z) => {
                    if (z === 1 && x === 0) return {getBlockEntitiesPos: () => []};
                    if (z !== 0 || x < 0 || x > 1) return null;
                    return {getBlockEntitiesPos: () => Array.from({length: 10}, (_, i) => x * 16 + i)};
                },
                getBlock: x => ({
                    x, y: 64, z: 0, id: 'minecraft:chest',
                    inventory: {getSlots: () => 1, getStackInSlot: () => ({
                        isEmpty: () => false, id: 'test:' + 'ore'.repeat(20), getCount: () => 7
                    })}
                })
            }
        };
        scannerPour(p);
        """
        )
        run = subprocess.run(
            [node, "-"], input=harness, capture_output=True, text=True, timeout=10, check=True
        )
        result = json.loads(run.stdout)
        self.assertLess(len(result["blocs"]), 20)
        self.assertEqual(result["chunks_complets"], [[0, 1]])
