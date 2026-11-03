"""Run the updater before importing the GUI/audio libraries, under the assistant lock."""

import sys

from monifactory_assistant.paths import ROOT
from monifactory_assistant.platform.support import SingleInstance


def main():
    if sys.stderr is None:  # Windows pythonw keeps startup diagnostics in the usual journal.
        sys.stdout = sys.stderr = (ROOT / "assistant.log").open("a", encoding="utf-8", buffering=1)
    if "--enfant" in sys.argv:
        from monifactory_assistant.app import main as run_child

        run_child()
        return
    with SingleInstance() as acquired:
        if not acquired:
            from tkinter import messagebox

            messagebox.showinfo("Assistant Minecraft", "L'assistant est déjà lancé.")
            return
        try:
            from monifactory_assistant.commands.auto_update import check_at_startup

            check_at_startup()
        except Exception as error:
            print(
                f"Mise à jour indisponible ({type(error).__name__}) : démarrage local.", flush=True
            )
        # Fresh imports after the fast-forward; the supervised child skips the update check.
        from monifactory_assistant.app import supervise

        supervise(already_locked=True)
