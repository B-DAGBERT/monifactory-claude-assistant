"""Entry points check updates only once, under the existing assistant lock."""

import unittest
from types import ModuleType
from unittest.mock import Mock, patch

from monifactory_assistant import startup


class StartupTests(unittest.TestCase):
    def run_startup(self, *, child=False, acquired=True, error=None):
        app = ModuleType("monifactory_assistant.app")
        app.main, app.supervise = Mock(), Mock()
        tkinter = ModuleType("tkinter")
        tkinter.messagebox = Mock()
        with (
            patch.dict("sys.modules", {"monifactory_assistant.app": app, "tkinter": tkinter}),
            patch("sys.argv", ["assistant_mc.py"] + (["--enfant"] if child else [])),
            patch.object(startup, "SingleInstance") as lock,
            patch(
                "monifactory_assistant.commands.auto_update.check_at_startup", side_effect=error
            ) as update,
        ):
            lock.return_value.__enter__.return_value = acquired
            startup.main()
        return app, lock, update

    def test_parent_checks_then_supervises_under_lock(self):
        app, lock, update = self.run_startup()
        lock.return_value.__enter__.assert_called_once()
        update.assert_called_once()
        app.supervise.assert_called_once_with(already_locked=True)
        app.main.assert_not_called()

    def test_child_skips_check_and_does_not_take_parent_lock(self):
        app, lock, update = self.run_startup(child=True)
        update.assert_not_called()
        lock.assert_not_called()
        app.main.assert_called_once()

    def test_second_assistant_cannot_update_running_instance(self):
        app, lock, update = self.run_startup(acquired=False)
        update.assert_not_called()
        app.supervise.assert_not_called()

    def test_unexpected_update_failure_still_starts_assistant(self):
        app, lock, update = self.run_startup(error=RuntimeError("unavailable"))
        app.supervise.assert_called_once_with(already_locked=True)
