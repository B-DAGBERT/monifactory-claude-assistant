"""Descriptions du joueur, des stocks et des quêtes pour les requêtes."""

import math
from collections import defaultdict

from monifactory_assistant.game.world import observation_date


def cardinal(dx, dz):
    """Direction d'un déplacement (N, NE, E...), le nord étant vers -Z comme dans Minecraft."""
    angle = math.degrees(math.atan2(dx, -dz)) % 360
    return ["N", "NE", "E", "SE", "S", "SO", "O", "NO"][round(angle / 45) % 8]


def describe_state(state, inventory_limit=60):
    # KubeJS écrit tous les nombres en décimal (64.0) : on les remet en entiers
    items = sorted(state.get("inventaire", {}).items(), key=lambda kv: -kv[1])
    return "\n".join(
        [
            f"Position : x={int(state['x'])} y={int(state['y'])} z={int(state['z'])} ({state['dimension']})",
            f"Écran ouvert : {state.get('ecran') or 'aucun, en jeu'} ; en main : {state.get('en_main')}",
            "Inventaire : "
            + (", ".join(f"{int(n)}x {i}" for i, n in items[:inventory_limit]) or "vide"),
        ]
    )


def describe_storage(storage, limit=150):
    """Contenu des coffres avec leurs coordonnées, pour que Codex puisse dire où aller chercher un objet."""
    where = defaultdict(list)
    for c in storage.containers:
        observed = observation_date(c.get("t"))
        location = f"{int(c['x'])} {int(c['y'])} {int(c['z'])}, {c['dimension']}, {observed}"
        for o in c["objets"]:
            where[o["item"]].append((int(o["n"]), location))
    if not where:
        return "Coffres : aucun mémorisé pour l'instant."
    items = sorted(where.items(), key=lambda kv: -sum(n for n, _ in kv[1]))
    parts = [
        f"{sum(n for n, _ in places)}x {item} ("
        + ", ".join(f"{n} en {pos}" for n, pos in places[:3])
        + ")"
        for item, places in items[:limit]
    ]
    return (
        f"Coffres mémorisés ({len(storage.containers)}, quantités observées aux dates indiquées, "
        "stock actuel à vérifier) : " + " ; ".join(parts)
    )


def describe_quests(quests, limit=25, per_chapter=8):
    if not quests:
        return "Quêtes : pas encore lues."
    lines = [
        f"Quêtes : {int(quests['terminees'])}/{int(quests['total'])} terminées.",
        "Chapitres : "
        + " ; ".join(
            f"{c['titre']} {int(c['terminees'])}/{int(c['total'])}"
            for c in quests["chapitres"]
            if c["total"]
        ),
    ]
    # Quêtes principales d'abord, dans l'ordre des chapitres, sans qu'un gros chapitre prenne toute la place
    available, per_chapter_count = [], {}
    for q in sorted(quests["disponibles"], key=lambda q: q["optionnelle"]):
        if per_chapter_count.get(q["chapitre"], 0) < per_chapter:
            per_chapter_count[q["chapitre"]] = per_chapter_count.get(q["chapitre"], 0) + 1
            available.append(q)
    lines.append(
        f"Quêtes disponibles maintenant (non terminées, {len(quests['disponibles'])} au total) :"
    )
    for q in available[:limit]:
        tasks = ", ".join(
            f"{t['titre']} {int(t['progres'])}/{int(t['max'])}"
            + (f" [{t['item']}]" if t.get("item") else "")
            for t in q["taches"]
        )
        optional = " (optionnelle)" if q["optionnelle"] else ""
        lines.append(
            f"- [{q['chapitre']}] {q['titre']}{optional} : {tasks}. {q['description'][:250]}"
        )
    return "\n".join(lines)


def where_hint(target, state):
    dx, dz = target["x"] - state["x"], target["z"] - state["z"]
    return f"{round(math.hypot(dx, dz))} blocs {cardinal(dx, dz)}"
