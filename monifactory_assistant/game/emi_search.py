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
        position = tuple(chest.get(k) for k in ("dimension", "x", "y", "z"))
        if any(
            not isinstance(chest.get(k), (int, float)) or not math.isfinite(chest[k])
            for k in ("x", "y", "z")
        ):
            continue
        previous = latest.get(position)

        def timestamp(c):
            value = c.get("t", 0)
            return value if isinstance(value, (int, float)) and math.isfinite(value) else 0

        if previous is None or timestamp(chest) >= timestamp(previous):
            latest[position] = chest
    chests = []
    for chest in latest.values():
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
                and math.isfinite(count)
                and count > 0
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
                "stock": f"{dimension}|{chest['x']}|{chest['y']}|{chest['z']}",
                "bloc": chest.get("bloc", ""),
                "texte": ", ".join(
                    f"{key.split(':')[-1].replace('_', ' ')} ×{count}" for key, count in best[:2]
                )
                + (" …" if len(best) > 2 else ""),
            }
        )
    # Les deux moitiés d'un coffre vanilla exposent le même inventaire. Garder les
    # deux contours, mais une seule réserve pour le parcours descendant du client.
    grouped = []
    for chest in chests:
        if chest["bloc"] in {"minecraft:chest", "minecraft:trapped_chest"}:
            twin = next(
                (
                    other
                    for other in grouped
                    if other["bloc"] == chest["bloc"]
                    and other["y"] == chest["y"]
                    and abs(other["x"] - chest["x"]) + abs(other["z"] - chest["z"]) == 1
                    and other["objets"] == chest["objets"]
                ),
                None,
            )
            if twin is not None:
                chest["stock"] = twin["stock"]
                continue
        grouped.append(chest)
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
            stamp = (stat.st_mtime_ns, stat.st_size)
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
