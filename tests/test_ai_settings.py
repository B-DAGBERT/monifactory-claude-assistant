"""In-game preferences: atomic private config, request isolation and next-turn routing."""

import ast
import copy
import json
import os
import queue
import tempfile
import time
import traceback
import unittest
from collections import deque
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant import providers
from monifactory_assistant.providers import claude, codex
from monifactory_assistant.providers.settings import AISettings
from tests.app_source import application_tree

ROOT = Path(__file__).resolve().parents[1]


class SettingsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bridge = SimpleNamespace(dir=self.root / "bridge")
        self.bridge.dir.mkdir()
        self.path = self.root / "assistant.local.json"
        self.legacy = {
            "model_plan": "legacy-plan",
            "model_question": "legacy-question",
            "minecraft_dir": "/keep/game",
            "providers": {"claude": {"model_plan": "opus", "private": "legacy-secret"}},
        }
        self.local = {"provider": "codex", "private": "never-expose", "auto_update": False}
        (self.root / "codex.local.json").write_text(json.dumps(self.legacy))
        self.path.write_text(json.dumps(self.local))
        self.config = {**self.legacy, **self.local}
        self.now = 1000.0
        self.settings = AISettings(self.root, self.config, clock=lambda: self.now)
        self.app = SimpleNamespace(
            busy=False, info_pending=set(), recorder=SimpleNamespace(stream=None)
        )
        self.counter = 0
        self.values = {
            "model_question": "gpt-custom",
            "effort_question": "low",
            "model_plan": "gpt-planning",
            "effort_plan": "high",
        }

    def request(self, **updates):
        self.counter += 1
        result = {
            "n": int(self.now * 1000) - 1000 + self.counter,
            "type": "configuration_ia",
            "moteur": self.settings.provider,
            "revision": self.settings.revision,
            "valeurs": self.values,
        }
        result.update(updates)
        return result

    def submit(self, request=None):
        request = self.request() if request is None else request
        path = self.bridge.dir / "configuration_ia.json"
        staged = path.with_suffix(".tmp")
        staged.write_text(json.dumps(request))
        staged.replace(path)
        self.changed = self.settings.poll(self.bridge, self.app)
        return self.settings.reply

    def test_view_contains_effective_profiles_and_defaults_but_no_private_keys(self):
        view = self.settings.view(self.app)
        self.assertEqual(view["moteur"], "codex")
        self.assertEqual(view["valeurs"]["model_question"], "legacy-question")
        self.assertEqual(view["valeurs"]["effort_plan"], "medium")
        self.assertEqual(view["defauts"]["model_plan"], providers.DEFAULTS["codex"]["plan"])
        self.assertEqual(view["efforts"], list(providers.EFFORTS))
        self.assertTrue(view["modifiable"])
        for secret in ("never-expose", "legacy-secret", "/keep/game", "auto_update"):
            self.assertNotIn(secret, json.dumps(view))

    def test_apply_preserves_private_other_provider_legacy_sessions_and_objectives(self):
        legacy_bytes = (self.root / "codex.local.json").read_bytes()
        for name in ("session.json", "session_claude.json", "plan_ia.json", "stockage.json"):
            (self.root / name).write_text('{"keep": true}')
        previous = self.settings.revision
        response = self.submit()
        self.assertEqual(response["etat"], "appliquee")
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved["private"], "never-expose")
        self.assertFalse(saved["auto_update"])
        self.assertEqual(saved["providers"]["claude"], self.legacy["providers"]["claude"])
        self.assertEqual(saved["providers"]["codex"], self.values)
        self.assertEqual(self.settings.values(), self.values)
        self.assertNotEqual(self.settings.revision, previous)
        self.assertTrue(self.settings.view(self.app)["modifiable"])
        self.assertEqual((self.root / "codex.local.json").read_bytes(), legacy_bytes)
        for name in ("session.json", "session_claude.json", "plan_ia.json", "stockage.json"):
            self.assertEqual((self.root / name).read_text(), '{"keep": true}')
        self.assertEqual(self.config["model_plan"], "legacy-plan")

    def test_disk_commit_happens_before_memory_publication(self):
        old = copy.deepcopy(self.config)
        replace = os.replace

        def commit(source, target):
            self.assertEqual(self.config, old)
            self.assertEqual(json.loads(self.path.read_text()), self.local)
            replace(source, target)
            self.assertEqual(json.loads(self.path.read_text())["providers"]["codex"], self.values)

        request = self.request()
        self.submit(request | {"type": "invalid"})
        # A new ID is required: a refused request is never replayed automatically.
        request = self.request()
        path = self.bridge.dir / "configuration_ia.json"
        path.write_text(json.dumps(request))
        with patch("monifactory_assistant.providers.settings.os.replace", side_effect=commit):
            self.assertTrue(self.settings.poll(self.bridge, self.app))
        self.assertEqual(self.settings.values(), self.values)

    def test_successful_commit_cannot_be_reported_as_failed_by_cleanup(self):
        with patch(
            "monifactory_assistant.providers.settings.Path.unlink",
            side_effect=OSError("cleanup unavailable"),
        ) as unlink:
            self.assertEqual(self.submit()["etat"], "appliquee")
            unlink.assert_not_called()
        self.assertEqual(self.settings.values(), self.values)
        self.assertEqual(json.loads(self.path.read_text())["providers"]["codex"], self.values)

    def test_failed_disk_write_keeps_file_memory_revision_and_cleans_temporary_file(self):
        old, raw, revision = (
            copy.deepcopy(self.config),
            self.path.read_bytes(),
            self.settings.revision,
        )
        path = self.bridge.dir / "configuration_ia.json"
        path.write_text(json.dumps(self.request()))
        with patch(
            "monifactory_assistant.providers.settings.os.replace", side_effect=OSError("read only")
        ):
            self.settings.poll(self.bridge, self.app)
        self.assertEqual(self.settings.reply["etat"], "refusee")
        self.assertEqual(self.config, old)
        self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(self.settings.revision, revision)
        self.assertEqual(list(self.root.glob(".assistant-local-*.tmp")), [])

    def test_busy_cards_and_microphone_refuse_without_retrying_the_same_number(self):
        for blocked in ("busy", "card", "microphone"):
            with self.subTest(blocked=blocked):
                self.app.busy = blocked == "busy"
                self.app.info_pending = {"item"} if blocked == "card" else set()
                self.app.recorder.stream = object() if blocked == "microphone" else None
                raw = self.path.read_bytes()
                request = self.request()
                self.assertFalse(self.settings.view(self.app)["modifiable"])
                self.assertEqual(self.submit(request)["etat"], "refusee")
                self.app.busy, self.app.info_pending, self.app.recorder.stream = False, set(), None
                self.assertEqual(self.submit(request)["etat"], "refusee")
                self.assertFalse(self.changed)
                self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(self.submit()["etat"], "appliquee")

    def test_duplicate_success_and_older_replayed_request_never_write_twice(self):
        request = self.request()
        with patch.object(self.settings, "_write_local", wraps=self.settings._write_local) as write:
            self.submit(request)
            self.submit(request)
            self.assertFalse(self.changed)
            newer = self.request()
            self.submit(newer)
            self.submit(request)
            self.assertFalse(self.changed)
            self.assertEqual(write.call_count, 2)
            self.assertEqual(self.settings.reply["n"], newer["n"])

    def test_restart_after_commit_acknowledges_saved_values_without_writing_again(self):
        request = self.request()
        self.assertEqual(self.submit(request)["etat"], "appliquee")
        raw = self.path.read_bytes()
        self.settings = AISettings(self.root, copy.deepcopy(self.config), clock=lambda: self.now)
        self.app.busy = True  # The new process may already be handling another question.
        with patch.object(
            self.settings, "_write_local", side_effect=AssertionError("duplicate save")
        ):
            self.assertEqual(self.submit(request)["etat"], "appliquee")
        self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(self.settings.reply["n"], request["n"])

    def test_expired_future_wrong_provider_type_and_revision_are_refused(self):
        raw = self.path.read_bytes()
        for change in (
            {"n": int(self.now * 1000) - 45_001},
            {"n": int(self.now * 1000) + 1},
            {"moteur": "claude"},
            {"type": "question"},
            {"revision": "old-screen"},
        ):
            with self.subTest(change=change):
                request = self.request(**change)
                reply = self.submit(request)
                self.assertEqual(reply["n"], request["n"])
                self.assertEqual(reply["etat"], "refusee")
                self.assertEqual(self.path.read_bytes(), raw)

    def test_invalid_names_efforts_and_extra_keys_are_rejected(self):
        raw = self.path.read_bytes()
        invalid = (
            "",
            "has space",
            "--model",
            "-x",
            "a\n",
            "$(echo)",
            "a;rm",
            "a" * 129,
            "sonnet[]",
            None,
            42,
        )
        for model in invalid:
            with self.subTest(model=model):
                reply = self.submit(self.request(valeurs=self.values | {"model_question": model}))
                self.assertEqual(reply["etat"], "refusee")
        for values in (
            self.values | {"effort_plan": "ultra"},
            self.values | {"provider": "claude"},
            {},
        ):
            self.assertEqual(self.submit(self.request(valeurs=values))["etat"], "refusee")
        self.assertEqual(self.path.read_bytes(), raw)

    def test_safe_custom_alias_is_saved_without_an_account_validation_call(self):
        for model in ("gpt-6.1-sol", "openai/model:revision", "sonnet[1m]", "a" * 128):
            with self.subTest(model=model):
                values = self.values | {"model_question": model}
                self.assertEqual(self.submit(self.request(valeurs=values))["etat"], "appliquee")
                self.assertEqual(self.settings.values()["model_question"], model)

    def test_external_change_and_late_conflict_are_not_overwritten(self):
        updated = self.local | {"private": "external-new-value"}
        self.path.write_text(json.dumps(updated))
        self.assertFalse(self.settings.view(self.app)["modifiable"])
        self.assertEqual(self.submit()["etat"], "refusee")
        self.assertEqual(json.loads(self.path.read_text()), updated)
        self.path.write_text(json.dumps(self.local))
        write = self.settings._write_local

        def external_edit(value):
            self.path.write_text(json.dumps(updated))
            return write(value)

        with patch.object(self.settings, "_write_local", side_effect=external_edit):
            self.assertEqual(self.submit()["etat"], "refusee")
        self.assertEqual(json.loads(self.path.read_text()), updated)
        self.assertEqual(self.settings.values()["model_question"], "legacy-question")

    def test_legacy_file_change_also_blocks_application(self):
        (self.root / "codex.local.json").write_text(
            json.dumps(self.legacy | {"private": "changed"})
        )
        self.assertEqual(self.submit()["etat"], "refusee")
        self.assertEqual(json.loads(self.path.read_text()), self.local)

    def test_partial_request_is_retried_but_invalid_ids_do_not_produce_an_ack(self):
        path = self.bridge.dir / "configuration_ia.json"
        path.write_text('{"n":')
        self.assertFalse(self.settings.poll(self.bridge, self.app))
        for value in ([], None, True, "1000"):
            self.submit(self.request(n=value))
            self.assertEqual(self.settings.reply, {})
        self.assertEqual(self.submit()["etat"], "appliquee")

    def test_startup_disk_disagreement_is_disabled(self):
        stale = self.config | {"minecraft_dir": "/previous/game"}
        settings = AISettings(self.root, stale)
        self.assertFalse(settings.view(self.app)["modifiable"])
        self.path.write_text("{")
        settings = AISettings(self.root, self.config)
        self.assertFalse(settings.view(self.app)["modifiable"])

    def test_claude_uses_its_defaults_and_accepts_xhigh_without_switching_provider(self):
        self.local["provider"] = "claude"
        self.path.write_text(json.dumps(self.local))
        self.config = {**self.legacy, **self.local}
        self.settings = AISettings(self.root, self.config, clock=lambda: self.now)
        view = self.settings.view(self.app)
        self.assertEqual(view["valeurs"]["model_question"], "sonnet")
        self.assertEqual(view["valeurs"]["model_plan"], "opus")
        values = self.values | {
            "model_question": "sonnet[1m]",
            "model_plan": "opus",
            "effort_plan": "xhigh",
        }
        self.assertEqual(self.submit(self.request(valeurs=values))["etat"], "appliquee")
        self.assertEqual(self.config["provider"], "claude")
        self.assertEqual(self.settings.values(), values)

    def test_missing_local_file_created_privately_and_preserves_existing_provider_options(self):
        self.path.unlink()
        self.config = copy.deepcopy(self.legacy)
        self.config["providers"]["codex"] = {"model_quick": "keep-quick", "private": "keep"}
        (self.root / "codex.local.json").write_text(json.dumps(self.config))
        self.settings = AISettings(self.root, self.config, clock=lambda: self.now)
        self.assertEqual(self.submit()["etat"], "appliquee")
        self.assertEqual(self.config["providers"]["codex"]["private"], "keep")
        self.assertEqual(self.config["providers"]["codex"]["model_quick"], "keep-quick")
        if os.name != "nt":
            self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)


def app_logic():
    source = application_tree()
    app = next(n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "App")
    app.body = [
        n
        for n in app.body
        if isinstance(n, ast.FunctionDef)
        and n.name in {"work_info", "work_plan", "check_mod_requests"}
    ]
    conversation = next(
        n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "Conversation"
    )
    namespace = dict(
        ai_provider=providers,
        json=json,
        time=time,
        traceback=traceback,
        log=Mock(),
        ask_claude=Mock(return_value="answer"),
        parse_info=lambda text: text,
        parse_plan=lambda text: text,
        describe_quests=lambda _: "quests",
        INFO_REQUEST="Info {nom} {item}",
        PLAN_REQUEST="Plan {context}",
        NEW_PLAN_EXTRA="",
        SYSTEM_PROMPT="rules",
        TIMEOUT=420,
    )
    exec(
        compile(ast.Module(body=[app, conversation], type_ignores=[]), "app.py", "exec"), namespace
    )
    return namespace


class DynamicRoutingTests(unittest.TestCase):
    def test_each_info_and_plan_reads_current_config_and_discussion_reconfigures(self):
        config = {
            "provider": "codex",
            "providers": {
                "codex": {
                    "model_question": "old",
                    "effort_question": "low",
                    "model_plan": "old-plan",
                    "effort_plan": "medium",
                }
            },
        }
        logic = app_logic()
        app = logic["App"]()
        app.recipe_index = SimpleNamespace(about=lambda _: "known")
        app.bridge = SimpleNamespace(dir=Path("/fake/game"), read=lambda _: {})
        app.context = lambda *args, **kwargs: "context"
        app.events = queue.Queue()
        with (
            patch.object(providers, "CONFIG", config),
            patch.object(providers, "conversation") as factory,
        ):
            backend = factory.return_value
            backend.ask.return_value = "answer"
            conversation = logic["Conversation"](app.bridge.dir)
            app.work_info("item", "Name")
            self.assertEqual(logic["ask_claude"].call_args.args[2], "old")
            config["providers"]["codex"].update(
                model_question="new",
                effort_question="high",
                model_plan="new-plan",
                effort_plan="max",
            )
            app.work_info("item", "Name")
            self.assertEqual(logic["ask_claude"].call_args.args[2], "new")
            self.assertEqual(logic["ask_claude"].call_args.kwargs["effort"], "high")
            app.work_plan(None)
            self.assertEqual(logic["ask_claude"].call_args.args[2], "new-plan")
            self.assertEqual(logic["ask_claude"].call_args.kwargs["effort"], "max")
            self.assertEqual(conversation.ask("question"), "answer")
            backend.configure.assert_called_once_with("new", "high")
            backend.restart.assert_not_called()
            factory.assert_called_once()

    def test_settings_poll_runs_even_without_a_normal_mod_request(self):
        logic = app_logic()
        app = logic["App"]()
        with tempfile.TemporaryDirectory() as directory:
            app.bridge = SimpleNamespace(dir=Path(directory))
            app.root = SimpleNamespace(after=Mock())
            app.ai_settings = SimpleNamespace(poll=Mock(return_value=True))
            app.refresh = Mock()
            app.check_mod_requests()
            app.ai_settings.poll.assert_called_once_with(app.bridge, app)
            app.refresh.assert_called_once_with(force=True)
            app.root.after.assert_called_once_with(300, app.check_mod_requests)

    def test_codex_next_turn_keeps_thread_and_passes_new_model_effort(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(codex, "HERE", Path(directory)),
            patch.object(codex, "CLIENT") as client,
        ):
            client.run.return_value = ("answer", "same-thread")
            conversation = codex.Conversation(Path(directory), "rules", "old")
            conversation._save_thread("same-thread")
            saved = conversation.path.read_bytes()
            conversation.configure("new", "high")
            client.close.assert_not_called()
            client.load_thread.assert_not_called()
            self.assertEqual(conversation.ask("question"), "answer")
            self.assertEqual(client.run.call_args.args[1], "same-thread")
            self.assertEqual(client.run.call_args.args[3], "new")
            self.assertEqual(client.run.call_args.kwargs["reasoning_effort"], "high")
            self.assertEqual(conversation.path.read_bytes(), saved)

    def test_claude_restarts_only_at_next_question_and_resumes_same_session(self):
        process = Mock()
        process.poll.return_value = None

        def reply(*args):
            args[5]("same-session")
            return "answer"

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(claude, "HERE", Path(directory)),
            patch.object(claude, "find_claude", return_value="/fake/claude"),
            patch.object(claude, "start", return_value=(process, queue.Queue(), deque())) as start,
            patch.object(claude, "close") as close,
            patch.object(claude, "read_reply", side_effect=reply),
        ):
            conversation = claude.Conversation(Path(directory), "rules", "sonnet")
            self.assertEqual(conversation.ask("one"), "answer")
            saved = conversation.path.read_bytes()
            closed_before = close.call_count
            conversation.configure("opus", "high")
            self.assertEqual(start.call_count, 1)
            self.assertEqual(close.call_count, closed_before)
            self.assertEqual(conversation.ask("two"), "answer")
            self.assertEqual(start.call_count, 2)
            args = start.call_args.args[0]
            self.assertEqual(args[args.index("--model") + 1], "opus")
            self.assertEqual(args[args.index("--effort") + 1], "high")
            self.assertEqual(args[args.index("--resume") + 1], "same-session")
            self.assertEqual(conversation.path.read_bytes(), saved)
            conversation.configure("opus", "high")
            conversation.ask("three")
            self.assertEqual(start.call_count, 2)
            conversation.stop()
