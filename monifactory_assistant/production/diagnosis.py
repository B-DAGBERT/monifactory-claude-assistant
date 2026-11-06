"""Explain supplied production evidence. This module never reads, scans or changes the game."""

from collections.abc import Mapping
from dataclasses import dataclass

from monifactory_assistant.production.factories import (
    conflicting_history,
    live_evidence,
    scoped_observations,
)
from monifactory_assistant.production.knowledge import (
    Completeness,
    Evidence,
    Freshness,
    KnowledgeSnapshot,
)
from monifactory_assistant.production.recipes import Recipe


@dataclass(frozen=True)
class Finding:
    code: str
    certainty: str  # observed, calculated, hypothesis, unknown; never an arbitrary percentage
    message: str
    evidence: tuple[Evidence, ...] = ()
    contradictions: tuple[str, ...] = ()
    missing: tuple[str, ...] = ()
    next_check: str = ""
    machine_key: str | None = None


@dataclass(frozen=True)
class DiagnosticReport:
    world: str | None
    recipe_id: str
    catalog_generation: int
    captured_at_ms: int
    findings: tuple[Finding, ...]
    hypotheses: tuple[Finding, ...]
    limitations: tuple[str, ...]
    next_action: str


def _empty_tank(value):
    return (
        isinstance(value, (tuple, list))
        and len(value) == 2
        and all(type(n) in (int, float) and n >= 0 for n in value)
        and value[0] == 0
        and value[1] > 0
    )


def _conflicting(records, snapshot):
    current = [o for o in records if live_evidence(o, snapshot)]
    present = [o for o in current if o.kind == "machine"]
    absent = [
        o
        for o in current
        if o.kind == "absence"
        and any(e.completeness == Completeness.COMPLETE for e in live_evidence(o, snapshot))
    ]
    if present and absent or len({o.value.get("block") for o in present}) > 1:
        return True
    for field in ("s", "f", "circuit", "se"):
        values = []
        for observation in present:
            state = observation.value.get("state")
            if isinstance(state, Mapping) and field in state:
                value = state[field]
                if value not in values:
                    values.append(value)
        if len(values) > 1:
            return True
    return False


def _machine_findings(observation, snapshot, recipe):
    evidence = live_evidence(observation, snapshot)
    key = observation.key
    if not evidence:
        return [
            Finding(
                "machine_historical",
                "unknown",
                f"Machine connue en {key}, sans état courant vérifié.",
                observation.evidence,
                missing=("État récent dans la connexion active",),
                next_check="Ouvre cette machine pour vérifier son état actuel.",
                machine_key=key,
            )
        ]
    if observation.kind == "absence":
        if observation.value.get("absence_scope") == "authorized_survey" and any(
            e.completeness == Completeness.COMPLETE for e in evidence
        ):
            return [
                Finding(
                    "machine_not_in_authorized_survey",
                    "observed",
                    f"Le relevé autorisé ne retrouve pas le bloc en {key} ; cela ne prouve pas sa disparition physique.",
                    evidence,
                    missing=("Présence physique et droits d’accès actuels",),
                    next_check="Vérifie la machine et ses droits d’accès à cet emplacement.",
                    machine_key=key,
                )
            ]
        return [
            Finding(
                "machine_absence_unproven",
                "unknown",
                "Ce relevé ne permet pas de conclure à la disparition physique de la machine.",
                evidence,
                missing=("Portée explicite et couverture complète de cet emplacement",),
                next_check="Vérifie cet emplacement sans conclure à une disparition.",
                machine_key=key,
            )
        ]
    findings = []
    state = observation.value.get("state")
    if not isinstance(state, Mapping):
        state = {}
    status = state.get("s")
    if status in ("WORKING", "WAITING", "SUSPEND", "IDLE"):
        labels = {
            "WORKING": "en marche",
            "WAITING": "en attente",
            "SUSPEND": "en pause",
            "IDLE": "à l’arrêt",
        }
        reason = state.get("r")
        reason = reason[:256] if status == "WAITING" and isinstance(reason, str) else ""
        findings.append(
            Finding(
                f"machine_{status.lower()}",
                "observed",
                f"Machine en {key} : {labels[status]}" + (f" ({reason})." if reason else "."),
                evidence,
                missing=("Identité de la recette effectivement exécutée",),
                next_check=(
                    "Lis la raison d’attente dans la machine."
                    if status == "WAITING"
                    else "Vérifie que la recette sélectionnée est celle attendue."
                ),
                machine_key=key,
            )
        )
    else:
        findings.append(
            Finding(
                "machine_status_unknown",
                "unknown",
                f"La présence de la machine en {key} est connue, son état ne l’est pas.",
                evidence,
                missing=("État de fonctionnement",),
                next_check="Ouvre la machine et lis son état.",
                machine_key=key,
            )
        )
    if state.get("f") is False:
        findings.append(
            Finding(
                "multiblock_unformed",
                "observed",
                "La machine signale une structure multibloc non formée.",
                evidence,
                contradictions=("Un état en marche est aussi signalé.",)
                if status == "WORKING"
                else (),
                next_check="Vérifie la structure avec l’aperçu natif du multibloc.",
                machine_key=key,
            )
        )
    if state.get("se") is True:
        findings.append(
            Finding(
                "boiler_dry",
                "observed",
                "La chaudière signale un manque d’eau.",
                evidence,
                next_check="Vérifie l’arrivée d’eau et l’état de la chaudière dans son interface.",
                machine_key=key,
            )
        )
    for field, name in (("eau", "eau"), ("vap", "vapeur")):
        if _empty_tank(state.get(field)):
            findings.append(
                Finding(
                    f"{field}_empty",
                    "observed",
                    f"Le réservoir de {name} observé est vide.",
                    evidence,
                    missing=("Connexion et alimentation effectives de ce réservoir",),
                    next_check=f"Vérifie l’alimentation en {name} de cette machine.",
                    machine_key=key,
                )
            )
    if recipe.circuit is not None:
        configured = state.get("circuit")
        known = type(configured) is int and configured >= 0
        if not known or configured != recipe.circuit:
            findings.append(
                Finding(
                    "circuit_mismatch" if known else "circuit_unknown",
                    "calculated" if known else "unknown",
                    (
                        f"Circuit observé {configured}, circuit requis {recipe.circuit}."
                        if known
                        else f"La recette requiert le circuit {recipe.circuit} ; son réglage n’est pas observé."
                    ),
                    evidence,
                    missing=() if known else ("Réglage natif du circuit",),
                    next_check=f"Vérifie le réglage {recipe.circuit} dans l’interface de la machine.",
                    machine_key=key,
                )
            )
    return findings


def diagnose(
    snapshot: KnowledgeSnapshot,
    recipe: Recipe,
    *,
    catalog_generation: int,
    machine_key=None,
    planning=None,
    planning_world=None,
    planning_generation=None,
    planning_evidence=(),
):
    """No implicit active project or inventory. A calculation must name its world and generation."""
    if type(catalog_generation) is not int or catalog_generation <= 0:
        raise ValueError("Génération de catalogue requise.")
    world = snapshot.scope.world if snapshot.scope is not None else None
    findings, hypotheses = [], []
    limitations = list(snapshot.limitations)
    if world is None:
        findings.append(
            Finding(
                "world_unknown",
                "unknown",
                "Monde non identifié : diagnostic local non attribuable.",
                missing=("Identité de monde fiable",),
                next_check="Connecte-toi au monde concerné avant de comparer les observations.",
            )
        )
        return DiagnosticReport(
            None,
            recipe.id,
            catalog_generation,
            snapshot.captured_at_ms,
            tuple(findings),
            (),
            tuple(limitations),
            findings[0].next_check,
        )
    if recipe.unsupported:
        findings.append(
            Finding(
                "recipe_conditions_unknown",
                "unknown",
                "Certaines conditions de cette recette ne sont pas interprétées.",
                missing=tuple(recipe.unsupported),
                next_check="Consulte la recette native pour ces conditions.",
            )
        )
    if planning is not None:
        if (
            planning_world != world
            or planning_generation != catalog_generation
            or getattr(planning, "generation", None) != catalog_generation
        ):
            findings.append(
                Finding(
                    "planning_context_mismatch",
                    "unknown",
                    "Le calcul fourni ne correspond pas au monde et au catalogue demandés.",
                    missing=("Calcul rattaché à ce monde et cette génération",),
                    next_check="Recalcule les besoins avec les observations du monde actif.",
                )
            )
        else:
            evidence = tuple(planning_evidence)
            attributed = bool(evidence) and all(e.world == world for e in evidence)
            stock_limits = ()
            if not attributed:
                stock_limits = ("Provenance des stocks utilisés dans le calcul",)
            elif any(
                e.freshness != Freshness.LIVE or e.session != snapshot.scope.session
                for e in evidence
            ):
                stock_limits = (
                    "Stocks historiques ou périmés à vérifier dans la connexion active",
                )
            for amount in planning.missing:
                findings.append(
                    Finding(
                        "ingredient_shortfall",
                        "calculated" if attributed else "unknown",
                        f"Le calcul indique un manque de {amount.quantity} {amount.resource.unit} de {amount.resource.id} dans les stocks fournis.",
                        evidence if attributed else (),
                        missing=stock_limits,
                        next_check="Vérifie ce besoin dans l’inventaire et les stockages observés.",
                    )
                )
            for amount in planning.waiting:
                findings.append(
                    Finding(
                        "awaiting_receipt",
                        "calculated" if attributed else "unknown",
                        f"{amount.quantity} {amount.resource.unit} de {amount.resource.id} sont attendues ; leur réception n’est pas confirmée.",
                        evidence if attributed else (),
                        missing=("Preuve de réception réelle", *stock_limits),
                        next_check="Vérifie la sortie de la machine avant de relancer la fabrication.",
                    )
                )
            for issue in planning.issues:
                findings.append(
                    Finding(
                        "planning_" + issue.code,
                        "calculated",
                        issue.message,
                        next_check="Précise les choix signalés avant d’interpréter le calcul.",
                    )
                )
    observations = scoped_observations(snapshot)
    selected = (
        [o for o in observations if o.key == machine_key]
        if machine_key is not None
        else [o for o in observations if o.value.get("block") in recipe.machines]
    )
    selected.sort(key=lambda o: (o.key, o.id))
    if len(selected) > 128:
        limitations.append(
            "Diagnostic limité aux 128 premières observations : préciser la machine."
        )
        selected = selected[:128]
    if not selected:
        findings.append(
            Finding(
                "machine_not_observed",
                "unknown",
                "Aucune machine compatible n’est identifiée dans ces observations.",
                missing=("Machine pertinente observée dans ce monde",),
                next_check="Indique ou ouvre la machine concernée ; son absence du relevé ne prouve pas son absence du monde.",
            )
        )
    by_key = {}
    for observation in selected:
        by_key.setdefault(observation.key, []).append(observation)
    conflicts = set()
    for key, records in by_key.items():
        history = conflicting_history(records, snapshot)
        if _conflicting(records, snapshot) or history:
            conflicts.add(key)
            findings.append(
                Finding(
                    "machine_observation_conflict",
                    "unknown",
                    f"Les observations récentes de la machine en {key} se contredisent.",
                    tuple(e for o in records for e in o.evidence),
                    contradictions=tuple(o.id for o in history or records),
                    missing=("État récent concordant",),
                    next_check="Ouvre cette machine pour départager les relevés.",
                    machine_key=key,
                )
            )
    for observation in selected:
        if observation.key in conflicts:
            continue
        if observation.kind == "machine":
            compatible = observation.value.get("block") in recipe.machines
            findings.append(
                Finding(
                    "machine_compatible" if compatible else "machine_compatibility_unknown",
                    "calculated" if compatible else "unknown",
                    "Le catalogue déclare cette machine compatible avec la recette."
                    if compatible
                    else "Le catalogue ne permet pas d’établir la compatibilité de cette machine.",
                    observation.evidence,
                    missing=("Conditions énergétiques et exécution effective de cette recette",),
                    next_check="Vérifie la recette et ses conditions dans l’interface de la machine.",
                    machine_key=observation.key,
                )
            )
        findings.extend(_machine_findings(observation, snapshot, recipe))
    hypotheses.append(
        Finding(
            "unobserved_connections",
            "hypothesis",
            "L’alimentation ou les sorties peuvent nécessiter une vérification ; aucune topologie n’est observée ici.",
            missing=(
                "Alimentation électrique effective",
                "Réseaux de câbles et tuyaux",
                "Disponibilité des sorties",
            ),
            next_check="Si les ingrédients et la machine conviennent, vérifie les entrées, l’alimentation et les sorties dans le jeu.",
        )
    )
    urgent = {
        "multiblock_unformed",
        "boiler_dry",
        "circuit_mismatch",
        "ingredient_shortfall",
        "awaiting_receipt",
        "machine_waiting",
        "machine_not_in_authorized_survey",
    }
    first = next((f for f in findings if f.code in urgent), None)
    if first is None:
        first = next((f for f in findings if f.certainty == "unknown"), None)
    if first is None:
        first = hypotheses[0]
    return DiagnosticReport(
        world,
        recipe.id,
        catalog_generation,
        snapshot.captured_at_ms,
        tuple(findings),
        tuple(hypotheses),
        tuple(limitations),
        first.next_check,
    )
