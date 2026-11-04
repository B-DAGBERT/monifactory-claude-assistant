"""Read the client's complete personal inventory, including carried backpacks."""


def personal_inventory(state, snapshot, now_ms):
    if not isinstance(state, dict) or not isinstance(snapshot, dict):
        return state
    stamp = snapshot.get("t")
    if (
        snapshot.get("actif") is not True
        or snapshot.get("synchronise") is not True
        or not isinstance(stamp, (int, float))
        or isinstance(stamp, bool)
        or not 0 <= now_ms - stamp < 2500
        or snapshot.get("joueur") != state.get("joueur")
        or snapshot.get("dimension") != state.get("dimension")
    ):
        return state
    items = snapshot.get("inventaire")
    if not isinstance(items, dict) or any(
        not isinstance(item, str)
        or not isinstance(count, int)
        or isinstance(count, bool)
        or not 0 <= count <= 1_000_000_000
        for item, count in items.items()
    ):
        return state
    return {**state, "inventaire": dict(items)}
