import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import codex_integration
import codex_setup
import mac_keys


class IntegrationTests(unittest.TestCase):
    def test_session_saved_before_turn_and_reused_after_restart(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(codex_integration, "HERE", Path(directory)),
        ):
            client = Mock()

            def run(*args, **kwargs):
                kwargs["on_thread"]("kept-thread")
                self.assertEqual(
                    json.loads((Path(directory) / "session.json").read_text())[
                        "thread_id"
                    ],
                    "kept-thread",
                )
                return "réponse", "kept-thread"

            client.run.side_effect = run
            with patch.object(codex_integration, "CLIENT", client):
                chat = codex_integration.Conversation(
                    Path(directory), "data {data_dir}", "model"
                )
                self.assertEqual(chat.ask("question"), "réponse")
                restored = codex_integration.Conversation(
                    Path(directory), "data {data_dir}", "model"
                )
                self.assertEqual(restored.session_id, "kept-thread")
                restored.restart()
                self.assertIsNone(restored.session_id)
                self.assertIsNone(
                    json.loads((Path(directory) / "session.json").read_text())[
                        "thread_id"
                    ]
                )

    def test_failed_turn_is_not_replayed(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(codex_integration, "HERE", Path(directory)),
        ):
            with patch.object(
                codex_integration.CLIENT, "run", side_effect=RuntimeError("connection")
            ) as run:
                chat = codex_integration.Conversation(Path(directory), "rules", "model")
                with self.assertRaises(RuntimeError):
                    chat.ask("question")
                self.assertEqual(run.call_count, 1)

    def test_mac_keys_hold_release_modifiers_and_no_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.json"
            calls = []
            with (
                patch.object(mac_keys, "_pressed", set()),
                patch.object(mac_keys, "_hotkeys", []),
                patch.object(mac_keys, "_down", {}),
                patch.object(mac_keys, "_up", {}),
            ):
                mac_keys.on_press_key(
                    "f8",
                    lambda _: (
                        calls.append("start")
                        if not mac_keys.is_pressed("shift")
                        else None
                    ),
                )
                mac_keys.on_release_key("f8", lambda _: calls.append("end"))
                mac_keys.add_hotkey("shift+f8", lambda: calls.append("type"))
                reader = mac_keys.Reader(path)
                now = time.time() * 1000 + 1
                events = [
                    {"n": n, "t": now, "keys": keys}
                    for n, keys in enumerate(
                        [["f8"], [], ["shift"], ["shift", "f8"], []], 1
                    )
                ]
                path.write_text(
                    json.dumps({"session": "one", "t": now, "events": events})
                )
                reader.poll()
                reader.poll()
                self.assertEqual(calls, ["start", "end", "type", "end"])
                events.append({"n": 6, "t": now, "keys": ["f8"]})
                path.write_text(
                    json.dumps({"session": "one", "t": now, "events": events})
                )
                reader.poll()
                path.write_text("{}")
                reader.poll()
                self.assertEqual(calls[-2:], ["start", "end"])

    def test_mac_ignores_old_events_and_repeated_keydown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.json"
            calls = []
            with (
                patch.object(mac_keys, "_pressed", set()),
                patch.object(mac_keys, "_hotkeys", []),
                patch.object(mac_keys, "_down", {}),
                patch.object(mac_keys, "_up", {}),
            ):
                mac_keys.add_hotkey("f7", lambda: calls.append("plan"))
                reader = mac_keys.Reader(path)
                path.write_text(
                    json.dumps(
                        {
                            "session": "a",
                            "t": time.time() * 1000,
                            "events": [
                                {"n": 1, "t": reader.started - 10000, "keys": ["f7"]}
                            ],
                        }
                    )
                )
                reader.poll()
                self.assertEqual(calls, [])
                mac_keys.transition(["f7"])
                mac_keys.transition(["f7"])
                self.assertEqual(calls, ["plan"])

    def test_install_backs_up_old_mod_and_script_and_preserves_world_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            instance = root / "instance"
            (instance / "mods").mkdir(parents=True)
            (instance / "kubejs/client_scripts").mkdir(parents=True)
            old = instance / "mods/assistantia-old.jar"
            old.write_bytes(b"old mod")
            unrelated = instance / "mods/other.jar"
            unrelated.write_bytes(b"keep")
            script = instance / "kubejs/client_scripts/assistant_ia.js"
            script.write_text("old script")
            (root / "jeu").mkdir()
            (root / "jeu/assistant_ia.js").write_text("new script")
            jar = root / "assistantia-new.jar"
            jar.write_bytes(b"new mod")
            with (
                patch.object(codex_setup, "HERE", root),
                patch.object(codex_setup, "artifact", return_value=jar),
            ):
                codex_setup.install(instance, dry_run=True)
                self.assertTrue(old.exists())
                self.assertFalse((root / "backups").exists())
                codex_setup.install(instance)
            self.assertFalse(old.exists())
            self.assertEqual(unrelated.read_bytes(), b"keep")
            self.assertEqual(script.read_text(), "new script")
            self.assertEqual(
                (instance / "mods/assistantia-new.jar").read_bytes(), b"new mod"
            )
            self.assertEqual(
                next((root / "backups").glob("*/assistantia-old.jar")).read_bytes(),
                b"old mod",
            )


if __name__ == "__main__":
    unittest.main()
