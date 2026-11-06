"""Descriptions du joueur, des stocks et des quêtes pour les requêtes."""

import math
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from monifactory_assistant.game.world import observation_date


def cardinal(dx, dz):
    """Direction d'un déplacement (N, NE, E...), le nord étant vers -Z comme dans Minecraft."""
    angle = math.degrees(math.atan2(dx, -dz)) % 360
    return ["N", "NE", "E", "SE", "S", "SO", "O", "NO"][round(angle / 45) % 8]


def describe_state(state, inventory_limit=60, *, items=None):
    # KubeJS écrit tous les nombres en décimal (64.0) : on les remet en entiers
    inventory = state.get("inventaire", {}).items()
    if items is not None:
        inventory = [(item, n) for item, n in inventory if item in items]
        inventory_limit = len(inventory)
    inventory = sorted(inventory, key=lambda kv: -kv[1])
    return "\n".join(
        [
            f"Position : x={int(state['x'])} y={int(state['y'])} z={int(state['z'])} ({state['dimension']})",
            f"Écran ouvert : {state.get('ecran') or 'aucun, en jeu'} ; en main : {state.get('en_main')}",
            ("Inventaire : " if items is None else "Inventaire (objets ciblés) : ")
            + (
                ", ".join(f"{int(n)}x {i}" for i, n in inventory[:inventory_limit])
                or ("vide" if items is None else "aucun des objets ciblés dans ce relevé")
            ),
        ]
    )


def describe_storage(storage, limit=150, *, items=None):
    """Contenu des coffres avec leurs coordonnées, pour que Codex puisse dire où aller chercher un objet."""
    where = defaultdict(list)
    for c in storage.containers:
        objects = c["objets"] if items is None else [o for o in c["objets"] if o["item"] in items]
        if not objects:
            continue
        observed = observation_date(c.get("t"))
        location = f"{int(c['x'])} {int(c['y'])} {int(c['z'])}, {c['dimension']}, {observed}"
        for o in objects:
            where[o["item"]].append((int(o["n"]), location))
    if not where:
        return (
            "Coffres : aucun mémorisé pour l'instant."
            if items is None
            else "Coffres : aucun des objets ciblés dans les observations mémorisées ; le stock actuel reste à vérifier."
        )
    entries = sorted(where.items(), key=lambda kv: -sum(n for n, _ in kv[1]))
    if items is not None:
        limit = len(entries)
    parts = [
        f"{sum(n for n, _ in places)}x {item} ("
        + ", ".join(f"{n} en {pos}" for n, pos in places[:3])
        + ")"
        for item, places in entries[:limit]
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


_ITEM_ID = re.compile(r"(?<![A-Za-z0-9_.-])[a-z0-9_.-]+:[a-z0-9_./-]+")


@dataclass(frozen=True)
class QuestionContext:
    """Une réduction explicite, ou le contexte intégral si la correspondance est incertaine."""

    profile: str = "complet"
    items: frozenset[str] | None = None
    machines: frozenset[str] | None = None

    @property
    def focused(self):
        return self.profile != "complet"


def question_context(question, recipes):
    """Sélection locale prudente ; aucune classification IA ni modification du catalogue."""
    full = QuestionContext()
    if not isinstance(question, str) or not question.strip() or recipes is None:
        return full
    normalized = "".join(
        c for c in unicodedata.normalize("NFKD", question.lower()) if not unicodedata.combining(c)
    )
    words = set(re.findall(r"[a-z0-9]+", normalized))
    # Progression, demandes composées et suivis ambigus gardent toute la mémoire.
    if (
        "contexte complet" in normalized
        or words
        & {
            "objectif",
            "objectifs",
            "plan",
            "projet",
            "progression",
            "etape",
            "etapes",
            "arbre",
            "chaine",
            "usine",
            "automatiser",
            "historique",
            "avant",
            "depuis",
            "encore",
            "deja",
            "viens",
            "ca",
            "cela",
            "ceci",
            "cette",
            "cet",
        }
        or "?" in question.rstrip(" ?")
        or " et " in normalized
    ):
        return full
    targets = recipes.items_in(question, limit=8)
    explicit = set(_ITEM_ID.findall(question.lower()))
    # items_in privilégie parfois un namespace : ne pas réduire dans ce cas ambigu.
    if explicit - set(targets):
        return full
    for item in targets:
        if item not in explicit and len(recipes.by_name.get(item.split(":", 1)[1], ())) != 1:
            return full
    if (
        not targets
        and not explicit
        and len(words) <= 14
        and words & {"voix", "gamma", "luminosite", "micro"}
        and words & {"activer", "desactiver", "couper", "regler", "changer", "masquer"}
        and not words
        & {
            "machine",
            "machines",
            "coffre",
            "coffres",
            "filon",
            "filons",
            "craft",
            "recette",
            "miner",
        }
    ):
        return QuestionContext("reglage", frozenset(), frozenset())
    if not targets or len(targets) > 3:
        return full
    storage = bool(
        words & {"coffre", "coffres", "stock", "stocks", "drawer", "drawers", "barrel", "barrels"}
    )
    recipe = bool(
        words
        & {"craft", "crafter", "fabriquer", "faire", "recette", "recettes", "assembler", "obtenir"}
    )
    machine = any(item in recipes.machine_of for item in targets)
    # Un diagnostic peut dépendre des boilers, câbles et machines voisines.
    # Sans recette/stock explicitement demandé, ne pas réduire sur le seul nom de la machine.
    if not (storage or recipe):
        return full
    if storage and recipe:
        return full
    selected, machines = set(targets), set(targets) if machine else set()
    if recipe or machine:
        # Inclure les ingrédients des variantes connues sur deux niveaux, sans couper une recette.
        frontier = set(targets)
        for _ in range(2):
            following = set()
            for item in sorted(frontier):
                for line in recipes.by_output.get(item, ()):
                    parts = line.split(" | ")
                    if len(parts) < 3 or " => " not in parts[1]:
                        return full
                    following.update(_ITEM_ID.findall(parts[1]))
                    machines.update(recipes.machines.get(parts[0], ()))
            following -= selected
            selected.update(following)
            if len(selected) > 96 or len(machines) > 96:
                return full
            frontier = following
    return QuestionContext(
        "stock" if storage else "machine" if machine else "recette",
        frozenset(selected),
        frozenset(machines) if machines or storage else None,
    )
