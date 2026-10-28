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
        self.git("init", "-b", "codex-compat")
        self.git("config", "user.name", "Update Test")
        self.git("config", "user.email", "update@example.invalid")
        shutil.copy2(ROOT / "Mettre à jour.command", self.source)
        (self.source / "Installer Mac.command").write_text(
            "#!/bin/bash\necho INSTALLER_CALLED\n"
        )
        (self.source / "tracked.txt").write_text("before\n")
        (self.source / ".gitignore").write_text("codex.local.json\n")
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "codex-compat")
        self.git(
            "clone", "--branch", "codex-compat", str(self.remote), str(self.client)
        )
        (self.client / "codex.local.json").write_text('{"private":true}\n')
        (self.source / "tracked.txt").write_text("after\n")
        self.git("commit", "-am", "Published update")
        self.git("push", "origin", "codex-compat")

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
        self.assertEqual((self.client / "tracked.txt").read_text(), "after\n")
        self.assertEqual(
            (self.client / "codex.local.json").read_text(), '{"private":true}\n'
        )

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
