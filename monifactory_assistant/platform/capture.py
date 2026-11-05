"""Capture et gestion du focus des fenêtres selon le système."""

import ctypes
import os
from datetime import datetime

import mss
from PIL import Image

from monifactory_assistant.settings import CAPTURES

user32 = ctypes.windll.user32 if os.name == "nt" else None


class Rect(ctypes.Structure):
    _fields_ = [
        ("left", ctypes.c_long),
        ("top", ctypes.c_long),
        ("right", ctypes.c_long),
        ("bottom", ctypes.c_long),
    ]


def active_monitor(monitors):
    """L'écran de la fenêtre active (le jeu, quand on appuie sur une touche en jouant), sinon l'écran principal."""
    rect = Rect()
    if user32 and user32.GetWindowRect(user32.GetForegroundWindow(), ctypes.byref(rect)):
        cx, cy = (rect.left + rect.right) // 2, (rect.top + rect.bottom) // 2
        for m in monitors[1:]:
            if m["left"] <= cx < m["left"] + m["width"] and m["top"] <= cy < m["top"] + m["height"]:
                return m
    return next((m for m in monitors[1:] if m.get("is_primary")), monitors[1])


def take_screenshot():
    CAPTURES.mkdir(exist_ok=True)
    with mss.mss() as sct:
        raw = sct.grab(active_monitor(sct.monitors))
    image = Image.frombytes("RGB", raw.size, raw.rgb)
    image.thumbnail((1600, 1600))
    path = CAPTURES / f"ecran-{datetime.now():%Y%m%d-%H%M%S}.jpg"
    image.save(path, quality=85)
    for old in sorted(CAPTURES.glob("ecran-*.jpg"))[:-30]:
        old.unlink(missing_ok=True)
    return path


def bring_to_front(window):
    window.update_idletasks()
    if not user32:
        window.lift()
        window.focus_force()
        return
    hwnd = user32.GetParent(window.winfo_id())
    # Un appui sur Alt autorise Windows à nous donner le focus pendant que le jeu est au premier plan.
    user32.keybd_event(0x12, 0, 0, 0)
    user32.keybd_event(0x12, 0, 2, 0)
    user32.SetForegroundWindow(hwnd)
    window.focus_force()


def make_non_activating(window):
    if not user32:
        return
    window.update_idletasks()
    hwnd = user32.GetParent(window.winfo_id())
    gwl_exstyle, ws_ex_noactivate, ws_ex_toolwindow = -20, 0x08000000, 0x80
    style = user32.GetWindowLongW(hwnd, gwl_exstyle)
    user32.SetWindowLongW(hwnd, gwl_exstyle, style | ws_ex_noactivate | ws_ex_toolwindow)
