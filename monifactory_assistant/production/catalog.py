"""Load an immutable, explicitly published recipe generation for offline calculations."""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Literal

from monifactory_assistant.game.snapshots import file_stamp
from monifactory_assistant.game.world_scope import scope_file
from monifactory_assistant.production.recipes import Catalog

SCHEMA = "monifactory.production.catalogue"
MAX_BYTES = 64 * 1024 * 1024


def _unique_keys(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("Catalogue avec clés JSON répétées.")
    return result


def _invalid_constant(_value):
    raise ValueError("Nombre JSON invalide dans le catalogue.")


def decode_document(raw):
    return json.loads(raw, object_pairs_hook=_unique_keys, parse_constant=_invalid_constant)


def _digest(payload):
    raw = json.dumps(
        payload, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False
    )
    return hashlib.sha256(raw.encode("ascii")).hexdigest()


def catalogue_document(stream, payload):
    """Prepare a complete versioned document for publication by a trusted producer."""
    return {
        "schema": SCHEMA,
        "version": 1,
        "source": stream,
        "sha256": _digest(payload),
        "catalogue": payload,
    }


@dataclass(frozen=True)
class CatalogueView:
    status: Literal["ready", "stale", "unavailable"]
    source: str
    catalog: Catalog | None = None
    sha256: str | None = None
    reason: str | None = None


class CatalogueReader:
    """Track one producer and retain its last accepted generation on invalid publication."""

    def __init__(self, path, source):
        if not isinstance(source, str) or not source.strip():
            raise ValueError("Source du catalogue manquante.")
        self.path = Path(path)
        self.source = source
        self._accepted = CatalogueView("unavailable", source)
        self._lock = Lock()

    def refresh(self):
        with self._lock:
            previous = self._accepted
            try:
                path = scope_file(self.path.parent, self.path.name)
                before = file_stamp(path)
                if before is None or not 0 < before[2] <= MAX_BYTES:
                    raise ValueError("Catalogue absent, vide ou trop volumineux.")
                with path.open("rb") as stream:
                    raw = stream.read(before[2] + 1)
                if len(raw) != before[2] or file_stamp(path) != before:
                    raise ValueError("Catalogue remplacé pendant la lecture.")
                document = decode_document(raw)
                if (
                    not isinstance(document, dict)
                    or document.get("schema") != SCHEMA
                    or type(document.get("version")) is not int
                    or document["version"] != 1
                    or document.get("source") != self.source
                ):
                    raise ValueError("Version ou source du catalogue inconnue.")
                payload = document.get("catalogue")
                digest = _digest(payload)
                if digest != document.get("sha256"):
                    raise ValueError("Empreinte du catalogue incohérente.")
                candidate = (
                    previous.catalog if previous.sha256 == digest else Catalog.from_dict(payload)
                )
                if file_stamp(scope_file(self.path.parent, self.path.name)) != before:
                    raise ValueError("Catalogue remplacé pendant la validation.")
                if candidate is previous.catalog:
                    return previous
                if (
                    previous.catalog is not None
                    and candidate.generation <= previous.catalog.generation
                ):
                    raise ValueError(
                        "Génération antérieure ou déjà utilisée par un autre catalogue."
                    )
            except (OSError, ValueError, TypeError, RecursionError) as error:
                reason = (
                    "Lecture du catalogue impossible." if isinstance(error, OSError) else str(error)
                )
                status = "stale" if previous.catalog is not None else "unavailable"
                return CatalogueView(status, self.source, previous.catalog, previous.sha256, reason)
            self._accepted = CatalogueView("ready", self.source, candidate, digest)
            return self._accepted
