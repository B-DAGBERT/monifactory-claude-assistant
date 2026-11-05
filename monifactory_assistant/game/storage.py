"""Physical chest identities shared by stock totals and EMI search."""

import math

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
