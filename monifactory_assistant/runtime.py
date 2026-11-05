"""Journal du processus et utilitaires de files de messages."""

import queue
from datetime import datetime

from monifactory_assistant.settings import LOG_FILE


def log(message):
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}\n")


def drain(q):
    while True:
        try:
            q.get_nowait()
        except queue.Empty:
            return
