"""Mémoire du monde et description des états des machines."""

import json
import re
import time

from monifactory_assistant.game.storage import Storage, distinct_containers
from monifactory_assistant.settings import WORLD_FILE


def block_name(block_id):
    """« gtceu:lp_steam_alloy_smelter » -> « Lp Steam Alloy Smelter » (le serveur n'envoie que les ids)."""
    return block_id.split(":")[-1].replace("_", " ").title()


class World:
    """Ce que le script serveur voit autour du joueur : tous les coffres (même jamais ouverts) et l'état des
    machines GregTech. Gardé en mémoire : on sait encore ce qu'il y a dans la base quand on s'en éloigne."""

    def __init__(self):
        try:
            saved = json.loads(WORLD_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        self.blocks = saved.get("blocs", {})  # clé "dimension|x|y|z" -> bloc vu par le serveur
        self.players = []
        self.errors = []
        self.last_t = 0  # heure de la dernière vue du serveur (ms)

    @property
    def active(self):
        return time.time() * 1000 - self.last_t < 15000

    def update(self, snapshot):
        """Intègre une vue du serveur. Renvoie True si elle est nouvelle."""
        if not snapshot or snapshot.get("t", 0) <= self.last_t:
            return False
        self.last_t = snapshot["t"]
        dimension = snapshot["dimension"]
        cx0, cz0, cx1, cz1 = snapshot["zone"]
        seen = {}
        for b in snapshot.get("blocs", []):
            x, y, z = (int(v) for v in b["p"])
            key = f"{dimension}|{x}|{y}|{z}"
            seen[key] = {
                "cle": key,
                "bloc": b["b"],
                "nom": block_name(b["b"]),
                "dimension": dimension,
                "x": x,
                "y": y,
                "z": z,
                "t": snapshot["t"],
                "v": 2,
                "objets": [{"item": i, "n": int(n)} for i, n in b.get("o", [])],
                "etat": b.get("m"),
            }
        # Ce qui était dans la zone regardée et n'y est plus a été cassé ou déplacé
        for key, b in list(self.blocks.items()):
            if (
                b["dimension"] == dimension
                and cx0 <= b["x"] // 16 <= cx1
                and cz0 <= b["z"] // 16 <= cz1
                and key not in seen
            ):
                del self.blocks[key]
        self.blocks.update(seen)
        self.players = snapshot.get("joueurs", [])
        self.errors = snapshot.get("erreurs", [])
        WORLD_FILE.write_text(
            json.dumps({"blocs": self.blocks}, ensure_ascii=False), encoding="utf-8"
        )
        return True

    def containers(self):
        """Ne regrouper que les doubles coffres connus, jamais les stockages indépendants."""
        return distinct_containers(
            b for b in self.blocks.values() if b["objets"] and b.get("etat") is None
        )

    def machines(self):
        return [b for b in self.blocks.values() if b.get("etat") is not None]

    def merged(self, storage):
        """Les coffres et machines vus par le serveur, complétés par ceux que le joueur a ouverts ailleurs."""
        keys = set(self.blocks)
        containers = self.containers() + [c for c in storage.containers if c["cle"] not in keys]
        machines = self.machines() + [m for m in storage.machines if m["cle"] not in keys]
        return Storage(
            containers,
            [b for b in self.blocks.values() if b.get("etat") is None]
            + [c for c in storage.raw if c["cle"] not in keys],
            machines,
        )


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
