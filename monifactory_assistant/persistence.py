"""Atomic local JSON snapshots with one verified previous version and recovery reporting."""

import copy
import json
import os
import stat
import tempfile
import threading
import time
from pathlib import Path

_LOCK = threading.RLock()
ISSUES = []


def _issue(path, message):
    ISSUES.append({"fichier": Path(path).name, "message": message, "t": time.time()})
    del ISSUES[:-20]


def _decode(raw, kind):
    value = json.loads(raw)
    if kind is not None and not isinstance(value, kind):
        raise ValueError("Format JSON inattendu")
    return value


def load_json(path, default=None, *, kind=dict, strict=False):
    path = Path(path)
    try:
        return _decode(path.read_text(encoding="utf-8"), kind)
    except FileNotFoundError:
        if not path.with_name(path.name + ".bak").exists():
            return copy.deepcopy(default)
    except (OSError, ValueError, UnicodeError):
        pass
    try:
        result = _decode(path.with_name(path.name + ".bak").read_text(encoding="utf-8"), kind)
    except (OSError, ValueError, UnicodeError) as error:
        _issue(path, "Sauvegarde illisible ; aucune copie valide.")
        if strict:
            raise ValueError(
                "Sauvegarde illisible : " + path.name + " (fichier conservé)."
            ) from error
        return copy.deepcopy(default)
    _issue(path, "Dernière copie valide récupérée.")
    return result


def _replace_bytes(path, raw, mode):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix="." + path.name + "-", suffix=".tmp", dir=path.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            os.chmod(temporary, mode)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def backup_json(path, raw=None):
    """Preserve a known-good baseline before a caller's guarded atomic commit."""
    path = Path(path)
    with _LOCK:
        if raw is None:
            if not path.exists():
                return
            raw = path.read_bytes()
        try:
            json.loads(raw)
        except (ValueError, UnicodeError):
            return
        mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
        _replace_bytes(path.with_name(path.name + ".bak"), raw, mode)


def atomic_json(path, value, *, backup=True, compact=False):
    path = Path(path)
    raw = json.dumps(
        value,
        ensure_ascii=False,
        indent=None if compact else 1,
        separators=(",", ":") if compact else None,
        allow_nan=False,
    ).encode("utf-8")
    with _LOCK:
        try:
            mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
            if backup and path.exists():
                previous = path.read_bytes()
                if previous == raw:
                    return
                try:
                    json.loads(previous)
                except (ValueError, UnicodeError):
                    pass  # Never replace a valid backup with an interrupted old file.
                else:
                    _replace_bytes(path.with_name(path.name + ".bak"), previous, mode)
            _replace_bytes(path, raw, mode)
        except OSError:
            _issue(path, "Enregistrement impossible ; version précédente conservée.")
            raise
