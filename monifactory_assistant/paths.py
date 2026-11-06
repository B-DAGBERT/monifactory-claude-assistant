"""Stable locations: moving code must never relocate a user's saved game data."""

from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parent
ROOT = PACKAGE_ROOT.parent
WORLD_FILE = ROOT / "monde_memoire.json"
