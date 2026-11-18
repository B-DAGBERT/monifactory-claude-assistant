"""Update from main; migrate clean legacy checkouts without discarding any commits."""

import subprocess
import sys

from monifactory_assistant.commands.auto_update import AutoUpdater
from monifactory_assistant.commands.setup import pending_path
from monifactory_assistant.config import HERE, minecraft_dir
from monifactory_assistant.platform.support import SingleInstance

BRANCH = "main"
LEGACY_BRANCHES = ("shared-assistant", "codex-compat")
TARGET = "origin/" + BRANCH
MAIN_REFSPEC = f"refs/heads/{BRANCH}:refs/remotes/{TARGET}"
COMPLETE = {"updated", "current"}


def switch_legacy_to_main(updater):
    if updater.git("status", "--porcelain"):
        raise RuntimeError("Mise à jour annulée. Conserve d’abord tes modifications dans Git.")
    # Explicit destination also works for old --single-branch clones.
    updater.git("fetch", "origin", MAIN_REFSPEC)
    existing_main = bool(updater.git("branch", "--list", BRANCH))
    for ref in ("HEAD", BRANCH) if existing_main else ("HEAD",):
        try:
            updater.git("merge-base", "--is-ancestor", ref, TARGET)
        except subprocess.CalledProcessError as error:
            raise RuntimeError(
                f"Mise à jour annulée. {ref} contient des commits absents de main."
            ) from error
    if existing_main:
        updater.git("switch", "--no-overwrite-ignore", BRANCH)
    else:
        # Create the alias at the installed checkout, leaving the release advance to the updater.
        updater.git("switch", "--no-overwrite-ignore", "--no-track", "-c", BRANCH)
    return not existing_main


def track_main(updater):
    # Register main for future plain git pull commands, including single-branch clones.
    try:
        refspecs = updater.git("config", "--get-all", "remote.origin.fetch").splitlines()
    except subprocess.CalledProcessError as error:
        if error.returncode != 1:
            raise
        refspecs = []
    if not any(
        ref.lstrip("+") in (MAIN_REFSPEC, "refs/heads/*:refs/remotes/origin/*") for ref in refspecs
    ):
        updater.git("config", "--add", "remote.origin.fetch", "+" + MAIN_REFSPEC)
    updater.git("branch", "--set-upstream-to=" + TARGET, BRANCH)


def main():
    with SingleInstance() as acquired:
        if not acquired:
            raise RuntimeError("Arrête l’assistant puis relance la mise à jour.")
        updater = AutoUpdater(HERE, minecraft_dir())
        if not updater.git_bin:
            raise RuntimeError("Git introuvable. Installe Git avant de mettre à jour.")
        current = updater.git("branch", "--show-current")
        if current not in (BRANCH, *LEGACY_BRANCHES):
            raise RuntimeError("Mise à jour annulée. Utilise la branche main.")
        legacy = current != BRANCH
        created = switch_legacy_to_main(updater) if legacy else False
        before = updater.git("rev-parse", "HEAD") if legacy else None
        state = None
        try:
            state = updater.check()["state"]
        finally:
            # An interrupted installation must resume from main with its journal intact.
            if (
                legacy
                and state not in COMPLETE
                and not pending_path(HERE).exists()
                and updater.git("branch", "--show-current") == BRANCH
                and updater.git("rev-parse", "HEAD") == before
                and not updater.git("status", "--porcelain")
            ):
                updater.git("switch", "--no-overwrite-ignore", current)
                if created:
                    updater.git("branch", "-d", BRANCH)
        if state in COMPLETE:
            track_main(updater)
            if state == "current":
                print("Monifactory Assistant est à jour.", flush=True)
            return 0
        return 1


def cli():
    try:
        result = main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print(f"Erreur : {error}", file=sys.stderr)
        result = 1
    sys.exit(result)


if __name__ == "__main__":
    cli()
