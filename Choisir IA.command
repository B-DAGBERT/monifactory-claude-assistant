#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -x .venv/bin/python ]]; then
    exec .venv/bin/python choose_ai.py
fi
exec python3 choose_ai.py
