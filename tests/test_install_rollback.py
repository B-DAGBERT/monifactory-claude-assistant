"""Installation interruptions on synthetic files, without Java, Minecraft or personal data."""

import contextlib
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.commands import setup


class InstallRollbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.instance = self.root / "instance"
        (self.instance / "mods").mkdir(parents=True)
        (self.instance / "kubejs/client_scripts").mkdir(parents=True)
        self.old = self.instance / "mods/assistantia-old.jar"
        self.old.write_bytes(b"original jar")
        self.script = self.instance / "kubejs/client_scripts/assistant_ia.js"
        self.script.write_bytes(b"original script")
        self.world = self.instance / "kubejs/assistant/stockage.json"
        self.world.parent.mkdir()
        self.world.write_bytes(b'{"valuable":"stock"}')
        self.other = self.instance / "mods/other.jar"
        self.other.write_bytes(b"other mod")
        self.incoming = self.root / "incoming"
        self.incoming.mkdir()
        self.jar = self.incoming / "assistantia-new.jar"
        self.jar.write_bytes(b"new jar")
        self.client_script = self.incoming / "assistant_ia.js"
        self.client_script.write_bytes(b"new script")
        self.new = self.instance / "mods" / self.jar.name
        self.patches = contextlib.ExitStack()
        self.addCleanup(self.patches.close)
        self.patches.enter_context(patch.object(setup, "HERE", self.root))
        self.patches.enter_context(contextlib.redirect_stdout(io.StringIO()))

    def install(self):
        return setup.install(self.instance, jar=self.jar, client_script=self.client_script)

    def assert_original(self):
        self.assertEqual(
            [self.old.name], [p.name for p in self.old.parent.glob("assistantia-*.jar")]
        )
        self.assertEqual(b"original jar", self.old.read_bytes())
        self.assertEqual(b"original script", self.script.read_bytes())
        self.assertEqual(b'{"valuable":"stock"}', self.world.read_bytes())
        self.assertEqual(b"other mod", self.other.read_bytes())

    def assert_backup(self):
        backups = list((self.root / "backups").glob("*/installation.json"))
        self.assertTrue(backups)
        for manifest in backups:
            self.assertEqual(
                {"mods": [self.old.name], "script": True}, json.loads(manifest.read_text())
            )
            self.assertEqual(b"original jar", (manifest.parent / self.old.name).read_bytes())
            self.assertEqual(b"original script", (manifest.parent / self.script.name).read_bytes())

    def test_script_is_staged_before_any_installed_file_is_replaced(self):
        copy = shutil.copy2
        interrupted = KeyboardInterrupt("during script staging")

        def copying(source, target, *args, **kwargs):
            if Path(source) == self.client_script:
                self.assert_original()
                raise interrupted
            return copy(source, target, *args, **kwargs)

        with patch.object(setup.shutil, "copy2", side_effect=copying):
            with self.assertRaises(KeyboardInterrupt) as caught:
                self.install()
        self.assertIs(interrupted, caught.exception)
        self.assert_original()
        self.assert_backup()

    def test_script_staging_failure_does_not_attempt_to_restore_untouched_files(self):
        copy = shutil.copy2

        def copying(source, target, *args, **kwargs):
            if Path(source) == self.client_script:
                raise PermissionError("staging unavailable")
            return copy(source, target, *args, **kwargs)

        with (
            patch.object(setup.shutil, "copy2", side_effect=copying),
            patch.object(setup, "restore_install", wraps=setup.restore_install) as restore,
        ):
            with self.assertRaises(PermissionError):
                self.install()
            restore.assert_not_called()
        self.assert_original()
        self.assert_backup()

    def test_interruptions_after_each_replacement_restore_both_files(self):
        replace = Path.replace
        for target in (self.new, self.script):
            for error in (KeyboardInterrupt("cancelled"), SystemExit(7), OSError("disk")):
                with self.subTest(target=target.name, error=type(error).__name__):
                    interrupted = False

                    def replacing(path, destination):
                        nonlocal interrupted
                        result = replace(path, destination)
                        if Path(destination) == target and not interrupted:
                            interrupted = True
                            raise error
                        return result

                    with patch.object(Path, "replace", replacing):
                        with self.assertRaises(type(error)) as caught:
                            self.install()
                    self.assertIs(error, caught.exception)
                    self.assert_original()
                    self.assert_backup()

    def test_interruption_while_removing_old_version_restores_it(self):
        unlink = Path.unlink
        interrupted = False

        def removing(path, *args, **kwargs):
            nonlocal interrupted
            result = unlink(path, *args, **kwargs)
            if path == self.old and not interrupted:
                interrupted = True
                raise KeyboardInterrupt("cancelled after deletion")
            return result

        with patch.object(Path, "unlink", removing):
            with self.assertRaises(KeyboardInterrupt):
                self.install()
        self.assert_original()
        self.assert_backup()

    def test_same_version_replacement_is_restored_after_interruption(self):
        self.jar = self.incoming / self.old.name
        self.jar.write_bytes(b"new jar at same version")
        replace = Path.replace
        interrupted = False

        def replacing(path, destination):
            nonlocal interrupted
            result = replace(path, destination)
            if Path(destination) == self.old and not interrupted:
                interrupted = True
                raise KeyboardInterrupt("same version")
            return result

        with patch.object(Path, "replace", replacing):
            with self.assertRaises(KeyboardInterrupt):
                self.install()
        self.assert_original()
        self.assert_backup()

    def test_interrupted_initial_install_removes_new_files_only(self):
        self.old.unlink()
        self.script.unlink()
        replace = Path.replace
        interrupted = False

        def replacing(path, destination):
            nonlocal interrupted
            result = replace(path, destination)
            if Path(destination) == self.script and not interrupted:
                interrupted = True
                raise KeyboardInterrupt("initial install")
            return result

        with patch.object(Path, "replace", replacing):
            with self.assertRaises(KeyboardInterrupt):
                self.install()
        self.assertEqual([], list(self.old.parent.glob("assistantia-*.jar")))
        self.assertFalse(self.script.exists())
        self.assertEqual(b"other mod", self.other.read_bytes())
        self.assertEqual(b'{"valuable":"stock"}', self.world.read_bytes())

    def test_success_installs_matching_pair_and_keeps_backup(self):
        backup = self.install()
        self.assertEqual(
            [self.new.name], [p.name for p in self.old.parent.glob("assistantia-*.jar")]
        )
        self.assertEqual(b"new jar", self.new.read_bytes())
        self.assertEqual(b"new script", self.script.read_bytes())
        self.assertTrue((backup / "installation.json").is_file())
        self.assert_backup()
        self.assertEqual(b"other mod", self.other.read_bytes())
        self.assertEqual(b'{"valuable":"stock"}', self.world.read_bytes())


if __name__ == "__main__":
    unittest.main()
