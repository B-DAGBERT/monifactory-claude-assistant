"""Update from main; migrate clean legacy checkouts without discarding any commits."""

import os
import shutil
import subprocess
import sys
from pathlib import Path

from monifactory_assistant.paths import ROOT as HERE

BRANCH = "main"
LEGACY_BRANCHES = ("shared-assistant", "codex-compat")


def main():
    git = next(
        (p for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git") if Path(p).is_file()),
        shutil.which("git"),
    )
    if not git:
        raise RuntimeError("Git introuvable. Installe Git avant de mettre à jour.")

    def run(*args):
        return subprocess.check_output([git, *args], cwd=HERE, text=True).strip()

    current = run("branch", "--show-current")
    if current not in (BRANCH, *LEGACY_BRANCHES):
        raise RuntimeError(
            "Mise à jour annulée : utilise la branche main, pas une branche de développement."
        )
    if run("status", "--porcelain"):
        raise RuntimeError("Mise à jour annulée : conserve d'abord tes modifications dans Git.")
    # Explicit destination also works for old --single-branch clones.
    target = "origin/" + BRANCH
    run("fetch", "origin", f"refs/heads/{BRANCH}:refs/remotes/{target}")

    def require_published(ref):
        try:
            run("merge-base", "--is-ancestor", ref, target)
        except subprocess.CalledProcessError as error:
            raise RuntimeError(
                f"Mise à jour annulée : {ref} contient des commits absents de main. "
                "Fais-les intégrer avant de mettre à jour ; aucun commit n’a été supprimé."
            ) from error

    require_published("HEAD")
    if current != BRANCH and run("branch", "--list", BRANCH):
        require_published(BRANCH)
    if current != BRANCH:
        if run("branch", "--list", BRANCH):
            run("switch", "--no-overwrite-ignore", BRANCH)
        else:
            run("switch", "--no-overwrite-ignore", "--no-track", "-c", BRANCH, target)
    run("merge", "--no-overwrite-ignore", "--ff-only", target)
    # Register main for future plain git pull commands, including single-branch clones.
    refspecs = run("config", "--get-all", "remote.origin.fetch").splitlines()
    main_refspec = f"refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}"
    if not any(
        ref.lstrip("+") in (main_refspec, "refs/heads/*:refs/remotes/origin/*") for ref in refspecs
    ):
        run("config", "--add", "remote.origin.fetch", "+" + main_refspec)
    run("branch", "--set-upstream-to=" + target, BRANCH)
    print("Source des mises à jour : main (Claude et Codex).", flush=True)
    installer = (
        ["cmd", "/c", "Installer.bat"] if os.name == "nt" else ["bash", "Installer Mac.command"]
    )
    subprocess.run(installer, cwd=HERE, check=True)


def cli():
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Erreur : {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    cli()
