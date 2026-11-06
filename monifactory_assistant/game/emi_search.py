"""Recipe ingredient lookup in the existing chest memory, without any AI call."""

import math
import re

from monifactory_assistant.game.snapshots import file_stamp
from monifactory_assistant.game.storage import grouped_containers, inventory_key, position

ITEM = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")


def _validate(request, state, now):
    if not isinstance(request, dict) or not request.get("actif") or not isinstance(state, dict):
        return None
    try:
        end = float(request["fin"])
        number = int(request["n"])
    except (KeyError, TypeError, ValueError, OverflowError):
        return None
    if not math.isfinite(end) or not now < end <= now + 601:
        return None
    world, dimension = request.get("monde"), request.get("dimension")
    if not isinstance(world, str) or not world or dimension != state.get("dimension"):
        return None
    raw = request.get("objets")
    if not isinstance(raw, list) or len(raw) > 4096:
        return None
    wanted = {item for item in raw if isinstance(item, str) and ITEM.fullmatch(item)}
    if not wanted:
        return None
    return number, end, world, dimension, frozenset(wanted)


def _timestamp(chest):
    value = chest.get("t", 0)
    return value if isinstance(value, (int, float)) and math.isfinite(value) else 0


def _stock_content(containers, dimension):
    """Describe the search input after fusion, omitting timestamps only when positions are unique."""
    content = {}
    for chest in containers:
        if not isinstance(chest, dict) or chest.get("dimension") != dimension:
            continue
        coordinates = position(chest)
        if coordinates is None:
            continue
        if coordinates in content:
            return None  # Conflicting raw observations still need timestamp ordering.
        block, items = chest.get("bloc", ""), inventory_key(chest)
        if not isinstance(block, str) or items is None:
            return None
        content[coordinates] = (block, items)
    return tuple(content.items())


def _matching_chests(containers, dimension, wanted):
    # Keep every physical chest, including both halves of a double chest. A newer empty
    # observation must replace an older nonempty one at the same coordinates.
    latest = {}
    for chest in containers:
        if not isinstance(chest, dict) or chest.get("dimension") != dimension:
            continue
        coordinates = position(chest)
        if coordinates is None:
            continue
        previous = latest.get(coordinates)

        if previous is None or _timestamp(chest) >= _timestamp(previous):
            latest[coordinates] = chest
    chests = []
    for chest, stock in grouped_containers(latest.values()):
        if chest.get("dimension") != dimension:
            continue
        hits = {}
        items = chest.get("objets")
        for item in items if isinstance(items, list) else []:
            if not isinstance(item, dict):
                continue
            key = item.get("item")
            count = item.get("n", 0)
            if (
                isinstance(key, str)
                and key in wanted
                and isinstance(count, (int, float))
                and not isinstance(count, bool)
                and math.isfinite(count)
                and 0 < count <= 1_000_000_000
                and int(count) == count
            ):
                hits[key] = hits.get(key, 0) + int(count)
        if not hits:
            continue
        best = sorted(hits.items(), key=lambda entry: (-entry[1], entry[0]))
        chests.append(
            {
                "x": chest["x"],
                "y": chest["y"],
                "z": chest["z"],
                "dimension": dimension,
                "n": sum(hits.values()),
                "objets": hits,
                "stock": stock,
                "bloc": chest.get("bloc", ""),
                "texte": ", ".join(
                    f"{key.split(':')[-1].replace('_', ' ')} ×{count}" for key, count in best[:2]
                )
                + (" …" if len(best) > 2 else ""),
            }
        )
    return chests


def _origin(state):
    origin = []
    for axis in ("x", "y", "z"):
        value = state.get(axis, 0)
        origin.append(value if isinstance(value, (int, float)) and math.isfinite(value) else 0)
    return tuple(origin)


def _ordered(chests, origin):
    return sorted(chests, key=lambda c: math.dist((c["x"], c["y"], c["z"]), origin))


def _view(request, validated, chests):
    number, end, world, dimension, wanted = validated
    count = len(chests)
    return {
        "n": number,
        "monde": world,
        "dimension": dimension,
        "fin": end,
        "recette": str(request.get("recette", "")),
        "objets": sorted(wanted),
        "coffres": chests,
        "total_coffres": count,
        "message": (f"{count} coffre(s) connu(s) contiennent des ingrédients de cette recette.")
        if count
        else "Aucun ingrédient trouvé dans les coffres connus de cette dimension. Ouvre les coffres à mémoriser.",
    }


def chest_search(request, containers, state, now):
    """The client validates the world token again before rendering these results."""
    validated = _validate(request, state, now)
    if validated is None:
        return None
    *_, dimension, wanted = validated
    return _view(
        request,
        validated,
        _ordered(_matching_chests(containers, dimension, wanted), _origin(state)),
    )


class RecipeChestSearch:
    def __init__(self):
        self.stamp = None
        self.request = None
        self.storage = None
        self.criteria = None
        self.stock_content = None
        self.matches = []
        self.origin = None
        self.ordered = []

    def view(self, bridge, storage, state, now):
        path = bridge.dir / "recherche_emi.json"
        try:
            stamp = file_stamp(path)
            if stamp is None:
                self.stamp = self.request = None
            elif stamp != self.stamp:
                request = bridge.read("recherche_emi.json")
                if request is None or file_stamp(path) != stamp:
                    return None  # A concurrent write will be retried on the next refresh.
                self.request, self.stamp = request, stamp
        except OSError:
            return None

        validated = _validate(self.request, state, now)
        if validated is None:
            return None
        _, _, world, dimension, wanted = validated
        criteria = world, dimension, wanted
        if storage is not self.storage or criteria != self.criteria:
            containers = getattr(storage, "raw", storage.containers)
            content = _stock_content(containers, dimension)
            if criteria != self.criteria or content is None or content != self.stock_content:
                self.matches = _matching_chests(containers, dimension, wanted)
                self.origin = None
            self.storage, self.criteria, self.stock_content = storage, criteria, content
        origin = _origin(state)
        if origin != self.origin:
            self.ordered = _ordered(self.matches, origin)
            self.origin = origin
        return _view(self.request, validated, self.ordered)
