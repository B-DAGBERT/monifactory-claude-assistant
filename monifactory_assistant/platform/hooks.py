"""Choisir les raccourcis natifs adaptés au système sans démarrer leur écoute."""

import sys

if sys.platform == "darwin":
    import monifactory_assistant.platform.mac_keys as keyboard
else:
    import keyboard

__all__ = ["keyboard"]
