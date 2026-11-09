"""Read the client's complete personal inventory, including carried backpacks."""

# The mod rewrites inventaire_joueur.json as soon as it changes and confirms an unchanged
# snapshot every 2 s; like etat.json, it stays current for 5 s (a short client stall included).
SNAPSHOT_MAX_AGE_MS = 5000


def personal_inventory(state, snapshot, now_ms):
    if not isinstance(state, dict) or not isinstance(snapshot, dict):
        return state
    stamp = snapshot.get("t")
    if (
        snapshot.get("actif") is not True
        or snapshot.get("synchronise") is not True
        or not isinstance(stamp, (int, float))
        or isinstance(stamp, bool)
        or not 0 <= now_ms - stamp < SNAPSHOT_MAX_AGE_MS
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


def recent_state(state, now_ms):
    """A future, stale or malformed frame cannot keep a disconnected game active."""
    if not isinstance(state, dict):
        return False
    stamp = state.get("t")
    return (
        isinstance(stamp, (int, float))
        and not isinstance(stamp, bool)
        and 0 <= now_ms - stamp < 5000
    )
