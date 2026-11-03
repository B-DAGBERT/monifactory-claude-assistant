"""Provider isolation plus real subprocess round trips against a fake Claude CLI."""

import json
import queue
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import monifactory_assistant.commands.choose_ai as choose_ai
import monifactory_assistant.commands.setup as codex_setup
import monifactory_assistant.config as codex_config
import monifactory_assistant.providers as ai_provider
import monifactory_assistant.providers.claude as claude


class SelectionTests(unittest.TestCase):
    def test_legacy_codex_models_and_claude_defaults_are_isolated(self):
        legacy = {"model_plan": "gpt-custom", "model_question": "gpt-fast"}
        self.assertEqual(ai_provider.model("plan", legacy), "gpt-custom")
        self.assertEqual(ai_provider.model("plan", dict(legacy, provider="claude")), "sonnet")
        self.assertEqual(ai_provider.model("quick", {"provider": "claude"}), "haiku")
        self.assertEqual(
            ai_provider.model(
                "plan", {"provider": "claude", "providers": {"claude": {"model_plan": "opus"}}}
            ),
            "opus",
        )
        with self.assertRaises(ValueError):
            ai_provider.provider({"provider": "invalid"})

    def test_claude_selection_imports_only_claude_backend(self):
        with (
            patch.object(ai_provider, "CONFIG", {"provider": "claude"}),
            patch.object(ai_provider.importlib, "import_module") as load,
        ):
            ai_provider.ask("question")
            load.assert_called_once_with("monifactory_assistant.providers.claude")
            load.return_value.ask.assert_called_once_with("question")

    def test_switch_preserves_settings_sessions_and_plans(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy = {"minecraft_dir": "world", "model_plan": "gpt-custom"}
            local = {"providers": {"claude": {"model_plan": "opus"}}, "provider": "codex"}
            (root / "codex.local.json").write_text(json.dumps(legacy))
            (root / "assistant.local.json").write_text(json.dumps(local))
            for name in ("session.json", "session_claude.json", "plan_ia.json"):
                (root / name).write_text('{"preserve":true}')
            with patch.object(choose_ai, "HERE", root), patch.object(codex_config, "HERE", root):
                choose_ai.select("claude")
                settings = codex_config.load_config()
                self.assertEqual(settings["minecraft_dir"], "world")
                self.assertEqual(ai_provider.model("plan", settings), "opus")
                choose_ai.select("codex")
                self.assertEqual(
                    ai_provider.model("plan", codex_config.load_config()), "gpt-custom"
                )
                with self.assertRaises(ValueError):
                    choose_ai.select("invalid")
                self.assertEqual(codex_config.load_config()["provider"], "codex")
            for name in ("session.json", "session_claude.json", "plan_ia.json"):
                self.assertEqual((root / name).read_text(), '{"preserve":true}')
            self.assertEqual(json.loads((root / "codex.local.json").read_text()), legacy)

    def test_setup_claude_does_not_require_codex(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(codex_setup, "HERE", Path(directory)),
            patch.object(codex_setup, "CONFIG", {"provider": "claude"}),
            patch.object(codex_setup, "minecraft_dir", return_value=Path(directory)),
            patch.object(codex_setup, "check_instance"),
            patch.object(codex_setup, "build"),
            patch.object(codex_setup, "install"),
            patch.object(ai_provider, "CONFIG", {"provider": "claude"}),
            patch.object(ai_provider, "find_claude", return_value="/fake/claude"),
            patch.object(
                ai_provider, "find_codex", side_effect=AssertionError("must not use Codex")
            ),
            patch.object(sys, "argv", ["setup", "setup"]),
        ):
            codex_setup.main()
            self.assertEqual(
                json.loads((Path(directory) / "assistant.local.json").read_text())["provider"],
                "claude",
            )


class ClaudeTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.script = self.root / "fake_cli.py"
        self.script.write_text("""import sys,json,time
from pathlib import Path
args=sys.argv[1:]
session=args[args.index('--resume')+1] if '--resume' in args else args[args.index('--session-id')+1] if '--session-id' in args else 'isolated'
Path('launches.jsonl').open('a').write(json.dumps(args)+'\\n')
def emit(value): print(json.dumps(value),flush=True)
def reply(prompt):
    Path('requests.jsonl').open('a').write(json.dumps(prompt)+'\\n')
    emit({'type':'system','subtype':'init','session_id':session})
    if prompt=='hang': time.sleep(5); return
    if prompt=='crash': sys.exit(2)
    if prompt=='error': emit({'type':'result','is_error':True,'subtype':'error','result':'Erreur simulée'}); return
    emit({'type':'stream_event','event':{'type':'message_start'}})
    emit({'type':'stream_event','event':{'delta':{'type':'text_delta','text':'Réponse : '+prompt}}})
    emit({'type':'assistant','message':{'content':[{'type':'tool_use','name':'Read','input':{'file_path':'stockage.json'}}]}})
    emit({'type':'result','is_error':False,'result':'Réponse : '+prompt,'session_id':session})
if '--input-format' in args:
    for line in sys.stdin:
        reply(json.loads(line)['message']['content'])
else:
    reply(sys.stdin.read())
""")
        self.addCleanup(patch.stopall)
        patch.object(claude, "HERE", self.root).start()
        patch.object(claude, "find_claude", return_value=str(self.script)).start()
        actual_start = claude.start
        self.starter = patch.object(
            claude, "start", side_effect=lambda args: actual_start([sys.executable, *args])
        ).start()
        self.data_dir = self.root / "game"
        self.data_dir.mkdir()

    def chat(self, timeout=2):
        chat = claude.Conversation(self.data_dir, "Read {data_dir}", "sonnet", timeout=timeout)
        self.addCleanup(chat.stop)
        return chat

    def sent(self):
        return [
            json.loads(line) for line in (self.root / "requests.jsonl").read_text().splitlines()
        ]

    def test_two_questions_keep_one_process_and_resume_after_restart(self):
        chat = self.chat()
        preview, text, tool = Mock(), Mock(), Mock()
        self.assertEqual(chat.ask("hello", text, tool, preview), "Réponse : hello")
        session = chat.session_id
        self.assertTrue(session)
        self.assertEqual(chat.ask("suite"), "Réponse : suite")
        self.assertEqual(self.starter.call_count, 1)
        preview.assert_called_once_with("Réponse : hello")
        text.assert_called_once_with("Réponse : hello")
        tool.assert_called_once_with("Read", {"file_path": "stockage.json"})
        self.assertEqual(self.sent(), ["hello", "suite"])
        chat.stop()
        restored = self.chat()
        self.assertEqual(restored.session_id, session)
        self.assertEqual(restored.ask("retour"), "Réponse : retour")
        args = self.starter.call_args.args[0]
        self.assertEqual(args[args.index("--resume") + 1], session)
        restored.restart()
        self.assertIsNone(restored.session_id)
        self.assertIsNone(json.loads(restored.path.read_text())["session_id"])
        self.assertFalse((self.root / "session.json").exists())

    def test_isolated_generation_never_overwrites_conversation(self):
        chat = self.chat()
        chat.ask("hello")
        previous = chat.path.read_bytes()
        self.assertEqual(claude.ask("plan", self.data_dir, "sonnet", "rules"), "Réponse : plan")
        self.assertIn("--no-session-persistence", self.starter.call_args.args[0])
        self.assertEqual(chat.path.read_bytes(), previous)

    def test_timeout_process_crash_and_error_do_not_replay_question(self):
        for prompt in ("hang", "crash", "error"):
            with self.subTest(prompt=prompt):
                chat = self.chat(timeout=0.5)
                before = self.starter.call_count
                with self.assertRaises(RuntimeError):
                    chat.ask(prompt)
                self.assertEqual(self.starter.call_count, before + 1)
                self.assertIsNone(chat.proc)
                self.assertEqual(self.sent().count(prompt), 1)

    def test_session_belongs_to_the_same_instance(self):
        self.chat().ask("hello")
        elsewhere = claude.Conversation(self.root / "other-game", "rules", "sonnet")
        self.addCleanup(elsewhere.stop)
        self.assertIsNone(elsewhere.session_id)

    def test_command_limits_tools_without_bypassing_permissions(self):
        args = claude.command("sonnet", self.data_dir, "rules", "medium", True)
        self.assertIn("--strict-mcp-config", args)
        self.assertNotIn("--dangerously-skip-permissions", args)
        self.assertEqual(args[args.index("--tools") + 1], "Read,Grep,Glob,WebSearch,WebFetch")
        self.assertNotIn("--bare", args)  # bare mode skips the user's subscription login

    def test_invalid_lines_cannot_extend_deadline_forever(self):
        class Noise:
            def get(self, timeout):
                return "not-json"

        with self.assertRaisesRegex(RuntimeError, "délai"):
            claude.read_reply(Noise(), timeout=0.01)
        lines = queue.Queue()
        lines.put(None)
        with self.assertRaisesRegex(RuntimeError, "arrêté"):
            claude.read_reply(lines)
