"""Local identity shared by the optional per-world storage and client bridge."""

import hashlib
import json
import os
import re
import stat
import time
from dataclasses import dataclass

from monifactory_assistant.game.snapshots import file_stamp

ACTIVATION = "isolation_mondes.json"
IDENTITY = "identite_monde.json"
IDENTITY_MAX_AGE_MS = 4000
_WORLD_ID = re.compile(r"m1_[0-9a-f]{64}")
_SESSION_ID = re.compile(r"[0-9a-f]{32}")
_DIGEST = re.compile(r"[0-9a-f]{64}")
_DIMENSION = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


@dataclass(frozen=True)
class WorldScope:
    world: str
    session: str
    dimension: str

    def fields(self):
        return {
            "monde_actif": self.world,
            "session_monde": self.session,
            "dimension_monde": self.dimension,
        }

    def accepts(self, data):
        return isinstance(data, dict) and all(data.get(k) == v for k, v in self.fields().items())


def installation_id(python_root, game_data):
    roots = {
        "python": os.path.abspath(python_root),
        "kubejs-assistant": os.path.abspath(game_data),
    }
    value = json.dumps(roots, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def read_activation(directory, python_root):
    """The activation marker is changed only while both local writers are stopped."""
    path = directory / ACTIVATION
    try:
        info = path.lstat()
    except FileNotFoundError:
        return "legacy"
    except OSError:
        return "blocked"
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_size > 8192
        or getattr(info, "st_file_attributes", 0) & 0x400
    ):
        return "blocked"
    try:
        marker = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return "blocked"
    if isinstance(marker, dict) and type(marker.get("v")) is int and marker["v"] == 1:
        if marker.get("actif") is False:
            return "legacy"
        if (
            marker.get("actif") is True
            and matches(_WORLD_ID, marker.get("monde"))
            and matches(_DIGEST, marker.get("revision"))
            and matches(_DIGEST, marker.get("preuve_restauration"))
            and marker.get("installation") == installation_id(python_root, directory)
        ):
            return "isolated"
    return "blocked"


def scope_folder(root, world, *, create=False):
    """A validated opaque identifier and two ordinary directories form the partition path."""
    if not matches(_WORLD_ID, world):
        raise ValueError("Identité de monde invalide.")
    parent = root / "mondes"
    folder = parent / world
    for component in (parent, folder):
        try:
            info = component.lstat()
        except FileNotFoundError:
            if not create:
                continue
            component.mkdir(exist_ok=True)
            info = component.lstat()
        if not stat.S_ISDIR(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("Dossier de monde invalide ou lié.")
    return folder


def scope_file(folder, name):
    path = folder / name
    try:
        info = path.lstat()
    except FileNotFoundError:
        return path
    if not stat.S_ISREG(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("Fichier de monde invalide ou lié.")
    return path


class WorldRouting:
    def __init__(self, directory, python_root):
        self.directory = directory
        self.mode = read_activation(directory, python_root)
        self._identity = (None, None)

    @property
    def enabled(self):
        return self.mode != "legacy"

    def current(self, now_ms=None):
        if self.mode != "isolated":
            return None
        try:
            path = scope_file(self.directory, IDENTITY)
            stamp = file_stamp(path)
            if stamp is None or stamp[2] > 8192:
                return None
            cached_stamp, data = self._identity
            if stamp != cached_stamp:
                data = json.loads(path.read_text(encoding="utf-8"))
                if file_stamp(path) != stamp:
                    return None
                self._identity = stamp, data
        except (OSError, ValueError):
            return None
        if not isinstance(data, dict) or type(data.get("v")) is not int or data["v"] != 1:
            return None
        now_ms = time.time() * 1000 if now_ms is None else now_ms
        t = data.get("t")
        if type(t) not in (int, float) or not 0 <= now_ms - t < IDENTITY_MAX_AGE_MS:
            return None
        if (
            matches(_WORLD_ID, data.get("monde"))
            and matches(_SESSION_ID, data.get("session"))
            and matches(_DIMENSION, data.get("dimension"))
        ):
            return WorldScope(data["monde"], data["session"], data["dimension"])
        return None
