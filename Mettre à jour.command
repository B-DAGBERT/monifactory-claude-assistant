#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
GIT="$(command -v git)"
# Prefer a standalone Git on Macs where Xcode's license blocks /usr/bin/git.
for candidate in /opt/homebrew/bin/git /usr/local/bin/git; do
    if [[ -x "$candidate" ]]; then GIT="$candidate"; break; fi
done
if [[ "$($GIT branch --show-current)" != codex-compat ]]; then
    echo "Mise à jour annulée : ce dossier doit être sur la branche codex-compat."
    exit 1
fi
if [[ -n "$($GIT status --porcelain)" ]]; then
    echo "Mise à jour annulée : des changements locaux doivent d’abord être conservés dans Git."
    exit 1
fi
"$GIT" fetch origin codex-compat
"$GIT" merge --ff-only origin/codex-compat
exec bash "Installer Mac.command"
