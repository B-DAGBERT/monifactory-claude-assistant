"""Bounded, read-only acquisition using the existing native world identity and files."""

import stat
import time
import uuid
from dataclasses import replace
from pathlib import Path

from monifactory_assistant.game.world_scope import (
    _DIMENSION,
    _SESSION_ID,
    _WORLD_ID,
    IDENTITY,
    IDENTITY_MAX_AGE_MS,
    WorldScope,
    matches,
    scope_file,
    scope_folder,
)
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.production.catalog import decode_document
from monifactory_assistant.production.knowledge import (
    Completeness,
    SourceSnapshot,
    build_knowledge,
)

CATALOGUE_PROOF_REQUEST = "production_v2_preuve_demande.json"
CATALOGUE_PROOF_REPLY = "production_v2_preuve_reponse.json"


def attest_catalogue(folder, scope, catalogue, *, timeout=0.45):
    """Require a new acknowledgement from the native client for this exact catalogue."""
    proof = {
        "v": 1,
        "nonce": uuid.uuid4().hex,
        **scope.fields(),
        **{key: catalogue[key] for key in ("source", "generation", "sha256")},
    }
    try:
        info = folder.lstat()
        if not stat.S_ISDIR(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            return False
        request = scope_file(folder, CATALOGUE_PROOF_REQUEST)
        atomic_json(request, proof, backup=False, compact=True)
    except (OSError, ValueError):
        return False
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            reply, _ = read_capture(folder / CATALOGUE_PROOF_REPLY, 4096)
            if reply == proof and type(reply["v"]) is int and type(reply["generation"]) is int:
                return True
        except (OSError, ValueError, TypeError, RecursionError):
            pass
        time.sleep(0.015)
    return False


def capture_stamp(path):
    try:
        info = path.stat()
    except FileNotFoundError:
        return None
    return info.st_ino, info.st_mtime_ns, info.st_size, info.st_ctime_ns


def read_capture(path, limit=8 * 1024 * 1024):
    """Reject partial replacement, links, special files and oversized snapshots."""
    path = scope_file(Path(path).parent, Path(path).name)
    before = capture_stamp(path)
    if before is None or not 0 < before[2] <= limit:
        raise ValueError("Relevé absent ou trop volumineux.")
    with path.open("rb") as stream:
        raw = stream.read(before[2] + 1)
    if len(raw) != before[2] or capture_stamp(scope_file(path.parent, path.name)) != before:
        raise ValueError("Relevé modifié pendant sa lecture.")
    data = decode_document(raw)
    if not isinstance(data, dict):
        raise ValueError("Relevé incomplet.")
    return data, before


def active_scope(bridge, now_ms=None):
    """Read the already exported identity, including legacy mode, without enabling lot 5."""
    if getattr(getattr(bridge, "routing", None), "mode", "blocked") == "blocked":
        return None
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    try:
        data, _ = read_capture(bridge.dir / IDENTITY, 8192)
        stamp = data.get("t")
        if (
            type(data.get("v")) is not int
            or data["v"] != 1
            or type(stamp) not in (int, float)
            or not 0 <= now_ms - stamp < IDENTITY_MAX_AGE_MS
            or not matches(_WORLD_ID, data.get("monde"))
            or not matches(_SESSION_ID, data.get("session"))
            or not matches(_DIMENSION, data.get("dimension"))
        ):
            return None
        return WorldScope(data["monde"], data["session"], data["dimension"])
    except (OSError, ValueError, TypeError, RecursionError):
        return None


def acquire(bridge, python_root, scope, request, now_ms):
    """Only independently scoped frames are current; legacy files are never reassigned."""
    if active_scope(bridge, now_ms) != scope:
        raise ValueError("Le monde ou la connexion a changé.")
    sources = []
    limits = [
        "Réservations, fabrications en cours et réceptions : aucun relevé de lot attesté pour ce calcul."
    ]
    native_inventory = False
    try:
        data, stamp = read_capture(bridge.dir / "production_v2_inventaire.json", 1024 * 1024)
        if (
            not request.get("capture")
            or data.get("capture") != request["capture"]
            or not scope.accepts(data)
        ):
            raise ValueError("La capture de l'inventaire ne correspond pas au clic.")
        sources.append(
            SourceSnapshot(
                "inventaire_joueur.json",
                data,
                "production_v2_inventaire.json",
                received_at_ms=stamp[1] // 1_000_000,
            )
        )
        native_inventory = True
        limits.append(
            "Inventaire natif : seuls les objets sans NBT sont comptés ; variantes non attestées."
        )
    except (OSError, ValueError, TypeError, RecursionError):
        limits.append("Inventaire du clic indisponible ; aucun ancien stock ne le remplace.")

    for name in ("etat.json", "monde.json"):
        try:
            data, stamp = read_capture(bridge.dir / name)
            if not scope.accepts(data):
                raise ValueError("Relevé sans preuve de monde/session.")
            if native_inventory and name == "etat.json":
                # It cannot restore NBT-aggregated stock excluded by the explicit native capture.
                data = {key: value for key, value in data.items() if key != "inventaire"}
            sources.append(SourceSnapshot(name, data, received_at_ms=stamp[1] // 1_000_000))
        except (OSError, ValueError, TypeError, RecursionError):
            limits.append(f"{name}: relevé courant non attesté.")

    isolated = getattr(bridge.routing, "mode", None) == "isolated"
    if isolated:
        for root, name in (
            (bridge.dir, "stockage.json"),
            (Path(python_root), "monde_memoire.json"),
        ):
            try:
                data, stamp = read_capture(scope_folder(root, scope.world) / name)
                sources.append(
                    SourceSnapshot(
                        name, data, world=scope.world, received_at_ms=stamp[1] // 1_000_000
                    )
                )
            except (OSError, ValueError, TypeError, RecursionError):
                limits.append(f"{name}: historique de ce monde indisponible.")
    else:
        limits.append("Les anciennes mémoires non isolées restent non attribuées à ce monde.")

    # This snapshot has no common transfer revision: the knowledge engine chooses personal
    # inventory, or a single server frame, without summing potentially moved physical items.
    snapshot = build_knowledge(scope, now_ms, sources)
    if native_inventory:
        rows = tuple(
            replace(
                row,
                evidence=tuple(
                    replace(proof, completeness=Completeness.PARTIAL)
                    if proof.reference == "production_v2_inventaire.json"
                    else proof
                    for proof in row.evidence
                ),
            )
            for row in snapshot.observations
        )
        snapshot = replace(snapshot, observations=rows)
    if active_scope(bridge) != scope:
        raise ValueError("Connexion modifiée pendant la lecture.")
    return replace(snapshot, limitations=(*snapshot.limitations, *limits))
