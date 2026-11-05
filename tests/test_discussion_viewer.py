import ast
import http.client
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from monifactory_assistant.ui.discussion import DiscussionViewer, recent_history


class ConnectionDisplayTests(unittest.TestCase):
    def test_answer_clears_writer_warning_and_applies_actions_only_once(self):
        source = ast.parse(
            Path(__file__).resolve().parents[1].joinpath("monifactory_assistant/app.py").read_text()
        )
        app = next(n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "App")
        app.body = [
            n
            for n in app.body
            if isinstance(n, ast.FunctionDef)
            and n.name in {"on_answer", "on_error", "on_connection_ready", "on_connection_problem"}
        ]
        actions = [{"type": "test"}]
        namespace = {
            "log": Mock(),
            "split_actions": lambda answer: (answer, actions),
            "readable": lambda answer: answer,
            "time": time,
        }
        exec(compile(ast.Module(body=[app], type_ignores=[]), "assistant_mc.py", "exec"), namespace)
        instance = namespace["App"]()
        instance.game = object()
        instance.notify, instance.add_history, instance.refresh, instance.run_actions = (
            Mock(),
            Mock(),
            Mock(),
            Mock(),
        )
        instance.busy = True
        instance.on_error("Utilise le panneau de suivi en lecture seule")
        self.assertIn("lecture seule", instance.connection_problem)
        self.assertFalse(instance.busy)
        instance.run_actions.assert_not_called()
        instance.on_answer("question", "réponse")
        self.assertEqual(instance.connection_problem, "")
        instance.add_history.assert_called_once_with("claude", "réponse")
        instance.run_actions.assert_called_once_with(actions)


class ViewerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / "vue_mod.json"
        self.viewer = DiscussionViewer(self.root, self.root / "runtime", port=0)
        self.addCleanup(self.viewer.close)

    def write_view(self, text="bonjour", **extra):
        self.path.write_text(
            json.dumps(
                {
                    "moteur": "Codex",
                    "ecran": {
                        "conversation": [{"de": "toi", "texte": text}],
                        "occupe": False,
                        "coffres": "must not be exposed",
                    },
                    "bulle": None,
                    **extra,
                }
            )
        )

    def request(self, route="state", method="GET", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.viewer.server.server_port, timeout=2)
        try:
            conn.request(method, self.viewer.prefix + route, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_live_mirror_without_ai_and_only_display_fields(self):
        self.write_view()
        status, headers, body = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        state = json.loads(body)
        self.assertEqual(state["messages"], [{"role": "user", "text": "bonjour"}])
        self.assertTrue(state["online"])
        self.assertNotIn(b"must not be exposed", body)
        self.write_view("nouveau message", bulle={"texte": "Erreur de connexion"})
        state = json.loads(self.request()[2])
        self.assertEqual(state["messages"][0]["text"], "nouveau message")
        self.assertEqual(state["notice"], "Erreur de connexion")

    def test_unchanged_file_is_not_reparsed_and_online_status_still_expires(self):
        self.write_view()
        real_loads = json.loads
        with patch("monifactory_assistant.ui.discussion.json.loads", wraps=real_loads) as loads:
            first = self.viewer.snapshot()
            self.assertTrue(first["online"])
            with patch(
                "monifactory_assistant.ui.discussion.time.time", return_value=time.time() + 60
            ):
                self.assertFalse(self.viewer.snapshot()["online"])
            self.assertEqual(loads.call_count, 1)
            self.write_view("new")
            self.assertEqual(self.viewer.snapshot()["messages"][0]["text"], "new")
            self.assertEqual(loads.call_count, 2)

    def test_atomic_replacement_is_read_even_with_same_timestamp_and_size(self):
        self.write_view("old")
        self.viewer.snapshot()
        stat = self.path.stat()
        replacement = self.root / "replacement.json"
        replacement.write_text(self.path.read_text().replace("old", "new"))
        os.utime(replacement, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        replacement.replace(self.path)
        self.assertEqual(self.viewer.snapshot()["messages"][0]["text"], "new")

    def test_writer_conflict_remains_visible_after_temporary_notice_expires(self):
        self.write_view()
        view = json.loads(self.path.read_text())
        view["ecran"]["erreur_connexion"] = "Ferme le fil natif Codex"
        self.path.write_text(json.dumps(view))
        self.assertEqual(self.viewer.snapshot()["connection_error"], "Ferme le fil natif Codex")
        view["ecran"]["erreur_connexion"] = ""
        self.path.write_text(json.dumps(view))
        self.assertEqual(self.viewer.snapshot()["connection_error"], "")

    def test_partial_write_keeps_display_and_stale_file_is_offline(self):
        self.write_view()
        old = time.time() - 60
        os.utime(self.path, (old, old))
        state = self.viewer.snapshot()
        self.assertFalse(state["online"])
        self.path.write_text('{"ecran":')
        self.assertEqual(self.viewer.snapshot()["messages"], state["messages"])
        self.path.unlink()
        self.assertEqual(self.viewer.snapshot()["messages"], state["messages"])

    def test_restore_recent_messages_but_not_unfinished_answer_or_other_provider(self):
        self.write_view("question")
        self.assertEqual(recent_history(self.root, "Codex"), [{"de": "toi", "texte": "question"}])
        view = json.loads(self.path.read_text())
        view["ecran"]["conversation"].append({"de": "claude", "texte": "partiel"})
        view["bulle"] = {"en_cours": True, "texte": "partiel"}
        self.path.write_text(json.dumps(view))
        self.assertEqual(len(recent_history(self.root, "Codex")), 1)
        self.assertEqual(recent_history(self.root, "Claude"), [])
        self.path.write_text("{")
        self.assertEqual(recent_history(self.root, "Codex"), [])

    def test_no_writes_no_arbitrary_files_and_no_cross_origin_reads(self):
        self.write_view()
        before = self.path.read_bytes()
        for method in ("POST", "PUT", "PATCH", "DELETE"):
            self.assertEqual(self.request(method=method)[0], 405)
        for route in ("../../session.json", "../vue_mod.json", "state?file=session.json", "send"):
            self.assertEqual(self.request(route)[0], 404)
        self.assertEqual(self.request(headers={"Host": "evil.example"})[0], 403)
        self.assertEqual(self.request(headers={"Origin": "https://evil.example"})[0], 403)
        conn = http.client.HTTPConnection("127.0.0.1", self.viewer.server.server_port)
        conn.request("GET", "/state")
        response = conn.getresponse()
        self.assertEqual(response.status, 404)
        response.read()
        conn.close()
        self.assertEqual(self.path.read_bytes(), before)

    def test_html_and_script_never_interpolate_messages(self):
        hostile = '<script>alert("x")</script>'
        self.write_view(hostile)
        status, headers, html = self.request("")
        self.assertEqual(status, 200)
        self.assertNotIn(hostile.encode(), html)
        self.assertIn("script-src 'self'", headers["Content-Security-Policy"])
        script = self.request("app.js")[2]
        self.assertIn(b"body.textContent = message.text", script)
        self.assertNotIn(b"innerHTML", script)
        self.assertEqual(json.loads(self.request()[2])["messages"][0]["text"], hostile)

    def test_busy_port_falls_back_without_stopping_assistant(self):
        other = DiscussionViewer(
            self.root, self.root / "other-runtime", self.viewer.server.server_port
        )
        self.addCleanup(other.close)
        self.assertNotEqual(other.server.server_port, self.viewer.server.server_port)
        self.assertEqual(self.viewer.server.server_address[0], "127.0.0.1")

    def test_restart_keeps_same_url_when_port_is_free(self):
        old_url, port = self.viewer.url, self.viewer.server.server_port
        self.viewer.close()
        other = DiscussionViewer(self.root, self.root / "runtime", port)
        self.addCleanup(other.close)
        self.assertEqual(other.url, old_url)
        self.assertEqual(json.loads((self.root / "runtime/suivi.json").read_text())["url"], old_url)
