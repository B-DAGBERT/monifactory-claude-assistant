"""Failures in an assistant callback or log must not stop later work."""

import queue
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant import app, runtime
from monifactory_assistant.application.game import GameController


class RuntimeResilienceTests(unittest.TestCase):
    def test_child_reports_failure_to_supervisor_without_blocking_for_dialog(self):
        with (
            patch.object(app, "App", side_effect=RuntimeError("startup failed")),
            patch.object(app, "log") as log,
            patch.object(app, "messagebox") as messagebox,
            patch.object(app.faulthandler, "enable"),
            patch.object(sys, "argv", ["assistant_mc.py", "--enfant"]),
            patch.dict("os.environ"),
            self.assertRaises(SystemExit) as error,
        ):
            app.main()
        self.assertEqual(error.exception.code, 1)
        self.assertIn("startup failed", log.call_args.args[0])
        messagebox.showerror.assert_not_called()

    def test_supervisor_restarts_after_child_failure(self):
        with (
            patch.object(app.subprocess, "call", side_effect=[1, 0]) as launch,
            patch.object(app.time, "sleep"),
            patch.object(app, "log"),
            patch.object(app, "messagebox") as messagebox,
        ):
            app.supervise(already_locked=True)
        self.assertEqual(launch.call_count, 2)
        messagebox.showerror.assert_not_called()

    def test_watch_rearms_after_failed_read_and_refresh(self):
        game = GameController()
        game.root = Mock()
        game.bridge = Mock(isolated=False)
        game.bridge.state.side_effect = [OSError("read failed"), None]
        game.refresh = Mock(side_effect=[OSError("refresh failed"), None])
        game.game = None
        game.session_open = False
        game.check_craftable = Mock()
        game.flush_coach = Mock()
        with patch("monifactory_assistant.application.game.log") as log:
            game.watch_game()
            delay, resume = game.root.after.call_args.args
            self.assertEqual(delay, 1000)
            resume()
        self.assertEqual(log.call_count, 2)
        self.assertEqual(game.refresh.call_count, 2)
        game.check_craftable.assert_called_once()
        self.assertEqual(game.root.after.call_count, 2)

    def test_one_failed_event_leaves_next_event_for_next_poll(self):
        child = app.App.__new__(app.App)
        child.root = Mock()
        child.events = queue.Queue()
        child.events.put(("broken",))
        child.events.put(("healthy", "next"))
        child.on_broken = Mock(side_effect=RuntimeError("event failed"))
        child.on_healthy = Mock()
        with patch.object(app, "log"):
            child.poll()
            child.root.after.assert_called_once_with(50, child.poll)
            child.poll()
        child.on_healthy.assert_called_once_with("next")

    def test_log_failure_retains_latest_error_without_raising(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(runtime, "LOG_FILE", Path(directory)),
            patch.object(runtime, "LAST_ERROR", {}),
        ):
            runtime.log("Traceback\nRuntimeError: injected failure")
            self.assertEqual(runtime.LAST_ERROR["message"], "RuntimeError: injected failure")


if __name__ == "__main__":
    unittest.main()
