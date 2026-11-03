"""Exercise the actual update launcher against a local Git remote (no network)."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GIT = next(
    (p for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git") if Path(p).is_file()),
    shutil.which("git"),
)


class UpdateTests(unittest.TestCase):
    def git(self, *args, cwd=None):
        return subprocess.run(
            [GIT, *args],
            cwd=cwd or self.source,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.remote = self.root / "remote.git"
        self.client = self.root / "client"
        self.git("init", "--bare", str(self.remote))
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Update Test")
        self.git("config", "user.email", "update@example.invalid")
        shutil.copy2(ROOT / "Mettre à jour.command", self.source)
        shutil.copy2(ROOT / "update_assistant.py", self.source)
        (self.source / "Installer Mac.command").write_text(
            "#!/bin/bash\necho INSTALLER_CALLED\n"
        )
        (self.source / "tracked.txt").write_text("before\n")
        (self.source / ".gitignore").write_text("codex.local.json\nassistant.local.json\nsession_claude.json\nsession.json\nplan_ia.json\nmonde_memoire.json\n")
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "main")
        self.git(
            "clone", "--branch", "main", str(self.remote), str(self.client)
        )
        (self.client / "codex.local.json").write_text('{"private":true}\n')
        (self.client / "assistant.local.json").write_text('{"provider":"claude"}\n')
        (self.client / "session_claude.json").write_text('{"session_id":"private"}\n')
        self.personal = {"session.json": '{"thread_id":"original"}\n',
                         "plan_ia.json": '{"objectif_en_pause":true}\n',
                         "monde_memoire.json": '{"coffres":["private"]}\n'}
        for name, text in self.personal.items():
            (self.client / name).write_text(text)
        (self.source / "tracked.txt").write_text("after\n")
        self.git("commit", "-am", "Published update")
        self.git("push", "origin", "main")

    def update(self):
        return subprocess.run(
            ["bash", "Mettre à jour.command"],
            cwd=self.client,
            env=dict(os.environ, GIT_TERMINAL_PROMPT="0"),
            capture_output=True,
            text=True,
        )

    def test_fast_forward_preserves_local_configuration_then_installs(self):
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual((self.client / "assistant.local.json").read_text(), '{"provider":"claude"}\n')
        self.assertEqual((self.client / "session_claude.json").read_text(), '{"session_id":"private"}\n')
        self.assertEqual((self.client / "tracked.txt").read_text(), "after\n")
        for name, text in self.personal.items():
            self.assertEqual((self.client / name).read_text(), text)
        self.assertEqual(
            (self.client / "codex.local.json").read_text(), '{"private":true}\n'
        )

    def test_legacy_branches_migrate_to_main_and_preserve_preferences(self):
        self.assertEqual(self.git("ls-remote", "--heads", "origin", "shared-assistant", "codex-compat"), "")
        for branch in ("shared-assistant", "codex-compat"):
            with self.subTest(branch=branch):
                self.git("branch", "-m", branch, cwd=self.client)
                result = self.update()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("INSTALLER_CALLED", result.stdout)
                self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "main")
                self.assertEqual(self.git("rev-parse", "--abbrev-ref", "@{upstream}", cwd=self.client), "origin/main")
                self.assertEqual((self.client / "assistant.local.json").read_text(), '{"provider":"claude"}\n')

    def test_development_branch_and_detached_head_are_never_changed(self):
        self.git("branch", "-m", "feature/work", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        for detached in (False, True):
            with self.subTest(detached=detached):
                if detached:
                    self.git("switch", "--detach", cwd=self.client)
                result = self.update()
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("INSTALLER_CALLED", result.stdout)
                self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)

    def test_existing_main_with_unpublished_commits_blocks_migration(self):
        self.git("config", "user.name", "Update Test", cwd=self.client)
        self.git("config", "user.email", "update@example.invalid", cwd=self.client)
        self.git("branch", "shared-assistant", cwd=self.client)
        (self.client / "tracked.txt").write_text("unpublished main\n")
        self.git("commit", "-am", "Local main work", cwd=self.client)
        main_before = self.git("rev-parse", "main", cwd=self.client)
        self.git("switch", "shared-assistant", cwd=self.client)
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "shared-assistant")
        self.assertEqual(self.git("rev-parse", "main", cwd=self.client), main_before)

    def test_existing_main_is_fast_forwarded_during_legacy_migration(self):
        self.git("switch", "-c", "shared-assistant", cwd=self.client)
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "main")
        self.assertEqual((self.client / "tracked.txt").read_text(), "after\n")

    def test_single_branch_legacy_clone_migrates_and_future_plain_pull_works(self):
        self.git("push", "origin", "HEAD:shared-assistant")
        self.client = self.root / "single-branch"
        self.git("clone", "--single-branch", "--branch", "shared-assistant", str(self.remote), str(self.client))
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "main")
        (self.source / "tracked.txt").write_text("next main update\n")
        self.git("commit", "-am", "Next main release")
        self.git("push", "origin", "main")
        self.git("pull", "--ff-only", cwd=self.client)
        self.assertEqual((self.client / "tracked.txt").read_text(), "next main update\n")

    def test_ahead_legacy_branch_keeps_its_commits_and_does_not_install(self):
        self.git("fetch", "origin", cwd=self.client)
        self.git("merge", "--ff-only", "origin/main", cwd=self.client)
        self.git("branch", "-m", "shared-assistant", cwd=self.client)
        self.git("config", "user.name", "Update Test", cwd=self.client)
        self.git("config", "user.email", "update@example.invalid", cwd=self.client)
        (self.client / "tracked.txt").write_text("unpublished legacy\n")
        self.git("commit", "-am", "Unpublished legacy work", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "shared-assistant")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)

    def test_untracked_file_blocks_update_without_changing_branch(self):
        self.git("branch", "-m", "shared-assistant", cwd=self.client)
        (self.client / "my_notes.txt").write_text("keep me\n")
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "shared-assistant")
        self.assertEqual((self.client / "my_notes.txt").read_text(), "keep me\n")

    def test_uncommitted_changes_are_never_overwritten(self):
        (self.client / "tracked.txt").write_text("my edit\n")
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual((self.client / "tracked.txt").read_text(), "my edit\n")

    def test_divergent_history_is_not_reset_or_installed(self):
        self.git("config", "user.name", "Update Test", cwd=self.client)
        self.git("config", "user.email", "update@example.invalid", cwd=self.client)
        (self.client / "tracked.txt").write_text("local commit\n")
        self.git("commit", "-am", "Local change", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)
        self.assertEqual((self.client / "tracked.txt").read_text(), "local commit\n")


if __name__ == "__main__":
    unittest.main()
