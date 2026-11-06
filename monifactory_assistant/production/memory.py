"""Read existing project saves and retrieve reviewed notes without a second project store."""

import json
import re
from collections import defaultdict
from dataclasses import dataclass
from heapq import nsmallest
from pathlib import Path
from typing import Literal

from monifactory_assistant.game.projects import ProjectStore
from monifactory_assistant.game.snapshots import file_stamp
from monifactory_assistant.game.world_scope import (
    WorldScope,
    installation_id,
    scope_file,
    scope_folder,
)


@dataclass(frozen=True)
class ProjectRef:
    installation: str
    world: str | None
    project: str


@dataclass(frozen=True)
class SavedStep:
    text: str
    completed: bool
    unchecked: bool


@dataclass(frozen=True)
class ProjectSummary:
    ref: ProjectRef
    name: str
    objective: str
    active: bool
    paused: bool
    revision: int | None
    completed: int
    total: int
    steps: tuple[SavedStep, ...]
    source: str


def read_projects(root: Path, game_data: Path, scope: WorldScope | None):
    """Project snapshots are historical; None keeps a legacy file explicitly unattributed."""
    if scope is not None and not isinstance(scope, WorldScope):
        raise ValueError("Portée de projet invalide.")
    owner = installation_id(root, game_data)
    folder = scope_folder(root, scope.world) if scope is not None else root
    path = scope_file(folder, "plan_ia.json")
    backup = scope_file(folder, "plan_ia.json.bak")
    before = file_stamp(path), file_stamp(backup)
    if before == (None, None):
        return ()
    # ProjectStore recovers from .bak if the primary is unreadable or not a dictionary.
    try:
        primary_valid = isinstance(json.loads(path.read_text(encoding="utf-8")), dict)
    except (OSError, ValueError):
        primary_valid = False
    source = path.name if primary_valid else backup.name
    store = ProjectStore(path)
    state = store.snapshot()
    saved_projects = {entry["id"]: entry["etat"] for entry in store.projects}
    summaries = []
    for entry in store.catalog(state):
        saved = saved_projects[entry["id"]]
        revision = saved.get("version")
        done = set(saved.get("etapes_faites", ()))
        unchecked = set(saved.get("etapes_decochees", ()))
        steps = tuple(
            SavedStep(step["texte"], index in done and index not in unchecked, index in unchecked)
            for index, step in enumerate((saved.get("plan") or {}).get("etapes", ()))
        )
        summaries.append(
            ProjectSummary(
                ProjectRef(owner, scope.world if scope is not None else None, entry["id"]),
                entry["nom"],
                entry["objectif"],
                entry["actif"],
                entry["pause"],
                revision if type(revision) is int and revision >= 0 else None,
                entry["faits"],
                entry["total"],
                steps,
                source,
            )
        )
    if (file_stamp(path), file_stamp(backup)) != before:
        raise ValueError("Les projets ont changé pendant la lecture.")
    return tuple(summaries)


@dataclass(frozen=True)
class ReviewedNote:
    """A previously reviewed statement. Reading it never confirms a pending proposal."""

    id: str
    project: ProjectRef
    subject: str
    text: str
    source: Literal["user_confirmed", "observed"]
    reference: str
    recorded_at_ms: int
    supersedes: str | None = None

    def __post_init__(self):
        if (
            not isinstance(self.project, ProjectRef)
            or self.source not in ("user_confirmed", "observed")
            or type(self.recorded_at_ms) is not int
            or self.recorded_at_ms < 0
            or any(
                not isinstance(value, str) or not value.strip()
                for value in (self.id, self.subject, self.text, self.reference)
            )
            or self.supersedes is not None
            and (not isinstance(self.supersedes, str) or not self.supersedes.strip())
        ):
            raise ValueError("Note de projet sans provenance ou contenu valide.")


def _terms(text):
    return set(re.findall(r"\w+", text.casefold()))


class ProjectNotes:
    """A bounded read query over a caller-owned history, without persisting assertions."""

    def __init__(self, project: ProjectRef, notes):
        self.project = project
        self.history = tuple(
            sorted(
                (note for note in notes if note.project == project),
                key=lambda note: (note.recorded_at_ms, note.id),
            )
        )
        seen = {}
        superseded = set()
        for note in self.history:
            if note.id in seen:
                raise ValueError("Identifiant de note répété dans ce projet.")
            if note.supersedes is not None:
                prior = seen.get(note.supersedes)
                if (
                    prior is None
                    or prior.subject != note.subject
                    or prior.recorded_at_ms >= note.recorded_at_ms
                ):
                    raise ValueError(
                        "La correction ne désigne pas une note antérieure sur le même sujet."
                    )
                superseded.add(prior.id)
            seen[note.id] = note
        self.current = tuple(note for note in self.history if note.id not in superseded)
        alternatives = defaultdict(set)
        for note in self.current:
            alternatives[note.subject].add(note.text)
        self.conflicts = tuple(
            sorted(subject for subject, texts in alternatives.items() if len(texts) > 1)
        )

    def search(self, query, *, limit=8):
        if not isinstance(query, str) or type(limit) is not int or not 1 <= limit <= 50:
            raise ValueError("Recherche de notes invalide.")
        wanted = _terms(query)

        def matches():
            for note in self.current:
                subject = wanted & _terms(note.subject)
                text = wanted & _terms(note.text)
                if not wanted or subject or text:
                    yield -len(subject), -len(text), -note.recorded_at_ms, note.id, note

        return tuple(hit[-1] for hit in nsmallest(limit, matches()))
