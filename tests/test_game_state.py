"""A broken or stale game frame must not revive an old world or kill polling."""

import ast
import unittest
from types import SimpleNamespace

from monifactory_assistant.game.inventory import personal_inventory, recent_state
from tests.app_source import application_tree


class GameStateTests(unittest.TestCase):
    def setUp(self):
        tree = application_tree()
        bridge = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Bridge")
        bridge.body = [
            m for m in bridge.body if isinstance(m, ast.FunctionDef) and m.name == "state"
        ]
        self.now = 100_500
        scope = {
            "personal_inventory": personal_inventory,
            "recent_state": recent_state,
            "time": SimpleNamespace(time=lambda: self.now / 1000),
        }
        exec(compile(ast.Module(body=[bridge], type_ignores=[]), "bridge", "exec"), scope)
        self.bridge = scope["Bridge"]()
        self.frame = {"t": 100_000, "joueur": "test", "dimension": "overworld", "inventaire": {}}
        self.bridge.isolated = False  # The legacy state reader is exercised here.
        self.bridge.last_state = None
        self.bridge.read = lambda name: self.frame if name == "etat.json" else None

    def test_partial_and_invalid_frames_keep_recent_frame_only(self):
        good = self.bridge.state()
        for bad in (None, [], [1], "loading", {"t": None}, {"t": "100000"}, {"t": True}):
            self.frame = bad
            self.assertEqual(self.bridge.state(), good)
        self.now = 105_000
        self.assertIsNone(self.bridge.state())

    def test_future_and_nonfinite_frames_never_connect(self):
        for stamp in (float("nan"), float("inf"), float("-inf"), self.now + 1, 1e200):
            self.frame = {"t": stamp}
            self.assertIsNone(self.bridge.state())
            self.assertIsNone(self.bridge.last_state)

    def test_valid_frame_recovers_after_disconnect(self):
        self.bridge.state()
        self.now += 6000
        self.assertIsNone(self.bridge.state())
        self.frame = {**self.frame, "t": self.now, "dimension": "nether"}
        self.assertEqual(self.bridge.state()["dimension"], "nether")
