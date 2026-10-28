#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
trap 'echo "Installation interrompue. Lis l’erreur ci-dessus."' ERR
PYTHON="${PYTHON_BIN:-}"
if [[ -z "$PYTHON" && -x .venv/bin/python ]]; then
    PYTHON="$PWD/.venv/bin/python"
fi
if [[ -z "$PYTHON" ]]; then
    for candidate in /opt/homebrew/bin/python3.12 /usr/local/bin/python3.12 \
        "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3.12" \
        /opt/homebrew/bin/python3 /usr/local/bin/python3; do
        if [[ -x "$candidate" ]] && "$candidate" -c 'import sys, tkinter; assert (3,10) <= sys.version_info[:2] < (3,14)' 2>/dev/null; then
            PYTHON="$candidate"
            break
        fi
    done
fi
if [[ -z "$PYTHON" ]]; then
    echo "Installe Python 3.12 avec Tk (python.org), puis relance ce fichier."
    exit 1
fi
if [[ ! -x .venv/bin/python ]]; then
    "$PYTHON" -m venv .venv
fi
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python codex_setup.py setup
echo "Prêt. Arrête l’ancien assistant, puis ouvre Lancer Mac.command."
