#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -x .venv/bin/python ]]; then
    exec .venv/bin/python update_assistant.py
fi
exec python3 update_assistant.py
