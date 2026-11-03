"""Compatibility entry points and stable data paths after moving the source files."""

import runpy
import unittest
from pathlib import Path
from types import ModuleType
from unittest.mock import Mock, patch

from monifactory_assistant import config, paths
from monifactory_assistant.commands import update

ROOT = Path(__file__).resolve().parents[1]


class LayoutTests(unittest.TestCase):
    def test_data_paths_stay_at_checkout_root(self):
        self.assertEqual(paths.ROOT, ROOT)
        self.assertEqual(config.HERE, ROOT)
        self.assertEqual(update.HERE, ROOT)
        self.assertEqual(paths.PACKAGE_ROOT, ROOT / "monifactory_assistant")
        self.assertTrue((paths.PACKAGE_ROOT / "platform/parler.ps1").is_file())

    def test_existing_launchers_delegate_without_loading_a_second_application(self):
        entries = {
            "assistant_mc.py": ("startup", "main"),
            "choose_ai.py": ("commands.choose_ai", "main"),
            "codex_setup.py": ("commands.setup", "cli"),
            "update_assistant.py": ("commands.update", "cli"),
        }
        for filename, (module, entry) in entries.items():
            with self.subTest(entry=filename):
                name = "monifactory_assistant." + module
                fake = ModuleType(name)
                callback = Mock()
                setattr(fake, entry, callback)
                with patch.dict("sys.modules", {name: fake}):
                    runpy.run_path(str(ROOT / filename), run_name="__main__")
                callback.assert_called_once_with()
