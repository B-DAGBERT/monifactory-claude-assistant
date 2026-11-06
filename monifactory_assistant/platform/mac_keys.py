"""Minecraft forwards key transitions. No global keyboard hook or Accessibility permission."""

import atexit
import json
import threading
import time

_hotkeys, _down, _up = [], {}, {}
_pressed = set()
_stop = threading.Event()
_listener = None


def add_hotkey(key, callback, args=()):
    _hotkeys.append((set(key.lower().split("+")), callback, args))


def on_press_key(key, callback):
    _down[key] = callback


def on_release_key(key, callback):
    _up[key] = callback


def is_pressed(key):
    return key in _pressed


def transition(keys):
    global _pressed
    before, _pressed = _pressed, set(keys)
    for key in before - _pressed:
        if key in _up:
            _up[key](None)
    for combo, callback, args in _hotkeys:
        if combo == _pressed and combo != before:
            callback(*args)
    for key in _pressed - before:
        if key in _down:
            _down[key](None)


def _timestamp(value):
    # JSON accepts NaN/Infinity too; comparisons reject them without integer coercion.
    return type(value) in (int, float) and 0 <= value < 1e16


def _valid_frame(data):
    if (
        not isinstance(data, dict)
        or not isinstance(data.get("session"), str)
        or not data["session"]
        or not _timestamp(data.get("t"))
        or not isinstance(data.get("events"), list)
    ):
        return False
    sequence = 0
    for event in data["events"]:
        if (
            not isinstance(event, dict)
            or type(event.get("n")) is not int
            or event["n"] <= sequence
            or not _timestamp(event.get("t"))
            or not isinstance(event.get("keys"), list)
            or not all(isinstance(key, str) and key for key in event["keys"])
        ):
            return False
        sequence = event["n"]
    return True


class Reader:
    def __init__(self, path):
        self.path = path
        self.started = time.time() * 1000
        self.session = None
        self.sequence = 0

    def poll(self):
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        now = time.time() * 1000
        if not _valid_frame(data) or not -2000 <= now - data["t"] <= 2000:
            # Release held keys, retaining the sequence so recovery cannot replay an action.
            # Validate the entire frame before invoking even its first event.
            transition(set())
            return
        session = data["session"]
        if session != self.session:
            transition(set())
            self.session, self.sequence = session, 0
        for event in data.get("events", []):
            if event["n"] > self.sequence:
                self.sequence = event["n"]
                if event["t"] >= self.started:
                    transition(event["keys"])


def start(data_dir):
    global _listener
    if _listener:
        return
    reader = Reader(data_dir / "codex_touches.json")

    def listen():
        while not _stop.wait(0.04):
            reader.poll()

    _listener = threading.Thread(target=listen, daemon=True)
    _listener.start()


atexit.register(_stop.set)
