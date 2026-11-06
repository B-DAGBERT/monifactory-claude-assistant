"""Qualification of opt-in backups using synthetic data only, never the installed instance."""

import contextlib
import hashlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant import data_backup as b
from monifactory_assistant.commands.backup_data import main


class DataBackupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.python = self.root / "python"
        self.game = self.root / "kubejs-assistant"
        self.python.mkdir()
        self.game.mkdir()
        self.archive = self.root / "archive"
        self.restored = self.root / "restored"
        self.put(
            self.python,
            "plan_ia.json",
            b'{"projets":[{"id":"pause","etat":{"objectif_en_pause":true}}]}',
        )
        self.put(self.python, "plan_ia.json.bak", b"older\r\n\x00")
        self.put(self.python, "plan_ia.json.avant-projets", b"legacy-format")
        self.put(self.python, "session.json", b'{"thread_id":"secret-session"}')
        self.put(self.python, "session_claude.json", b'{"session_id":"another-session"}')
        self.put(self.python, "assistant.local.json", b'{"private":"DONT_PRINT_CONTENT"}')
        self.put(self.python, "fiches.json", b"\xffinvalid JSON is preserved\x00")
        self.put(self.python, "journal.jsonl", b'{"t":1}\n{"truncated":')
        self.put(self.python, ".plan_ia.json-recovery.tmp", b"interrupted-original")
        self.put(self.python, "backups/old/blob.bin", b"\x00\x01\xff")
        self.put(self.python, "captures/image.png", b"PNG fixture")
        self.put(self.python, ".runtime/scenarios/report.json", b"{}")
        self.put(self.python, "code.py", b"not user data")
        self.put(self.game, "stockage.json", b'{"conteneurs":[]}')
        self.put(self.game, "filons_gregtech/0123.json", b'{"source":"gregtech"}')
        self.put(self.game, "future-unknown.bin", b"unknown future format\xff")
        self.put(self.game, "demandes/old.json", b'{"n":2}')
        self.put(self.game, "filons/imports/pending.json.tmp", b"incomplete import")
        (self.game / "empty-directory").mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, root, name, raw):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        return path

    def backup(self, **kwargs):
        return b.backup(self.python, self.game, self.archive, guard=lambda: None, **kwargs)

    def rewrite_manifest(self, data):
        raw = b._json(data)
        (self.archive / "manifest.json").write_bytes(raw)
        (self.archive / "complete.json").write_bytes(
            b._json({"format": b.FORMAT, "manifest_sha256": hashlib.sha256(raw).hexdigest()})
        )

    def test_inventory_is_read_only_and_preserves_unknown_formats(self):
        before = sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*"))
        data, _ = b.inventory(self.python, self.game)
        after = sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*"))
        self.assertEqual(before, after)
        paths = {(r["root"], r["path"]) for r in data["entries"]}
        self.assertIn(("python", "plan_ia.json.avant-projets"), paths)
        self.assertIn(("python", ".plan_ia.json-recovery.tmp"), paths)
        self.assertIn(("python", "backups/old/blob.bin"), paths)
        self.assertIn(("kubejs-assistant", "future-unknown.bin"), paths)
        self.assertIn(("kubejs-assistant", "empty-directory"), paths)
        self.assertNotIn(("python", "code.py"), paths)
        self.assertNotIn("DONT_PRINT_CONTENT", json.dumps(data))

    def test_exact_roundtrip_and_idempotent_backup(self):
        data, _ = b.inventory(self.python, self.game)
        self.assertFalse(self.backup()["reused"])
        self.assertEqual(data, b.verify(self.archive))
        before = sorted(p.name for p in self.root.iterdir())
        self.assertTrue(self.backup()["reused"])
        self.assertEqual(before, sorted(p.name for p in self.root.iterdir()))
        result = b.restore(self.archive, self.restored)
        self.assertEqual(set(result["roots"]), set(b.ROOTS))
        for record in data["entries"]:
            original = (self.python if record["root"] == "python" else self.game) / record["path"]
            restored = self.restored / record["root"] / record["path"]
            if record["kind"] == "file":
                self.assertEqual(original.read_bytes(), restored.read_bytes())
                self.assertEqual(original.stat().st_mtime_ns, restored.stat().st_mtime_ns)
            else:
                self.assertTrue(restored.is_dir())
        self.assertFalse((self.restored / "python/code.py").exists())
        # Neither sessions, project state, nor opaque legacy contents have been reserialized.
        self.assertEqual(data, b.verify(self.archive))
        with self.assertRaises(b.BackupError):
            b.restore(self.archive, self.restored)

    def test_interrupted_backup_is_never_published_and_can_be_restarted(self):
        for phase in ("inventoried", "copied", "verified", "before_publish"):
            with self.subTest(phase=phase):
                target = self.root / phase

                def stop(current):
                    if current == phase:
                        raise RuntimeError("simulated power loss")

                original, stamps = b.inventory(self.python, self.game)
                with self.assertRaises(RuntimeError):
                    b.backup(self.python, self.game, target, guard=lambda: None, checkpoint=stop)
                self.assertFalse(target.exists())
                self.assertEqual((original, stamps), b.inventory(self.python, self.game))
                for partial in self.root.glob(".*.partial"):
                    with self.assertRaises(b.BackupError):
                        b.verify(partial)
                b.backup(self.python, self.game, target, guard=lambda: None)
                self.assertEqual(original, b.verify(target))

    def test_interrupted_restore_leaves_archive_unchanged(self):
        self.backup()
        original = b.verify(self.archive)
        for phase in ("copied", "verified", "before_publish"):
            target = self.root / ("restore-" + phase)

            def stop(current):
                if current == phase:
                    raise RuntimeError("interrupted restore")

            with self.assertRaises(RuntimeError):
                b.restore(self.archive, target, checkpoint=stop)
            self.assertFalse(target.exists())
            self.assertEqual(original, b.verify(self.archive))
            b.restore(self.archive, target)
            self.assertTrue((target / "python/session.json").is_file())

    def test_interruption_after_atomic_publication_is_recoverable(self):
        sync = b._sync_directory

        def fail_after_publish(path):
            if path == self.root and self.archive.exists():
                raise OSError("simulated interruption after rename")
            sync(path)

        with patch.object(b, "_sync_directory", side_effect=fail_after_publish):
            with self.assertRaises(OSError):
                self.backup()
        self.assertEqual(b.inventory(self.python, self.game)[0], b.verify(self.archive))
        self.assertTrue(self.backup()["reused"])

        def fail_after_restore(path):
            if path == self.root and self.restored.exists():
                raise OSError("simulated interruption after restore rename")
            sync(path)

        with patch.object(b, "_sync_directory", side_effect=fail_after_restore):
            with self.assertRaises(OSError):
                b.restore(self.archive, self.restored)
        self.assertEqual(
            (self.python / "session.json").read_bytes(),
            (self.restored / "python/session.json").read_bytes(),
        )
        with self.assertRaises(b.BackupError):
            b.restore(self.archive, self.restored)

    def test_concurrent_content_change_prevents_publication(self):
        for phase in ("inventoried", "copied", "verified"):
            with self.subTest(phase=phase):
                changed = False

                def change(current):
                    nonlocal changed
                    if current == phase and not changed:
                        changed = True
                        self.put(self.game, "stockage.json", os.urandom(32))

                target = self.root / ("changed-" + phase)
                with self.assertRaises(b.BackupError):
                    b.backup(self.python, self.game, target, guard=lambda: None, checkpoint=change)
                self.assertFalse(target.exists())

    def test_concurrent_file_addition_and_removal_are_detected(self):
        for action in ("add", "remove"):
            with self.subTest(action=action):

                def change(phase):
                    if phase == "verified":
                        if action == "add":
                            self.put(self.game, "new.json", b"new live observation")
                        else:
                            (self.game / "new.json").unlink()

                target = self.root / action
                with self.assertRaises(b.BackupError):
                    b.backup(self.python, self.game, target, guard=lambda: None, checkpoint=change)
                self.assertFalse(target.exists())

    def test_same_bytes_replaced_file_is_detected(self):
        def replace(phase):
            if phase == "verified":
                path = self.game / "stockage.json"
                raw, stamp = path.read_bytes(), path.stat()
                path.unlink()
                path.write_bytes(raw)
                os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))

        with self.assertRaises(b.BackupError):
            self.backup(checkpoint=replace)
        self.assertFalse(self.archive.exists())

    def test_writer_guards_run_twice_and_refuse_new_writer(self):
        calls = []

        def guard():
            calls.append(True)
            if len(calls) == 2:
                raise b.BackupError("writer started")

        with self.assertRaises(b.BackupError):
            b.backup(self.python, self.game, self.archive, guard=guard)
        self.assertEqual(2, len(calls))
        self.assertFalse(self.archive.exists())

    def test_writer_detection_fails_closed(self):
        for game, assistant in ((True, False), (None, False), (False, True), (False, None)):
            with (
                patch.object(b, "minecraft_running", return_value=game),
                patch.object(b, "_assistant_running", return_value=assistant),
            ):
                with self.assertRaises(b.BackupError):
                    b.writers_stopped()
        with (
            patch.object(b, "minecraft_running", return_value=False),
            patch.object(b, "_assistant_running", return_value=False),
        ):
            b.writers_stopped()

    def test_never_overwrites_existing_or_racing_destination(self):
        self.archive.mkdir()
        self.put(self.archive, "keep.bin", b"untouched")
        with self.assertRaises((b.BackupError, OSError)):
            self.backup()
        self.assertEqual(b"untouched", (self.archive / "keep.bin").read_bytes())

        def occupy(phase):
            if phase == "before_publish":
                self.restored.mkdir()

        with self.assertRaises(OSError):
            b.backup(self.python, self.game, self.restored, guard=lambda: None, checkpoint=occupy)
        self.assertEqual([], list(self.restored.iterdir()))

    def test_restore_race_does_not_replace_new_empty_directory(self):
        self.backup()

        def occupy(phase):
            if phase == "before_publish":
                self.restored.mkdir()

        with self.assertRaises(OSError):
            b.restore(self.archive, self.restored, checkpoint=occupy)
        self.assertEqual([], list(self.restored.iterdir()))

    def test_existing_backup_is_not_reused_after_source_change(self):
        self.backup()
        old = b.verify(self.archive)
        self.put(self.game, "stockage.json", b"newer inventory")
        with self.assertRaises(b.BackupError):
            self.backup()
        self.assertEqual(old, b.verify(self.archive))

    def test_corrupt_missing_or_extra_archived_files_are_rejected(self):
        self.backup()
        path = self.archive / "kubejs-assistant/stockage.json"
        raw = path.read_bytes()
        for mutation in ("corrupt", "missing", "extra"):
            with self.subTest(mutation=mutation):
                if mutation == "corrupt":
                    path.write_bytes(b"x" * len(raw))
                if mutation == "missing":
                    path.unlink()
                if mutation == "extra":
                    self.put(self.archive, "kubejs-assistant/extra", b"new")
                with self.assertRaises(b.BackupError):
                    b.verify(self.archive)
                with self.assertRaises(b.BackupError):
                    b.restore(self.archive, self.restored)
                self.assertFalse(self.restored.exists())
                if mutation in ("corrupt", "missing"):
                    path.write_bytes(raw)
                if mutation == "extra":
                    (self.archive / "kubejs-assistant/extra").unlink()

    def test_manifest_hash_and_path_escape_are_rejected(self):
        self.backup()
        data = b.verify(self.archive)
        (self.archive / "manifest.json").write_text("{}")
        with self.assertRaises(b.BackupError):
            b.verify(self.archive)
        for name in ("../escape", "/absolute", "C:/drive", "folder\\escape", "folder/../escape"):
            altered = json.loads(json.dumps(data))
            altered["entries"][0]["path"] = name
            self.rewrite_manifest(altered)
            with self.assertRaises(b.BackupError):
                b.restore(self.archive, self.restored)
            self.assertFalse(self.restored.exists())

    def test_no_source_symlinks_or_archive_symlinks_followed(self):
        outside = self.put(self.root, "outside", b"not selected")
        link = self.game / "linked"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("symlinks unavailable")
        with self.assertRaises(b.BackupError):
            b.inventory(self.python, self.game)
        link.unlink()
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(b.BackupError):
            b.inventory(self.python, self.game)
        link.unlink()
        self.backup()
        path = self.archive / "kubejs-assistant/stockage.json"
        path.unlink()
        path.symlink_to(outside)
        with self.assertRaises(b.BackupError):
            b.verify(self.archive)

    def test_source_link_introduced_after_inventory_is_rejected(self):
        outside = self.put(self.root, "outside", b"different private data")

        def change(phase):
            if phase == "inventoried":
                path = self.game / "stockage.json"
                path.unlink()
                path.symlink_to(outside)

        with self.assertRaises(b.BackupError):
            self.backup(checkpoint=change)
        self.assertFalse(self.archive.exists())

    def test_fifo_is_rejected_without_reading(self):
        if not hasattr(os, "mkfifo"):
            self.skipTest("POSIX only")
        os.mkfifo(self.game / "pipe")
        with self.assertRaises(b.BackupError):
            b.inventory(self.python, self.game)

    def test_overlapping_paths_and_partial_archives_are_rejected(self):
        with self.assertRaises(b.BackupError):
            b.backup(self.python, self.game, self.game / "backup", guard=lambda: None)
        with self.assertRaises(b.BackupError):
            b.inventory(self.python, self.python / "backups")
        self.backup()
        with self.assertRaises(b.BackupError):
            b.restore(self.archive, self.archive / "restored")
        partial = self.root / "manual.partial"
        self.archive.rename(partial)
        with self.assertRaises(b.BackupError):
            b.verify(partial)

    def test_cli_dry_run_cannot_publish_or_read_configuration(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = main(
                [
                    "backup",
                    "--python-root",
                    str(self.python),
                    "--game-data",
                    str(self.game),
                    "--destination",
                    str(self.archive),
                    "--dry-run",
                ]
            )
        self.assertEqual(0, code)
        self.assertTrue(json.loads(output.getvalue())["dry_run"])
        self.assertNotIn("DONT_PRINT_CONTENT", output.getvalue())
        self.assertFalse(self.archive.exists())

    def test_cli_no_live_sources_or_default_activation(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                main(["backup"])
            self.assertEqual(1, main(["verify", str(self.root / "missing")]))
        self.assertFalse(self.archive.exists())

    def test_archive_changed_during_restore_is_not_published(self):
        self.backup()
        changed = False

        def change(phase):
            nonlocal changed
            if phase == "copied" and not changed:
                changed = True
                (self.archive / "kubejs-assistant/stockage.json").write_bytes(b"other source")

        with self.assertRaises(b.BackupError):
            b.restore(self.archive, self.restored, checkpoint=change)
        self.assertFalse(self.restored.exists())


if __name__ == "__main__":
    unittest.main()
