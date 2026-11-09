"""Two local installations, same coordinates, independent scopes and delayed exchanges."""

import json
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant.application.game import GameController
from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.game.bridge import Bridge
from monifactory_assistant.game.emi_search import RecipeChestSearch
from monifactory_assistant.game.storage import StorageMonitor
from monifactory_assistant.game.world import World
from monifactory_assistant.game.world_scope import installation_id

WORLD_A = "m1_" + "a" * 64
WORLD_B = "m1_" + "b" * 64
SESSION_A = "1" * 32
SESSION_B = "2" * 32
OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
ROOT = Path(__file__).resolve().parents[1]


def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def tags(world, session, dimension=OVERWORLD):
    return {"monde_actif": world, "session_monde": session, "dimension_monde": dimension}


def state(world, session, dimension=OVERWORLD):
    return {
        **tags(world, session, dimension),
        "dimension": dimension,
        "t": time.time() * 1000,
        "x": 0,
        "y": 64,
        "z": 0,
        "joueur": "Test",
        "inventaire": {"test:plate": 1},
    }


def setup_bridge(root, *, active=True):
    game = root / "game"
    directory = game / "kubejs/assistant"
    directory.mkdir(parents=True)
    if active:
        dump(
            directory / "isolation_mondes.json",
            {
                "v": 1,
                "actif": True,
                "monde": WORLD_A,
                "revision": "e" * 64,
                "installation": installation_id(ROOT, directory),
                "preuve_restauration": "f" * 64,
            },
        )
    with patch("monifactory_assistant.game.bridge.HERE", ROOT):
        return Bridge(game)


def receipt(bridge, world, session, dimension=OVERWORLD):
    dump(
        bridge.dir / "identite_monde.json",
        {
            "v": 1,
            "monde": world,
            "session": session,
            "dimension": dimension,
            "t": time.time() * 1000,
        },
    )


def enter(bridge, world, session, dimension=OVERWORLD):
    receipt(bridge, world, session, dimension)
    dump(bridge.dir / "etat.json", state(world, session, dimension))
    return bridge.state()


def stored(count):
    return {
        "conteneurs": [
            {
                "v": 2,
                "cle": f"{OVERWORLD}|0|64|0",
                "bloc": "minecraft:barrel",
                "nom": "Barrel",
                "dimension": OVERWORLD,
                "x": 0,
                "y": 64,
                "z": 0,
                "t": 1000,
                "objets": [{"item": "test:plate", "n": count}],
            }
        ],
        "machines": [],
    }


class WorldRoutingTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.bridge = setup_bridge(self.root)

    def test_legacy_and_invalid_marker_have_distinct_behavior(self):
        legacy = setup_bridge(self.root / "legacy", active=False)
        dump(legacy.dir / "etat.json", {"t": time.time() * 1000, "dimension": OVERWORLD})
        self.assertTrue(legacy.state())
        legacy.write("commande.json", {"action": "test"})
        self.assertEqual(legacy.read("commande.json"), {"action": "test"})
        (legacy.dir / "isolation_mondes.json").write_text("{invalid", encoding="utf-8")
        blocked = Bridge(legacy.game_dir)
        self.assertTrue(blocked.isolated)
        self.assertIsNone(blocked.state())
        self.assertIsNone(blocked.read("stockage.json"))
        blocked.write("commande.json", {"action": "refus"})
        self.assertEqual(legacy.read("commande.json"), {"action": "test"})

    def test_copied_activation_does_not_activate_another_installation(self):
        other = setup_bridge(self.root / "other", active=False)
        shutil.copyfile(
            self.bridge.dir / "isolation_mondes.json", other.dir / "isolation_mondes.json"
        )
        with patch("monifactory_assistant.game.bridge.HERE", ROOT):
            blocked = Bridge(other.game_dir)
        self.assertTrue(blocked.isolated)
        self.assertEqual(blocked.routing.mode, "blocked")
        self.assertIsNone(enter(blocked, WORLD_A, SESSION_A))

    def test_server_epoch_rejects_replayed_json_before_and_after_reconnection(self):
        self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A))
        snapshot = {
            **tags(WORLD_A, SESSION_A),
            "t": 500,
            "dimension": OVERWORLD,
            "zone": [0, 0, 0, 0],
            "blocs": [{"p": [0, 64, 0], "b": "minecraft:barrel", "o": [["test:plate", 4]]}],
            "joueurs": [{"nom": "Amie", "x": 12, "y": 70, "z": 9, "dimension": NETHER}],
        }
        path_a = self.root / "A.json"
        world_a = World(path_a)
        dump(self.bridge.dir / "monde.json", snapshot)
        self.assertTrue(world_a.poll(self.bridge, OVERWORLD))
        self.assertEqual(world_a.players[0]["dimension"], NETHER)

        # Same visible Minecraft coordinates and player, another connection/world.
        receipt(self.bridge, WORLD_B, SESSION_B)
        self.assertIsNone(self.bridge.read("monde.json"))
        self.assertIsNone(self.bridge.state())
        self.assertTrue(enter(self.bridge, WORLD_B, SESSION_B))
        world_b = World(self.root / "B.json")
        self.assertFalse(world_b.poll(self.bridge, OVERWORLD))
        self.assertFalse(world_b.memory_file.exists())
        dump(self.bridge.dir / "monde.json", {**snapshot, **tags(WORLD_B, SESSION_B)})
        self.assertTrue(world_b.poll(self.bridge, OVERWORLD))
        self.assertEqual(world_b.players, world_a.players)

        self.assertTrue(enter(self.bridge, WORLD_A, "3" * 32))
        dump(self.bridge.dir / "monde.json", snapshot)
        restarted = World(path_a)
        self.assertFalse(restarted.poll(self.bridge, OVERWORLD))
        self.assertEqual(restarted.containers()[0]["objets"][0]["n"], 4)
        self.assertFalse(restarted.active)

    def test_two_scopes_and_installations_never_fall_back_to_legacy_stock(self):
        dump(self.bridge.dir / "stockage.json", stored(99))
        count_by_world = {WORLD_A: 7, WORLD_B: 12}
        for world, count in count_by_world.items():
            dump(self.bridge.dir / "mondes" / world / "stockage.json", stored(count))
        for world, session in [(WORLD_A, SESSION_A), (WORLD_B, SESSION_B), (WORLD_A, "3" * 32)]:
            self.assertTrue(enter(self.bridge, world, session))
            monitor = StorageMonitor()
            storage = monitor.refresh(self.bridge, World(enabled=False))
            self.assertEqual(storage.totals()["test:plate"], count_by_world[world])
        other = setup_bridge(self.root / "other")
        dump(other.dir / "mondes" / WORLD_A / "stockage.json", stored(42))
        self.assertTrue(enter(other, WORLD_A, "4" * 32))
        self.assertEqual(
            StorageMonitor().refresh(other, World(enabled=False)).totals()["test:plate"], 42
        )
        self.assertEqual(json.loads((self.bridge.dir / "stockage.json").read_text()), stored(99))

    def test_dimension_and_session_gate_emi_inventory_actions_and_pending_views(self):
        self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A))
        request = {
            **tags(WORLD_A, SESSION_A),
            "actif": True,
            "n": 1,
            "fin": time.time() + 45,
            "monde": "jeton-natif-emi",
            "dimension": OVERWORLD,
            "objets": ["test:plate"],
        }
        dump(self.bridge.dir / "recherche_emi.json", request)
        dump(self.bridge.dir / "mondes" / WORLD_A / "stockage.json", stored(7))
        search = RecipeChestSearch()
        storage = StorageMonitor().refresh(self.bridge, World(enabled=False))
        view = search.view(self.bridge, storage, self.bridge.state(), time.time())
        self.assertEqual(view["coffres"][0]["n"], 7)
        self.assertEqual(view["session_monde"], SESSION_A)
        self.bridge.write("vue_mod.json", {"ecran": {}})
        old_view = self.bridge.read("vue_mod.json")
        self.assertTrue(self.bridge.accepts(old_view))

        self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A, NETHER))
        self.assertFalse(self.bridge.accepts(old_view))
        self.assertIsNone(search.view(self.bridge, storage, self.bridge.state(), time.time()))
        interface = InterfaceController()
        interface.bridge = self.bridge
        interface.handle_mod_request = Mock()
        outcome = interface.dispatch_queued_request({**tags(WORLD_A, SESSION_A), "n": 1})
        self.assertEqual(outcome["etat"], "refusee")
        interface.handle_mod_request.assert_not_called()

        # An inventory from the preceding session cannot replace the new pockets.
        self.assertTrue(enter(self.bridge, WORLD_A, SESSION_B))
        dump(
            self.bridge.dir / "inventaire_joueur.json",
            {
                **tags(WORLD_A, SESSION_A),
                "joueur": "Test",
                "dimension": OVERWORLD,
                "t": time.time() * 1000,
                "actif": True,
                "synchronise": True,
                "inventaire": {"test:plate": 77},
            },
        )
        self.assertEqual(self.bridge.state()["inventaire"], {"test:plate": 1})

    def test_switch_preserves_places_world_memory_and_old_project_saves_at_rest(self):
        app = GameController()
        app.bridge = self.bridge
        app.world = World(enabled=False)
        first = self.root / "mondes" / WORLD_A
        # An old project save of this world is kept as is, never read nor rewritten.
        dump(first / "plan_ia.json", {"plan": {"objectif": "Mine A"}, "objectif_en_pause": True})
        saved_plan = (first / "plan_ia.json").read_bytes()
        dump(first / "lieux.json", {"Base A": {"x": 0}})
        with patch("monifactory_assistant.application.game.ROOT", self.root):
            self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A))
            app.apply_world_scope(self.bridge.scope)
            self.assertEqual(app.places, {"Base A": {"x": 0}})
            self.assertTrue(enter(self.bridge, WORLD_B, SESSION_B))
            app.apply_world_scope(self.bridge.scope)
            self.assertEqual(app.places, {})
            app.places["Base B"] = {"x": 0}
            app.save_places()
            self.assertTrue(enter(self.bridge, WORLD_A, "3" * 32))
            app.apply_world_scope(self.bridge.scope)
            self.assertEqual(app.places, {"Base A": {"x": 0}})
        self.assertEqual(
            json.loads((self.root / "mondes" / WORLD_B / "lieux.json").read_text()),
            {"Base B": {"x": 0}},
        )
        self.assertEqual((first / "plan_ia.json").read_bytes(), saved_plan)
        self.assertFalse((self.root / "mondes" / WORLD_B / "plan_ia.json").exists())

    def test_server_chests_are_published_for_the_mod_in_the_world_partition(self):
        """coffres_connus.json : relevés du serveur seulement, par monde, écrits s'ils changent."""
        client = stored(7)
        client["conteneurs"][0]["nom"] = "Tonneau étiqueté"

        def frame(t, drawer, chest_items):
            return {
                "t": t,
                "dimension": OVERWORLD,
                "zone": [0, 0, 0, 0],
                "blocs": [
                    # Tiroir jamais ouvert : seul le serveur le connaît.
                    {"p": [2, 64, 2], "b": "functionalstorage:oak_1", "o": drawer},
                    # Coffre vidé depuis la dernière visite : le relevé vide est publié.
                    {"p": [9, 64, 9], "b": "minecraft:barrel", "o": chest_items},
                    # Double coffre : les deux moitiés portent le même inventaire.
                    {"p": [5, 64, 5], "b": "minecraft:chest", "o": [["minecraft:sand", 30]]},
                    {"p": [6, 64, 5], "b": "minecraft:chest", "o": [["minecraft:sand", 30]]},
                    {"p": [4, 64, 4], "b": "gtceu:steam_solid_boiler", "o": [], "m": {"s": "IDLE"}},
                ],
            }

        world = World(enabled=False)
        self.assertTrue(world.update(frame(500, [["minecraft:sand", 379]], []), received_ms=5000))
        app = GameController()
        app.bridge = self.bridge
        app.world = World(enabled=False)
        with patch("monifactory_assistant.application.game.ROOT", self.root):
            self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A))
            app.apply_world_scope(self.bridge.scope)
            app.world = world
            dump(self.bridge.dir / "mondes" / WORLD_A / "stockage.json", client)
            write = Mock(wraps=self.bridge.write_text)
            with patch.object(self.bridge, "write_text", write):
                app.publish_known_chests(app.world)
                published = self.bridge.dir / "mondes" / WORLD_A / "coffres_connus.json"

                def place(bloc, nom, x, z, objets):
                    return {
                        "v": 2,
                        "bloc": bloc,
                        "nom": nom,
                        "dimension": OVERWORLD,
                        "x": x,
                        "y": 64,
                        "z": z,
                        "t": 5000,
                        "objets": objets,
                    }

                sable = [{"item": "minecraft:sand", "n": 30}]
                expected = {
                    "v": 2,
                    "conteneurs": [
                        place(
                            "functionalstorage:oak_1",
                            "Oak 1",
                            2,
                            2,
                            [{"item": "minecraft:sand", "n": 379}],
                        ),
                        place("minecraft:barrel", "Barrel", 9, 9, []),
                        place("minecraft:chest", "Chest", 5, 5, sable),
                        # Seconde moitié : vide, pour ne pas compter deux fois le double coffre.
                        place("minecraft:chest", "Chest", 6, 5, []),
                    ],
                    # Le client n'est jamais recopié : le mod lit stockage.json lui-même.
                    "machines": [
                        {
                            "v": 2,
                            "bloc": "gtceu:steam_solid_boiler",
                            "dimension": OVERWORLD,
                            "x": 4,
                            "y": 64,
                            "z": 4,
                            "t": 5000,
                        }
                    ],
                }
                self.assertEqual(
                    published.read_text(encoding="utf-8"),
                    json.dumps(expected, ensure_ascii=False, separators=(",", ":")),
                )
                self.assertNotIn("Tonneau", published.read_text(encoding="utf-8"))
                self.assertFalse((self.bridge.dir / "coffres_connus.json").exists())
                self.assertEqual(write.call_count, 1)

                # Même mémoire du serveur : aucune réécriture, même si le client change.
                client["conteneurs"][0]["objets"][0]["n"] = 8
                dump(self.bridge.dir / "mondes" / WORLD_A / "stockage.json", client)
                app.publish_known_chests(app.world)
                self.assertEqual(write.call_count, 1)

                # Nouveau relevé du serveur : réécrit.
                self.assertTrue(
                    world.update(frame(900, [["minecraft:sand", 300]], []), received_ms=26000)
                )
                app.publish_known_chests(app.world)
                self.assertEqual(write.call_count, 2)
                self.assertIn('"n":300', published.read_text(encoding="utf-8"))
                saved = published.read_bytes()

                # Monde changé mais pas encore confirmé : aucune partition, rien d'écrit.
                receipt(self.bridge, WORLD_B, SESSION_B)
                self.assertIsNone(self.bridge.data_path("coffres_connus.json"))
                app.publish_known_chests(World(enabled=False))
                self.assertEqual(write.call_count, 2)

                # Autre monde : cache remis à zéro, fichier propre au monde, l'ancien intact.
                self.assertTrue(enter(self.bridge, WORLD_B, SESSION_B))
                app.apply_world_scope(self.bridge.scope)
                self.assertIsNone(app.known_chests_published)
                app.publish_known_chests(app.world)
                self.assertEqual(write.call_count, 3)
        other = json.loads(
            (self.bridge.dir / "mondes" / WORLD_B / "coffres_connus.json").read_text("utf-8")
        )
        self.assertEqual(other, {"v": 2, "conteneurs": [], "machines": []})
        self.assertEqual(published.read_bytes(), saved)

    def test_expired_native_receipt_cannot_reactivate_cached_state(self):
        self.assertTrue(enter(self.bridge, WORLD_A, SESSION_A))
        stamp = time.time() * 1000 - 6000
        dump(
            self.bridge.dir / "identite_monde.json",
            {
                "v": 1,
                "monde": WORLD_A,
                "session": SESSION_A,
                "dimension": OVERWORLD,
                "t": stamp,
            },
        )
        self.assertIsNone(self.bridge.state())
        self.bridge.write("commande.json", {"action": "oublier_coffres"})
        self.assertFalse((self.bridge.dir / "commande.json").exists())

    def test_kubejs_accepts_only_annotated_server_packet_in_active_context(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis")
        client = (ROOT / "jeu/assistant_ia.js").read_text(encoding="utf-8")
        receiver = client[
            client.index("function lirePartie(") : client.index(
                "NetworkEvents.dataReceived('assistant_ia_monde'"
            )
        ]
        helper = client[
            client.index("function memeContexte(") : client.index("function annoterTrame(")
        ]
        script = (
            """
        var DOSSIER = '', mondeRecu = 0, writes = [];
        var current = {monde: 'world-a', session: 'session-a', dimension: 'minecraft:overworld'};
        var contexte = current;
        function contexteCourant() {return current;}
        function nombre(f) {return Number(f[0]());}
        function premiereValeur(f) {return f[0]();}
        function str(s) {return String(s == null ? '' : s);}
        var JsonIO = {
            write: (_, v) => writes.push(v.value),
            parseRaw: str => ({getAsJsonObject: () => {
                const value = JSON.parse(str);
                return {value: value, get: k => ({getAsString: () => value[k]}),
                        addProperty: (k, v) => {value[k] = v;}};
            }})
        };
        """
            + helper
            + receiver
            + """
        function packet(world, session, dimension, n) {
            let values = {
                assistant_monde: world, assistant_session: session,
                p0: JSON.stringify({dimension: dimension, blocs: []})
            };
            return {data: {getInt: () => n, getString: k => values[k]}};
        }
        recevoirMonde(packet('world-a', 'session-a', 'minecraft:overworld', 1));
        current = {monde: 'world-b', session: 'session-b', dimension: 'minecraft:overworld'};
        recevoirMonde(packet('world-a', 'session-a', 'minecraft:overworld', 1));
        contexte = current;
        recevoirMonde(packet('world-a', 'session-a', 'minecraft:overworld', 1));
        recevoirMonde(packet('world-b', 'session-b', 'minecraft:the_nether', 1));
        recevoirMonde(packet('world-b', 'session-b', 'minecraft:overworld', 31));
        recevoirMonde(packet('world-b', 'session-b', 'minecraft:overworld', 1));
        console.log(JSON.stringify(writes));
        """
        )
        result = subprocess.run(
            [node, "-"], input=script, text=True, capture_output=True, check=True, timeout=10
        )
        self.assertEqual(
            [v["monde_actif"] for v in json.loads(result.stdout)], ["world-a", "world-b"]
        )

    def test_kubejs_switch_loads_independent_maps_and_preserves_old_pending_write(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis")
        client = (ROOT / "jeu/assistant_ia.js").read_text(encoding="utf-8")
        prefix = """
        var a = 'm1_' + 'a'.repeat(64), b = 'm1_' + 'b'.repeat(64);
        var activeWorld = a, currentSession = '1'.repeat(32);
        var failRead = false, writes = [], errors = [], runTick;
        function entry(value) { return {get: function(k) {return value[k];}}; }
        var identity = {isolation: () => true, monde: () => activeWorld, session: () => currentSession};
        var memory = {
            lire: function(world, name) {
                if (failRead) throw Error('invalid memory');
                if (name !== 'stockage.json') return null;
                let objet = entry({item: 'test:plate', n: world === a ? 7 : 12});
                let coffre = entry({v: 2, cle: 'minecraft:overworld|0|64|0', x: 0, y: 64, z: 0,
                    bloc: 'minecraft:barrel', dimension: 'minecraft:overworld', nom: 'Barrel',
                    t: 1000, objets: [objet]});
                return entry({conteneurs: [coffre], machines: []});
            },
            ecrire: (world, name, data) => {writes.push([world, name, data]);}
        };
        var Java = {loadClass: function(name) {
            if (name === 'fr.assistantia.IdentiteMonde') return identity;
            if (name === 'fr.assistantia.MemoireMondeKubeJS') return memory;
            return function(){};
        }};
        var Client = {player: {rayTrace: () => null}, level: {dimension: 'minecraft:overworld'}};
        var JsonIO = {read: path => {
                if (path === 'kubejs/assistant/commande.json') return null;
                throw Error('legacy fallback');
            },
            of: v => ({getAsJsonObject: () => v}), write: () => {throw Error('unexpected root write');}};
        var Platform = {isLoaded: () => true};
        var ClientEvents = {tick: fn => {runTick = fn;}, paintScreen: fn => {}};
        var NetworkEvents = {dataReceived: () => {}};
        var console = {error: value => errors.push(value), warn: () => {}};
        """
        suffix = """
        function count() {return stockage['minecraft:overworld|0|64|0'].objets[0].n;}
        runTick({});
        var initial = count();
        stockageModifie = true;
        activeWorld = b;
        currentSession = '2'.repeat(32);
        failRead = true;
        runTick({});
        var failedClosed = contexte == null;
        failRead = false;
        runTick({});
        var second = count();
        activeWorld = a;
        currentSession = '3'.repeat(32);
        runTick({});
        process.stdout.write(JSON.stringify({initial: initial, second: second,
            returned: count(), writes: writes, failedClosed: failedClosed, errors: errors}));
        """
        result = subprocess.run(
            [node, "-"],
            input=prefix + client + suffix,
            text=True,
            capture_output=True,
            check=True,
            timeout=10,
        )
        actual = json.loads(result.stdout)
        self.assertEqual([actual["initial"], actual["second"], actual["returned"]], [7, 12, 7])
        self.assertTrue(actual["failedClosed"])
        self.assertEqual(actual["writes"][0][:2], [WORLD_A, "stockage.json"])
        self.assertEqual(len(actual["writes"]), 1)
        self.assertEqual(len(actual["errors"]), 1)
