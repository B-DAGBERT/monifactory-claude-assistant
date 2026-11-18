"""Exercise the manual launcher and shared updater against a local Git remote."""

import json
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

TEST_BUILD = """
import os
from importlib.util import find_spec

if find_spec("monifactory_assistant") is not None:
    from monifactory_assistant.commands import auto_update

    class FixtureUpdater(auto_update.AutoUpdater):
        def __init__(self, root, instance):
            super().__init__(root, instance, game_running=lambda: os.environ["TEST_UPDATE_GAME"] == "running")

        def build(self, stage):
            if os.environ["TEST_UPDATE_GAME"] == "build-failure":
                raise RuntimeError("failing fixture build")
            jar = stage / "mod/build/libs/assistantia-test.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes((stage / "mod/source.txt").read_bytes())

        def deploy(self, stage, before, target):
            backup = super().deploy(stage, before, target)
            if os.environ["TEST_UPDATE_GAME"] == "interrupted-install":
                raise OSError("interrupted fixture installation")
            return backup

    auto_update.AutoUpdater = FixtureUpdater
"""


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
        self.support = self.root / "support"
        self.support.mkdir()
        (self.support / "sitecustomize.py").write_text(TEST_BUILD, encoding="utf-8")
        self.instance = self.root / "instance"
        (self.instance / "mods").mkdir(parents=True)
        (self.instance / "kubejs/client_scripts").mkdir(parents=True)
        self.jar = self.instance / "mods/assistantia-previous.jar"
        self.script = self.instance / "kubejs/client_scripts/assistant_ia.js"
        self.jar.write_text("previous jar")
        self.script.write_text("previous script")
        self.git("init", "--bare", str(self.remote))
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Update Test")
        self.git("config", "user.email", "update@example.invalid")
        shutil.copy2(ROOT / "Mettre à jour.command", self.source)
        shutil.copy2(ROOT / "update_assistant.py", self.source)
        shutil.copytree(
            ROOT / "monifactory_assistant",
            self.source / "monifactory_assistant",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
        )
        (self.source / "Installer Mac.command").write_text("#!/bin/bash\necho INSTALLER_CALLED\n")
        (self.source / "tracked.txt").write_text("before\n")
        (self.source / "requirements.txt").write_text("")
        (self.source / "mod").mkdir()
        (self.source / "mod/gradle.properties").write_text("mod_version=test\n")
        (self.source / "mod/source.txt").write_text("release jar")
        (self.source / "jeu").mkdir()
        (self.source / "jeu/assistant_ia.js").write_text("release script")
        (self.source / ".gitignore").write_text(
            "__pycache__/\n.runtime/\nbackups/\nassistant.lock\ncodex.local.json\nassistant.local.json\n"
            "session_claude.json\nsession.json\nplan_ia.json\nmonde_memoire.json\n"
        )
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "main")
        self.git("clone", "--branch", "main", str(self.remote), str(self.client))
        (self.client / "codex.local.json").write_text('{"private":true}\n')
        (self.client / "assistant.local.json").write_text('{"provider":"claude"}\n')
        (self.client / "session_claude.json").write_text('{"session_id":"private"}\n')
        self.personal = {
            "session.json": '{"thread_id":"original"}\n',
            "plan_ia.json": '{"objectif_en_pause":true}\n',
            "monde_memoire.json": '{"coffres":["private"]}\n',
        }
        for name, text in self.personal.items():
            (self.client / name).write_text(text)
        (self.source / "tracked.txt").write_text("after\n")
        self.git("commit", "-am", "Published update")
        self.git("push", "origin", "main")

    def update(self, mode="closed"):
        return subprocess.run(
            ["bash", "Mettre à jour.command"],
            cwd=self.client,
            env=dict(
                os.environ,
                GIT_TERMINAL_PROMPT="0",
                MINECRAFT_DIR=str(self.instance),
                PYTHONPATH=os.pathsep.join(map(str, (self.client, self.support))),
                TEST_UPDATE_GAME=mode,
            ),
            capture_output=True,
            text=True,
            timeout=20,
        )

    def assert_transaction_installed(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        installed = json.loads((self.client / ".runtime/installed-update.json").read_text())
        self.assertEqual(installed["revision"], self.git("rev-parse", "HEAD", cwd=self.client))
        self.assertEqual(installed["jar"], "assistantia-test.jar")
        self.assertFalse(self.jar.exists())
        self.assertEqual(self.jar.with_name(installed["jar"]).read_text(), "release jar")
        self.assertEqual(self.script.read_text(), "release script")
        self.assertFalse((self.client / ".runtime/install-pending.json").exists())

    def assert_original_pair(self):
        self.assertEqual(list(self.instance.glob("mods/assistantia-*.jar")), [self.jar])
        self.assertEqual(self.jar.read_text(), "previous jar")
        self.assertEqual(self.script.read_text(), "previous script")

    def test_fast_forward_preserves_local_configuration_then_installs(self):
        result = self.update()
        self.assert_transaction_installed(result)
        self.assertEqual(
            (self.client / "assistant.local.json").read_text(), '{"provider":"claude"}\n'
        )
        self.assertEqual(
            (self.client / "session_claude.json").read_text(), '{"session_id":"private"}\n'
        )
        self.assertEqual((self.client / "tracked.txt").read_text(), "after\n")
        for name, text in self.personal.items():
            self.assertEqual((self.client / name).read_text(), text)
        self.assertEqual((self.client / "codex.local.json").read_text(), '{"private":true}\n')

    def publish_private_plan(self):
        (self.source / "plan_ia.json").write_text('{"objectif_en_pause":false}')
        self.git("add", "-f", "plan_ia.json")
        self.git("commit", "-m", "Accidentally tracked private plan")
        self.git("push", "origin", "main")

    def assert_private_collision_refused(self, before, branch):
        self.assertEqual(self.git("status", "--porcelain", cwd=self.client), "")
        result = self.update()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("INSTALLER_CALLED", result.stdout)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), branch)
        self.assert_original_pair()
        for name, text in self.personal.items():
            self.assertEqual((self.client / name).read_text(), text)

    def test_ignored_private_plan_tracked_upstream_blocks_fast_forward(self):
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        self.publish_private_plan()
        self.assert_private_collision_refused(before, "main")

    def test_legacy_switch_to_existing_main_preserves_ignored_plan(self):
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        self.git("switch", "-c", "shared-assistant", cwd=self.client)
        self.publish_private_plan()
        self.git("fetch", "origin", cwd=self.client)
        self.git("branch", "-f", "main", "origin/main", cwd=self.client)
        self.assert_private_collision_refused(before, "shared-assistant")

    def test_legacy_creation_of_main_preserves_ignored_plan(self):
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        self.git("branch", "-m", "shared-assistant", cwd=self.client)
        self.publish_private_plan()
        self.assert_private_collision_refused(before, "shared-assistant")
        self.assertEqual(self.git("branch", "--list", "main", cwd=self.client), "")

    def test_legacy_branches_migrate_to_main_and_preserve_preferences(self):
        self.assertEqual(
            self.git("ls-remote", "--heads", "origin", "shared-assistant", "codex-compat"), ""
        )
        for branch in ("shared-assistant", "codex-compat"):
            with self.subTest(branch=branch):
                self.git("branch", "-m", branch, cwd=self.client)
                result = self.update()
                self.assert_transaction_installed(result)
                self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "main")
                self.assertEqual(
                    self.git("rev-parse", "--abbrev-ref", "@{upstream}", cwd=self.client),
                    "origin/main",
                )
                self.assertEqual(
                    (self.client / "assistant.local.json").read_text(), '{"provider":"claude"}\n'
                )

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
        self.assert_transaction_installed(result)

    def test_minecraft_running_preserves_legacy_checkout_and_installation(self):
        self.git("branch", "-m", "shared-assistant", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        result = self.update("running")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "shared-assistant")
        self.assertEqual(self.git("branch", "--list", "main", cwd=self.client), "")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)
        self.assertEqual((self.client / "tracked.txt").read_text(), "before\n")
        self.assert_original_pair()

    def test_failed_build_preserves_main_and_installation(self):
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        result = self.update("build-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)
        self.assertEqual((self.client / "tracked.txt").read_text(), "before\n")
        self.assert_original_pair()

    def test_legacy_checkout_with_pending_installation_restarts_from_main(self):
        self.git("branch", "-m", "shared-assistant", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        result = self.update("interrupted-install")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "main")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)
        self.assertEqual(self.git("rev-parse", "shared-assistant", cwd=self.client), before)
        self.assertTrue((self.client / ".runtime/install-pending.json").is_file())
        self.assert_transaction_installed(self.update())

    def test_single_branch_legacy_clone_migrates_and_future_plain_pull_works(self):
        self.git("push", "origin", "HEAD:shared-assistant")
        self.client = self.root / "single-branch"
        self.git(
            "clone",
            "--single-branch",
            "--branch",
            "shared-assistant",
            str(self.remote),
            str(self.client),
        )
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

    def test_old_root_updater_can_install_package_layout_then_update_again(self):
        old_updater = self.git("show", "4a6bba3:update_assistant.py", cwd=ROOT)
        self.git("rm", "-r", "monifactory_assistant")
        (self.source / "update_assistant.py").write_text(old_updater + "\n")
        self.git("commit", "-am", "Old flat installation")
        self.git("push", "origin", "main")
        self.client = self.root / "old-layout"
        self.git("clone", "--branch", "main", str(self.remote), str(self.client))
        (self.client / "assistant.local.json").write_text('{"provider":"claude"}\n')
        shutil.copy2(ROOT / "update_assistant.py", self.source)
        shutil.copytree(
            ROOT / "monifactory_assistant",
            self.source / "monifactory_assistant",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
        )
        self.git("add", ".")
        self.git("commit", "-m", "Organized package layout")
        self.git("push", "origin", "main")
        result = self.update()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            "INSTALLER_CALLED", result.stdout
        )  # The historical launcher bootstraps the package.
        self.assert_transaction_installed(self.update())
        self.assertTrue((self.client / "monifactory_assistant/commands/update.py").is_file())
        self.assertEqual(
            (self.client / "assistant.local.json").read_text(), '{"provider":"claude"}\n'
        )

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
