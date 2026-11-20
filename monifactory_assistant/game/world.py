"""Mémoire du monde et description des états des machines."""

import re
import time
from concurrent.futures import Future
from datetime import datetime
from enum import Enum, auto
from threading import Lock, Thread
from time import monotonic

from monifactory_assistant.game.snapshots import file_stamp
from monifactory_assistant.game.storage import Storage, distinct_containers
from monifactory_assistant.game.world_scope import scope_file, scope_folder
from monifactory_assistant.paths import WORLD_FILE
from monifactory_assistant.persistence import atomic_json, load_json

OBSERVATION_MAX_AGE_MS = 15_000
# Cadence des sauvegardes déclenchées par les trames. Production relit le fichier comme historique.
MEMORY_SAVE_INTERVAL_MS = 30_000
MEMORY_RETRY_INTERVAL = 5
# Cadence de renouvellement du candidat pour les dates et niveaux seuls, au fil des trames.
DATES_PUBLISH_INTERVAL_MS = 60_000
# État de machine que voit le joueur : statut, multibloc formé, chaudière à sec. Les niveaux
# envoyés par etatMachine (pr, du, r, eau, t, vap) bougent à chaque trame, comme les dates.
VISIBLE_STATE = ("s", "f", "se")
MAX_INTEGER = 2**53 - 1


class SaveResult(Enum):
    CLEAN = auto()
    SAVED = auto()
    FAILED = auto()
    PENDING = auto()  # Écriture de reprise déjà en cours.


def block_name(block_id):
    """« gtceu:lp_steam_alloy_smelter » -> « Lp Steam Alloy Smelter » (le serveur n'envoie que les ids)."""
    return block_id.split(":")[-1].replace("_", " ").title()


def machine_label(machine_id):
    """« gtceu:lp_steam_compressor » -> « LP Steam Compressor »."""
    words = block_name(machine_id).split(" ")
    return " ".join(
        w.upper() if w.lower() in ("lp", "hp", "lv", "mv", "hv", "ev", "iv") else w for w in words
    )


def _integers(values, length):
    return (
        isinstance(values, list)
        and len(values) == length
        and all(
            type(v) in (int, float) and -MAX_INTEGER <= v <= MAX_INTEGER and int(v) == v
            for v in values
        )
    )


def _block_observation(block, dimension, zone, date):
    """Un bloc mal formé rend les absences de sa trame ambiguës."""
    if not isinstance(block, dict) or not _integers(block.get("p"), 3):
        return None
    x, y, z = map(int, block["p"])
    cx0, cz0, cx1, cz1 = zone
    if not (cx0 <= x // 16 <= cx1 and cz0 <= z // 16 <= cz1):
        return None
    block_id, state = block.get("b"), block.get("m")
    items = block.get("o", [])
    if not isinstance(block_id, str) or not block_id or not isinstance(items, list):
        return None
    if state is not None:
        if not isinstance(state, dict):
            return None
        if any(key in state and not _integers(state[key], 2) for key in ("eau", "t", "vap")):
            return None
        if any(key in state and not _integers([state[key]], 1) for key in ("pr", "du")):
            return None
        if state.get("s") == "WORKING" and state.get("du") and "pr" not in state:
            return None
    counts = []
    for entry in items:
        if not isinstance(entry, list) or len(entry) != 2:
            return None
        item, n = entry
        if not isinstance(item, str) or not item or not _integers([n], 1) or n < 0:
            return None
        if n:
            counts.append({"item": item, "n": int(n)})
    key = f"{dimension}|{x}|{y}|{z}"
    return {
        "cle": key,
        "bloc": block_id,
        "nom": block_name(block_id),
        "dimension": dimension,
        "x": x,
        "y": y,
        "z": z,
        "t": date,
        "v": 2,
        "objets": counts,
        "etat": state,
    }


def _visible_state(state):
    """Statut, alertes et « en marche » de machine_status, sans les niveaux ; None sans machine."""
    if not isinstance(state, dict):
        return state
    return (
        *(state.get(key) for key in VISIBLE_STATE),
        state.get("s") == "WORKING" and bool(state.get("du")),
    )


def _same_content(old, new):
    """Même bloc, même contenu : seuls la date et les niveaux de la machine peuvent différer."""
    return (
        isinstance(old, dict)
        and old.keys() == new.keys()
        and all(old[key] == new[key] for key in new if key not in ("t", "etat"))
        and _visible_state(old["etat"]) == _visible_state(new["etat"])
    )


class World:
    """Relevés serveur récents et mémoire persistante des blocs précédemment observés.

    `blocks` porte les dates et niveaux de machine de la dernière trame : les consommateurs en
    direct le lisent. `publishable_blocks` est l'état à publier : un changement de contenu y entre
    aussitôt, des dates et niveaux seuls au plus toutes les DATES_PUBLISH_INTERVAL_MS.
    `saved_blocks` est le contenu du fichier : au plus un enregistrement toutes les
    MEMORY_SAVE_INTERVAL_MS, puis à la sortie du direct et à l'arrêt de l'assistant.
    """

    # Monde créé sans __init__ : rien n'est encore enregistré ni publié.
    saved_blocks = publishable_blocks = saved_t = None
    publishable_t = 0
    unsaved_content = False  # un contenu attend le fichier : son enregistrement refait la .bak
    _retry = _retry_at = None
    _flush_after_retry = False
    _memory_t = 0
    _save_lock = partition_root = None

    def __init__(self, path=None, *, enabled=True, partition_root=None, pending=None):
        self.memory_file = (path or WORLD_FILE) if enabled else None
        self.partition_root = partition_root
        if pending is None:
            # Une reprise d'une autre partition ne doit pas bloquer les écritures du direct.
            self._save_lock = Lock()
            saved = load_json(self.memory_file, {}) if self.memory_file is not None else {}
            self.blocks = saved.get("blocs", {})  # clé "dimension|x|y|z" -> bloc vu par le serveur
            self.saved_blocks = self.blocks
        else:
            # Transférer la mémoire et l'écriture en cours, sans reprendre le contexte du direct.
            if pending.memory_file != self.memory_file:
                raise ValueError("La mémoire en attente appartient à une autre partition.")
            self._save_lock = pending._save_lock
            self.blocks, self.saved_blocks = pending.blocks, pending.saved_blocks
            self._memory_t, self.saved_t = pending._memory_t, pending.saved_t
            self.unsaved_content = pending.unsaved_content
            self._retry, self._retry_at = pending._retry, pending._retry_at
            self._flush_after_retry = pending._flush_after_retry
        self.publishable_blocks = self.blocks
        self.players = []
        self.errors = []
        self.last_t = 0  # heure de la dernière vue du serveur (ms)
        self.live_dimension = None
        self.last_source_t = None
        self.snapshot_stamp = None

    @property
    def active(self):
        return (
            self.live_dimension is not None
            and 0 <= time.time() * 1000 - self.last_t < OBSERVATION_MAX_AGE_MS
        )

    def invalidate_live(self):
        """Une sortie du monde clôt le direct, avec conservation du dernier relevé."""
        self.live_dimension = None
        return self.flush()

    def flush(self):
        """Enregistre la mémoire en attente et indique si elle reste à sauvegarder."""
        if not self.save_pending():
            return SaveResult.CLEAN
        if self._retry is not None:
            self._flush_after_retry = True
            return SaveResult.PENDING
        return self._save(self.blocks, self._memory_t)

    def _save(self, blocks, date):
        if getattr(self, "memory_file", WORLD_FILE) is None:
            self.saved_blocks, self.saved_t, self.unsaved_content = blocks, date, False
            return SaveResult.CLEAN
        backup = self.unsaved_content
        result = self._write_memory(blocks, backup)
        self.unsaved_content = False
        return self._record_save(result, blocks, date, backup)

    def _write_memory(self, blocks, backup):
        path = getattr(self, "memory_file", WORLD_FILE)
        try:
            if self.partition_root is not None:
                folder = scope_folder(self.partition_root, path.parent.name)
                if scope_file(folder, path.name) != path:
                    return SaveResult.FAILED
                scope_file(folder, path.name + ".bak")
            atomic_json(path, {"blocs": blocks}, backup=backup, compact=True, lock=self._save_lock)
        except (OSError, ValueError):
            return SaveResult.FAILED
        return SaveResult.SAVED

    def _record_save(self, result, blocks, date, backup):
        if result is SaveResult.SAVED:
            self.saved_blocks, self.saved_t = blocks, date
            self._retry_at = (
                monotonic() if self._flush_after_retry and self.blocks is not blocks else None
            )
        else:
            self.unsaved_content |= backup
            self._retry_at = monotonic() + MEMORY_RETRY_INTERVAL
        self._flush_after_retry = False
        return result

    def _finish_retry(self):
        if self._retry is not None:
            future, blocks, date, backup = self._retry
            if future.done():
                self._retry = None
                result = SaveResult.FAILED
                try:
                    result = future.result()
                finally:
                    self._record_save(result, blocks, date, backup)

    def save_pending(self):
        self._finish_retry()
        return (
            getattr(self, "memory_file", WORLD_FILE) is not None
            and self.blocks is not self.saved_blocks
        )

    def retry_due(self):
        return (
            self.save_pending()
            and self._retry is None
            and self._retry_at is not None
            and monotonic() >= self._retry_at
        )

    def flush_in_background(self):
        """Fige le relevé à écrire. Seul le propriétaire courant applique le résultat."""
        if not self.save_pending():
            return None
        if self._retry is not None:
            return self._retry[0]
        future = Future()
        blocks, backup = self.blocks, self.unsaved_content

        def write():
            try:
                future.set_result(self._write_memory(blocks, backup))
            except Exception as error:
                future.set_exception(error)

        Thread(target=write, name="monde-memoire", daemon=True).start()
        self._retry = (future, blocks, self._memory_t, backup)
        self.unsaved_content = False
        return future

    def observation_live(self, block):
        return (
            self.active
            and block.get("dimension") == self.live_dimension
            and block.get("t") == self.last_t
        )

    def poll(self, bridge, dimension=None):
        path = bridge.dir / "monde.json"
        try:
            stamp = file_stamp(path)
            if stamp is None or stamp == self.snapshot_stamp:
                return False
            # KubeJS écrit localement à la réception. Utiliser cette horloge commune
            # aux observations de coffres client et serveur.
            received = stamp[1] // 1_000_000
            if not 0 <= time.time() * 1000 - received < OBSERVATION_MAX_AGE_MS:
                return False
            snapshot = bridge.read("monde.json")
            if not isinstance(snapshot, dict) or file_stamp(path) != stamp:
                return False
            if dimension is not None and snapshot.get("dimension") != dimension:
                return False
        except OSError:
            return False
        changed = self.update(snapshot, received_ms=received)
        self.snapshot_stamp = stamp
        return changed

    def update(self, snapshot, *, received_ms=None):
        """Valide toute la trame avant de modifier la mémoire ou sa fraîcheur."""
        if not isinstance(snapshot, dict):
            return False
        source_time = snapshot.get("t")
        date = source_time if received_ms is None else received_ms
        if not _integers([source_time, date], 2) or source_time < 0 or date <= self.last_t:
            return False
        dimension, zone, blocks = (
            snapshot.get("dimension"),
            snapshot.get("zone"),
            snapshot.get("blocs"),
        )
        if (
            isinstance(dimension, str)
            and getattr(self, "live_dimension", None) == dimension
            and date - self.last_t < OBSERVATION_MAX_AGE_MS
            and source_time <= self.last_source_t
        ):
            return False
        if (
            not isinstance(dimension, str)
            or not dimension
            or not _integers(zone, 4)
            or not isinstance(blocks, list)
        ):
            return False
        cx0, cz0, cx1, cz1 = zone
        if cx0 > cx1 or cz0 > cz1:
            return False
        seen = {}
        for block in blocks:
            observation = _block_observation(block, dimension, zone, date)
            if observation is None or observation["cle"] in seen:
                return False
            seen[observation["cle"]] = observation
        # Une omission ne prouve une absence que dans un chunk entièrement transmis.
        coverage = snapshot.get("chunks_complets")
        complete = (
            {
                tuple(chunk)
                for chunk in coverage
                if _integers(chunk, 2) and cx0 <= chunk[0] <= cx1 and cz0 <= chunk[1] <= cz1
            }
            if isinstance(coverage, list)
            else set()
        )
        updated = dict(self.blocks)
        content = volatile = False  # volatile : dates ou niveaux de machine seulement
        if complete:
            for key, b in self.blocks.items():
                if (
                    b["dimension"] == dimension
                    and (b["x"] // 16, b["z"] // 16) in complete
                    and key not in seen
                ):
                    if b.get("absent") is True and b.get("objets") == [] and b.get("etat") is None:
                        continue  # Absence déjà relevée : garder sa première date.
                    updated[key] = {**b, "t": date, "objets": [], "etat": None, "absent": True}
                    content = True
        for key, observation in seen.items():
            previous = self.blocks.get(key)
            if previous is None or not _same_content(previous, observation):
                content = True
            elif previous != observation:
                volatile = True
        updated.update(seen)
        memory = updated if content or volatile else self.blocks
        if content:
            # Publié aussitôt ; le prochain enregistrement refera la copie de récupération.
            self.unsaved_content = True
            self.publishable_blocks, self.publishable_t = memory, date
        elif (
            memory is not self.publishable_blocks
            and date - self.publishable_t >= DATES_PUBLISH_INTERVAL_MS
        ):
            self.publishable_blocks, self.publishable_t = memory, date
        self.blocks = memory
        self._memory_t = date
        self._finish_retry()
        if (
            memory is not self.saved_blocks
            and self._retry is None
            and (self.saved_t is None or date - self.saved_t >= MEMORY_SAVE_INTERVAL_MS)
        ):
            self._save(memory, date)
        players, errors = snapshot.get("joueurs"), snapshot.get("erreurs")
        self.players = (
            [
                p
                for p in players
                if isinstance(p, dict)
                and isinstance(p.get("nom"), str)
                and isinstance(p.get("dimension"), str)
                and _integers([p.get("x"), p.get("y"), p.get("z")], 3)
            ]
            if isinstance(players, list)
            else []
        )
        self.errors = [e for e in errors if isinstance(e, str)] if isinstance(errors, list) else []
        self.last_t = date
        self.last_source_t = source_time
        self.live_dimension = dimension
        return True

    def containers(self):
        """Ne regrouper que les doubles coffres connus, jamais les stockages indépendants."""
        return distinct_containers(
            b for b in self.blocks.values() if b["objets"] and b.get("etat") is None
        )

    def machines(self):
        return [b for b in self.blocks.values() if b.get("etat") is not None]

    @staticmethod
    def _date(observation):
        value = observation.get("t", 0)
        return (
            value
            if isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value < 1e16
            else 0
        )

    @classmethod
    def _fusion(cls, serveur, client):
        observations = {}
        for c in [*serveur, *client]:
            key = (c.get("dimension"), c.get("x"), c.get("y"), c.get("z"))
            ancien = observations.get(key)
            # À date identique, conserver le relevé serveur ; sinon le plus récent gagne.
            if ancien is None or cls._date(c) > cls._date(ancien):
                observations[key] = c
        return list(observations.values())

    def merged(self, storage):
        """Même coffre physique : le relevé complet le plus récent, y compris vide, gagne."""
        observations = self._fusion(self.blocks.values(), [*storage.raw, *storage.machines])
        raw = [b for b in observations if b.get("etat") is None]
        machines = [b for b in observations if b.get("etat") is not None and not b.get("absent")]
        return Storage([c for c in distinct_containers(raw) if c.get("objets")], raw, machines)


def machine_status(m):
    """État lisible d'une machine vue par le serveur."""
    state = m.get("etat") or {}
    parts = []
    if "eau" in state:  # KubeJS envoie les nombres en décimal (16000.0) : on les remet en entiers
        water, capacity = (int(v) for v in state["eau"])
        temperature, maximum = (int(v) for v in state["t"])
        parts.append(
            f"eau {water}/{capacity} mB, {temperature}/{maximum} °C"
            + (", À SEC" if state.get("se") else "")
        )
    if "vap" in state:
        parts.append(f"steam {int(state['vap'][0])}/{int(state['vap'][1])} mB")
    status = state.get("s")
    if status == "WORKING" and state.get("du"):
        parts.append(f"en marche {round(100 * state['pr'] / state['du'])} %")
    elif status == "WAITING":
        parts.append("en attente" + (f" ({state['r']})" if state.get("r") else ""))
    elif status == "SUSPEND":
        parts.append("en pause")
    elif status == "IDLE":
        parts.append("à l'arrêt")
    if state.get("f") is False:
        parts.append("multibloc NON formé")
    return ", ".join(parts) or "posée"


def machine_color(status):
    return (
        "rouge"
        if "À SEC" in status or "NON formé" in status
        else "jaune"
        if "attente" in status
        else "vert"
        if "en marche" in status
        else "gris"
    )


def observation_date(stamp):
    if type(stamp) in (float, int) and 0 < stamp < 1e16:
        try:
            return datetime.fromtimestamp(stamp / 1000).strftime("%d/%m/%Y %H:%M:%S")
        except (ValueError, OverflowError, OSError):
            pass
    return "date inconnue"


def describe_world(world, limit=60, *, machine_ids=None):
    live, historical = [], []
    machines = [
        m
        for m in world.machines()
        if (machine_ids is None or m["bloc"] in machine_ids)
        and not re.search(r"hatch|_bus$", m["bloc"])
    ]
    for m in sorted(machines, key=World._date, reverse=True)[:limit]:
        where = f"{m['nom']} en {m['x']} {m['y']} {m['z']} ({m['dimension']})"
        if world.observation_live(m):
            live.append(f"{where} : {machine_status(m)}")
        else:
            historical.append(f"{where} : {machine_status(m)} au {observation_date(m.get('t'))}")
    lines = []
    if live:
        lines.append("Machines vues par le serveur (état en direct) : " + " ; ".join(live))
    if historical:
        lines.append(
            "Machines du serveur (historique, état actuel inconnu) : " + " ; ".join(historical)
        )
    if world.players:
        heading = (
            "Autres joueurs connectés"
            if world.active
            else f"Dernières positions connues des autres joueurs ({observation_date(world.last_t)})"
        )
        lines.append(
            heading
            + " : "
            + " ; ".join(
                f"{p['nom']} en {p['x']} {p['y']} {p['z']} ({p['dimension']})"
                for p in world.players
            )
        )
    return "\n".join(lines)


def describe_machines(storage, limit=60, *, machine_ids=None):
    machines = storage.machines
    if machine_ids is not None:
        machines = [m for m in machines if m["bloc"] in machine_ids]
    if not machines:
        return "Machines posées : aucune repérée."
    return "Machines repérées en partie (derniers emplacements observés) : " + " ; ".join(
        f"{m['nom']} [{m['bloc']}] en {int(m['x'])} {int(m['y'])} {int(m['z'])} "
        f"({m['dimension']}, {observation_date(m.get('t'))})"
        for m in machines[:limit]
    )
