#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ ! -x .venv/bin/python ]]; then
    echo "Ouvre d’abord Installer Mac.command."
    exit 1
fi
exec .venv/bin/python assistant_mc.py
