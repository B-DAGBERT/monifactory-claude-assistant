"""Revisions used by stock monitoring and EMI search."""

import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.game.emi_search import RecipeChestSearch
from monifactory_assistant.game.storage import Storage, StorageMonitor
from monifactory_assistant.game.world import World


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
        var Java = {loadClass: () => function () {}};
        var NetworkEvents = {dataReceived: function () {}};
        var ServerEvents = {tick: function () {}};
        """
            + source
            + """
        presents.test = 0;
        const p = {
            getName: () => ({getString: () => 'test'}), getX: () => 0, getZ: () => 0,
            server: {players: []},
            sendData: (_, data) => console.log(data.p0),
            level: {
                dimension: 'test:world',
                getChunkSource: () => ({
                    getChunkNow: (x, z) => {
                        if (z === 1 && x === 0) return {getBlockEntitiesPos: () => []};
                        if (z !== 0 || x < 0 || x > 1) return null;
                        return {getBlockEntitiesPos: () => [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map(i => x * 16 + i)};
                    }
                }),
                getBlock: x => ({
                    x: x, y: 64, z: 0, id: 'minecraft:chest',
                    inventory: {getSlots: () => 1, getStackInSlot: () => ({
                        isEmpty: () => false, id: 'test:' + '堆'.repeat(35), getCount: () => 7
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
        self.assertGreater(len(result["blocs"]), 0)
        self.assertLess(len(result["blocs"]), 20)
        self.assertLessEqual(len(run.stdout.strip().encode("utf-8")), 1100)
        self.assertEqual(result["chunks_complets"], [[0, 1]])

    def test_server_reports_empty_inventory_and_never_replaces_stock_with_partial_count(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis pour exécuter le script KubeJS simulé")
        server = Path(__file__).resolve().parents[1] / "serveur/assistant_ia_serveur.js"
        source = server.read_text()
        harness = (
            """
        var Java = {loadClass: () => function () {}};
        var NetworkEvents = {dataReceived: function () {}};
        var ServerEvents = {tick: function () {}};
        """
            + source
            + """
        presents.test = 0;
        const chunks = {
            '0,0': {m_5928_: () => [0]},
            '1,0': {m_5928_: () => [16]},
            '-1,0': {m_5928_: () => [-1]}
        };
        const invs = {
            0: {getSlots: () => 1, getStackInSlot: () => ({isEmpty: () => true})},
            16: {getSlots: () => 201, getStackInSlot: () => { throw Error('limite ignorée'); }},
            '-1': {getSlots: () => 2, getStackInSlot: (i) => {
                if (i === 1) throw Error('lecture interrompue');
                return {isEmpty: () => false, id: 'test:plate', getCount: () => 4};
            }}
        };
        let paquet = null;
        let charges = 0;
        const p = {
            getName: () => ({getString: () => 'test'}), getX: () => 0, getZ: () => 0,
            server: {players: []}, sendData: (_, data) => { paquet = data; },
            level: {
                dimension: 'test:world',
                getChunk: () => { charges++; throw Error('chargement forcé'); },
                m_7726_: () => ({m_7131_: (x, z) => chunks[x + ',' + z]}),
                getBlock: x => ({x: x, y: 64, z: 0, id: 'minecraft:chest', inventory: invs[x]})
            }
        };
        scannerPour(p);
        console.log(JSON.stringify({paquet: paquet, charges: charges}));
        """
        )
        run = subprocess.run(
            [node, "-"], input=harness, capture_output=True, text=True, timeout=10, check=True
        )
        result = json.loads(run.stdout)
        frame = json.loads(result["paquet"]["p0"])
        self.assertEqual(result["charges"], 0)
        self.assertEqual(frame["chunks_complets"], [[0, 0]])
        self.assertEqual(frame["blocs"], [{"p": [0, 64, 0], "b": "minecraft:chest", "o": []}])
        world = object.__new__(World)
        world.last_t, world.blocks = 0, {}
        with patch("monifactory_assistant.game.world.atomic_json"):
            self.assertTrue(world.update(frame))
        recent = frame["t"] - 1000
        known = [
            {
                "x": x,
                "y": 64,
                "z": 0,
                "dimension": "test:world",
                "t": recent,
                "objets": [{"item": "test:plate", "n": 32}],
                "bloc": "minecraft:chest",
            }
            for x in (0, 16, -1)
        ]
        self.assertEqual({c["x"] for c in world.merged(Storage(known)).containers}, {16, -1})

    def test_malformed_trame_cannot_clear_full_chunk(self):
        world = object.__new__(World)
        world.last_t, world.blocks = 0, {}
        chest = {"p": [0, 64, 0], "b": "minecraft:chest", "o": [["test:plate", 4]]}
        frame = {"dimension": "test:world", "zone": [0, 0, 0, 0], "chunks_complets": [[0, 0]]}
        with patch("monifactory_assistant.game.world.atomic_json") as save:
            self.assertTrue(world.update({**frame, "t": 1000, "blocs": [chest]}))
            self.assertFalse(world.update({**frame, "t": 2000}))
            self.assertFalse(
                world.update({**frame, "t": 2000, "blocs": [{**chest, "p": [17, 64, 0]}]})
            )
            self.assertFalse(
                world.update({**frame, "t": 2000, "blocs": [{**chest, "o": [["test:plate", -1]]}]})
            )
            self.assertEqual(world.last_t, 1000)
            self.assertEqual(save.call_count, 1)
            self.assertEqual(len(world.containers()), 1)

    def test_poll_uses_local_receive_time_and_rejects_old_or_other_dimension_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monde.json"
            world = object.__new__(World)
            world.last_t, world.blocks, world.snapshot_stamp = 0, {}, None
            bridge = SimpleNamespace(dir=path.parent, read=lambda _: json.loads(path.read_text()))
            frame = {
                "dimension": "test:world",
                "zone": [0, 0, 0, 0],
                "blocs": [{"p": [0, 64, 0], "b": "minecraft:chest", "o": [["test:plate", 3]]}],
            }
            received = int(time.time() * 1000) - 1000
            path.write_text(json.dumps({**frame, "t": received - 180000}))
            os.utime(path, ns=(received * 1_000_000, received * 1_000_000))
            with patch("monifactory_assistant.game.world.atomic_json"):
                self.assertFalse(world.poll(bridge, "test:nether"))
                self.assertTrue(world.poll(bridge, "test:world"))
                self.assertEqual(world.last_t, received)
                self.assertTrue(world.active)
                self.assertEqual(world.containers()[0]["t"], received)
                path.write_text(json.dumps({**frame, "t": received + 180000}))
                stale = (received - 60000) * 1_000_000
                os.utime(path, ns=(stale, stale))
                self.assertFalse(world.poll(bridge, "test:world"))
                self.assertEqual(world.last_t, received)

    def test_multibyte_packet_reaches_the_existing_client_receiver(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis pour exécuter le script KubeJS simulé")
        root = Path(__file__).resolve().parents[1]
        server = (root / "serveur/assistant_ia_serveur.js").read_text()
        client = (root / "jeu/assistant_ia.js").read_text()
        receiver = client[
            client.index("function lirePartie(") : client.index(
                "NetworkEvents.dataReceived('assistant_ia_monde'"
            )
        ]
        harness = (
            """
        var Java = {loadClass: () => function () {}};
        var NetworkEvents = {dataReceived: function () {}};
        var ServerEvents = {tick: function () {}};
        """
            + server
            + receiver
            + """
        var mondeRecu = 0;
        var DOSSIER = '';
        function premiereValeur(lectures) { return lectures[0](); }
        function nombre(lectures) { return Number(lectures[0]()); }
        var releve = null;
        var JsonIO = {
            parseRaw: texte => ({getAsJsonObject: () => JSON.parse(texte)}),
            write: (_, valeur) => { releve = valeur; }
        };
        presents.test = 0;
        const item = 'test:' + '物'.repeat(22000);
        let tailles = [];
        scannerPour({
            getName: () => ({getString: () => 'test'}), getX: () => 0, getZ: () => 0,
            server: {players: []},
            sendData: (_, data) => {
                for (let i = 0; i < data.n; i++) {
                    let octets = 0;
                    let fragment = data['p' + i];
                    for (let j = 0; j < fragment.length; j++) {
                        let code = fragment.charCodeAt(j);
                        octets += code >= 1 && code <= 127 ? 1 : code <= 2047 ? 2 : 3;
                    }
                    tailles.push(octets);
                }
                recevoirMonde({data: {getInt: k => data[k], getString: k => data[k]}});
            },
            level: {
                dimension: 'test:world',
                getChunkSource: () => ({getChunkNow: (x, z) =>
                    x === 0 && z === 0 ? {getBlockEntitiesPos: () => [0]} : null}),
                getBlock: () => ({x: 0, y: 64, z: 0, id: 'minecraft:chest',
                    inventory: {getSlots: () => 1, getStackInSlot: () => ({
                        isEmpty: () => false, id: item, getCount: () => 1
                    })}})
            }
        });
        console.log(JSON.stringify({tailles: tailles, intact: releve.blocs[0].o[0][0] === item}));
        """
        )
        run = subprocess.run(
            [node, "-"], input=harness, capture_output=True, text=True, timeout=10, check=True
        )
        result = json.loads(run.stdout)
        self.assertGreater(len(result["tailles"]), 1)
        self.assertLessEqual(max(result["tailles"]), 65535)
        self.assertTrue(result["intact"])
