"""Platform lock ownership without creating a live assistant or mutating its lock."""

import ctypes
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant.platform import support


class InstanceLockTests(unittest.TestCase):
    @unittest.skipIf(os.name == "nt", "POSIX flock only")
    def test_posix_second_lock_waits_until_owner_exits(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(support, "HERE", Path(directory)),
        ):
            with support.SingleInstance() as first:
                self.assertTrue(first)
                with support.SingleInstance() as second:
                    self.assertFalse(second)
            with support.SingleInstance() as later:
                self.assertTrue(later)

    def test_windows_preserves_handle_width_and_closes_existing_mutex_handle(self):
        kernel = Mock()
        handle = 0x123456789ABC
        kernel.CreateMutexW.return_value = handle
        for existing in (False, True):
            with (
                self.subTest(existing=existing),
                patch.object(support.os, "name", "nt"),
                patch.object(support.ctypes, "WinDLL", return_value=kernel, create=True),
                patch.object(
                    support.ctypes,
                    "get_last_error",
                    return_value=183 if existing else 0,
                    create=True,
                ),
            ):
                with support.SingleInstance() as acquired:
                    self.assertEqual(acquired, not existing)
                self.assertIs(kernel.CreateMutexW.restype, ctypes.c_void_p)
                self.assertEqual(kernel.CloseHandle.argtypes, [ctypes.c_void_p])
                kernel.CloseHandle.assert_called_with(handle)

    def test_windows_failed_mutex_creation_does_not_claim_ownership(self):
        kernel = Mock()
        kernel.CreateMutexW.return_value = None
        with (
            patch.object(support.os, "name", "nt"),
            patch.object(support.ctypes, "WinDLL", return_value=kernel, create=True),
            patch.object(support.ctypes, "get_last_error", return_value=5, create=True),
            patch.object(
                support.ctypes, "WinError", return_value=OSError("access denied"), create=True
            ),
        ):
            with self.assertRaisesRegex(OSError, "access denied"):
                with support.SingleInstance():
                    self.fail("must not start an unlocked assistant")
        kernel.CloseHandle.assert_not_called()
