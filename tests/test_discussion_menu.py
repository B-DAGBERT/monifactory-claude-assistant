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
from unittest.mock import Mock

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
        self.app.projects = SimpleNamespace(active="current")
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
        self.app.start_question.assert_called_once_with(self.request["texte"], None)
        self.assertEqual(self.app.question_ack, {"n": self.n, "etat": "acceptee"})
        self.app.check_mod_requests()
        self.write(self.request)
        self.app.check_mod_requests()
        self.app.start_question.assert_called_once()

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
