"""Malformed keyboard frames must release held keys without killing the Mac reader."""

import contextlib
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.platform import mac_keys


class MacReaderResilienceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "codex_touches.json"
        self.patches = contextlib.ExitStack()
        self.addCleanup(self.patches.close)
        for name, value in (
            ("_pressed", set()),
            ("_hotkeys", []),
            ("_down", {}),
            ("_up", {}),
            ("_listener", None),
            ("_stop", threading.Event()),
        ):
            self.patches.enter_context(patch.object(mac_keys, name, value))
        self.calls = []
        mac_keys.on_press_key("f8", lambda _: self.calls.append("talk"))
        mac_keys.on_release_key("f8", lambda _: self.calls.append("release"))
        mac_keys.add_hotkey("f7", lambda: self.calls.append("plan"))
        self.reader = mac_keys.Reader(self.path)

    def write(self, value):
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value), encoding="utf-8")
        temporary.replace(self.path)

    def frame(self, events, session="a"):
        now = time.time() * 1000
        return {
            "session": session,
            "t": now,
            "events": [{"n": n, "t": now, "keys": keys} for n, keys in events],
        }

    def test_invalid_frames_release_keys_and_recover_without_replay(self):
        invalid = (
            [],
            None,
            {"t": "invalid"},
            {"session": [], "t": 1, "events": []},
            {"session": "a", "t": float("nan"), "events": []},
            {"session": "a", "t": float("inf"), "events": []},
            {"session": "a", "t": True, "events": []},
            {"session": "a", "t": time.time() * 1000, "events": {}},
            {"session": "a", "t": time.time() * 1000, "events": [{}]},
        )
        for value in invalid:
            with self.subTest(value=value):
                self.reader = mac_keys.Reader(self.path)
                self.calls.clear()
                held = self.frame([(1, ["f8"])])
                self.write(held)
                self.reader.poll()
                self.write(value)
                self.reader.poll()
                self.assertEqual(["talk", "release"], self.calls)
                self.assertEqual("a", self.reader.session)
                self.assertEqual(1, self.reader.sequence)
                self.write(held)
                self.reader.poll()
                self.assertEqual(["talk", "release"], self.calls)
                self.write(self.frame([(2, ["f7"])]))
                self.reader.poll()
                self.reader.poll()
                self.assertEqual(["talk", "release", "plan"], self.calls)
                mac_keys.transition([])

    def test_whole_frame_validated_before_actions_or_sequence_changes(self):
        for invalid_event in (
            {},
            {"n": True, "t": 1, "keys": []},
            {"n": -1, "t": 1, "keys": []},
            {"n": 3, "t": "bad", "keys": []},
            {"n": 3, "t": 1, "keys": "f7"},
            {"n": 3, "t": 1, "keys": [[]]},
            {"n": 3, "t": 1, "keys": [None]},
        ):
            with self.subTest(event=invalid_event):
                self.reader = mac_keys.Reader(self.path)
                self.calls.clear()
                frame = self.frame([(1, ["f7"])])
                frame["events"].append(invalid_event)
                self.write(frame)
                self.reader.poll()
                self.assertEqual([], self.calls)
                self.assertEqual(0, self.reader.sequence)
                self.assertIsNone(self.reader.session)
                self.write(self.frame([(1, ["f7"])]))
                self.reader.poll()
                self.assertEqual(["plan"], self.calls)
                mac_keys.transition([])

    def test_out_of_order_or_duplicate_events_cannot_partially_execute(self):
        for events in ([(2, ["f7"]), (1, [])], [(1, ["f7"]), (1, [])]):
            with self.subTest(events=events):
                self.write(self.frame(events))
                self.reader.poll()
                self.assertEqual([], self.calls)
                self.assertEqual(0, self.reader.sequence)

    def test_stale_or_future_frame_releases_without_resetting_sequence(self):
        for offset in (-3000, 60000):
            with self.subTest(offset=offset):
                self.reader = mac_keys.Reader(self.path)
                self.calls.clear()
                self.write(self.frame([(1, ["f8"])]))
                self.reader.poll()
                frame = self.frame([(2, ["f7"])], session="other")
                frame["t"] += offset
                self.write(frame)
                self.reader.poll()
                self.assertEqual(["talk", "release"], self.calls)
                self.assertEqual("a", self.reader.session)
                self.assertEqual(1, self.reader.sequence)

    def test_missing_invalid_utf8_and_partial_json_release_keys(self):
        for content in (None, b"\xff", b'{"session":'):
            with self.subTest(content=content):
                self.reader = mac_keys.Reader(self.path)
                self.calls.clear()
                self.write(self.frame([(1, ["f8"])]))
                self.reader.poll()
                if content is None:
                    self.path.unlink()
                else:
                    self.path.write_bytes(content)
                self.reader.poll()
                self.assertEqual(["talk", "release"], self.calls)
                self.assertEqual(1, self.reader.sequence)

    def test_session_change_releases_key_and_processes_new_sequence_once(self):
        self.write(self.frame([(8, ["f8"])]))
        self.reader.poll()
        self.write(self.frame([(1, ["f7"])], session="new"))
        self.reader.poll()
        self.reader.poll()
        self.assertEqual(["talk", "release", "plan"], self.calls)
        self.assertEqual(1, self.reader.sequence)

    def test_live_listener_survives_invalid_frame_then_handles_valid_one(self):
        self.write([])
        polled = threading.Event()
        action = threading.Event()
        failures = []
        mac_keys.add_hotkey("f10", action.set)
        original = mac_keys.Reader.poll

        def poll(reader):
            original(reader)
            polled.set()

        with (
            patch.object(mac_keys.Reader, "poll", poll),
            patch.object(threading, "excepthook", lambda args: failures.append(args.exc_value)),
        ):
            try:
                mac_keys.start(self.path.parent)
                self.assertTrue(polled.wait(1), failures)
                listener = mac_keys._listener
                mac_keys.start(self.path.parent)
                self.assertIs(listener, mac_keys._listener)
                self.assertTrue(listener.is_alive())
                self.write(self.frame([(1, ["f10"])]))
                self.assertTrue(action.wait(1), failures)
                self.assertEqual([], failures)
            finally:
                mac_keys._stop.set()
                mac_keys._listener.join(timeout=1)
            self.assertFalse(mac_keys._listener.is_alive())


if __name__ == "__main__":
    unittest.main()
