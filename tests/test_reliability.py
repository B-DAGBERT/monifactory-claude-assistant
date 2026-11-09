"""Crash, burst, reconnect and disk failure scenarios with real production components."""

import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.game.requests import CommandQueue
from monifactory_assistant.game.snapshots import frame_value, render_frame
from monifactory_assistant.persistence import ISSUES, atomic_json, load_json


class PersistenceTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = Path(tmp.name) / "save.json"
        self.addCleanup(ISSUES.clear)

    def test_corrupt_primary_recovers_backup_and_never_overwrites_it_with_bad_json(self):
        atomic_json(self.path, {"pause": True})
        atomic_json(self.path, {"pause": False})
        self.path.write_text("{interrupted")
        self.assertEqual(load_json(self.path, {}, strict=True), {"pause": True})
        self.assertIn("récupérée", ISSUES[-1]["message"])
        atomic_json(self.path, {"pause": True, "recovered": True})
        self.assertEqual(load_json(self.path), {"pause": True, "recovered": True})
        self.assertEqual(
            json.loads(self.path.with_name("save.json.bak").read_text()), {"pause": True}
        )
        self.assertFalse(list(self.path.parent.glob("*.tmp")))

    def test_disk_failure_preserves_primary_and_leaves_no_temporary_files(self):
        atomic_json(self.path, {"old": 1})
        with patch.object(Path, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                atomic_json(self.path, {"new": 2})
        self.assertEqual(load_json(self.path), {"old": 1})
        self.assertFalse(list(self.path.parent.glob("*.tmp")))
        self.assertIn("impossible", ISSUES[-1]["message"])

    def test_missing_wrong_type_and_both_corrupt(self):
        self.assertEqual(load_json(self.path, {}), {})
        self.path.write_text("[]")
        with self.assertRaises(ValueError):
            load_json(self.path, {}, strict=True)
        self.path.with_name("save.json.bak").write_text('{"good":true}')
        self.assertEqual(load_json(self.path, {}, strict=True), {"good": True})

    def test_parallel_writers_always_publish_whole_snapshots(self):
        errors = []

        def writer(index):
            try:
                for _ in range(8):
                    atomic_json(self.path, {"value": str(index) * 2000})
            except Exception as error:
                errors.append(error)

        threads = [threading.Thread(target=writer, args=(i,)) for i in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertFalse(errors)
        self.assertEqual(len(load_json(self.path)["value"]), 2000)
        self.assertEqual(len(load_json(self.path.with_name("save.json.bak"))["value"]), 2000)


class RequestQueueTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name)
        (self.folder / "demandes").mkdir()
        self.now = 1000.0
        self.queue = CommandQueue(self.folder, clock=lambda: self.now)
        self.dispatch = Mock(return_value={"etat": "traitee", "message": "ok"})
        self.restore = Mock()

    def put(self, ident, n=None, **values):
        request = {"requete": ident, "n": int(self.now * 1000) if n is None else n, **values}
        path = self.folder / "demandes" / (f"{request['n']:020d}-{ident}.json")
        path.write_text(json.dumps(request))
        return request

    def test_burst_keeps_every_request_in_order_and_bounds_work_per_poll(self):
        for i in reversed(range(12)):
            self.put(str(i), 999_000 + i)
        self.assertTrue(self.queue.poll(self.dispatch, self.restore))
        self.assertEqual(self.dispatch.call_count, 8)
        self.assertEqual(self.queue.pending, 4)
        self.queue.poll(self.dispatch, self.restore)
        self.assertEqual(
            [c.args[0]["requete"] for c in self.dispatch.call_args_list],
            [str(i) for i in range(12)],
        )
        self.assertFalse(list((self.folder / "demandes").glob("*.json")))

    def test_repeated_request_and_restart_restore_ack_without_executing_again(self):
        self.put("same", type="question")
        self.dispatch.return_value = {
            "etat": "traitee",
            "question": {"n": 1_000_000, "etat": "acceptee"},
        }
        self.queue.poll(self.dispatch, self.restore)
        self.put("same", type="question")
        restarted = CommandQueue(self.folder, clock=lambda: self.now)
        restarted.poll(self.dispatch, self.restore)
        self.dispatch.assert_called_once()
        self.assertEqual(self.restore.call_args.args[1]["question"]["etat"], "acceptee")

    def test_crash_after_reservation_never_replays_an_uncertain_action(self):
        self.put("uncertain")
        self.queue.rows["uncertain"] = {"n": 1_000_000, "etat": "traitement"}
        self.queue._save()
        restarted = CommandQueue(self.folder, clock=lambda: self.now)
        restarted.poll(self.dispatch, self.restore)
        self.dispatch.assert_not_called()
        self.assertEqual(self.restore.call_args.args[1]["etat"], "interrompue")

    def test_failure_to_reserve_never_dispatches(self):
        self.put("disk")
        with patch("monifactory_assistant.game.requests.atomic_json", side_effect=OSError("disk")):
            self.queue.poll(self.dispatch, self.restore)
        self.dispatch.assert_not_called()
        self.assertTrue(self.queue.error)
        self.assertTrue(list((self.folder / "demandes").glob("*.json")))

    def test_crash_after_dispatch_but_before_ack_never_dispatches_twice(self):
        self.put("after")
        original = self.queue._save
        calls = 0

        def fail_ack():
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("lost ack")
            original()

        with patch.object(self.queue, "_save", side_effect=fail_ack):
            self.queue.poll(self.dispatch, self.restore)
        self.assertEqual(self.dispatch.call_count, 1)
        restarted = CommandQueue(self.folder, clock=lambda: self.now)
        restarted.poll(self.dispatch, self.restore)
        self.assertEqual(self.dispatch.call_count, 1)
        self.assertEqual(restarted.last["etat"], "interrompue")

    def test_expired_future_malformed_and_handler_failure_are_visible(self):
        self.put("old", 1)
        self.put("future", 2_000_000)
        (self.folder / "demandes" / "bad.json").write_text("null")
        self.queue.poll(self.dispatch, self.restore)
        self.dispatch.assert_not_called()
        self.assertEqual({c.args[1]["etat"] for c in self.restore.call_args_list}, {"expiree"})
        self.assertTrue((self.folder / "demandes" / "bad.invalide").exists())
        self.put("raises")
        self.dispatch.side_effect = RuntimeError("handler")
        self.queue.poll(self.dispatch, self.restore)
        self.assertEqual(self.queue.last["etat"], "interrompue")

    def test_context_change_disconnect_and_busy_refuse_without_starting_ai(self):
        app = InterfaceController()
        app.game = {"dimension": "minecraft:overworld"}
        app.question_ack = {}
        app.mod_request_n = None
        app.start_question, app.notify = Mock(), Mock()
        for request in (
            {"n": 1, "type": "question", "texte": "Q", "dimension_envoi": "minecraft:the_nether"},
            {"n": 1, "type": "projet", "action": "choisir", "id": "principal"},
        ):
            result = app.dispatch_queued_request(request)
            self.assertEqual(result["etat"], "refusee")
        app.game = None
        result = app.dispatch_queued_request(
            {"n": 2, "type": "question", "texte": "Q", "dimension_envoi": "minecraft:overworld"}
        )
        self.assertEqual(result["etat"], "refusee")
        app.busy = True
        result = app.dispatch_queued_request({"n": 3, "type": "question", "texte": "Q"})
        self.assertEqual(result["etat"], "refusee")
        app.start_question.assert_not_called()


class RenderingTests(unittest.TestCase):
    def test_one_calculation_per_frame_and_no_cache_between_frames_or_threads(self):
        app = object()
        compute = Mock(return_value={"iron": 2})
        with render_frame(app):
            first = frame_value(app, "stock", compute)
            self.assertIs(first, frame_value(app, "stock", compute))
            compute.assert_called_once()
            other = []
            t = threading.Thread(
                target=lambda: other.append(frame_value(app, "stock", lambda: {"iron": 9}))
            )
            t.start()
            t.join()
            self.assertEqual(other, [{"iron": 9}])
        compute.return_value = {"iron": 3}
        self.assertEqual(frame_value(app, "stock", compute), {"iron": 3})

    def test_unchanged_view_still_throttles_and_failed_render_can_retry(self):
        app = InterfaceController()
        app.root = SimpleNamespace(after=Mock())
        app.refresh_pending = False
        app.refresh_views = Mock()
        with patch("monifactory_assistant.application.interface.time.monotonic", return_value=100):
            for _ in range(20):
                app.refresh()
        self.assertEqual(app.refresh_views.call_count, 1)
        app.root.after.assert_called_once()
        with patch("monifactory_assistant.application.interface.time.monotonic", return_value=101):
            app.delayed_refresh()
        self.assertEqual(app.refresh_views.call_count, 2)
        app.refresh_views.side_effect = RuntimeError("disk")
        with self.assertRaises(RuntimeError):
            app.refresh(force=True)
        app.refresh_views.side_effect = None
        app.refresh(force=True)
        self.assertEqual(app.refresh_views.call_count, 4)


class RecoveryAndDiagnosticsTests(unittest.TestCase):
    def test_corrupt_receipt_stops_dispatch_without_crashing_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "demandes").mkdir()
            (root / "reponses_demandes.json").write_text("{broken")
            (root / "demandes/one.json").write_text('{"requete":"one","n":1000000}')
            queue = CommandQueue(root, clock=lambda: 1000)
            action = Mock()
            queue.poll(action)
            action.assert_not_called()
            self.assertIn("illisible", queue.error)
            self.assertTrue((root / "demandes/one.json").exists())

    def test_stock_count_is_shared_only_inside_one_frame(self):
        from monifactory_assistant.game.storage import Storage

        stack = {"item": "iron", "n": 2}
        storage = Storage([{"objets": [stack]}], machines=[{"bloc": "furnace"}])
        app = SimpleNamespace(storage=storage)
        with render_frame(app):
            before = storage.totals()
            self.assertIs(storage.totals(), before)
            self.assertIs(storage.placed(), storage.placed())
            stack["n"] = 7
            self.assertEqual(storage.totals()["iron"], 2)
        self.assertEqual(storage.totals()["iron"], 7)

    def test_diagnostic_dates_the_inventory_and_omits_private_config(self):
        from monifactory_assistant.diagnostics import view

        app = SimpleNamespace(
            game=None,
            bridge=SimpleNamespace(last_state={"t": 100_000}, script_version=4),
            world=SimpleNamespace(active=False),
            busy=False,
            settings={"secret": "not-exported"},
            diagnostic_revision="test",
        )
        with patch("time.time", return_value=110):
            data = view(app)
        # The mod shows the age from this date: the view must not change second after second,
        # or vue_mod.json would be rewritten and re-read by the game every second.
        with patch("time.time", return_value=999):
            self.assertEqual(view(app), data)
        self.assertFalse(data["jeu_connecte"])
        self.assertEqual(data["inventaire_t"], 100_000)
        self.assertNotIn("age_inventaire", data)
        self.assertEqual(data["revision"], "test")
        self.assertNotIn("not-exported", json.dumps(data))
        app.bridge.last_state = {}
        self.assertIsNone(view(app)["inventaire_t"])

    def test_render_duration_alone_does_not_rewrite_the_mod_view(self):
        app = InterfaceController()
        app.bridge = SimpleNamespace(write=Mock())
        app.last_mod_view = None
        timings = iter([1.2, 3.4, 5.0, 5.1])
        others = iter([1, 1, 1, 2])

        def diagnostic(_app):
            return {"rendu_ms": next(timings), "file": next(others)}

        with (
            patch(
                "monifactory_assistant.application.interface.build_mod_view",
                side_effect=lambda _app: {"vie": 7, "ecran": {"machines": []}},
            ),
            patch("monifactory_assistant.application.interface.diagnostic_view", diagnostic),
        ):
            for _ in range(4):
                app.refresh_views()
        # Written on the first view and on a real change, not when only the timing moved.
        self.assertEqual(app.bridge.write.call_count, 2)
        written = app.bridge.write.call_args.args[1]
        self.assertEqual(written["ecran"]["diagnostic"], {"rendu_ms": 5.1, "file": 2})

    def test_valid_receipt_survives_file_delete_failure_without_reexecution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "demandes").mkdir()
            request = root / "demandes/one.json"
            request.write_text('{"requete":"one","n":1000000}')
            queue = CommandQueue(root, clock=lambda: 1000)
            dispatch = Mock(return_value={"etat": "traitee"})
            original = Path.unlink

            def fail_request(path, *args, **kwargs):
                if path == request:
                    raise OSError("temporarily locked")
                return original(path, *args, **kwargs)

            with patch.object(Path, "unlink", new=fail_request):
                queue.poll(dispatch)
            queue = CommandQueue(root, clock=lambda: 1000)
            queue.poll(dispatch)
            dispatch.assert_called_once()
            self.assertFalse(request.exists())

    def test_late_ack_does_not_replace_the_current_question_ack(self):
        app = InterfaceController()
        app.mod_request_n = 200
        app.question_ack = {"n": 200, "etat": "acceptee"}
        app.restore_request_reply(
            {"n": 100, "type": "question"}, {"question": {"n": 100, "etat": "refusee"}}
        )
        self.assertEqual(app.question_ack, {"n": 200, "etat": "acceptee"})
        self.assertEqual(app.mod_request_n, 200)

    def test_new_inbox_does_not_revive_an_old_legacy_action_on_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "demandes").mkdir()
            (root / "demande_mod.json").write_text('{"n":1,"type":"action","action":"voix"}')
            (root / "demandes/new.json").write_text(
                '{"requete":"new","n":1000000,"type":"question","texte":"Q"}'
            )
            app = InterfaceController()
            app.bridge = SimpleNamespace(dir=root)
            app.request_queue = CommandQueue(root, clock=lambda: 1000)
            app.game = {"dimension": "minecraft:overworld"}
            app.root = SimpleNamespace(after=Mock())
            app.mod_request_n = app.mod_request_time = None
            app.question_ack = {}
            app.busy = False
            app.refresh, app.start_question, app.on_voice = Mock(), Mock(), Mock()
            app.check_mod_requests()
            app.start_question.assert_called_once_with("Q", None)
            app.on_voice.assert_not_called()
            self.assertEqual(app.question_ack, {"n": 1000000, "etat": "acceptee"})
