"""Local configuration; never overwrite it when updating the Git branch."""

import os
import shutil
from pathlib import Path

from monifactory_assistant.paths import ROOT as HERE
from monifactory_assistant.persistence import atomic_json, load_json


def find_codex():
    candidates = [
        os.environ.get("CODEX_BIN"),
        shutil.which("codex"),
        "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
        "/Applications/Codex.app/Contents/Resources/codex",
    ]
    return next((p for p in candidates if p and Path(p).is_file()), None)


def load_config():
    # The old Codex configuration remains valid; common settings override it.
    config = {}
    for name in ("codex.local.json", "assistant.local.json"):
        path = HERE / name
        if not path.exists() and not path.with_name(path.name + ".bak").exists():
            continue
        value = load_json(path, {}, strict=True)
        if not isinstance(value, dict):
            raise ValueError(f"{name} doit contenir un objet JSON.")
        config.update(value)
    return config


CONFIG = load_config()


def minecraft_dir():
    configured = os.environ.get("MINECRAFT_DIR") or CONFIG.get("minecraft_dir")
    if configured:
        return Path(configured).expanduser()
    candidates = [
        Path.home() / prefix / "curseforge/minecraft/Instances/Monifactory"
        for prefix in ("Documents", "")
    ]
    return next((p for p in candidates if p.is_dir()), candidates[0])


def write_json(path, value):
    atomic_json(path, value)
