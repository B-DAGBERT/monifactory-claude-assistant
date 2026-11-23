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

    def deploy(self, stage, before, target):
        with patch.object(setup, "HERE", self.root):
            return super().deploy(stage, before, target)


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

    def test_ignored_private_plan_tracked_upstream_is_never_overwritten(self):
        private_plan = (self.client / "plan_ia.json").read_bytes()
        (self.source / "plan_ia.json").write_text('{"paused":false,"owner":"upstream"}')
        self.git("add", "-f", "plan_ia.json")
        self.git("commit", "-m", "Accidentally tracked plan")
        self.git("push", "origin", "main")
        self.assertEqual(self.git("status", "--porcelain", cwd=self.client), "")
        self.assertEqual(self.updater.check()["state"], "failed")
        self.assertEqual((self.client / "plan_ia.json").read_bytes(), private_plan)
        self.assert_unchanged()

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

    def test_branch_switched_after_install_recovers_main_pair_offline(self):
        deploy = self.updater.deploy
        receipt = self.updater.receipt.read_bytes()

        def switch_after_install(stage, before, target):
            backup = deploy(stage, before, target)
            self.assertEqual(
                (self.jar.read_text(), self.script.read_text()), ("new jar", "new script")
            )
            self.git("switch", "-c", "work", cwd=self.client)
            return backup

        with patch.object(self.updater, "deploy", side_effect=switch_after_install):
            status = self.updater.check()
        self.assertEqual(self.git("rev-parse", "work", cwd=self.client), self.old, status)
        self.assertEqual(self.git("branch", "--show-current", cwd=self.client), "work")
        self.assertEqual(status["state"], "repair_pending")
        self.assertTrue(setup.pending_path(self.client).exists())
        self.remote.rename(self.remote.with_name("offline.git"))
        self.git("switch", "main", cwd=self.client)
        restarted = Harness(self.client, self.instance, game_running=lambda: False)
        self.assertEqual(restarted.check()["state"], "offline")
        self.assert_unchanged()
        self.assertEqual(restarted.receipt.read_bytes(), receipt)
        self.assertTrue(restarted.installed(restarted.payload(self.old)))
        self.assertFalse(setup.pending_path(self.client).exists())

    def interrupt_switch(self):
        self.git("switch", "-c", "work")
        (self.source / "app.py").write_text("development")
        self.git("commit", "-am", "Working branch")
        work = self.git("rev-parse", "HEAD")
        self.git("fetch", str(self.source), "work:work", cwd=self.client)
        git = self.updater.git

        def switch_while_locked(*args, **kwargs):
            result = git(*args, **kwargs)
            if args[0] == "merge":
                with self.assertRaises(subprocess.CalledProcessError):
                    git("switch", "work")
            return result

        with patch.object(self.updater, "git", side_effect=switch_while_locked):
            status = self.updater.check()
        if status["state"] == "updated":
            # Some Git backends reject the switch before touching the index.
            self.assertEqual(git("rev-parse", "work"), work)
            self.assertEqual(git("status", "--porcelain"), "")
            return None
        self.assertEqual(status["state"], "repair_pending")
        self.assertEqual(git("branch", "--show-current"), "main")
        self.assertEqual(git("rev-parse", "HEAD"), self.old)
        self.assertEqual(git("write-tree"), git("rev-parse", "work^{tree}"))
        self.assertEqual((self.client / "app.py").read_text(), "development")
        self.assertEqual((self.jar.read_text(), self.script.read_text()), ("new jar", "new script"))
        self.assertTrue(setup.pending_path(self.client).exists())
        self.assertTrue(self.updater.checkout_journal.exists())
        self.remote.rename(self.remote.with_name("offline.git"))
        return work

    def test_refused_switch_completes_release_offline(self):
        work = self.interrupt_switch()
        if work is None:
            return
        restarted = Harness(self.client, self.instance, game_running=lambda: False)
        for _ in range(2):
            self.assertEqual(restarted.check()["state"], "offline")
            self.assertEqual(restarted.git("rev-parse", "work"), work)
            self.assertEqual(restarted.git("rev-parse", "HEAD"), self.target)
            self.assertEqual(restarted.git("status", "--porcelain"), "")
            self.assertTrue(restarted.installed(restarted.payload(self.target)))
            self.assertEqual(
                (self.jar.read_text(), self.script.read_text()), ("new jar", "new script")
            )
            self.assertFalse(restarted.checkout_journal.exists())
            self.assertFalse(setup.pending_path(self.client).exists())

    def test_refused_switch_preserves_player_files(self):
        work = self.interrupt_switch()
        if work is None:
            return
        app = self.client / "app.py"
        note = self.client / "player-note.txt"
        app.write_text("development plus uncommitted player edit")
        note.write_text("an untracked player note")
        restarted = Harness(self.client, self.instance, game_running=lambda: False)
        self.assertEqual(restarted.check()["state"], "repair_pending")
        self.assertEqual(restarted.git("rev-parse", "work"), work)
        self.assertEqual(restarted.git("show", ":app.py"), "development")
        self.assertEqual(app.read_text(), "development plus uncommitted player edit")
        self.assertEqual(note.read_text(), "an untracked player note")
        # Remove only the conflicting edit. An unrelated note must not block recovery.
        app.write_text("development")
        self.assertEqual(restarted.check()["state"], "local_changes")
        self.assertEqual(restarted.git("rev-parse", "HEAD"), self.target)
        self.assertEqual(restarted.git("status", "--porcelain"), "?? player-note.txt")
        self.assertEqual(restarted.git("rev-parse", "work"), work)
        self.assertEqual(note.read_text(), "an untracked player note")
        self.assertTrue(restarted.installed(restarted.payload(self.target)))

    def test_offline_recovery_after_checkout_before_reference_commit(self):
        class PowerLoss(BaseException):
            pass

        git = self.updater.git

        def disconnect(*args, **kwargs):
            result = git(*args, **kwargs)
            if args[0] == "merge":
                raise PowerLoss()
            return result

        with (
            patch.object(self.updater, "git", side_effect=disconnect),
            self.assertRaises(PowerLoss),
        ):
            self.updater.check()
        self.assertEqual(self.git("rev-parse", "main", cwd=self.client), self.old)
        self.assertTrue(setup.pending_path(self.client).exists())
        self.remote.rename(self.remote.with_name("offline.git"))
        self.assertEqual(self.updater.check()["state"], "offline")
        self.assertEqual(self.git("rev-parse", "main", cwd=self.client), self.target)
        self.assertEqual(self.git("status", "--porcelain", cwd=self.client), "")
        self.assertFalse((self.client / ".git/SQUASH_MSG").exists())
        self.assertTrue(self.updater.installed(self.updater.payload(self.target)))

    def test_python_only_ref_commit_recovers_offline_without_reinstalling(self):
        self.updater.check()
        before = self.target
        receipt = self.updater.receipt.read_bytes()
        self.publish(python_only=True)
        git = self.updater.git

        class PowerLoss(BaseException):
            pass

        def disconnect(*args, **kwargs):
            result = git(*args, **kwargs)
            if args[0] == "merge":
                raise PowerLoss()
            return result

        with (
            patch.object(self.updater, "git", side_effect=disconnect),
            self.assertRaises(PowerLoss),
        ):
            self.updater.check()
        self.assertEqual(self.git("rev-parse", "main", cwd=self.client), before)
        self.assertFalse(setup.pending_path(self.client).exists())
        self.remote.rename(self.remote.with_name("offline.git"))
        self.assertEqual(self.updater.check()["state"], "offline")
        self.assertEqual(self.git("rev-parse", "main", cwd=self.client), self.target)
        self.assertEqual(self.git("status", "--porcelain", cwd=self.client), "")
        self.assertTrue(self.updater.installed(self.updater.payload(self.target)))
        self.assertEqual(self.updater.receipt.read_bytes(), receipt)
        self.assertFalse(self.updater.checkout_journal.exists())

    def test_resume_before_git_merge_rolls_back_even_offline(self):
        class Interruption(BaseException):
            pass

        replace = Path.replace

        def replace_jar(path, target):
            result = replace(path, target)
            if target == self.jar:
                raise Interruption
            return result

        with patch.object(Path, "replace", replace_jar), self.assertRaises(Interruption):
            self.updater.check()
        self.assertEqual(self.jar.read_text(), "new jar")
        self.assertEqual(self.script.read_text(), "old script")
        self.assertTrue(setup.pending_path(self.client).exists())
        self.remote.rename(self.remote.with_name("unavailable.git"))
        self.updater.game_running.return_value = True
        self.assertEqual(self.updater.check()["state"], "repair_pending")
        self.assertEqual(self.jar.read_text(), "new jar")
        self.updater.game_running.return_value = False
        source = self.client / "app.py"
        old_source = source.read_bytes()
        source.write_text("changed after interrupted Git merge")
        self.assertEqual(self.updater.check()["state"], "repair_pending")
        self.assertEqual(source.read_text(), "changed after interrupted Git merge")
        self.assertTrue(setup.pending_path(self.client).exists())
        source.write_bytes(old_source)
        self.assertEqual(self.updater.check()["state"], "offline")
        self.assert_unchanged()
        self.assertFalse(setup.pending_path(self.client).exists())

    def test_resume_after_git_merge_reinstalls_exact_pair_and_receipt_offline(self):
        class Interruption(BaseException):
            pass

        with (
            patch.object(self.updater, "remember_install", side_effect=Interruption),
            self.assertRaises(Interruption),
        ):
            self.updater.check()
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), self.target)
        self.assertTrue(setup.pending_path(self.client).exists())
        # Disc persistence at the interruption boundary may still carry one old member.
        self.script.write_text("old script")
        self.remote.rename(self.remote.with_name("unavailable.git"))
        self.assertEqual(self.updater.check()["state"], "offline")
        self.assertEqual(self.jar.read_text(), "new jar")
        self.assertEqual(self.script.read_text(), "new script")
        self.assertTrue(self.updater.installed(self.updater.payload(self.target)))
        self.assertFalse(setup.pending_path(self.client).exists())

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
        receipt = self.updater.receipt.read_bytes()
        self.publish(python_only=True)
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.updater.builds, 1)
        self.assertEqual(self.updater.receipt.read_bytes(), receipt)
        self.assertEqual((self.client / "app.py").read_text(), "new app")

    def test_python_only_update_cannot_certify_a_jar_changed_during_preparation(self):
        self.updater.check()
        previous = self.target
        receipt = self.updater.receipt.read_bytes()
        self.publish(python_only=True, dependencies=True)
        with patch.object(
            self.updater, "dependencies", side_effect=lambda stage: self.jar.write_text("foreign")
        ):
            self.assertEqual(self.updater.check()["state"], "local_changes")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.client), previous)
        self.assertEqual(self.updater.receipt.read_bytes(), receipt)
        self.assertFalse(self.updater.installed(self.updater.payload(self.target)))

    def test_requirements_are_installed_only_when_changed(self):
        self.updater.check()
        self.assertEqual(self.updater.dependency_updates, 0)
        self.publish(python_only=True, dependencies=True)
        self.assertEqual(self.updater.check()["state"], "updated")
        self.assertEqual(self.updater.dependency_updates, 1)


class StagedBuildTests(unittest.TestCase):
    def test_staged_build_receives_the_local_java_home(self):
        # The staged checkout has no assistant.local.json, so its own lookup would miss java_home.
        with tempfile.TemporaryDirectory() as tmp:
            jdk = Path(tmp) / "jdk-17"
            updater = AutoUpdater(tmp, Path(tmp) / "instance")
            with (
                patch.object(setup, "java_home", return_value=jdk),
                patch.object(updater, "run_logged") as run,
            ):
                updater.build(Path(tmp) / "stage")
            self.assertEqual(run.call_args.kwargs["JAVA_HOME"], str(jdk))


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
