"""Keep observations after a server feed is gone and label them in both providers' prompt."""

import json
import os
import tempfile
import unittest
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.application.game import GameController
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import World, describe_world
from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.persistence import atomic_json

OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
START = 1_790_000_000_000
MACHINE = {
    "p": [3, 64, 0],
    "b": "gtceu:lp_steam_alloy_smelter",
    "m": {"s": "WORKING", "pr": 3, "du": 4},
}
CHEST = {"p": [4, 64, 0], "b": "minecraft:barrel", "o": [["gtceu:tin_ingot", 9]]}
PLAYER = {"nom": "Camille", "x": 8, "y": 64, "z": 9, "dimension": NETHER}


def frame(*blocks, t=START, dimension=OVERWORLD, players=()):
    return {
        "t": t,
        "dimension": dimension,
        "zone": [0, 0, 0, 0],
        "blocs": list(blocks),
        "joueurs": list(players),
    }


class WorldFreshnessTests(unittest.TestCase):
    def setUp(self):
        directory = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(directory)
        self.enterContext(
            patch("monifactory_assistant.game.world.WORLD_FILE", self.root / "world.json")
        )
        self.clock = self.enterContext(
            patch("monifactory_assistant.game.world.time.time", return_value=START / 1000)
        )
        self.world = World()

    def test_restart_retains_historical_machine_and_stock_even_inside_freshness_window(self):
        self.assertTrue(self.world.update(frame(MACHINE, CHEST, players=[PLAYER])))
        self.assertTrue(self.world.active)
        self.assertIn("Machines vues par le serveur (état en direct)", describe_world(self.world))
        restarted = World()
        self.assertFalse(restarted.active)
        self.assertEqual(restarted.machines(), self.world.machines())
        self.assertEqual(restarted.containers(), self.world.containers())
        self.assertIn("Machines du serveur (historique", describe_world(restarted))
        self.assertNotIn("état en direct", describe_world(restarted))

    def test_recent_feed_does_not_refresh_unseen_or_other_dimension_machine(self):
        self.world.update(frame(MACHINE, CHEST))
        self.clock.return_value += 3
        self.assertTrue(
            self.world.update(frame(t=START + 3_000, dimension=NETHER, players=[PLAYER]))
        )
        self.assertTrue(self.world.active)
        old = describe_world(self.world)
        self.assertIn("Machines du serveur (historique", old)
        self.assertIn("Autres joueurs connectés", old)
        self.assertIn("Camille en 8 64 9 (minecraft:the_nether)", old)
        self.assertIn("minecraft:overworld", old)
        self.assertEqual(self.world.containers()[0]["t"], START)
        self.clock.return_value += 3
        self.assertTrue(self.world.update(frame(MACHINE, t=START + 6_000)))
        self.assertIn("état en direct", describe_world(self.world))

    def test_disconnection_and_script_absence_keep_positions_but_mark_them_historical(self):
        self.world.update(frame(MACHINE, CHEST, players=[PLAYER]))
        self.world.invalidate_live()
        self.assertFalse(self.world.active)
        text = describe_world(self.world)
        self.assertIn("Dernières positions connues des autres joueurs", text)
        self.assertIn("Camille en 8 64 9 (minecraft:the_nether)", text)
        self.assertNotIn("état en direct", text)
        self.assertEqual(self.world.containers()[0]["objets"][0]["n"], 9)
        self.clock.return_value += 3
        self.world.update(frame(MACHINE, t=START + 3_000))
        self.assertTrue(self.world.active)
        self.clock.return_value += 15
        self.assertFalse(self.world.active)
        self.assertNotIn("état en direct", describe_world(self.world))

    def test_bad_or_older_packets_do_not_reactivate_feed_and_client_only_stock_still_works(self):
        self.world.update(frame(MACHINE, CHEST))
        self.world.invalidate_live()
        self.assertFalse(self.world.update(frame(MACHINE)))
        self.assertFalse(self.world.update(frame({}, t=START + 3_000)))
        self.assertFalse(self.world.active)
        standalone = World()
        standalone.blocks = {}
        chest = {**self.world.containers()[0], "x": 17}
        self.assertEqual(standalone.merged(Storage([chest])).containers, [chest])
        self.assertEqual(describe_world(standalone), "")

    def test_poll_rejects_wrong_dimension_and_old_local_reception(self):
        path = self.root / "monde.json"
        bridge = SimpleNamespace(
            dir=self.root, read=lambda name: json.loads((self.root / name).read_text())
        )
        path.write_text(json.dumps(frame(MACHINE)), encoding="utf-8")
        os.utime(path, ns=(START * 1_000_000, START * 1_000_000))
        self.assertFalse(self.world.poll(bridge, NETHER))
        self.clock.return_value += 20
        self.assertFalse(self.world.poll(bridge, OVERWORLD))
        self.assertFalse(self.world.active)
        received = START + 20_000
        os.utime(path, ns=(received * 1_000_000, received * 1_000_000))
        self.assertTrue(self.world.poll(bridge, OVERWORLD))
        self.assertTrue(self.world.active)
        path.write_text(json.dumps(frame(t=START - 1)), encoding="utf-8")
        self.clock.return_value += 3
        replayed = START + 23_000
        os.utime(path, ns=(replayed * 1_000_000, replayed * 1_000_000))
        self.assertFalse(self.world.poll(bridge, OVERWORLD))
        self.assertEqual(self.world.last_t, received)

    def test_live_player_positions_are_present_even_without_any_visible_inventories(self):
        self.world.update(frame(players=[PLAYER]))
        self.assertIn("Camille en 8 64 9", describe_world(self.world))
        self.assertNotIn("Machines", describe_world(self.world))

    def test_game_watch_invalidates_live_status_before_waiting_for_next_packet(self):
        game = GameController()
        game.world, game.game = self.world, {"dimension": OVERWORLD}
        game.session_open = False
        game.bridge = SimpleNamespace(state=Mock(side_effect=[{"dimension": NETHER}, None]))
        game.root = Mock()
        game.check_recipes = game.check_game = game.check_death = game.flush_coach = (
            game.refresh
        ) = Mock()
        self.world.update(frame(MACHINE, players=[PLAYER]))
        game.watch_game()
        self.assertEqual(game.game["dimension"], NETHER)
        self.assertFalse(self.world.active)
        self.clock.return_value += 3
        self.world.update(frame(t=START + 3_000, dimension=NETHER, players=[PLAYER]))
        self.assertTrue(self.world.active)
        game.watch_game()
        self.assertIsNone(game.game)
        self.assertFalse(self.world.active)
        self.assertEqual(self.world.players, [PLAYER])

    def test_machine_card_labels_historical_observation(self):
        game = GameController()
        game.world, game.game = self.world, {"dimension": OVERWORLD}
        request = {"origine": "bloc", "x": 3, "y": 64, "z": 0, "dimension": OVERWORLD}
        self.world.update(frame(MACHINE))
        self.assertEqual(game.machine_at(request), "en marche 75 %")
        self.world.invalidate_live()
        self.assertIn("Dernier relevé", game.machine_at(request))

    def test_codex_and_claude_get_same_freshness_rules_and_no_extra_turn(self):
        controller = ConversationController()
        controller.game = {"x": 0, "y": 64, "z": 0, "dimension": OVERWORLD}
        controller.world, controller.storage = self.world, Storage([])
        controller.veins, controller.places, controller.removed_chests = [], {}, []
        controller.guide, controller.speak = None, False
        controller.describe_journal = lambda _: ""
        controller.recipe_index = RecipeIndex()
        controller.events, controller.speaker = Queue(), Mock()
        self.world.update(frame(MACHINE, CHEST, players=[PLAYER]))
        self.world.invalidate_live()
        controller.storage = self.world.merged(Storage([]))
        for label in ("Codex", "Claude"):
            with (
                self.subTest(label=label),
                patch(
                    "monifactory_assistant.application.conversation.ai_provider.label",
                    return_value=label,
                ),
                patch("monifactory_assistant.application.conversation.log"),
            ):
                controller.conversation = Mock()
                controller.conversation.ask.return_value = "Réponse"
                controller.work_question("Le smelter tourne ?", None, None)
                controller.conversation.ask.assert_called_once()
                prompt = controller.conversation.ask.call_args.args[0]
                self.assertIn("Machines du serveur (historique", prompt)
                self.assertIn("Dernières positions connues", prompt)
                self.assertIn("quantités observées", prompt)
                self.assertNotIn("objectif", prompt.casefold())
                self.assertNotIn("état en direct", prompt)


def state(block, **changes):
    return {**block, "m": {**block["m"], **changes}}


BOILER = {
    "p": [5, 64, 0],
    "b": "gtceu:hp_steam_solar_boiler",
    "m": {
        "s": "WORKING",
        "pr": 0,
        "du": 0,
        "eau": [16000, 16000],
        "t": [1000, 1000],
        "se": False,
        "vap": [13980, 16000],
    },
}
BOILER_KEY = f"{OVERWORLD}|5|64|0"


CHEST_KEY = f"{OVERWORLD}|4|64|0"
MACHINE_KEY = f"{OVERWORLD}|3|64|0"


def tin(n):
    return {**CHEST, "o": [["gtceu:tin_ingot", n]]}


class WorldSaveCadenceTests(unittest.TestCase):
    """Fichier au plus toutes les 30 s, quel que soit le changement ; la .bak avec un contenu.

    coffres_connus.json part de la mémoire vive : aussitôt si son contenu hors dates change,
    des dates plus récentes seules au plus toutes les 60 s.
    """

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.path = self.root / "monde_memoire.json"
        self.clock = self.enterContext(
            patch("monifactory_assistant.game.world.time.time", return_value=START / 1000)
        )
        self.saves = self.enterContext(
            patch("monifactory_assistant.game.world.atomic_json", wraps=atomic_json)
        )
        self.world = World(self.path)
        self.publish = Mock(return_value=True)
        self.game = GameController()
        self.game.bridge = SimpleNamespace(
            data_path=lambda name: self.root / name, publish_text=self.publish
        )

    def receive(self, *blocks, t, covered=False, players=()):
        """Une trame du serveur, puis la publication de coffres_connus.json comme check_game."""
        self.clock.return_value = t / 1000
        snapshot = frame(*blocks, t=t, players=players)
        if covered:
            snapshot["chunks_complets"] = [[0, 0]]
        self.assertTrue(self.world.update(snapshot))
        self.game.publish_known_chests(self.world)

    def counts(self):
        return self.saves.call_count, self.publish.call_count

    def saved_dates(self, path=None):
        return {b["t"] for b in World(path or self.path).blocks.values()}

    def test_same_content_with_new_dates_is_neither_written_nor_republished(self):
        self.receive(MACHINE, CHEST, t=START, players=[PLAYER])
        written = self.path.read_bytes()
        moved = {**PLAYER, "x": 20}
        self.receive(MACHINE, CHEST, t=START + 3_000, players=[moved])
        self.assertEqual(self.counts(), (1, 1))
        self.assertEqual(self.path.read_bytes(), written)
        # La mémoire vive, le direct et les joueurs suivent chaque trame.
        self.assertEqual({b["t"] for b in self.world.blocks.values()}, {START + 3_000})
        self.assertEqual((self.world.last_t, self.world.players), (START + 3_000, [moved]))
        self.assertTrue(self.world.observation_live(self.world.machines()[0]))
        self.assertIn("état en direct", describe_world(self.world))

    def test_machine_levels_alone_are_neither_written_nor_republished(self):
        waiting = {
            "p": [6, 64, 0],
            "b": "gtceu:lv_assembler",
            "m": {"s": "WAITING", "pr": 10, "du": 100, "r": "Pas assez d'énergie"},
        }
        self.receive(MACHINE, BOILER, waiting, CHEST, t=START)
        written = self.path.read_bytes()
        levels = (
            state(MACHINE, pr=1, du=5),
            state(BOILER, vap=[9000, 16000], eau=[15000, 16000], t=[990, 1000]),
            state(waiting, pr=11, r="Sortie pleine"),
        )
        self.receive(*levels, CHEST, t=START + 3_000)
        self.assertEqual(self.counts(), (1, 1))
        self.assertEqual(self.path.read_bytes(), written)
        # La mémoire vive garde les niveaux de la dernière trame.
        self.assertEqual(self.world.blocks[BOILER_KEY]["etat"]["vap"], [9000, 16000])
        text = describe_world(self.world)
        for shown in ("en marche 20 %", "steam 9000/16000 mB", "en attente (Sortie pleine)"):
            self.assertIn(shown, text)

    def test_visible_machine_state_waits_for_the_next_save_and_is_not_republished(self):
        furnace = {
            "p": [7, 64, 0],
            "b": "gtceu:electric_blast_furnace",
            "m": {"s": "IDLE", "pr": 0, "du": 0, "f": True},
        }
        self.receive(MACHINE, BOILER, furnace, CHEST, t=START)
        idle, unformed, dry = (
            state(MACHINE, s="IDLE"),
            state(furnace, f=False),
            state(BOILER, se=True),
        )
        steps = (
            ("s", (idle, BOILER, furnace)),
            ("f", (idle, BOILER, unformed)),
            ("se", (idle, dry, unformed)),
            ("en marche", (idle, state(dry, pr=1, du=20), unformed)),
        )
        for k, (label, machines) in enumerate(steps, start=1):
            with self.subTest(label):
                self.receive(*machines, CHEST, t=START + 3_000 * k)
                self.assertEqual(self.counts(), (1, 1))  # l'état n'est pas publié
        self.assertEqual(World(self.path).blocks[MACHINE_KEY]["etat"]["s"], "WORKING")
        for k in range(5, 11):  # enregistrement dû 30 s après le premier, avec la .bak
            self.receive(*steps[-1][1], CHEST, t=START + 3_000 * k)
        self.assertEqual(self.counts(), (2, 1))
        self.assertTrue(self.saves.call_args.kwargs["backup"])
        self.assertEqual(World(self.path).blocks, self.world.blocks)

    def test_drawer_changing_every_frame_is_republished_at_once_and_saved_every_30_s(self):
        self.receive(MACHINE, CHEST, t=START)
        for k in range(1, 21):
            self.receive(MACHINE, tin(9 + k), t=START + 3_000 * k)
            self.assertEqual(self.publish.call_count, 1 + k)
            self.assertIn(f'"n":{9 + k}', self.publish.call_args.args[1])
        self.assertEqual(self.saves.call_count, 3)  # au démarrage, à 30 s et à 60 s
        self.assertTrue(all(call.kwargs["backup"] for call in self.saves.call_args_list))
        self.assertEqual(World(self.path).blocks[CHEST_KEY]["objets"][0]["n"], 29)

    def test_backup_is_refreshed_only_by_a_save_that_carries_new_content(self):
        backup = self.path.with_name(self.path.name + ".bak")
        self.receive(MACHINE, CHEST, t=START)
        self.receive(MACHINE, tin(8), t=START + 3_000)
        for k in range(2, 11):  # à 30 s : contenu nouveau, la .bak reçoit l'état précédent
            self.receive(MACHINE, tin(8), t=START + 3_000 * k)
        self.assertEqual(self.saves.call_count, 2)
        self.assertTrue(self.saves.call_args.kwargs["backup"])
        kept = backup.read_bytes()
        self.assertEqual(json.loads(kept)["blocs"][CHEST_KEY]["objets"][0]["n"], 9)
        for k in range(11, 21):  # à 60 s : dates seules, la .bak ne change pas
            self.receive(MACHINE, tin(8), t=START + 3_000 * k)
        self.assertEqual(self.saves.call_count, 3)
        self.assertFalse(self.saves.call_args.kwargs["backup"])
        self.assertEqual(backup.read_bytes(), kept)
        for k in range(21, 31):  # nouveau contenu à 63 s, enregistré à 90 s avec la .bak
            self.receive(MACHINE, CHEST, t=START + 3_000 * k)
        self.assertEqual(self.saves.call_count, 4)
        self.assertTrue(self.saves.call_args.kwargs["backup"])
        self.assertEqual(json.loads(backup.read_bytes())["blocs"][CHEST_KEY]["objets"][0]["n"], 8)

    def test_dates_and_levels_are_saved_every_30_s_and_republished_once_per_minute(self):
        def boiler(k):
            return state(BOILER, vap=[16000 - 100 * k, 16000])

        self.receive(BOILER, CHEST, t=START)
        for k in range(1, 20):
            self.receive(boiler(k), CHEST, t=START + 3_000 * k)
        self.assertEqual(self.counts(), (2, 1))  # fichier à 30 s, sans .bak
        self.assertFalse(self.saves.call_args.kwargs["backup"])
        self.receive(boiler(20), CHEST, t=START + 60_000)
        self.assertEqual(self.counts(), (3, 2))
        saved = World(self.path).blocks
        self.assertEqual({b["t"] for b in saved.values()}, {START + 60_000})
        self.assertEqual(saved[BOILER_KEY]["etat"]["vap"], [14000, 16000])
        self.assertIn(f'"t":{START + 60_000}', self.publish.call_args.args[1])
        for k in range(21, 40):
            self.receive(boiler(k), CHEST, t=START + 3_000 * k)
        self.assertEqual(self.counts(), (4, 2))

    def test_absent_block_keeps_its_first_absence_date(self):
        self.receive(MACHINE, CHEST, t=START, covered=True)
        self.receive(MACHINE, t=START + 3_000, covered=True)
        for k in range(2, 23):  # toujours absent pendant plus d'une minute
            self.receive(MACHINE, t=START + 3_000 * k, covered=True)
        marker = self.world.blocks[CHEST_KEY]
        self.assertEqual((marker["absent"], marker["objets"]), (True, []))
        self.assertEqual(marker["t"], START + 3_000)
        self.assertEqual(self.saves.call_count, 3)  # au démarrage, à 30 s et à 60 s
        self.assertEqual(World(self.path).blocks[CHEST_KEY]["t"], START + 3_000)
        published = json.loads(self.publish.call_args.args[1])
        self.assertEqual(
            [(c["objets"], c["t"]) for c in published["conteneurs"]], [([], START + 3_000)]
        )
        self.assertEqual(self.world.blocks[MACHINE_KEY]["t"], START + 66_000)

    def test_restart_reloads_the_last_saved_state(self):
        self.receive(MACHINE, CHEST, t=START)
        for k in range(1, 11):  # enregistré à 30 s
            self.receive(MACHINE, tin(9 + k), t=START + 3_000 * k)
        self.receive(state(MACHINE, s="IDLE"), tin(30), t=START + 33_000)
        # Arrêt forcé : rien n'est enregistré à la sortie.
        restarted = World(self.path)
        self.assertEqual(restarted.blocks, self.world.saved_blocks)
        self.assertEqual(restarted.blocks[CHEST_KEY]["objets"][0]["n"], 19)
        self.assertEqual(restarted.blocks[MACHINE_KEY]["etat"]["s"], "WORKING")
        self.world.flush()
        self.assertEqual(World(self.path).blocks, self.world.blocks)

    def test_leaving_the_live_view_saves_everything_pending_and_a_failure_keeps_it(self):
        self.receive(BOILER, CHEST, t=START)
        self.receive(state(BOILER, vap=[9000, 16000]), tin(8), t=START + 3_000)
        self.assertEqual(self.publish.call_count, 2)  # la publication n'attend pas le fichier
        with patch(
            "monifactory_assistant.persistence._replace_bytes",
            side_effect=PermissionError("fichier verrouillé"),
        ):
            self.world.invalidate_live()
        self.assertFalse(self.world.active)
        self.assertEqual(self.saved_dates(), {START})
        self.world.invalidate_live()
        saved = World(self.path).blocks
        self.assertEqual({b["t"] for b in saved.values()}, {START + 3_000})
        self.assertEqual(saved[BOILER_KEY]["etat"]["vap"], [9000, 16000])
        self.assertEqual(saved[CHEST_KEY]["objets"][0]["n"], 8)
        self.assertTrue(self.saves.call_args.kwargs["backup"])  # il porte un contenu nouveau
        calls = self.saves.call_count
        self.world.flush()
        self.game.publish_known_chests(self.world)
        self.assertEqual(self.counts(), (calls, 2))

    def test_world_session_or_dimension_switch_saves_everything_pending(self):
        first = WorldScope("m1_" + "a" * 64, "1" * 32, OVERWORLD)
        switches = (
            ("connexion", WorldScope(first.world, "2" * 32, OVERWORLD)),
            ("dimension", WorldScope(first.world, first.session, NETHER)),
            ("monde", WorldScope("m1_" + "b" * 64, first.session, OVERWORLD)),
        )
        for label, scope in switches:
            with (
                self.subTest(label),
                patch("monifactory_assistant.application.game.ROOT", self.root),
            ):
                path = self.root / f"{label}.json"
                world = World(path)
                self.assertTrue(world.update(frame(BOILER, CHEST, t=START)))
                low = state(BOILER, vap=[9000, 16000])
                self.assertTrue(world.update(frame(low, tin(8), t=START + 3_000)))
                self.assertEqual(self.saved_dates(path), {START})
                self.game.world, self.game.speaker = world, Mock()
                self.game.active_world_scope = first
                self.game.apply_world_scope(scope)
                saved = World(path).blocks
                self.assertEqual({b["t"] for b in saved.values()}, {START + 3_000})
                self.assertEqual(saved[BOILER_KEY]["etat"]["vap"], [9000, 16000])
                self.assertEqual(saved[CHEST_KEY]["objets"][0]["n"], 8)
                self.assertEqual(self.game.world is world, label != "monde")

    def test_assistant_exit_saves_everything_pending_and_closes_publications(self):
        from monifactory_assistant.app import App

        self.receive(BOILER, CHEST, t=START)
        self.receive(state(BOILER, vap=[9000, 16000]), tin(8), t=START + 3_000)
        app = App.__new__(App)
        app.root, app.bridge, app.world = Mock(), Mock(), self.world
        app.root.mainloop.side_effect = KeyboardInterrupt  # Ctrl+C dans la console
        with self.assertRaises(KeyboardInterrupt):
            app.run()
        saved = World(self.path).blocks
        self.assertEqual({b["t"] for b in saved.values()}, {START + 3_000})
        self.assertEqual(saved[BOILER_KEY]["etat"]["vap"], [9000, 16000])
        self.assertEqual(saved[CHEST_KEY]["objets"][0]["n"], 8)
        app.bridge.close_publications.assert_called_once_with()
