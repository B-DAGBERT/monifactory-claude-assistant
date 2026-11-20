"""Lecture et mise en forme des observations jointes à une question."""

import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from monifactory_assistant.game.context import (
    QuestionContext,
    describe_state,
    describe_storage,
    question_context,
)
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.veins import describe_veins
from monifactory_assistant.game.world import World, describe_machines, describe_world


@dataclass(frozen=True)
class QuestionContextSources:
    """Les lectures sont différées jusqu'à la section qui les utilise."""

    read_recipe_index: Callable[[], RecipeIndex]
    read_world_hint: Callable[[], str | None]
    read_game: Callable[[], dict | None]
    read_storage: Callable[[], Storage]
    read_veins: Callable[[], list[dict]]
    read_world: Callable[[], World]
    read_journal: Callable[[int], str]
    read_places: Callable[[], dict[str, dict]]
    read_guide: Callable[[], dict | None]
    read_removed_chests: Callable[[], list[tuple[float, dict, bool]]]


def build_question_context(
    sources: QuestionContextSources,
    shot: Path | str | None,
    *,
    with_storage: bool,
    question: str | None,
) -> str:
    """Matérialise le texte au fil des lectures, sans retenir les observations mutables."""
    recipes = sources.read_recipe_index() if with_storage else None
    selection = (
        question_context(question, recipes)
        if with_storage and question is not None
        else QuestionContext()
    )
    parts = []
    hint = sources.read_world_hint()
    if hint is not None:
        parts.append(hint)
    if sources.read_game():
        parts.append(describe_state(sources.read_game(), items=selection.items))
    if with_storage:
        if selection.profile != "reglage":
            parts.append(describe_storage(sources.read_storage(), items=selection.items))
            prefix = "Machines ciblées. " if selection.machines is not None else ""
            parts.append(
                prefix + describe_machines(sources.read_storage(), machine_ids=selection.machines)
            )
            parts.append(describe_veins(sources.read_veins(), sources.read_game()))
        world = describe_world(sources.read_world(), machine_ids=selection.machines)
        if world:
            parts.append(world)
        if recipes.pattern and selection.profile != "reglage":
            parts.append(
                f"Recettes du pack : cherche-les seulement dans {recipes.pattern}, le groupe actif."
            )
        if selection.focused:
            parts.append(
                "Contexte ciblé pour cette question ; les autres stocks et événements ne sont pas détaillés. "
                "Si une information manque, consulte les fichiers locaux en lecture seule "
                f"(stockage.txt, {recipes.catalog_files}, machines_recettes.txt, inventaire_joueur.json). "
                "Ne conclus pas qu'un objet absent de cet extrait n'existe pas. "
                "Ajoute « contexte complet » dans une question pour joindre le contexte étendu à ce tour."
            )
    if with_storage and not selection.focused:
        journal = sources.read_journal(25)
        if journal:
            parts.append(journal)
    if sources.read_places() and selection.profile != "reglage":
        parts.append(
            "Lieux mémorisés : "
            + " ; ".join(
                f"{name} ({p['x']} {p['y']} {p['z']}, {p['dimension']})"
                for name, p in sources.read_places().items()
            )
        )
    if sources.read_guide():
        parts.append(f"Guidage en cours vers : {sources.read_guide()['label']}")
    removed = [
        f"{int(c['x'])} {int(c['y'])} {int(c['z'])} (il y a {int((time.time() - t) / 60)} min)"
        for t, c, broken in sources.read_removed_chests()
        if broken
    ]
    if removed:
        parts.append(
            "Coffres retirés automatiquement de la mémoire (cassés) : " + ", ".join(removed)
        )
    if shot:
        parts.append(
            f"Capture de mon écran : {shot} (lis-la avec view_image SEULEMENT si ma question porte sur ce que "
            "je vois : « ça », « ce bloc », « cet écran »… ; sinon ne l'ouvre pas, ça ralentit)"
        )
    return "\n".join(parts)
