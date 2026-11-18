"""Physical chest identities shared by stock totals and EMI search."""

import math
from collections import defaultdict
from datetime import datetime

from monifactory_assistant.game.snapshots import file_stamp, frame_value

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


def chest_twin(chest, here=None):
    """None for unknown, () for a proven single, or the exact adjacent position."""
    if not isinstance(chest, dict):
        return None
    twin, block = chest.get("jumeau"), chest.get("bloc")
    if not isinstance(twin, list) or not isinstance(block, str) or block not in SHARED_CHESTS:
        return None
    if here is None:
        here = position(chest)
        if here is None:
            return None
    if not twin:
        return ()
    if len(twin) != 2 or any(
        type(n) not in (int, float) or not -(2**31) <= n < 2**31 or n != int(n) for n in twin
    ):
        return None
    dimension, x, y, z = here
    vx, vz = map(int, twin)
    return (dimension, vx, y, vz) if abs(vx - x) + abs(vz - z) == 1 else None


def _neighbors(here):
    dimension, x, y, z = here
    return ((dimension, x + dx, y, z + dz) for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)))


def _large(chest):
    return str(chest.get("nom", "")).startswith("Large")


def _shared_chest(chest):
    block = chest.get("bloc")
    return isinstance(block, str) and block in SHARED_CHESTS


def _chest_pairs(entries):
    """Match native halves first, then unambiguous legacy Large observations."""
    pairs = {}
    if not any(_shared_chest(chest) for here, chest in entries if here is not None):
        return pairs, {}
    latest = {}
    for place, chest in entries:
        if place is not None and (place not in latest or _date(chest) > _date(latest[place])):
            latest[place] = chest
    indexed = {here: chest for here, chest in latest.items() if _shared_chest(chest)}
    hints = {place: chest_twin(chest, place) for place, chest in indexed.items()}

    def pair(a, b):
        if a not in pairs and b not in pairs:
            pairs[a], pairs[b] = b, a

    native_candidates, claims = {}, defaultdict(int)
    for here, chest in indexed.items():
        other = hints[here]
        candidate = indexed.get(other)
        if (
            candidate is not None
            and candidate.get("bloc") == chest.get("bloc")
            and hints[other] in (None, here)
        ):
            native_candidates[here] = other
            claims[other] += 1
    for here, other in native_candidates.items():
        if claims[other] == 1:
            pair(here, other)

    candidates = {}
    for here, chest in indexed.items():
        block = chest.get("bloc")
        if (
            hints[here] is not None
            or not isinstance(block, str)
            or block not in SHARED_CHESTS
            or not _large(chest)
        ):
            continue
        neighbors = [
            there
            for there in _neighbors(here)
            if there in indexed
            and hints[there] is None
            and indexed[there].get("bloc") == block
            and _large(indexed[there])
        ]
        if len(neighbors) == 1:
            candidates[here] = neighbors[0]
    for here, other in candidates.items():
        if candidates.get(other) == here:
            pair(here, other)
    return pairs, hints


def _grouped_containers(containers, *, legacy_server):
    entries = [(position(chest), chest) for chest in containers]
    pairs, hints = _chest_pairs(entries)
    leaders = {}
    paired = set()
    for index, (here, chest) in enumerate(entries):
        if here is None:
            continue
        other = pairs.get(here)
        stock = "|".join(map(str, min(here, other) if other is not None else here))
        block = chest.get("bloc")
        # Old server packets omit the block's half. Keep the existing identical-inventory
        # fallback for those untyped rows only. It cannot absorb a proven single or a third chest.
        if (
            legacy_server
            and other is None
            and hints.get(here) is None
            and isinstance(block, str)
            and block in SHARED_CHESTS
        ):
            contents = inventory_key(chest)
            if contents is not None and not _large(chest):
                candidates = [
                    candidate
                    for neighbor in _neighbors(here)
                    if (candidate := leaders.get(neighbor)) is not None
                    and candidate[1] not in paired
                    and candidate[2] == block
                    and candidate[3] == contents
                ]
                if candidates:
                    _, stock, _, _ = min(candidates, key=lambda c: c[0])
                    paired.add(stock)
                else:
                    leaders[here] = (index, stock, block, contents)
        yield chest, stock


def grouped_containers(containers):
    """Yield every position and its stock key, including both inspectable halves."""
    return _grouped_containers(containers, legacy_server=True)


def _date(chest):
    value = chest.get("t", 0)
    return value if type(value) in (int, float) and math.isfinite(value) else 0


def distinct_containers(containers):
    newest = {}
    for chest, stock in grouped_containers(containers):
        prior = newest.get(stock)
        if prior is None or _date(chest) > _date(prior):
            newest[stock] = chest
    return list(newest.values())


def latest_remembered_containers(containers):
    """Keep the newest observation of each compatible physical client chest."""
    sorted_rows = sorted(containers, key=lambda c: -_date(c))
    stock_by_row = {
        id(chest): stock for chest, stock in _grouped_containers(sorted_rows, legacy_server=False)
    }
    seen = set()
    result = []
    for chest in sorted_rows:
        stock = stock_by_row.get(id(chest))
        if stock is not None:
            if stock in seen:
                continue
            seen.add(stock)
        result.append(chest)
    return result


def _published_place(entry):
    """Version, bloc, position et date entières d'une observation, ou None si elle est mal formée."""
    here = position(entry)
    block, date = entry.get("bloc"), entry.get("t")
    if (
        here is None
        or not isinstance(block, str)
        or not block
        or isinstance(date, bool)
        or not isinstance(date, (int, float))
        or not math.isfinite(date)
        or not 0 <= date < 1e16
    ):
        return None
    dimension, x, y, z = here
    return {"v": 2, "bloc": block, "dimension": dimension, "x": x, "y": y, "z": z, "t": int(date)}


def known_chests(observations):
    """Relevés du serveur (drawers, coffres jamais ouverts…) publiés pour l'onglet Recette du mod.

    Seulement ceux du serveur : le mod lit lui-même la mémoire du client (stockage.json) et garde,
    par position, l'observation la plus récente. Recopier ici les coffres du client garderait un
    coffre que le client a oublié (cassé, « oublie ce coffre ») tant que l'assistant ne republie pas.
    Les relevés vides (coffre vidé, bloc disparu) sont publiés : plus récents qu'un ancien contenu
    mémorisé par le client au même endroit, ils le masquent. Les deux moitiés d'un double coffre vu
    par le serveur portent le même inventaire : la seconde est publiée vide, à la même date.
    """
    raw = [o for o in observations if isinstance(o, dict) and o.get("etat") is None]
    containers, seen, stocks = [], set(), set()
    for chest, stock in grouped_containers(raw):
        place = _published_place(chest)
        counts = inventory_key(chest) if place is not None else None
        if counts is None:
            continue
        here = (place["dimension"], place["x"], place["y"], place["z"])
        if here in seen:
            continue
        seen.add(here)
        absorbed = stock in stocks
        stocks.add(stock)
        name = chest.get("nom")
        entry = {"v": 2, "bloc": place["bloc"]}
        if isinstance(name, str) and name:
            entry["nom"] = name
        entry.update(place)
        if chest_twin(chest, here) is not None:
            entry["jumeau"] = chest["jumeau"]
        entry["objets"] = [] if absorbed else [{"item": item, "n": n} for item, n in counts]
        if absorbed:
            # An empty alias suppresses old records at this location without replacing real stock.
            entry["alias_stock"] = True
        containers.append(entry)
    machines = [
        place
        for place in (
            _published_place(m)
            for m in observations
            if isinstance(m, dict) and m.get("etat") is not None and not m.get("absent")
        )
        if place is not None
    ]
    return {"v": 2, "conteneurs": containers, "machines": machines}


class Storage:
    """Coffres mémorisés par le jeu (stockage.json) : ce qu'il y a dedans et où ils sont."""

    def __init__(self, containers, raw=None, machines=None):
        self.containers = containers
        self.raw = containers if raw is None else raw  # avec les deux moitiés des grands coffres
        self.machines = machines or []  # blocs à interface ouverts au moins une fois
        self._export_key = None

    @staticmethod
    def load(bridge):
        """Les coffres mémorisés, ou None si le fichier est illisible pour l'instant (le jeu est en train de
        l'écrire) : il ne faut surtout pas croire que tous les coffres ont disparu."""
        data = bridge.read("stockage.json")
        if data is None:
            path = (
                bridge.data_path("stockage.json")
                if hasattr(bridge, "data_path")
                else bridge.dir / "stockage.json"
            )
            return None if path is not None and path.exists() else Storage([])
        # Les coffres mémorisés par une version plus ancienne du script du jeu sont faussés : ignorés
        containers = [c for c in data.get("conteneurs") or [] if c.get("v") == 2]
        # Un même inventaire peut être ouvert depuis deux positions.
        from monifactory_assistant.game.storage import latest_remembered_containers

        kept = latest_remembered_containers(containers)
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
        """Seulement une relation attestée pour les actions sur les positions physiques."""
        from monifactory_assistant.game.storage import chest_twin, position

        if a.get("bloc") != b.get("bloc"):
            return False
        place_a, place_b = position(a), position(b)
        if place_a is None or place_b is None:
            return False
        twin_a, twin_b = chest_twin(a), chest_twin(b)
        return (
            twin_a == place_b
            and twin_b in (None, place_a)
            or (twin_b == place_a and twin_a in (None, place_b))
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

    def export_key(self):
        """Comparer ce qui sera exporté avant de formater les dates et les lignes."""
        if self._export_key is None:
            self._export_key = tuple(
                (
                    c["x"],
                    c["y"],
                    c["z"],
                    c["dimension"],
                    c["bloc"],
                    c["nom"],
                    int(c["t"] // 60_000),
                    c["objets"],
                )
                for c in self.containers
            )
        return self._export_key

    def as_text(self):
        lines = []
        dates = {}
        for c in self.containers:
            minute = int(c["t"] // 60_000)
            if minute not in dates:
                dates[minute] = datetime.fromtimestamp(minute * 60).strftime("%d/%m %H:%M")
            date = dates[minute]
            where = f"{int(c['x'])} {int(c['y'])} {int(c['z'])} | {c['dimension']} | {c['bloc']} ({c['nom']})"
            lines += [f"{o['item']} | {int(o['n'])} | {where} | {date}" for o in c["objets"]]
        return "\n".join(lines) + "\n"


class StorageMonitor:
    """Réutilise le dernier relevé valide et sa fusion jusqu'à une nouvelle observation."""

    def __init__(self):
        self.client = self.storage = Storage([])
        self.path = self.stamp = None
        self.server_blocks = self.merged_client = None

    def refresh(self, bridge, world):
        path = (
            bridge.data_path("stockage.json")
            if hasattr(bridge, "data_path")
            else bridge.dir / "stockage.json"
        )
        if path != self.path:
            self.client = self.storage = Storage([])
            self.path, self.stamp = path, None
            self.server_blocks = self.merged_client = None
        if path is None:
            return self.storage
        try:
            stamp = file_stamp(path)
            if stamp != self.stamp:
                client = Storage.load(bridge)
                if client is not None and file_stamp(path) == stamp:
                    self.client, self.stamp = client, stamp
        except OSError:
            pass  # Garder le dernier relevé complet et réessayer au prochain cycle.
        if world.blocks is not self.server_blocks or self.client is not self.merged_client:
            self.storage = world.merged(self.client) if world.blocks else self.client
            self.server_blocks, self.merged_client = world.blocks, self.client
        return self.storage
