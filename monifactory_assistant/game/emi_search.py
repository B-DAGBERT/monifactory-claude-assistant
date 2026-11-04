"""Recipe ingredient lookup in the existing chest memory, without any AI call."""

import math
import re

ITEM = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")


def chest_search(request, containers, state, now):
    """The client validates the world token again before rendering these results."""
    if not isinstance(request, dict) or not request.get("actif") or not state:
        return None
    try:
        end = float(request["fin"])
        number = int(request["n"])
    except (KeyError, TypeError, ValueError):
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
    chests = []
    for chest in containers:
        if chest.get("dimension") != dimension:
            continue
        hits = {}
        for item in chest.get("objets", []):
            key = item.get("item")
            count = item.get("n", 0)
            if key in wanted and isinstance(count, (int, float)) and count > 0:
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
                "texte": ", ".join(
                    f"{key.split(':')[-1].replace('_', ' ')} ×{count}" for key, count in best[:2]
                )
                + (" …" if len(best) > 2 else ""),
            }
        )
    chests.sort(
        key=lambda c: math.dist(
            (c["x"], c["y"], c["z"]), (state.get("x", 0), state.get("y", 0), state.get("z", 0))
        )
    )
    count = len(chests)
    return {
        "n": number,
        "monde": world,
        "dimension": dimension,
        "fin": end,
        "recette": str(request.get("recette", "")),
        "objets": sorted(wanted),
        "coffres": chests[:16],
        "total_coffres": count,
        "message": (
            f"{count} coffre(s) connu(s) contiennent des ingrédients de cette recette."
            + (" Les 16 plus proches sont signalés." if count > 16 else "")
        )
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
            stamp = (stat.st_mtime_ns, stat.st_size)
            if stamp != self.stamp:
                request = bridge.read("recherche_emi.json")
                if request is None:
                    return None  # Retry a partially written file; never keep an old recipe visible.
                self.request, self.stamp = request, stamp
        except OSError:
            self.request = None
            return None
        return chest_search(self.request, storage.containers, state, now)
