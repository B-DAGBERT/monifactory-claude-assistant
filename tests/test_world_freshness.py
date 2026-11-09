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
        game.check_craftable = game.check_game = game.check_death = game.flush_coach = (
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
