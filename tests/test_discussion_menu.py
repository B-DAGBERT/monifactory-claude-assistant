"""Exercise the Minecraft discussion protocol without Tk, Minecraft, or AI calls."""

import ast
import json
import os
import shutil
import subprocess
import tempfile
import time
import traceback
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.ui.discussion import restore_discussion
from tests.app_source import application_tree

ROOT = Path(__file__).resolve().parents[1]


class DiscussionMenuTests(unittest.TestCase):
    def setUp(self):
        source = application_tree()
        app = next(n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "App")
        app.body = [
            n
            for n in app.body
            if isinstance(n, ast.FunctionDef)
            and n.name in {"check_mod_requests", "handle_mod_request"}
        ]
        namespace = dict(json=json, time=time, traceback=traceback, log=Mock())
        exec(compile(ast.Module(body=[app], type_ignores=[]), "app.py", "exec"), namespace)
        self.app = namespace["App"]()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "demande_mod.json"
        self.app.bridge = SimpleNamespace(dir=self.path.parent)
        self.app.root = SimpleNamespace(after=Mock())
        self.app.mod_request_n = self.app.mod_request_time = None
        self.app.question_ack = {}
        self.app.busy = False
        self.app.start_question = Mock()
        self.app.notify = Mock()
        self.n = int(time.time() * 1000)
        self.request = {"type": "question", "n": self.n, "texte": "Comment faire une pioche ?"}

    def write(self, request):
        self.path.write_text(json.dumps(request))
        # A distinct file timestamp for every simulated atomic replacement.
        self.mtime = getattr(self, "mtime", time.time()) + 1
        os.utime(self.path, (self.mtime, self.mtime))

    def test_accepts_once_and_acknowledges_the_exact_id(self):
        self.write(self.request)
        self.app.check_mod_requests()
        self.app.start_question.assert_called_once_with(
            self.request["texte"], None, action_scope=None
        )
        self.assertEqual(self.app.question_ack, {"n": self.n, "etat": "acceptee"})
        self.app.check_mod_requests()
        self.write(self.request)
        self.app.check_mod_requests()
        self.app.start_question.assert_called_once()

    def test_admission_never_publishes_a_provisional_refusal_of_the_question(self):
        # start_question refreshes vue_mod.json. The menu ends its wait on a refusal of its
        # number and keeps the draft, and a dict mutated after publication looks unchanged
        # to the next refresh, which compares values with the last view.
        published = []

        def start(*_args, **_options):
            published.append((self.app.question_ack, json.dumps(self.app.question_ack)))
            return True

        self.app.start_question = Mock(side_effect=start)
        self.write(self.request)
        self.app.check_mod_requests()
        self.assertEqual(self.app.question_ack, {"n": self.n, "etat": "acceptee"})
        self.assertEqual(len(published), 1)
        for ack, text in published:
            self.assertEqual(json.dumps(ack), text)
            self.assertNotEqual(json.loads(text).get("n"), self.n)

    def test_same_text_can_be_asked_again_with_a_new_id(self):
        self.write(self.request)
        self.app.check_mod_requests()
        self.write({**self.request, "n": self.n + 1})
        self.app.check_mod_requests()
        self.assertEqual(self.app.start_question.call_count, 2)
        self.assertEqual(self.app.question_ack["n"], self.n + 1)

    def test_busy_rejects_without_queuing_an_unwanted_second_question(self):
        self.app.busy = True
        self.write(self.request)
        self.app.check_mod_requests()
        self.assertEqual(self.app.question_ack["etat"], "refusee")
        self.app.busy = False
        self.app.check_mod_requests()
        self.app.start_question.assert_not_called()

    def test_restart_expires_an_old_question_without_sending_it(self):
        self.write({**self.request, "n": self.n - 60000})
        self.app.check_mod_requests()
        self.assertEqual(self.app.question_ack, {"n": self.n - 60000, "etat": "expiree"})
        self.app.start_question.assert_not_called()

    def test_restart_accepts_a_recent_question_waiting_for_the_assistant(self):
        self.write({**self.request, "n": self.n - 5000})
        self.app.check_mod_requests()
        self.assertEqual(self.app.question_ack["etat"], "acceptee")
        self.app.start_question.assert_called_once()

    def test_partial_json_is_retried_even_if_file_timestamp_does_not_change(self):
        self.path.write_text('{"type":')
        modified = self.path.stat().st_mtime
        self.app.check_mod_requests()
        self.assertIsNone(self.app.mod_request_time)
        self.path.write_text(json.dumps(self.request))
        os.utime(self.path, (modified, modified))
        self.app.check_mod_requests()
        self.app.start_question.assert_called_once()

    def test_another_action_updates_the_processed_id_without_acknowledging_question(self):
        self.app.set_setting = Mock()
        self.write({"type": "reglage", "n": self.n + 1, "cle": "voix", "valeur": False})
        self.app.check_mod_requests()
        self.assertEqual(self.app.mod_request_n, self.n + 1)
        self.assertEqual(self.app.question_ack, {})
        self.app.start_question.assert_not_called()

    def test_production_failure_keeps_native_requests_running_and_rearms_once(self):
        self.app.production = SimpleNamespace(
            poll=Mock(side_effect=PermissionError("transient production failure"))
        )
        self.write(self.request)
        self.app.check_mod_requests()
        self.app.start_question.assert_called_once_with(
            self.request["texte"], None, action_scope=None
        )
        self.app.root.after.assert_called_once_with(300, self.app.check_mod_requests)

    def test_invalid_scope_rearms_once_without_handling_pending_requests(self):
        self.app.bridge.isolated = True
        self.app.bridge.scope = None
        self.app.bridge.scope_valid = Mock(return_value=False)
        self.write(self.request)
        self.app.check_mod_requests()
        self.app.start_question.assert_not_called()
        self.app.root.after.assert_called_once_with(300, self.app.check_mod_requests)

    def test_java_drafts_duplicate_protection_and_scroll(self):
        java_home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (java_home / "bin/javac").exists():
            java_home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = (
            str(java_home / "bin/javac")
            if (java_home / "bin/javac").exists()
            else shutil.which("javac")
        )
        java = (
            str(java_home / "bin/java")
            if (java_home / "bin/java").exists()
            else shutil.which("java")
        )
        if not javac or not java:
            self.skipTest("JDK 17 required for discussion model tests")
        with tempfile.TemporaryDirectory() as directory:
            sources = [
                ROOT / "mod/src/main/java/fr/assistantia/EtatDiscussion.java",
                ROOT / "tests/java/fr/assistantia/EtatDiscussionTest.java",
            ]
            subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.EtatDiscussionTest"],
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertIn("défilement validés", result.stdout)


class QuestionInputTests(unittest.TestCase):
    def setUp(self):
        class QuestionApp(ConversationController, InterfaceController):
            pass

        self.app = app = QuestionApp()
        app.bridge = SimpleNamespace(
            isolated=True,
            scope="session-a",
            scope_valid=Mock(return_value=True),
            accepts=Mock(return_value=True),
        )
        app.busy, app.popup = False, None
        app.conversation = SimpleNamespace(generation=object())
        app.speaker = Mock()
        app.recorder = SimpleNamespace(stream=None)
        app.safe_screenshot = Mock(return_value="capture vocale")
        app.notify = Mock()
        app.set_status = Mock()
        app.work_question = Mock()
        self.samples = [0.5] * 16_000

        def start_mic():
            app.recorder.stream = object()

        def stop_mic():
            app.recorder.stream = None
            return self.samples

        app.recorder.start = Mock(side_effect=start_mic)
        app.recorder.stop = Mock(side_effect=stop_mic)
        thread = patch("monifactory_assistant.application.conversation.threading.Thread")
        self.worker = thread.start()
        self.addCleanup(thread.stop)

    def test_keeps_50_questions_even_with_more_answers_and_restores_them(self):
        app = self.app
        app.history = []
        for index in range(60):
            app.on_history("toi", f"Question {index}")
            app.on_history("claude", "Réponse")
            app.on_history("claude", "Notification")
        app.on_history("toi", "  ")
        questions = [f"Question {i}" for i in range(10, 60)]
        self.assertEqual(app.question_history, questions)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vue_mod.json"
            path.write_text(
                json.dumps(
                    {
                        "moteur": "Codex",
                        "ecran": {
                            "conversation": app.history[-30:],
                            "questions_recentes": ["Plus ancienne", *questions, None, " ", 42],
                        },
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(restore_discussion(directory, "Codex"), (app.history[-30:], questions))

    def test_mod_cannot_start_text_during_recording_and_voice_starts_once(self):
        app = self.app
        app.on_talk_start()
        app.handle_mod_request({"type": "question", "n": 1, "texte": "Une seconde question"})
        self.assertEqual(app.question_ack, {"n": 1, "etat": "refusee"})
        self.assertFalse(app.busy)
        self.worker.assert_not_called()
        app.on_talk_start()  # A duplicate start cannot cancel the live recording.
        app.on_talk_end()
        app.recorder.stop.assert_called_once()
        self.worker.assert_called_once_with(
            target=app.work_question, args=(None, self.samples, "capture vocale"), daemon=True
        )
        self.assertTrue(app.busy)

    def test_a_popup_submission_cannot_start_a_second_question(self):
        app = self.app
        app.entry = SimpleNamespace(get=lambda: "Brouillon local")
        app.shot = "capture du brouillon"
        app.close_popup = Mock()
        app.handle_mod_request({"type": "question", "n": 1, "texte": "Question du mod"})
        app.submit()
        self.worker.assert_called_once_with(
            target=app.work_question, args=("Question du mod", None, None), daemon=True
        )

    def test_switching_connections_during_recording_discards_the_old_audio(self):
        app = self.app
        app.on_talk_start()
        app.bridge.scope = "session-b"
        app.on_talk_end()
        app.recorder.stop.assert_called_once()
        app.notify.assert_called_once()
        self.assertIn("Le monde a changé", app.notify.call_args.args[0])
        self.worker.assert_not_called()
        self.assertFalse(app.busy)
