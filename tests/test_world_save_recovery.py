"""Exercise world-memory recovery through the game poll and normal shutdown."""

import tempfile
import threading
import unittest
from concurrent.futures import wait
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant import persistence
from monifactory_assistant.app import App
from monifactory_assistant.game.world import MEMORY_RETRY_INTERVAL, SaveResult, World
from monifactory_assistant.game.world_scope import WorldScope
from tests.test_world_freshness import CHEST_KEY, NETHER, OVERWORLD, START, frame, tin


class WorldSaveRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch("monifactory_assistant.application.game.ROOT", self.root))
        self.clock = self.enterContext(
            patch("monifactory_assistant.game.world.monotonic", return_value=100)
        )
        self.log = self.enterContext(patch("monifactory_assistant.application.game.log"))
        self.app = App.__new__(App)
        self.app.root = Mock()
        self.app.bridge = Mock(isolated=True)
        self.app.world = World(enabled=False)
        self.app.game = None
        self.app.session_open = False
        for name in (
            "on_game_connected",
            "check_recipes",
            "check_game",
            "check_death",
            "flush_coach",
            "refresh",
        ):
            setattr(self.app, name, Mock())
        self.a, self.b = (WorldScope("m1_" + name * 64, "1" * 32, OVERWORLD) for name in "ab")
        self.unavailable = set()
        self.replace_bytes = persistence._replace_bytes

        def replace_if_available(path, raw, mode):
            if path in self.unavailable:
                raise PermissionError("fichier momentanément indisponible")
            return self.replace_bytes(path, raw, mode)

        self.replace = self.enterContext(
            patch.object(persistence, "_replace_bytes", side_effect=replace_if_available)
        )

    def tick(self, scope):
        self.app.bridge.scope = scope
        self.app.bridge.state.return_value = {"dimension": scope.dimension} if scope else None
        self.app.watch_game()

    def retry(self, scope):
        self.clock.return_value += MEMORY_RETRY_INTERVAL
        self.tick(scope)
        self.app._world_save_retry.result(timeout=2)
        self.tick(scope)

    def path(self, scope):
        return self.root / "mondes" / scope.world / "monde_memoire.json"

    def saved(self, scope, key=CHEST_KEY):
        return World(self.path(scope)).blocks[key]["objets"][0]["n"]

    def observe(self, count, offset=0, dimension=OVERWORLD):
        self.assertTrue(
            self.app.world.update(frame(tin(count), t=START + offset, dimension=dimension))
        )

    def block_primary_write(self):
        entered, release = threading.Event(), threading.Event()
        write = self.replace.side_effect

        def blocked(path, raw, mode):
            if path == self.path(self.a):
                entered.set()
                if not release.wait(5):
                    raise TimeoutError("le test n’a pas libéré l’écriture")
            return write(path, raw, mode)

        self.replace.side_effect = blocked
        self.addCleanup(release.set)
        return entered, release

    def start_active_retry(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        self.assertIs(self.app.world.flush(), SaveResult.FAILED)
        self.unavailable.clear()
        entered, release = self.block_primary_write()
        self.clock.return_value += MEMORY_RETRY_INTERVAL
        self.tick(self.a)
        self.assertTrue(entered.wait(2))
        self.observe(6, 6_000)
        return self.app._world_save_retry, release

    def test_failed_save_is_retried_in_its_partition_after_switch(self):
        self.tick(self.a)
        original = self.app.world
        self.observe(9)
        self.observe(8, 3_000)
        self.assertEqual(original.blocks[CHEST_KEY]["objets"][0]["n"], 8)
        self.unavailable.add(self.path(self.a))
        self.tick(self.b)
        self.assertIsNot(self.app.world, original)
        self.assertEqual(self.app.active_world_scope, self.b)
        self.assertEqual(self.saved(self.a), 9)
        self.observe(31)
        b_bytes = self.path(self.b).read_bytes()
        self.app.publish_known_chests(self.app.world)
        published = self.app.bridge.publish_text.call_args_list[:]
        self.unavailable.clear()
        self.retry(self.b)
        self.assertEqual(self.path(self.b).read_bytes(), b_bytes)
        self.assertEqual(self.saved(self.a), 8)
        self.assertEqual(self.app.bridge.publish_text.call_args_list, published)
        self.assertEqual(self.app._pending_world_saves, {})
        backup = self.path(self.a).with_suffix(".json.bak")
        self.assertEqual(World(backup).blocks[CHEST_KEY]["objets"][0]["n"], 9)
        self.path(self.a).write_bytes(b"{")
        self.assertEqual(self.saved(self.a), 9)

    def test_flush_reports_clean_success_and_pending(self):
        self.tick(self.a)
        self.assertIs(self.app.world.flush(), SaveResult.CLEAN)
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        self.assertIs(self.app.world.flush(), SaveResult.FAILED)
        self.assertEqual(self.saved(self.a), 9)
        self.unavailable.clear()
        self.assertIs(self.app.world.flush(), SaveResult.SAVED)
        self.assertIs(self.app.world.flush(), SaveResult.CLEAN)
        self.assertEqual(self.saved(self.a), 8)

    def test_session_and_dimension_changes_retry_without_a_new_server_frame(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        nether = replace(self.a, dimension=NETHER)
        self.tick(nether)
        self.assertIsNone(self.app.world.live_dimension)
        self.assertEqual(self.saved(self.a), 9)
        self.observe(77, 6_000, NETHER)
        reconnected = replace(nether, session="2" * 32)
        self.tick(reconnected)
        self.unavailable.clear()
        self.retry(reconnected)
        self.assertEqual(self.saved(self.a), 8)
        self.assertEqual(self.saved(self.a, f"{NETHER}|4|64|0"), 77)
        self.assertIsNone(self.app.world.live_dimension)

    def test_detached_retry_validates_the_file_again(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        path = self.path(self.a)
        moved = path.with_suffix(".old")
        path.rename(moved)
        path.mkdir()
        self.tick(self.b)
        self.retry(self.b)
        self.assertTrue(path.is_dir())
        self.assertIn(path, self.app._pending_world_saves)
        path.rmdir()
        moved.rename(path)
        self.retry(self.b)
        self.assertEqual(self.saved(self.a), 8)

    def test_repeated_failures_are_spaced_and_multiple_partitions_resume(self):
        self.tick(self.a)
        self.unavailable.add(self.path(self.a))
        self.observe(8)  # première sauvegarde déjà refusée
        self.observe(7, 3_000)
        self.tick(self.b)
        self.observe(31)
        self.observe(30, 3_000)
        self.unavailable.add(self.path(self.b))
        self.tick(None)
        self.assertIsNone(self.app.active_world_scope)
        self.assertEqual(set(self.app._pending_world_saves), {self.path(self.a), self.path(self.b)})
        self.retry(
            None
        )  # A échoue à nouveau, B reçoit son premier essai de reprise au tick suivant
        running = self.app._world_save_retry
        if running is not None:
            running.result(timeout=2)
        self.tick(None)
        calls = self.replace.call_count
        for _ in range(3):
            self.tick(None)
        self.assertEqual(self.replace.call_count, calls)
        self.assertFalse(self.path(self.a).exists())
        self.assertEqual(self.saved(self.b), 31)
        self.unavailable.clear()
        self.retry(None)
        running = self.app._world_save_retry
        if running is not None:
            running.result(timeout=2)
        self.tick(None)
        self.assertEqual((self.saved(self.a), self.saved(self.b)), (7, 30))
        self.assertEqual(self.app._pending_world_saves, {})

    def test_reentry_while_retry_writes_keeps_newer_updates_in_order(self):
        self.tick(self.a)
        original = self.app.world
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        self.tick(self.b)
        self.unavailable.clear()
        entered, release = self.block_primary_write()
        self.clock.return_value += MEMORY_RETRY_INTERVAL
        self.tick(self.b)
        retry = self.app._world_save_retry
        try:
            self.assertTrue(entered.wait(2))
            self.observe(31)  # B peut enregistrer pendant qu'une reprise de A est bloquée.
            self.assertFalse(retry.done())
            returned = replace(self.a, session="2" * 32)
            self.tick(returned)
            self.assertIsNot(self.app.world, original)
            self.assertEqual(self.app.world.blocks[CHEST_KEY]["objets"][0]["n"], 8)
            self.assertEqual(self.app.world.last_t, 0)
            self.assertIsNone(self.app.world.live_dimension)
            self.observe(6, 36_000)
            self.assertIs(self.app.world.flush(), SaveResult.PENDING)
        finally:
            release.set()
            self.assertIs(retry.result(timeout=2), SaveResult.SAVED)
        self.assertEqual(self.saved(self.a), 8)
        self.assertIs(self.app.world.flush(), SaveResult.SAVED)
        self.tick(returned)
        self.assertEqual(self.saved(self.a), 6)
        self.assertEqual(
            World(self.path(self.a).with_suffix(".json.bak")).blocks[CHEST_KEY]["objets"][0]["n"],
            8,
        )

    def test_leaving_during_retry_leaves_the_newer_update_eligible(self):
        retry, release = self.start_active_retry()
        try:
            self.tick(self.b)
        finally:
            release.set()
            self.assertIs(retry.result(timeout=2), SaveResult.SAVED)
        self.assertEqual(self.saved(self.a), 8)
        self.retry(self.b)
        self.assertEqual(self.saved(self.a), 6)
        self.assertEqual(self.app._pending_world_saves, {})

    def test_shutdown_attempts_inactive_and_active_partitions(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        self.tick(self.b)
        self.observe(31)
        self.observe(30, 3_000)
        self.unavailable.clear()
        self.app.run()
        self.assertEqual((self.saved(self.a), self.saved(self.b)), (8, 30))
        self.assertEqual(self.app._pending_world_saves, {})
        self.app.bridge.close_publications.assert_called_once_with()

    def test_shutdown_reports_persistent_errors_for_each_partition(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        self.unavailable.add(self.path(self.a))
        self.tick(self.b)
        self.observe(31)
        self.observe(30, 3_000)
        self.unavailable.add(self.path(self.b))
        with patch("sys.stderr") as stderr:
            self.app.run()
        entries = [args[0] for args, _ in self.log.call_args_list if "à l’arrêt" in args[0]]
        self.assertEqual(len(entries), 2)
        for scope in (self.a, self.b):
            self.assertIn(str(self.path(scope)), "\n".join(entries))
            self.assertIn(str(self.path(scope)), str(stderr.write.call_args_list))
        self.assertEqual((self.saved(self.a), self.saved(self.b)), (9, 31))
        self.app.bridge.close_publications.assert_called_once_with()

    def test_shutdown_reports_unfinished_io_within_the_wait_budget(self):
        self.tick(self.a)
        self.observe(9)
        self.observe(8, 3_000)
        entered, release = self.block_primary_write()
        try:
            with patch("sys.stderr") as stderr:
                self.app.run()
            self.assertTrue(entered.is_set())
            self.assertIn("observations non confirmées", str(stderr.write.call_args_list))
            self.assertEqual(self.saved(self.a), 9)
            self.app.bridge.close_publications.assert_called_once_with()
        finally:
            release.set()
            if future := self.app.world.flush_in_background():
                future.result(timeout=2)

    def test_shutdown_saves_newer_updates_behind_an_inflight_retry(self):
        retry, release = self.start_active_retry()

        def resume_when_waiting(futures, **options):
            self.assertIn(retry, futures)
            release.set()
            return wait(futures, **options)

        with patch("monifactory_assistant.application.game.wait", side_effect=resume_when_waiting):
            self.app.run()
        self.assertEqual(self.saved(self.a), 6)
        self.assertFalse(any("à l’arrêt" in args[0] for args, _ in self.log.call_args_list))
        self.app.bridge.close_publications.assert_called_once_with()
