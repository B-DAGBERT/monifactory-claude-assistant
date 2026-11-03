"""Local configuration; never overwrite it when updating the Git branch."""

import json
import os
import shutil
from pathlib import Path

from monifactory_assistant.paths import ROOT as HERE


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
        if not path.exists():
            continue
        value = json.loads(path.read_text(encoding="utf-8"))
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
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)
