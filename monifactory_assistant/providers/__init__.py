"""Select the local AI engine without importing or starting the other provider."""

import importlib
import os
import shutil
from pathlib import Path

from monifactory_assistant.config import CONFIG, find_codex

DEFAULTS = {
    "codex": {"plan": "gpt-6.1-sol", "question": "gpt-5.6-sol", "quick": "gpt-5.6-sol"},
    "claude": {"plan": "sonnet", "question": "sonnet", "quick": "haiku"},
}


def provider(config=None):
    name = (CONFIG if config is None else config).get("provider", "codex")
    if name not in DEFAULTS:
        raise ValueError("Moteur inconnu : choisis codex ou claude dans assistant.local.json.")
    return name


def label():
    return {"codex": "Codex", "claude": "Claude"}[provider()]


def model(kind, config=None):
    config = CONFIG if config is None else config
    name = provider(config)
    custom = config.get("providers", {}).get(name, {})
    # Legacy top-level models belong to Codex, never forward gpt-* models to Claude.
    legacy = config.get("model_" + kind) if name == "codex" else None
    return custom.get("model_" + kind) or legacy or DEFAULTS[name][kind]


def find_claude():
    candidates = [
        os.environ.get("CLAUDE_BIN"),
        shutil.which("claude"),
        str(Path.home() / ".local/bin/claude"),
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
    ]
    if os.name == "nt":
        candidates.append(str(Path.home() / ".local/bin/claude.exe"))
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Claude-Data"
        candidates.extend(
            str(p)
            for p in sorted(
                base.glob("*/claude-code/*/*/claude.exe"),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
        )
    return next((p for p in candidates if p and Path(p).is_file()), None)


def executable():
    return find_codex() if provider() == "codex" else find_claude()


def backend():
    return importlib.import_module("monifactory_assistant.providers." + provider())


def ask(*args, **kwargs):
    return backend().ask(*args, **kwargs)


def conversation(*args, **kwargs):
    return backend().Conversation(*args, **kwargs)
