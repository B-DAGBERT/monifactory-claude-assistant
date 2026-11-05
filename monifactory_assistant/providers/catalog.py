"""Model names from the local CLI cache; no inference, authentication or network calls."""

import json
import os
import time
from pathlib import Path

from monifactory_assistant import providers


class ModelCatalog:
    def __init__(self, path=None, *, clock=time.monotonic):
        self.path = (
            Path(path)
            if path is not None
            else Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex") / "models_cache.json"
        )
        self.clock = clock
        self.checked = None
        self.signature = None
        self.models = []

    def _read_codex(self):
        now = self.clock()
        if self.checked is not None and now - self.checked < 5:
            return
        self.checked = now
        try:
            stat = self.path.stat()
            signature = (stat.st_ino, stat.st_mtime_ns, stat.st_size)
            if signature == self.signature:
                return
            if stat.st_size > 2_000_000:
                raise ValueError("Model catalog too large")
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            rows = payload.get("models") if isinstance(payload, dict) else None
            if not isinstance(rows, list):
                raise ValueError("Invalid model catalog")
            models, seen = [], set()
            for row in rows[:200]:
                if not isinstance(row, dict) or row.get("visibility") != "list":
                    continue
                ident = row.get("slug")
                if not isinstance(ident, str) or not ident or len(ident) > 128 or ident in seen:
                    continue
                levels = row.get("supported_reasoning_levels", [])
                efforts = (
                    [
                        e["effort"]
                        for e in levels
                        if isinstance(e, dict)
                        and e.get("effort") in providers.EFFORTS_BY_PROVIDER["codex"]
                    ]
                    if isinstance(levels, list)
                    else []
                )
                # Models with no effort supported by our transport are not selectable.
                if not efforts:
                    continue
                name = row.get("display_name")
                models.append(
                    {
                        "id": ident,
                        "nom": name[:100] if isinstance(name, str) and name else ident,
                        "efforts": list(dict.fromkeys(efforts)),
                        "origine": "Catalogue Codex local",
                    }
                )
                seen.add(ident)
            self.models = models
            self.signature = signature
        except (OSError, ValueError, UnicodeError):
            self.models = []
            self.signature = None

    def view(self, provider, values, defaults):
        if provider == "codex":
            self._read_codex()
            rows = [dict(row) for row in self.models]
            source = (
                "Catalogue Codex local · accès selon ton compte."
                if rows
                else "Catalogue indisponible · modèles configurés et par défaut."
            )
        else:
            rows = [
                {
                    "id": ident,
                    "nom": name,
                    "efforts": list(providers.EFFORTS_BY_PROVIDER[provider]),
                    "origine": "Alias Claude Code",
                }
                for ident, name in (
                    ("sonnet", "Claude Sonnet"),
                    ("opus", "Claude Opus"),
                    ("haiku", "Claude Haiku"),
                )
            ]
            source = "Modèles Claude Code · accès selon ton compte."
        known = {r["id"] for r in rows}
        for settings, label in (
            (values, "Configuration actuelle"),
            (defaults, "Valeur par défaut"),
        ):
            for kind in ("question", "plan"):
                ident = settings.get("model_" + kind)
                if not isinstance(ident, str) or not ident or ident in known:
                    continue
                rows.append(
                    {
                        "id": ident,
                        "nom": ident,
                        "origine": label,
                        "efforts": list(providers.EFFORTS_BY_PROVIDER[provider]),
                    }
                )
                known.add(ident)
        return {"modeles": rows, "catalogue": source}
