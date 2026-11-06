"""Explicit, installation-bound migration on copies; no startup hook or guessed world."""

import hashlib
import io
import json
import os
import re
import stat
import tempfile
import time
from pathlib import Path

from monifactory_assistant import data_backup as backup
from monifactory_assistant.game.world_scope import IDENTITY_MAX_AGE_MS, installation_id
from monifactory_assistant.persistence import atomic_json

FORMAT = "monifactory-world-migration-v1"
WORLD = re.compile(r"m1_[0-9a-f]{64}\Z")
SESSION = re.compile(r"[0-9a-f]{32}\Z")
MARKER = "isolation_mondes.json"
SCOPED = {
    "python": ("monde_memoire.json", "plan_ia.json", "lieux.json", "journal.jsonl"),
    "kubejs-assistant": ("stockage.json", "filons.json"),
}


class MigrationError(backup.BackupError):
    pass


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _bytes(root, name):
    backup._relative(name)
    root = backup._directory(root)
    result = io.BytesIO()
    backup._read_file(root, name, (root / name).lstat(), result)
    return result.getvalue()


def _read(root, name):
    value = json.loads(_bytes(root, name))
    if not isinstance(value, dict):
        raise MigrationError(f"Document invalide : {name}")
    return value


def _roots(python_root, game_data):
    roots = dict(zip(backup.ROOTS, (backup._directory(python_root), backup._directory(game_data))))
    backup._separate(list(roots.values()))
    return roots


def _installation(roots):
    return installation_id(roots["python"], roots["kubejs-assistant"])


def _once(path, value):
    raw = backup._json(value)
    if path.exists() or path.is_symlink():
        if _bytes(path.parent, path.name) != raw:
            raise MigrationError(f"Document existant différent, conservé : {path.name}")
        return
    # Publish a new file without replacing a concurrent writer's file.
    with tempfile.NamedTemporaryFile(
        prefix=".migration-", suffix=".partial", dir=path.parent, delete=False
    ) as stream:
        temporary = Path(stream.name)
        os.chmod(temporary, 0o600)
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.link(temporary, path)
    except FileExistsError:
        if _bytes(path.parent, path.name) != raw:
            raise MigrationError(f"Document créé simultanément : {path.name}") from None
    backup._sync_directory(path.parent)
    temporary.unlink()  # Our temporary hardlink only; never a source or historical file.


def identify(python_root, game_data, receipt, *, now=time.time):
    """Capture while connected, then close the game before copying or activating anything."""
    roots = _roots(python_root, game_data)
    target = backup._destination(receipt)
    backup._separate([*roots.values(), target])
    identity = _read(roots["kubejs-assistant"], "identite_monde.json")
    stamp = identity.get("t")
    if (
        identity.get("v") != 1
        or not WORLD.fullmatch(str(identity.get("monde", "")))
        or not SESSION.fullmatch(str(identity.get("session", "")))
        or not isinstance(identity.get("dimension"), str)
        or not identity["dimension"]
        or type(stamp) not in (int, float)
        or not 0 <= now() * 1000 - stamp < IDENTITY_MAX_AGE_MS
        or identity.get("actif") is False
    ):
        raise MigrationError(
            "Connecte-toi au serveur : son identité technique doit être récente et complète."
        )
    # Only opaque identifiers; no address or player name is required in this receipt.
    identity = {k: identity[k] for k in ("v", "monde", "session", "dimension", "t")}
    value = {
        "format": FORMAT,
        "installation": _installation(roots),
        "sources": {k: str(v) for k, v in roots.items()},
        "identite": identity,
    }
    _once(target, value)
    return value


def _receipt(path, roots):
    path = Path(path)
    value = _read(path.parent, path.name)
    identity = value.get("identite", {})
    if (
        value.get("format") != FORMAT
        or value.get("installation") != _installation(roots)
        or value.get("sources") != {k: str(v) for k, v in roots.items()}
        or not isinstance(identity, dict)
        or identity.get("v") != 1
        or not WORLD.fullmatch(str(identity.get("monde", "")))
        or not SESSION.fullmatch(str(identity.get("session", "")))
    ):
        raise MigrationError("Le reçu d'identité ne correspond pas à ces deux racines locales.")
    return value


def _world_ids(value):
    """Only explicit world fields count; dimensions, coordinates and player names never do."""
    result = set()
    if isinstance(value, dict):
        for key, item in value.items():
            if (
                key in ("monde", "monde_actif", "world_id", "world")
                and isinstance(item, str)
                and item
            ):
                result.add(item)
            if isinstance(item, (dict, list)):
                result.update(_world_ids(item))
    elif isinstance(value, list):
        for item in value:
            result.update(_world_ids(item))
    return result


def _selected(root, name):
    return any(
        name == base
        or name.startswith(base + ".")
        or (name.startswith("." + base + "-") and name.endswith(".tmp"))
        for base in SCOPED[root]
    )


def _summary(name, raw):
    """Counts are explanatory; byte hashes preserve every identifier/quantity/state exactly."""
    if name == "journal.jsonl":
        return {"lignes": len(raw.splitlines())}
    try:
        data = json.loads(raw)
    except (ValueError, UnicodeError):
        return {"format": "invalide_conserve"}
    if not isinstance(data, dict):
        return {"format": "inconnu_conserve"}
    counts = {}
    for key in ("conteneurs", "machines", "blocs", "gisements", "projets"):
        if isinstance(data.get(key), (list, dict)):
            counts[key] = len(data[key])
    if "plan" in data and "projets" not in counts:
        counts["projets"] = 1
    return counts


def _analyse(archive, manifest, world):
    mappings, historical, conflicts, summaries = [], [], [], {}
    known = set()
    for entry in manifest["entries"]:
        if entry["kind"] != "file":
            continue
        root, name = entry["root"], entry["path"]
        if (
            root == "kubejs-assistant"
            and name.startswith(("filons/", "filons_gregtech/"))
            and name.endswith(".json")
        ):
            try:
                known.update(_world_ids(json.loads(_bytes(archive / root, name))))
            except (ValueError, UnicodeError):
                historical.append({"root": root, "path": name, "raison": "format inconnu conservé"})
        if not _selected(root, name):
            historical.append(
                {
                    "root": root,
                    "path": name,
                    "raison": "conservé sans déplacement ; non partitionné",
                }
            )
            continue
        raw = _bytes(archive / root, name)
        ids = set()
        if name.startswith(("journal.jsonl", ".journal.jsonl-")):
            # Inspect every complete record without repairing/reformatting the log.
            # An interrupted final line stays byte-exact in all preserved copies.
            for line in raw.splitlines():
                try:
                    ids.update(_world_ids(json.loads(line)))
                except (ValueError, UnicodeError):
                    continue
        else:
            try:
                parsed = json.loads(raw)
                if not isinstance(parsed, dict):
                    raise ValueError("Objet JSON attendu")
                ids = _world_ids(parsed)
            except (ValueError, UnicodeError):
                # A silent omission would hide this historical domain after activation.
                # Keep both source and archive, but require repair and a new preparation.
                problem = {
                    "root": root,
                    "path": name,
                    "raison": "JSON illisible ou forme inconnue conservé",
                }
                historical.append(problem)
                conflicts.append(problem)
                continue
        foreign = sorted(ids - {world})
        if foreign:
            conflicts.append({"root": root, "path": name, "mondes": foreign})
            historical.append({"root": root, "path": name, "raison": "origine contradictoire"})
            continue
        mappings.append(entry)
        summaries[root + "/" + name] = _summary(name, raw)
    # Old vein IDs are path-derived hashes, not the new m1 token. One old ID is expected.
    # Several old IDs may be moved instances or several worlds: no arbitrary assignment.
    if len(known) > 1 or any(WORLD.fullmatch(ident) and ident != world for ident in known):
        conflicts.append({"root": "kubejs-assistant", "path": "filons*", "mondes": sorted(known)})
    return mappings, historical, conflicts, summaries


def _copy_files(source, target, entries):
    target.mkdir(mode=0o700)
    for entry in entries:
        path = target / entry["path"]
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        raw = _bytes(source, entry["path"])
        if len(raw) != entry["size"] or _sha(raw) != entry["sha256"]:
            raise MigrationError("Copie non conforme à l'archive.")
        backup._write_new(path, raw)


def _check_copy(root, entries, *, directories=False):
    found = backup._scan(backup._directory(root))
    expected = {e["path"]: e for e in entries}
    actual_files = {name for name, info in found.items() if stat.S_ISREG(info.st_mode)}
    expected_files = {name for name, e in expected.items() if e["kind"] == "file"}
    if actual_files != expected_files:
        raise MigrationError("Nombre ou chemins des fichiers copiés différents.")
    expected_directories = (
        {name for name, e in expected.items() if e["kind"] == "directory"}
        if directories
        else {
            str(parent)
            for name in expected_files
            for parent in Path(name).parents
            if str(parent) != "."
        }
    )
    actual_directories = {name for name, info in found.items() if stat.S_ISDIR(info.st_mode)}
    if actual_directories != expected_directories:
        raise MigrationError("Dossiers restaurés différents de l'archive.")
    for name in expected_files:
        record = expected[name]
        if backup._read_file(root, name, found[name]) != (record["size"], record["sha256"]):
            raise MigrationError("Empreinte de la copie différente de l'original.")


def _verify_restore(preparation, manifest):
    for root in backup.ROOTS:
        _check_copy(
            preparation / "restauration" / root,
            [e for e in manifest["entries"] if e["root"] == root],
            directories=True,
        )
    return {
        "v": 1,
        "revision": _sha(_bytes(preparation / "archive", "manifest.json")),
        "fichiers": sum(e["kind"] == "file" for e in manifest["entries"]),
        "octets": sum(e.get("size", 0) for e in manifest["entries"]),
        "inventaire_sha256": _sha(backup._json(manifest["entries"])),
    }


def _load(preparation):
    preparation = backup._directory(preparation)
    if preparation.name.endswith(".partial"):
        raise MigrationError("Une préparation partielle ne peut pas être utilisée.")
    plan = _read(preparation, "migration.json")
    if plan.get("format") != FORMAT or not WORLD.fullmatch(str(plan.get("monde", ""))):
        raise MigrationError("Format de préparation invalide.")
    roots = _roots(plan["sources"]["python"], plan["sources"]["kubejs-assistant"])
    if _installation(roots) != plan.get("installation"):
        raise MigrationError("Installation différente de la préparation.")
    backup._separate([*roots.values(), preparation])
    manifest = backup.verify(preparation / "archive")
    if manifest["sources"] != plan["sources"] or _sha(
        _bytes(preparation / "archive", "manifest.json")
    ) != plan.get("revision"):
        raise MigrationError("Archive différente de la préparation.")
    receipt = _receipt(preparation / "identite.json", roots)
    if receipt["identite"]["monde"] != plan["monde"]:
        raise MigrationError("Identité différente de la préparation.")
    mappings, historical, conflicts, summaries = _analyse(
        preparation / "archive", manifest, plan["monde"]
    )
    if any(
        plan.get(key) != val
        for key, val in (
            ("copies", mappings),
            ("historique", historical),
            ("contradictions", conflicts),
            ("comptages", summaries),
        )
    ):
        raise MigrationError("Correspondance de migration altérée.")
    for root in backup.ROOTS:
        _check_copy(preparation / "copie" / root, [e for e in mappings if e["root"] == root])
    proof = _verify_restore(preparation, manifest)
    if _read(preparation, "preuve_restauration.json") != proof or _sha(
        backup._json(proof)
    ) != plan.get("preuve_restauration"):
        raise MigrationError("Restauration non vérifiée.")
    return preparation, plan, roots, manifest


def _sources_unchanged(roots, manifest, world, *, partitions=False):
    current, _ = backup.inventory(*roots.values())
    expected = manifest["entries"]
    old = {(e["root"], e["path"]) for e in expected}
    prefix = "mondes/" + world

    def permitted(entry):
        return (
            partitions
            and (entry["root"], entry["path"]) not in old
            and (entry["path"] in ("mondes", prefix) or entry["path"].startswith(prefix + "/"))
        )

    actual = [e for e in current["entries"] if not permitted(e)]

    def comparable(entries):
        return [
            {
                k: v
                for k, v in e.items()
                if not (
                    partitions
                    and e["path"] == "mondes"
                    and e["kind"] == "directory"
                    and k == "mtime_ns"
                )
            }
            for e in entries
        ]

    if comparable(actual) != comparable(expected):
        raise MigrationError("Les données sources ont changé ; nouvelle préparation nécessaire.")


def prepare(
    python_root,
    game_data,
    receipt,
    destination,
    *,
    guard=backup.writers_stopped,
    checkpoint=lambda _: None,
):
    roots = _roots(python_root, game_data)
    destination = backup._destination(destination)
    backup._separate([*roots.values(), destination])
    identity = _receipt(receipt, roots)
    world = identity["identite"]["monde"]
    guard()
    if destination.exists() or destination.is_symlink():
        _, plan, old_roots, manifest = _load(destination)
        if old_roots != roots or plan["monde"] != world:
            raise MigrationError(
                "Préparation existante liée à une autre installation ou un autre monde."
            )
        _sources_unchanged(roots, manifest, world)
        return plan
    latest_path = roots["kubejs-assistant"] / "identite_monde.json"
    if latest_path.exists():
        latest = _read(latest_path.parent, latest_path.name)
        if latest.get("monde") and latest["monde"] != world:
            raise MigrationError("Le serveur a changé depuis l'identification ; refaire le reçu.")
    stage = Path(tempfile.mkdtemp(prefix=".migration-", suffix=".partial", dir=destination.parent))
    backup.backup(*roots.values(), stage / "archive", guard=guard)
    manifest = backup.verify(stage / "archive")
    checkpoint("backed_up")
    backup.restore(stage / "archive", stage / "restauration")
    proof = _verify_restore(stage, manifest)
    backup._write_new(stage / "preuve_restauration.json", backup._json(proof))
    checkpoint("restored")
    mappings, historical, conflicts, summaries = _analyse(stage / "archive", manifest, world)
    (stage / "copie").mkdir(mode=0o700)
    for root in backup.ROOTS:
        _copy_files(
            stage / "archive" / root,
            stage / "copie" / root,
            [e for e in mappings if e["root"] == root],
        )
    plan = {
        "format": FORMAT,
        "v": 1,
        "installation": _installation(roots),
        "sources": {k: str(v) for k, v in roots.items()},
        "monde": world,
        "revision": proof["revision"],
        "preuve_restauration": _sha(backup._json(proof)),
        "copies": mappings,
        "historique": historical,
        "contradictions": conflicts,
        "comptages": summaries,
        "confirmation_requise": True,
    }
    backup._write_new(stage / "identite.json", backup._json(identity))
    backup._write_new(stage / "migration.json", backup._json(plan))
    checkpoint("prepared")
    _sources_unchanged(roots, manifest, world)
    guard()
    backup._sync_directory(stage)
    backup._publish(stage, destination)
    _load(destination)
    return plan


def confirm(preparation, world, *, single_server=False, guard=backup.writers_stopped):
    guard()
    path, plan, roots, manifest = _load(preparation)
    if not single_server or world != plan["monde"]:
        raise MigrationError(
            "Confirme explicitement le serveur unique et son identifiant exact pour cette installation."
        )
    if plan["contradictions"]:
        raise MigrationError(
            "Migration incomplète ou origines contradictoires : consulte les fichiers signalés dans migration.json ; données conservées, activation refusée."
        )
    _sources_unchanged(roots, manifest, world)
    value = {
        "v": 1,
        "monde": world,
        "installation": plan["installation"],
        "revision": plan["revision"],
        "preparation_sha256": _sha(_bytes(path, "migration.json")),
        "serveur_unique_confirme": True,
    }
    _once(path / "confirmation.json", value)
    return value


def _marker(roots):
    path = roots["kubejs-assistant"] / MARKER
    if not path.exists() and not path.is_symlink():
        return None
    marker = _read(path.parent, path.name)
    if (
        marker.get("v") != 1
        or type(marker.get("actif")) is not bool
        or marker.get("installation") != _installation(roots)
        or not WORLD.fullmatch(str(marker.get("monde", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(marker.get("revision", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(marker.get("preuve_restauration", "")))
    ):
        raise MigrationError("Marqueur existant inconnu, conservé sans remplacement.")
    return marker


def activate(preparation, *, guard=backup.writers_stopped, checkpoint=lambda _: None):
    guard()
    path, plan, roots, manifest = _load(preparation)
    confirmation = _read(path, "confirmation.json")
    expected = {
        "v": 1,
        "monde": plan["monde"],
        "installation": plan["installation"],
        "revision": plan["revision"],
        "preparation_sha256": _sha(_bytes(path, "migration.json")),
        "serveur_unique_confirme": True,
    }
    if confirmation != expected or plan["contradictions"]:
        raise MigrationError(
            "Confirmation absente, différente, migration incomplète ou origines contradictoires."
        )
    desired = {
        "v": 1,
        "actif": True,
        "monde": plan["monde"],
        "installation": plan["installation"],
        "revision": plan["revision"],
        "preuve_restauration": plan["preuve_restauration"],
    }
    marker = _marker(roots)
    if marker == desired:
        return {**desired, "deja_active": True}
    if marker is not None:
        raise MigrationError(
            "Marqueur déjà présent : conserve les partitions ; aucune réactivation automatique d'une ancienne copie."
        )
    _sources_unchanged(roots, manifest, plan["monde"], partitions=True)
    for root in backup.ROOTS:
        folder = roots[root] / "mondes"
        if folder.exists() or folder.is_symlink():
            backup._directory(folder)
        else:
            folder.mkdir(mode=0o700)
        target = folder / plan["monde"]
        entries = [e for e in plan["copies"] if e["root"] == root]
        if target.exists() or target.is_symlink():
            _check_copy(target, entries)
        else:
            # Stage outside the source root, on its parent's filesystem. No unknown
            # temporary observation is added to the originals; cross-device rename fails safely.
            staged = Path(
                tempfile.mkdtemp(prefix=".migration-", suffix=".partial", dir=roots[root].parent)
            )
            _copy_files(path / "copie" / root, staged / "donnees", entries)
            _check_copy(staged / "donnees", entries)
            backup._sync_directory(staged / "donnees")
            backup._publish(staged / "donnees", target)
        checkpoint(root + "_published")
    # All original bytes remain untouched. The marker alone enables both complete partitions.
    checkpoint("before_activation")
    for root in backup.ROOTS:
        _check_copy(
            roots[root] / "mondes" / plan["monde"], [e for e in plan["copies"] if e["root"] == root]
        )
    _sources_unchanged(roots, manifest, plan["monde"], partitions=True)
    guard()
    _once(roots["kubejs-assistant"] / MARKER, desired)
    return desired


def deactivate(python_root, game_data, *, guard=backup.writers_stopped):
    guard()
    roots = _roots(python_root, game_data)
    marker = _marker(roots)
    if marker is None:
        return {"v": 1, "actif": False, "deja_inactif": True}
    if marker["actif"]:
        marker = {**marker, "actif": False}
        atomic_json(roots["kubejs-assistant"] / MARKER, marker)
    return marker
