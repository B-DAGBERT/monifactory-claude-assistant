#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ ! -x .venv/bin/python ]]; then
    echo "Lance d’abord Installer Mac.command."
    exit 1
fi
suite="${1:-}"
if [[ -z "$suite" ]]; then
    echo "1. Basique — collecte, sorties machine, moules, circuits et fluides"
    echo "2. Complexe — retards, gros lots, sources contradictoires et reprises"
    echo "3. Tous"
    read -r -p "Choix [3] : " choix
    case "${choix:-3}" in
        1) suite=basique ;;
        2) suite=complexe ;;
        3) suite=tous ;;
        *) echo "Choix invalide."; exit 2 ;;
    esac
fi
code=0
.venv/bin/python -m monifactory_assistant.commands.scenarios "$suite" || code=$?
if [[ -t 0 ]]; then read -r -p "Entrée pour fermer…" _; fi
exit "$code"
