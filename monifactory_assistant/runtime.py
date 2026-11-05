"""Journal du processus et utilitaires de files de messages."""

import queue
import time
from datetime import datetime

from monifactory_assistant.settings import LOG_FILE

LAST_ERROR = {"message": "", "t": 0}


def log(message):
    if "Traceback" in message or "Erreur" in message or "ratée" in message:
        # Keep the final exception line; never export prompts or full tracebacks to the GUI.
        LAST_ERROR.update(message=message.strip().splitlines()[-1][:220], t=time.time())
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}\n")


def drain(q):
    while True:
        try:
            q.get_nowait()
        except queue.Empty:
            return
