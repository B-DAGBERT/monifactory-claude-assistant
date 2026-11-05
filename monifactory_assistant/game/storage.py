"""Physical chest identities shared by stock totals and EMI search."""

import math
from collections import defaultdict
from datetime import datetime

from monifactory_assistant.game.snapshots import frame_value

SHARED_CHESTS = {
    "minecraft:chest",
    "minecraft:trapped_chest",
    *(
        "sophisticatedstorage:" + name
        for name in (
            "chest",
            "copper_chest",
            "iron_chest",
            "gold_chest",
            "diamond_chest",
            "netherite_chest",
        )
    ),
}


def position(chest):
    if not isinstance(chest, dict) or not isinstance(chest.get("dimension"), str):
        return None
    coords = [chest.get(k) for k in ("x", "y", "z")]
    if any(
        isinstance(v, bool)
        or not isinstance(v, (int, float))
        or not math.isfinite(v)
        or int(v) != v
        for v in coords
    ):
        return None
    return (chest["dimension"], *(int(v) for v in coords))


def inventory_key(chest):
    """Compare the entire inventory, never only the ingredients of the current recipe."""
    items = chest.get("objets")
    if not isinstance(items, list):
        return None
    counts = {}
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("item"), str):
            return None
        count = item.get("n")
        if (
            isinstance(count, bool)
            or not isinstance(count, (int, float))
            or not math.isfinite(count)
            or not 0 <= count <= 1_000_000_000
            or int(count) != count
        ):
            return None
        if count:
            key = item["item"]
            counts[key] = counts.get(key, 0) + int(count)
    return tuple(sorted(counts.items()))


def grouped_containers(containers):
    """Yield every physical position with its shared-stock key in linear neighbor lookups.

    The legacy server snapshot has no vanilla chest half property. Only matching complete
    inventories on adjacent supported chests can be inferred as a double chest. Drawers,
    barrels and unknown storage retain independent stocks. A pair cannot absorb a third chest.
    """
    leaders = {}
    paired = set()
    for index, chest in enumerate(containers):
        here = position(chest)
        if here is None:
            continue
        stock = "|".join(map(str, here))
        block = chest.get("bloc")
        if isinstance(block, str) and block in SHARED_CHESTS:
            contents = inventory_key(chest)
            if contents is not None:
                dimension, x, y, z = here
                candidates = []
                for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    candidate = leaders.get((dimension, x + dx, y, z + dz))
                    if (
                        candidate is not None
                        and candidate[1] not in paired
                        and candidate[2] == block
                        and candidate[3] == contents
                    ):
                        candidates.append(candidate)
                if candidates:
                    _, stock, _, _ = min(candidates, key=lambda c: c[0])
                    paired.add(stock)
                else:
                    leaders[here] = (index, stock, block, contents)
        yield chest, stock


def distinct_containers(containers):
    seen = set()
    result = []
    for chest, stock in grouped_containers(containers):
        if stock not in seen:
            seen.add(stock)
            result.append(chest)
    return result


def latest_remembered_containers(containers, twins):
    """Keep the newest legacy client observations using the existing asymmetric rule.

    A candidate named Large is hidden by any kept neighbor with the same block,
    dimension and height, regardless of its content or its neighbor's display name.
    Unlike server stock grouping, this must also replace stale, different inventories.
    Nonstandard positions retain the original predicate instead of guessing their identity.
    """
    kept, nonstandard = [], []
    occupied = set()
    for chest in sorted(containers, key=lambda c: -c["t"]):
        here = position(chest)
        block = chest.get("bloc")
        indexed = here is not None and isinstance(block, str)
        if str(chest.get("nom", "")).startswith("Large"):
            if indexed:
                dimension, x, y, z = here
                neighboring = any(
                    (block, dimension, x + dx, y, z + dz) in occupied
                    for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1))
                )
                if neighboring or any(twins(chest, other) for other in nonstandard):
                    continue
            elif any(twins(chest, other) for other in kept):
                continue
        kept.append(chest)
        if indexed:
            occupied.add((block, *here))
        else:
            nonstandard.append(chest)
    return kept


class Storage:
    """Coffres mémorisés par le jeu (stockage.json) : ce qu'il y a dedans et où ils sont."""

    def __init__(self, containers, raw=None, machines=None):
        self.containers = containers
        self.raw = containers if raw is None else raw  # avec les deux moitiés des grands coffres
        self.machines = machines or []  # blocs à interface ouverts au moins une fois

    @staticmethod
    def load(bridge):
        """Les coffres mémorisés, ou None si le fichier est illisible pour l'instant (le jeu est en train de
        l'écrire) : il ne faut surtout pas croire que tous les coffres ont disparu."""
        data = bridge.read("stockage.json")
        if data is None:
            return None if (bridge.dir / "stockage.json").exists() else Storage([])
        # Les coffres mémorisés par une version plus ancienne du script du jeu sont faussés : ignorés
        containers = [c for c in data.get("conteneurs") or [] if c.get("v") == 2]
        # Un grand coffre occupe deux blocs côte à côte : selon le côté ouvert, le jeu le mémorise à l'une
        # ou l'autre position. On ne garde que la plus récente des deux.
        from monifactory_assistant.game.storage import latest_remembered_containers

        kept = latest_remembered_containers(containers, Storage.twins)
        return Storage(kept, containers, [m for m in data.get("machines") or [] if m.get("v") == 2])

    def placed(self):
        """Nombre de machines posées, partagé pendant un cycle de rendu."""

        def compute():
            counts = defaultdict(int)
            for m in self.machines:
                counts[m["bloc"]] += 1
            return counts

        return frame_value(self, "machines", compute)

    @staticmethod
    def twins(a, b):
        """Les deux moitiés d'un même grand coffre."""
        return (
            str(a.get("nom", "")).startswith("Large")
            and a["bloc"] == b["bloc"]
            and a["dimension"] == b["dimension"]
            and a["y"] == b["y"]
            and abs(a["x"] - b["x"]) + abs(a["z"] - b["z"]) == 1
        )

    def totals(self):
        def compute():
            totals = defaultdict(int)
            for c in self.containers:
                for o in c["objets"]:
                    totals[o["item"]] += int(o["n"])
            return totals

        return frame_value(self, "stocks", compute)

    def find(self, item, state):
        """Quantité dans les coffres de la dimension du joueur, et le coffre le plus proche qui en contient."""
        total, nearest, nearest_distance = 0, None, None
        for c in self.containers:
            count = sum(int(o["n"]) for o in c["objets"] if o["item"] == item)
            if not count or c["dimension"] != state["dimension"]:
                continue
            total += count
            distance = math.dist((c["x"], c["z"]), (state["x"], state["z"]))
            if nearest is None or distance < nearest_distance:
                nearest, nearest_distance = c, distance
        return total, nearest

    def as_text(self):
        lines = []
        for c in self.containers:
            date = datetime.fromtimestamp(c["t"] / 1000).strftime("%d/%m %H:%M")
            where = f"{int(c['x'])} {int(c['y'])} {int(c['z'])} | {c['dimension']} | {c['bloc']} ({c['nom']})"
            lines += [f"{o['item']} | {int(o['n'])} | {where} | {date}" for o in c["objets"]]
        return "\n".join(lines) + "\n"
