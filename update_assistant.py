"""Update only a clean shared checkout, using fast-forward, before running its installer."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
BRANCH = "shared-assistant"


def main():
    git = next((p for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git") if Path(p).is_file()), shutil.which("git"))
    if not git:
        raise RuntimeError("Git introuvable. Installe Git avant de mettre à jour.")

    def run(*args):
        return subprocess.check_output([git, *args], cwd=HERE, text=True).strip()

    if run("branch", "--show-current") not in (BRANCH, "codex-compat"):
        raise RuntimeError("Mise à jour annulée : utilise la branche shared-assistant.")
    if run("status", "--porcelain"):
        raise RuntimeError("Mise à jour annulée : conserve d'abord tes modifications dans Git.")
    run("fetch", "origin", BRANCH)
    run("merge", "--ff-only", "origin/" + BRANCH)
    installer = ["cmd", "/c", "Installer.bat"] if os.name == "nt" else ["bash", "Installer Mac.command"]
    subprocess.run(installer, cwd=HERE, check=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Erreur : {error}", file=sys.stderr)
        sys.exit(1)
