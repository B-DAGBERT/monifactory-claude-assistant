"""Opt-in, byte-preserving backups. No world assignment, activation, or in-place restore."""

import ctypes
import fnmatch
import hashlib
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath

from monifactory_assistant.commands.auto_update import minecraft_running

FORMAT = "monifactory-data-backup-v1"
ROOTS = ("python", "kubejs-assistant")
# Keep opaque/old formats and recovery copies too; never deserialize user files.
PYTHON_PATTERNS = (
    "*.json",
    "*.jsonl",
    "*.json.*",
    "*.jsonl.*",
    ".*.json-*.tmp",
    "assistant.log*",
)
PYTHON_DIRECTORIES = ("backups", "captures", ".runtime")


class BackupError(RuntimeError):
    pass


def _stamp(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def _not_link(path, info):
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise BackupError(f"Lien ou jonction refusé : {path}")
    if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
        raise BackupError(f"Fichier spécial refusé : {path}")


def _directory(path):
    path = Path(os.path.abspath(path))
    for ancestor in (*reversed(path.parents), path):
        info = ancestor.lstat()
        _not_link(ancestor, info)
        if not stat.S_ISDIR(info.st_mode):
            raise BackupError(f"Dossier attendu : {ancestor}")
    return path


def _separate(paths):
    for i, a in enumerate(paths):
        for b in paths[i + 1 :]:
            if a == b or a in b.parents or b in a.parents:
                raise BackupError("Les sources et destinations doivent être disjointes.")


def _destination(path):
    path = Path(os.path.abspath(path))
    _directory(path.parent)
    if path.name.endswith(".partial"):
        raise BackupError("Le suffixe .partial est réservé aux opérations interrompues.")
    return path


def _selected(name):
    return name in PYTHON_DIRECTORIES or any(fnmatch.fnmatchcase(name, p) for p in PYTHON_PATTERNS)


def _scan(root, selected=False):
    """Inventory structure without following links, including empty directories."""
    result = {}

    def walk(folder, prefix=""):
        before = folder.lstat()
        _not_link(folder, before)
        for child in sorted(folder.iterdir()):
            if not prefix and selected and not _selected(child.name):
                continue
            rel = prefix + child.name
            info = child.lstat()
            _not_link(child, info)
            result[rel] = info
            if stat.S_ISDIR(info.st_mode):
                walk(child, rel + "/")
        if _stamp(before) != _stamp(folder.lstat()):
            raise BackupError("Dossier modifié pendant l'inventaire ; recommence après arrêt.")

    walk(root)
    return result


def _open_regular(root, relative, expected):
    """On POSIX each component is opened with O_NOFOLLOW, including directories."""
    parts = PurePosixPath(relative).parts
    if os.open in os.supports_dir_fd and hasattr(os, "O_NOFOLLOW"):
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for name in parts[:-1]:
                child = os.open(
                    name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory
                )
                os.close(directory)
                directory = child
            handle = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
        finally:
            os.close(directory)
    else:
        # Windows: reject reparse points at every component, then verify the opened file.
        _directory((root / relative).parent)
        info = (root / relative).lstat()
        _not_link(root / relative, info)
        handle = os.open(root / relative, os.O_RDONLY | getattr(os, "O_BINARY", 0))
    stream = os.fdopen(handle, "rb")
    if _stamp(os.fstat(stream.fileno())) != _stamp(expected):
        stream.close()
        raise BackupError("Un fichier a été remplacé pendant sa lecture.")
    return stream


def _read_file(root, relative, expected, output=None):
    digest = hashlib.sha256()
    size = 0
    with _open_regular(root, relative, expected) as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
            size += len(block)
            if output is not None:
                output.write(block)
        if _stamp(expected) != _stamp(os.fstat(stream.fileno())):
            raise BackupError("Un fichier a changé pendant sa lecture.")
    if _stamp(expected) != _stamp((root / relative).lstat()):
        raise BackupError("Un fichier a changé après sa lecture.")
    return size, digest.hexdigest()


def inventory(python_root, game_data):
    """Read-only inventory: only names/metadata/hashes, never personal content in the report."""
    roots = dict(zip(ROOTS, (_directory(python_root), _directory(game_data))))
    _separate(list(roots.values()))
    records = []
    stamps = {}
    for label, root in roots.items():
        entries = _scan(root, label == "python")
        stamps[label] = {name: _stamp(info) for name, info in entries.items()}
        for name, info in entries.items():
            record = {
                "root": label,
                "path": name,
                "mode": stat.S_IMODE(info.st_mode),
                "mtime_ns": info.st_mtime_ns,
                "kind": "directory" if stat.S_ISDIR(info.st_mode) else "file",
            }
            if record["kind"] == "file":
                record["size"], record["sha256"] = _read_file(root, name, info)
            records.append(record)
        if stamps[label] != {
            name: _stamp(info) for name, info in _scan(root, label == "python").items()
        }:
            raise BackupError("Sources modifiées pendant l'inventaire.")
    return {
        "format": FORMAT,
        "sources": {k: str(v) for k, v in roots.items()},
        "entries": records,
    }, stamps


def _json(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )


def _write_new(path, raw):
    with path.open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def _sync_directory(path):
    if os.name == "nt":
        return  # No portable directory fsync; real Windows qualification remains required.
    handle = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(handle)
    finally:
        os.close(handle)


def _publish(stage, target):
    """Atomic rename with no replacement, including an empty target created by a competitor."""
    if os.name == "nt":
        os.rename(stage, target)  # Windows rename refuses an existing destination.
    else:
        libc = ctypes.CDLL(None, use_errno=True)
        if sys.platform == "darwin":
            rename = libc.renamex_np
            rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
            args = (os.fsencode(stage), os.fsencode(target), 4)  # RENAME_EXCL
        elif hasattr(libc, "renameat2"):
            rename = libc.renameat2
            rename.argtypes = [
                ctypes.c_int,
                ctypes.c_char_p,
                ctypes.c_int,
                ctypes.c_char_p,
                ctypes.c_uint,
            ]
            args = (-100, os.fsencode(stage), -100, os.fsencode(target), 1)  # RENAME_NOREPLACE
        else:
            raise BackupError("Renommage atomique sans remplacement indisponible sur ce système.")
        rename.restype = ctypes.c_int
        if rename(*args) != 0:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), str(target))
    _sync_directory(target.parent)


def _relative(value):
    if not isinstance(value, str) or not value or "\\" in value or ":" in value or "\x00" in value:
        raise BackupError("Chemin d'archive invalide.")
    path = PurePosixPath(value)
    if path.is_absolute() or any(p in ("", ".", "..") for p in value.split("/")):
        raise BackupError("Chemin d'archive invalide.")
    return value


def _manifest(archive, *, staged=False):
    archive = _directory(archive)
    if not staged and archive.name.endswith(".partial"):
        raise BackupError("Une archive partielle n'est jamais utilisable.")
    for name in ("manifest.json", "complete.json"):
        info = (archive / name).lstat()
        _not_link(archive / name, info)
        if not stat.S_ISREG(info.st_mode) or info.st_size > 32 * 1024 * 1024:
            raise BackupError("Manifeste invalide.")

    def read_metadata(name):
        stream = io.BytesIO()
        _read_file(archive, name, (archive / name).lstat(), stream)
        return stream.getvalue()

    raw = read_metadata("manifest.json")
    complete = json.loads(read_metadata("complete.json"))
    if complete != {"format": FORMAT, "manifest_sha256": hashlib.sha256(raw).hexdigest()}:
        raise BackupError("Empreinte du manifeste invalide ou archive incomplète.")
    data = json.loads(raw)
    if (
        not isinstance(data, dict)
        or data.get("format") != FORMAT
        or not isinstance(data.get("entries"), list)
    ):
        raise BackupError("Format d'archive inconnu.")
    seen = set()
    for entry in data["entries"]:
        if not isinstance(entry, dict) or entry.get("root") not in ROOTS:
            raise BackupError("Racine d'archive invalide.")
        name = _relative(entry.get("path"))
        key = entry["root"], name
        if key in seen or entry.get("kind") not in ("file", "directory"):
            raise BackupError("Entrée dupliquée ou invalide.")
        seen.add(key)
        if type(entry.get("mode")) is not int or not 0 <= entry["mode"] <= 0o7777:
            raise BackupError("Permissions d'archive invalides.")
        if type(entry.get("mtime_ns")) is not int:
            raise BackupError("Date d'archive invalide.")
        if entry["kind"] == "file" and (
            type(entry.get("size")) is not int
            or entry["size"] < 0
            or not isinstance(entry.get("sha256"), str)
            or len(entry["sha256"]) != 64
            or any(c not in "0123456789abcdef" for c in entry["sha256"])
        ):
            raise BackupError("Taille ou empreinte invalide.")
    for root, name in seen:
        for parent in PurePosixPath(name).parents:
            if str(parent) != "." and (root, str(parent)) not in seen:
                raise BackupError("Répertoire parent absent du manifeste.")
    return data


def verify(archive, *, staged=False):
    """Read all archived bytes and reject extra, missing, replaced or linked files."""
    archive = _directory(archive)
    data = _manifest(archive, staged=staged)
    if {p.name for p in archive.iterdir()} != {"manifest.json", "complete.json", *ROOTS}:
        raise BackupError("Contenu inattendu à la racine de l'archive.")
    for label in ROOTS:
        root = _directory(archive / label)
        expected = {e["path"]: e for e in data["entries"] if e["root"] == label}
        found = _scan(root)
        if set(expected) != set(found):
            raise BackupError("Fichiers manquants ou supplémentaires dans l'archive.")
        for name, info in found.items():
            entry = expected[name]
            directory = stat.S_ISDIR(info.st_mode)
            if directory != (entry["kind"] == "directory"):
                raise BackupError("Type de fichier différent du manifeste.")
            if not directory and _read_file(root, name, info) != (entry["size"], entry["sha256"]):
                raise BackupError("Contenu différent de l'empreinte du manifeste.")
    return data


def _assistant_running():
    """Conservative process check; no command line is printed or stored in backups."""
    try:
        if os.name == "nt":
            script = (
                "$p=Get-CimInstance Win32_Process -ErrorAction Stop | "
                "Where-Object {$_.Name -match '^python(w|[0-9.]*)?\\.exe$'}; "
                "$found=@($p | Where-Object {$_.CommandLine -match "
                "'(?i)assistant_mc\\.py|monifactory_assistant\\.app'}).Count -gt 0; "
                "if($found){'running'}elseif(@($p | Where-Object {-not $_.CommandLine}).Count -gt 0){'unknown'}else{'closed'}"
            )
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True,
                text=True,
                timeout=8,
                check=True,
            ).stdout.strip()
            return True if out == "running" else False if out == "closed" else None
        result = subprocess.run(
            ["ps", "-axo", "pid=,args="], capture_output=True, text=True, timeout=5, check=True
        )
        for line in result.stdout.splitlines():
            fields = line.strip().split(None, 1)
            if len(fields) != 2 or fields[0] == str(os.getpid()):
                continue
            command = fields[1].lower()
            if "assistant_mc.py" in command or "-m monifactory_assistant.app" in command:
                executable = subprocess.run(
                    ["ps", "-p", fields[0], "-o", "comm="],
                    capture_output=True,
                    text=True,
                    timeout=3,
                    check=True,
                ).stdout.strip()
                if "python" in Path(executable).name.lower():
                    return True
        return False
    except (OSError, subprocess.SubprocessError):
        return None


def writers_stopped():
    if minecraft_running() is not False or _assistant_running() is not False:
        raise BackupError("Ferme Minecraft et l'assistant ; leur arrêt doit pouvoir être vérifié.")


def backup(
    python_root, game_data, destination, *, guard=writers_stopped, checkpoint=lambda _: None
):
    """Publish only after two full source inventories and an independent archive verification."""
    target = _destination(destination)
    roots = (_directory(python_root), _directory(game_data))
    _separate([*roots, target])
    guard()
    data, stamps = inventory(*roots)
    checkpoint("inventoried")
    if target.exists() or target.is_symlink():
        if verify(target) != data:
            raise BackupError("Destination existante différente ; aucun écrasement.")
        if inventory(*roots) != (data, stamps):
            raise BackupError("Sources modifiées pendant la vérification.")
        guard()
        return {"archive": str(target), "reused": True, "entries": len(data["entries"])}
    stage = Path(
        tempfile.mkdtemp(prefix="." + target.name + "-", suffix=".partial", dir=target.parent)
    )
    for label in ROOTS:
        (stage / label).mkdir(mode=0o700)
    for entry in data["entries"]:
        source = roots[ROOTS.index(entry["root"])]
        path = stage / entry["root"] / entry["path"]
        if entry["kind"] == "directory":
            path.mkdir(mode=0o700)
        else:
            info = (source / entry["path"]).lstat()
            if _stamp(info) != stamps[entry["root"]][entry["path"]]:
                raise BackupError("Source modifiée avant sa copie.")
            with path.open("xb") as output:
                os.chmod(path, 0o600)
                actual = _read_file(source, entry["path"], info, output)
                output.flush()
                os.fsync(output.fileno())
            if actual != (entry["size"], entry["sha256"]):
                raise BackupError("Copie différente de l'inventaire.")
        checkpoint("copied")
    raw = _json(data)
    _write_new(stage / "manifest.json", raw)
    _write_new(
        stage / "complete.json",
        _json({"format": FORMAT, "manifest_sha256": hashlib.sha256(raw).hexdigest()}),
    )
    verify(stage, staged=True)
    checkpoint("verified")
    checkpoint("before_publish")
    if inventory(*roots) != (data, stamps):
        raise BackupError("Écrivain ou modification concurrente détecté ; archive non publiée.")
    guard()
    for path in sorted(
        (p for p in stage.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True
    ):
        _sync_directory(path)
    _sync_directory(stage)
    _publish(stage, target)
    return {"archive": str(target), "reused": False, "entries": len(data["entries"])}


def restore(archive, destination, *, checkpoint=lambda _: None):
    """Restore two NEW roots together under one NEW parent; never restore over a live tree."""
    archive, target = _directory(archive), _destination(destination)
    _separate([archive, target])
    if target.exists() or target.is_symlink():
        raise BackupError("La destination de restauration doit être entièrement nouvelle.")
    data = verify(archive)
    # A sensitive archive can contain A/a: refuse before writing on case-insensitive targets.
    names = [(e["root"], e["path"].casefold()) for e in data["entries"]]
    if len(names) != len(set(names)):
        raise BackupError("Noms incompatibles avec une restauration portable (casse).")
    stage = Path(
        tempfile.mkdtemp(prefix="." + target.name + "-", suffix=".partial", dir=target.parent)
    )
    for label in ROOTS:
        (stage / label).mkdir(mode=0o700)
    for entry in data["entries"]:
        source = archive / entry["root"]
        output = stage / entry["root"] / entry["path"]
        if entry["kind"] == "directory":
            output.mkdir(mode=0o700)
        else:
            info = (source / entry["path"]).lstat()
            with output.open("xb") as stream:
                actual = _read_file(source, entry["path"], info, stream)
                stream.flush()
                os.fsync(stream.fileno())
            if actual != (entry["size"], entry["sha256"]):
                raise BackupError("Archive modifiée pendant la restauration.")
        checkpoint("copied")
    # Re-read restored bytes before applying original permissions (which can be read-only).
    for entry in data["entries"]:
        path = stage / entry["root"] / entry["path"]
        if entry["kind"] == "file":
            if _read_file(stage / entry["root"], entry["path"], path.lstat()) != (
                entry["size"],
                entry["sha256"],
            ):
                raise BackupError("Restauration non conforme.")
    if verify(archive) != data:
        raise BackupError("Manifeste modifié pendant la restauration.")
    checkpoint("verified")
    for entry in sorted(
        data["entries"], key=lambda e: len(PurePosixPath(e["path"]).parts), reverse=True
    ):
        path = stage / entry["root"] / entry["path"]
        os.chmod(
            path, entry["mode"] & 0o777
        )  # Never restore setuid/setgid/sticky execution privileges.
        os.utime(path, ns=(entry["mtime_ns"], entry["mtime_ns"]))
        if entry["kind"] == "directory":
            _sync_directory(path)
    for label in ROOTS:
        _sync_directory(stage / label)
    _sync_directory(stage)
    checkpoint("before_publish")
    _publish(stage, target)
    return {
        "restored": str(target),
        "roots": {k: str(target / k) for k in ROOTS},
        "entries": len(data["entries"]),
    }
