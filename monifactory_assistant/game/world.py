"""Mémoire du monde et description des états des machines."""

import re
import time

from monifactory_assistant.game.snapshots import file_stamp
from monifactory_assistant.game.storage import Storage, distinct_containers
from monifactory_assistant.persistence import atomic_json, load_json
from monifactory_assistant.settings import WORLD_FILE

OBSERVATION_MAX_AGE_MS = 15_000
MAX_INTEGER = 2**53 - 1


def block_name(block_id):
    """« gtceu:lp_steam_alloy_smelter » -> « Lp Steam Alloy Smelter » (le serveur n'envoie que les ids)."""
    return block_id.split(":")[-1].replace("_", " ").title()


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


class World:
    """Ce que le script serveur voit autour du joueur : tous les coffres (même jamais ouverts) et l'état des
    machines GregTech. Gardé en mémoire : on sait encore ce qu'il y a dans la base quand on s'en éloigne."""

    def __init__(self):
        saved = load_json(WORLD_FILE, {})
        self.blocks = saved.get("blocs", {})  # clé "dimension|x|y|z" -> bloc vu par le serveur
        self.players = []
        self.errors = []
        self.last_t = 0  # heure de la dernière vue du serveur (ms)
        self.snapshot_stamp = None

    @property
    def active(self):
        return 0 <= time.time() * 1000 - self.last_t < OBSERVATION_MAX_AGE_MS

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
        if complete:
            for key, b in self.blocks.items():
                if (
                    b["dimension"] == dimension
                    and (b["x"] // 16, b["z"] // 16) in complete
                    and key not in seen
                ):
                    updated[key] = {**b, "t": date, "objets": [], "etat": None, "absent": True}
        updated.update(seen)
        if updated != self.blocks:
            atomic_json(WORLD_FILE, {"blocs": updated})
            self.blocks = updated
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
        return Storage(distinct_containers(c for c in raw if c.get("objets")), raw, machines)


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


def describe_world(world, limit=60):
    if not world.blocks:
        return ""
    # Les pièces de multibloc (Hatch, Bus) n'apportent rien : on garde les machines et les contrôleurs
    machines = [m for m in world.machines() if not re.search(r"hatch|_bus$", m["bloc"])]
    lines = [
        "Machines vues par le serveur (état en direct) : "
        + " ; ".join(
            f"{m['nom']} en {m['x']} {m['y']} {m['z']} : {machine_status(m)}"
            for m in machines[:limit]
        )
    ]
    if world.players:
        lines.append(
            "Autres joueurs connectés : "
            + " ; ".join(
                f"{p['nom']} en {p['x']} {p['y']} {p['z']} ({p['dimension']})"
                for p in world.players
            )
        )
    return "\n".join(lines)


def describe_machines(storage, limit=60):
    if not storage.machines:
        return (
            "Machines posées : aucune repérée (elles le sont quand le joueur ouvre leur interface)."
        )
    return (
        "Machines déjà posées (repérées quand le joueur a ouvert leur interface) : "
        + " ; ".join(
            f"{m['nom']} [{m['bloc']}] en {int(m['x'])} {int(m['y'])} {int(m['z'])}"
            for m in storage.machines[:limit]
        )
    )
