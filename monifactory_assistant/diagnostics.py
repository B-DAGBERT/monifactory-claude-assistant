"""Compact local diagnostics; no AI request, credentials or account data."""

import subprocess

from monifactory_assistant.paths import ROOT
from monifactory_assistant.persistence import ISSUES
from monifactory_assistant.runtime import LAST_ERROR


def view(app):
    """Diagnostic du menu Y. Les dates restent des dates : le mod calcule les âges lui-même, la
    vue ne change donc pas à chaque seconde et vue_mod.json n'est réécrit qu'à un vrai
    changement."""
    state = app.game or {}
    bridge = app.bridge
    stamp = (state or getattr(bridge, "last_state", None) or {}).get("t", 0)
    stamp = (
        int(stamp)
        if isinstance(stamp, (int, float)) and not isinstance(stamp, bool) and stamp > 0
        else None
    )
    queue = getattr(app, "request_queue", None)
    rows = queue.view() if queue else {}
    return {
        "revision": getattr(app, "diagnostic_revision", ""),
        "jeu_connecte": bool(state),
        "inventaire_t": stamp,  # date du relevé (ms) ; le mod en affiche l'âge
        "dimension": state.get("dimension", ""),
        "serveur_actif": app.world.active,
        "occupe": app.busy,
        "file": rows.get("en_attente", 0),
        "derniere_action": rows.get("derniere", {}),
        "derniere_erreur": getattr(app, "last_error", "")
        or LAST_ERROR["message"]
        or rows.get("erreur", "")
        or getattr(app, "connection_problem", ""),
        "sauvegardes": ISSUES[-3:],
        "rendu_ms": round(getattr(app, "last_render_ms", 0), 1),
        "script_attendu": getattr(bridge, "script_version", 0),
        "script_charge": state.get("version_script", 0),
    }


def revision():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=3,
        ).strip()
    except (OSError, subprocess.SubprocessError):
        return "non disponible"
