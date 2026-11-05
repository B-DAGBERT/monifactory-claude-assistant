"""Recipe ingredient lookup in the existing chest memory, without any AI call."""

import math
import re

from monifactory_assistant.game.storage import grouped_containers, position

ITEM = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")


def chest_search(request, containers, state, now):
    """The client validates the world token again before rendering these results."""
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

        def timestamp(c):
            value = c.get("t", 0)
            return value if isinstance(value, (int, float)) and math.isfinite(value) else 0

        if previous is None or timestamp(chest) >= timestamp(previous):
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
    # Invalid or incomplete player coordinates must not break the assistant's whole view.
    origin = []
    for axis in ("x", "y", "z"):
        value = state.get(axis, 0)
        origin.append(value if isinstance(value, (int, float)) and math.isfinite(value) else 0)
    chests.sort(key=lambda c: math.dist((c["x"], c["y"], c["z"]), origin))
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


class RecipeChestSearch:
    def __init__(self):
        self.stamp = None
        self.request = None

    def view(self, bridge, storage, state, now):
        path = bridge.dir / "recherche_emi.json"
        try:
            stat = path.stat()
            stamp = (stat.st_ino, stat.st_mtime_ns, stat.st_size)
            if stamp != self.stamp:
                request = bridge.read("recherche_emi.json")
                if request is None:
                    return None  # Retry a partially written file; never keep an old recipe visible.
                self.request, self.stamp = request, stamp
        except OSError:
            self.stamp = None
            self.request = None
            return None
        return chest_search(self.request, getattr(storage, "raw", storage.containers), state, now)
