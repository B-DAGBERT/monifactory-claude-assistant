"""Logical installations on supplied evidence. No scan, persistence or physical topology inference."""

import re
from dataclasses import dataclass, field

from monifactory_assistant.production.knowledge import (
    Completeness,
    Evidence,
    Freshness,
    KnowledgeSnapshot,
    KnowledgeStatus,
    Observation,
)
from monifactory_assistant.production.recipes import Catalog, Resource


def _text(value, name, limit=200):
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > limit
        or any(ord(c) < 32 for c in value)
    ):
        raise ValueError(f"{name} invalide.")


@dataclass(frozen=True, order=True)
class MachineRef:
    """A declared member binds location AND block type, not its future replacement."""

    key: str
    block: str

    def __post_init__(self):
        _text(self.key, "Emplacement")
        _text(self.block, "Bloc")
        parts = self.key.split("|")
        if len(parts) != 4 or not parts[0]:
            raise ValueError("Dimension et position requises.")
        if any(not re.fullmatch(r"-?\d{1,10}", part) for part in parts[1:]):
            raise ValueError("Position invalide.")


@dataclass(frozen=True)
class FactoryDefinition:
    """A pure document, persistable only with an explicit player declaration in this world."""

    id: str
    world: str
    name: str
    function: str
    members: tuple[MachineRef, ...]
    evidence: tuple[Evidence, ...]
    version: int = 1

    def __post_init__(self):
        for value, name in (
            (self.id, "Identifiant"),
            (self.name, "Nom"),
            (self.function, "Fonction"),
        ):
            _text(value, name)
        if not isinstance(self.world, str) or not re.fullmatch(r"m1_[0-9a-f]{64}", self.world):
            raise ValueError("Identité de monde du lot 5 requise.")
        if type(self.version) is not int or self.version != 1:
            raise ValueError("Version d'installation inconnue.")
        if not isinstance(self.members, tuple) or not 1 <= len(self.members) <= 256:
            raise ValueError("Une installation contient de 1 à 256 machines déclarées.")
        if any(not isinstance(member, MachineRef) for member in self.members):
            raise ValueError("Référence de machine invalide.")
        if len({member.key for member in self.members}) != len(self.members):
            raise ValueError("Emplacement déclaré plusieurs fois.")
        if not isinstance(self.evidence, tuple) or not self.evidence:
            raise ValueError("Déclaration du joueur requise.")
        if any(
            not isinstance(e, Evidence)
            or e.status != KnowledgeStatus.USER_CONFIRMED
            or e.world != self.world
            or not e.source
            or not e.reference
            for e in self.evidence
        ):
            raise ValueError("Le groupe exige une déclaration du joueur liée au même monde.")


def scoped_observations(snapshot: KnowledgeSnapshot, kinds=("machine", "absence")):
    """A historical record without a world is never silently adopted by the current world."""
    if snapshot.scope is None:
        return ()
    return tuple(
        observation
        for observation in snapshot.observations
        if observation.kind in kinds
        and observation.evidence
        and all(e.world == snapshot.scope.world for e in observation.evidence)
    )


def live_evidence(observation: Observation, snapshot: KnowledgeSnapshot):
    if snapshot.scope is None:
        return ()
    dimension = observation.key.split("|", 1)[0]
    return tuple(
        e
        for e in observation.evidence
        if e.status == KnowledgeStatus.OBSERVED
        and e.freshness == Freshness.LIVE
        and e.world == snapshot.scope.world
        and e.session == snapshot.scope.session
        and e.dimension == dimension
        and e.dimension == snapshot.scope.dimension
        and type(e.received_at_ms) is int
        and 0 < e.received_at_ms <= snapshot.captured_at_ms
    )


def conflicting_history(records, snapshot: KnowledgeSnapshot):
    """Recover a merge conflict's proofs without turning discarded history into live state."""
    if snapshot.scope is None:
        return ()
    references = {
        (o.key, e.source, e.reference, e.observed_at_ms, e.received_at_ms)
        for o in records
        for e in o.evidence
        if e.status == KnowledgeStatus.UNKNOWN and e.world == snapshot.scope.world
    }
    matched = tuple(
        o
        for o in snapshot.history
        if o.evidence
        and all(e.world == snapshot.scope.world for e in o.evidence)
        and any(
            (o.key, e.source, e.reference, e.observed_at_ms, e.received_at_ms) in references
            for e in o.evidence
        )
    )
    if len(matched) > 1 and any(
        (o.kind, o.value) != (matched[0].kind, matched[0].value) for o in matched[1:]
    ):
        return matched
    return ()


@dataclass(frozen=True)
class FactoryMember:
    machine: MachineRef
    state: str  # observed, historical, not_in_authorized_survey, replaced, conflict, unknown
    observations: tuple[Observation, ...]


@dataclass(frozen=True)
class FactoryView:
    definition: FactoryDefinition
    members: tuple[FactoryMember, ...]
    physical_connections_known: bool = field(default=False, init=False)


def project_factories(snapshot: KnowledgeSnapshot, definitions):
    """Project current evidence without changing a declared group when a member disappears."""
    if snapshot.scope is None:
        return ()
    by_key = {}
    for observation in scoped_observations(snapshot):
        by_key.setdefault(observation.key, []).append(observation)
    result = []
    for definition in sorted(definitions, key=lambda value: value.id):
        if definition.world != snapshot.scope.world:
            continue
        members = []
        for member in definition.members:
            observations = tuple(sorted(by_key.get(member.key, ()), key=lambda value: value.id))
            current = [o for o in observations if live_evidence(o, snapshot)]
            present = [o for o in current if o.kind == "machine"]
            absent = [
                o
                for o in current
                if o.kind == "absence"
                and any(e.completeness == Completeness.COMPLETE for e in live_evidence(o, snapshot))
            ]
            if (
                present
                and absent
                or len({o.value.get("block") for o in present}) > 1
                or conflicting_history(observations, snapshot)
            ):
                state = "conflict"
            elif present:
                state = "observed" if present[0].value.get("block") == member.block else "replaced"
            elif absent:
                state = (
                    "not_in_authorized_survey"
                    if all(o.value.get("absence_scope") == "authorized_survey" for o in absent)
                    else "unknown"
                )
            else:
                state = "historical" if observations else "unknown"
            members.append(FactoryMember(member, state, observations))
        result.append(FactoryView(definition, tuple(members)))
    return tuple(result)


@dataclass(frozen=True)
class FunctionProposal:
    category: str
    members: tuple[MachineRef, ...]
    observation_ids: tuple[str, ...]
    catalog_generation: int
    confirmed: bool = field(default=False, init=False)
    physical_connection: bool = field(default=False, init=False)


def propose_functions(snapshot: KnowledgeSnapshot, catalog: Catalog):
    """Group declared recipe capabilities, never by proximity or by guessed block-name tiers."""
    categories = {}
    for recipe in catalog.recipes:
        for machine in recipe.machines:
            categories.setdefault(machine, set()).add(recipe.category)
    groups = {}
    for observation in scoped_observations(snapshot, ("machine",)):
        block = observation.value.get("block")
        for category in categories.get(block, ()):
            members, ids = groups.setdefault(category, (set(), set()))
            members.add(MachineRef(observation.key, block))
            ids.add(observation.id)
    return tuple(
        FunctionProposal(category, tuple(sorted(members)), tuple(sorted(ids)), catalog.generation)
        for category, (members, ids) in sorted(groups.items())
    )


@dataclass(frozen=True)
class RecipeDependency:
    producer: str
    consumer: str
    resource: Resource
    conditional: bool
    reusable: bool
    catalog_generation: int
    physical_connection: bool = field(default=False, init=False)


def recipe_dependencies(catalog: Catalog, recipe_ids, *, limit=4096):
    """Compute exact resource/variant links. Alternatives and chance outputs stay conditional."""
    if type(limit) is not int or not 1 <= limit <= 100_000:
        raise ValueError("Limite de relations invalide.")
    ids = set(recipe_ids)
    recipes = tuple(recipe for recipe in catalog.recipes if recipe.id in ids)
    if {recipe.id for recipe in recipes} != ids:
        raise ValueError("Recette inconnue dans cette génération.")
    producers = {}
    for recipe in recipes:
        for output in recipe.outputs:
            if output.chance_numerator == 0:
                continue
            producers.setdefault(output.amount.resource, []).append((recipe, output))
    result = {}
    for consumer in recipes:
        for ingredient in consumer.inputs:
            for option in ingredient.options:
                for producer, output in producers.get(option.resource, ()):
                    if producer.id == consumer.id:
                        continue
                    key = (producer.id, consumer.id, option.resource, ingredient.reusable)
                    conditional = (
                        len(ingredient.options) > 1
                        or output.chance_numerator != output.chance_denominator
                        or bool(producer.unsupported or consumer.unsupported)
                    )
                    previous = result.get(key)
                    if previous is not None:
                        conditional = conditional and previous.conditional
                    result[key] = RecipeDependency(
                        producer.id,
                        consumer.id,
                        option.resource,
                        conditional,
                        ingredient.reusable,
                        catalog.generation,
                    )
                    if len(result) > limit:
                        raise ValueError("Trop de relations ; sélectionner une chaîne plus petite.")
    return tuple(result.values())
