"""Startup updates against a local remote and real installation files, without AI or network."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant.commands import setup
from monifactory_assistant.commands.auto_update import (
    AutoUpdater,
    minecraft_command,
    minecraft_running,
    notice_key,
)

GIT = shutil.which("git")


class Harness(AutoUpdater):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.builds = 0
        self.dependency_updates = 0

    def build(self, stage):
        self.builds += 1
        jar = stage / "mod/build/libs/assistantia-test.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes((stage / "mod/source.txt").read_bytes())

    def dependencies(self, stage):
        self.dependency_updates += 1

    def deploy(self, stage):
        with patch.object(setup, "HERE", self.root):
            return super().deploy(stage)


class UpdateNoticeTests(unittest.TestCase):
    def test_notice_key_tracks_release_not_repeated_checks(self):
        with patch("monifactory_assistant.commands.auto_update.read_json") as read:
            read.return_value = {"target": "release-a", "message": "Disponible", "checked_at": 1}
            first = notice_key()
            read.return_value = {"target": "release-a", "message": "Prête", "checked_at": 2}
            self.assertEqual(notice_key(), first)
            read.return_value = {"revision": "release-b", "message": "Prête", "checked_at": 2}
            self.assertNotEqual(notice_key(), first)
            read.return_value = {"message": "Hors ligne"}
            self.assertEqual(notice_key(), "Hors ligne")


class AutomaticUpdateTests(unittest.TestCase):
    def git(self, *args, cwd=None):
        return subprocess.run(
            [GIT, *args], cwd=cwd or self.source, check=True, text=True, capture_output=True
        ).stdout.strip()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.source, self.client, self.remote = (
            base / "source",
            base / "client",
            base / "remote.git",
        )
        self.source.mkdir()
        self.git("init", "--bare", str(self.remote))
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Updater Test")
        self.git("config", "user.email", "updater@example.invalid")
        (self.source / "mod").mkdir()
        (self.source / "jeu").mkdir()
        (self.source / "mod/gradle.properties").write_text("mod_version=test\n")
        (self.source / "mod/source.txt").write_text("old jar")
        (self.source / "jeu/assistant_ia.js").write_text("old script")
        (self.source / "requirements.txt").write_text("")
        (self.source / "app.py").write_text("old app")
        (self.source / ".gitignore").write_text(
            ".runtime/\nbackups/\n*.local.json\nsession.json\nplan_ia.json\n"
        )
        self.git("add", ".")
        self.git("commit", "-m", "Initial")
        self.old = self.git("rev-parse", "HEAD")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "main")
        self.git("clone", "--branch", "main", str(self.remote), str(self.client))
        self.instance = base / "instance"
        (self.instance / "mods").mkdir(parents=True)
        (self.instance / "kubejs/client_scripts").mkdir(parents=True)
        self.jar = self.instance / "mods/assistantia-test.jar"
        self.script = self.instance / "kubejs/client_scripts/assistant_ia.js"
        self.jar.write_text("old jar")
        self.script.write_text("old script")
        self.personal = {
            "assistant.local.json": '{"provider":"claude"}',
            "session.json": '{"thread_id":"keep"}',
            "plan_ia.json": '{"paused":true}',
        }
        for name, contents in self.personal.items():
            (self.client / name).write_text(contents)
        self.updater = Harness(self.client, self.instance, game_running=Mock(return_value=False))
        self.updater.remember_install(self.old)
        self.publish()

    def publish(self, *, python_only=False, dependencies=False):
        if python_only:
            (self.source / "app.py").write_text("new app")
        else:
            (self.source / "mod/source.txt").write_text("new jar")
            (self.source / "jeu/assistant_ia.js").write_text("new script")
        if dependencies:
            (self.source / "requirements.txt").write_text("new-dependency\n")
        self.git("add", ".")
        self.git("commit", "-m", "Update")
        self.git("push", "origin", "main")
        self.target = self.git("rev-parse", "HEAD")

    def assert_unchanged(self):
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), self.old)
        self.assertEqual(self.jar.read_text(), "old jar")
        self.assertEqual(self.script.read_text(), "old script")

    def test_closed_game_builds_installs_and_preserves_personal_data(self):
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), self.target)
        self.assertEqual(self.jar.read_text(), "new jar")
        self.assertEqual(self.script.read_text(), "new script")
        self.assertEqual(len(list((self.instance / "mods").glob("assistantia*.jar"))), 1)
        for name, contents in self.personal.items():
            self.assertEqual((self.client / name).read_text(), contents)
        self.assertEqual(len(list((self.client / "backups").glob("*/installation.json"))), 1)
        self.assertFalse([p for p in (self.client / ".runtime").glob("update-*") if p.is_dir()])
        self.assertEqual(self.updater.check()["state"], "current")
        self.assertEqual(self.updater.builds, 1)

    def test_open_or_unknown_game_defers_without_updating_sources(self):
        for running in (True, None):
            self.updater.game_running.return_value = running
            self.assertEqual(self.updater.check()["state"], "pending")
            self.assert_unchanged()
        self.assertEqual(self.updater.builds, 0)

    def test_game_starting_during_build_defers_installation(self):
        self.updater.game_running.side_effect = [False, True]
        self.assertEqual(self.updater.check()["state"], "pending")
        self.assertEqual(self.updater.builds, 1)
        self.assert_unchanged()

    def test_offline_keeps_installed_version(self):
        self.remote.rename(self.remote.with_name("unavailable.git"))
        self.assertEqual(self.updater.check()["state"], "offline")
        self.assert_unchanged()

    def test_development_branch_and_local_edits_are_preserved(self):
        self.git("switch", "-c", "feature/work", cwd=self.client)
        self.assertEqual(self.updater.check()["state"], "local_changes")
        self.git("switch", "main", cwd=self.client)
        (self.client / "app.py").write_text("local edit")
        self.assertEqual(self.updater.check()["state"], "local_changes")
        self.assertEqual((self.client / "app.py").read_text(), "local edit")
        self.assert_unchanged()

    def test_local_commits_are_never_discarded(self):
        self.git("config", "user.name", "Test", cwd=self.client)
        self.git("config", "user.email", "test@example.invalid", cwd=self.client)
        (self.client / "app.py").write_text("local commit")
        self.git("commit", "-am", "Local work", cwd=self.client)
        before = self.git("rev-parse", "HEAD", cwd=self.client)
        self.assertEqual(self.updater.check()["state"], "local_commits")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), before)

    def test_failed_build_does_not_change_code_or_mod(self):
        self.updater.build = Mock(side_effect=RuntimeError("compiler failed"))
        self.assertEqual(self.updater.check()["state"], "failed")
        self.assert_unchanged()

    def test_script_install_failure_rolls_back_jar_and_script(self):
        copy = shutil.copy2

        def failing_copy(source, target, *args, **kwargs):
            if "/jeu/assistant_ia.js" in str(source):
                raise OSError("disk unavailable")
            return copy(source, target, *args, **kwargs)

        with patch.object(setup.shutil, "copy2", side_effect=failing_copy):
            self.assertEqual(self.updater.check()["state"], "failed")
        self.assert_unchanged()

    def test_git_failure_after_install_restores_original_files(self):
        git = self.updater.git

        def failing_git(*args, **kwargs):
            if args[0] == "merge":
                raise subprocess.CalledProcessError(1, "git merge")
            return git(*args, **kwargs)

        with patch.object(self.updater, "git", side_effect=failing_git):
            self.assertEqual(self.updater.check()["state"], "failed")
        self.assert_unchanged()

    def test_dirty_edit_during_compilation_prevents_install(self):
        build = self.updater.build

        def edited_build(stage):
            build(stage)
            (self.client / "app.py").write_text("edited while compiling")

        self.updater.build = edited_build
        self.assertEqual(self.updater.check()["state"], "local_changes")
        self.assert_unchanged()

    def test_missing_receipt_or_tampered_jar_is_repaired_even_at_same_commit(self):
        self.updater.check()
        self.jar.write_text("bad jar")
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.jar.read_text(), "new jar")
        self.updater.receipt.unlink()
        self.assertEqual(self.updater.check()["state"], "updated")

    def test_python_only_update_skips_rebuilding_unchanged_mod(self):
        self.updater.check()
        self.publish(python_only=True)
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.updater.builds, 1)
        self.assertEqual((self.client / "app.py").read_text(), "new app")

    def test_requirements_are_installed_only_when_changed(self):
        self.updater.check()
        self.assertEqual(self.updater.dependency_updates, 0)
        self.publish(python_only=True, dependencies=True)
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.updater.dependency_updates, 1)


class ProcessDetectionTests(unittest.TestCase):
    def test_minecraft_is_distinguished_from_gradle_and_shells(self):
        self.assertTrue(
            minecraft_command(
                "/path/java -Xmx4G cpw.mods.bootstraplauncher.BootstrapLauncher --gameDir /game"
            )
        )
        self.assertTrue(minecraft_command("java net.minecraft.client.main.Main"))
        self.assertFalse(
            minecraft_command("/path/java org.gradle.launcher.daemon.bootstrap.GradleDaemon")
        )
        self.assertFalse(minecraft_command("/bin/sh -c grep net.minecraft.client.main.Main"))

    def test_java_path_with_spaces_is_recognized_using_process_executable(self):
        self.assertTrue(
            minecraft_command(
                "/Users/Some User/java net.minecraft.client.main.Main", "/Users/Some User/java"
            )
        )
        with (
            patch("os.name", "posix"),
            patch(
                "subprocess.run",
                side_effect=[
                    Mock(
                        stdout="42 /Users/Some User/java net.minecraft.client.main.Main",
                        returncode=0,
                    ),
                    Mock(stdout="/Users/Some User/java\n", returncode=0),
                ],
            ),
        ):
            self.assertTrue(minecraft_running())

    def test_process_read_failure_is_unknown_not_closed(self):
        with patch("subprocess.run", side_effect=OSError("access denied")):
            self.assertIsNone(minecraft_running())

    def test_windows_detection_returns_only_a_boolean(self):
        for result, expected in (
            ("running", True),
            ("closed", False),
            ("unknown", None),
            ("", None),
        ):
            with patch("os.name", "nt"), patch("subprocess.run", return_value=Mock(stdout=result)):
                self.assertIs(minecraft_running(), expected)
