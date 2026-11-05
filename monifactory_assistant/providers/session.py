"""Façade de conversation partagée entre Claude et Codex."""

from pathlib import Path

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.prompts import SYSTEM_PROMPT
from monifactory_assistant.settings import TIMEOUT


def ask_claude(prompt, data_dir, model, on_text=None, on_tool=None, effort=None):
    return ai_provider.ask(
        prompt, data_dir, model, SYSTEM_PROMPT, on_text, on_tool, effort, TIMEOUT
    )


class Conversation:
    def __init__(self, data_dir):
        self.impl = ai_provider.conversation(
            data_dir,
            SYSTEM_PROMPT,
            ai_provider.model("question"),
            ai_provider.effort("question", "low"),
            TIMEOUT,
        )

    def ask(self, *args, **kwargs):
        self.impl.configure(ai_provider.model("question"), ai_provider.effort("question", "low"))
        return self.impl.ask(*args, **kwargs)

    def restart(self):
        return self.impl.restart()

    def stop(self):
        return self.impl.stop()

    def prepare(self):
        prepare = getattr(self.impl, "prepare", None)
        if prepare:
            prepare()


def tool_status(name, data):
    detail = (
        data.get("pattern")
        or data.get("query")
        or data.get("url")
        or Path(data.get("file_path") or data.get("path") or "").name
    )
    return (
        f"{ai_provider.label()} cherche : {detail}"
        if detail
        else f"{ai_provider.label()} utilise {name}…"
    )[:90]
