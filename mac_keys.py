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
        if now - data.get("t", 0) > 2000:
            transition(set())  # release the microphone if the game closes or freezes
            return
        session = data.get("session")
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
