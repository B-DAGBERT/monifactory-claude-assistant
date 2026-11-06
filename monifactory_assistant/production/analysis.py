"""Compose one catalogue generation and one world snapshot without acquiring new data."""

from dataclasses import dataclass

from .catalog import CatalogueView
from .diagnosis import DiagnosticReport, diagnose
from .knowledge import Evidence, Freshness, KnowledgeSnapshot
from .planner import PlanResult, StockLedger, StockSource, plan
from .recipes import Amount, Resource


@dataclass(frozen=True)
class StockProof:
    source_id: str
    evidence: tuple[Evidence, ...]


@dataclass(frozen=True)
class ProductionAnalysis:
    plan: PlanResult
    diagnosis: DiagnosticReport | None
    catalogue: CatalogueView
    stock_proofs: tuple[StockProof, ...]
    stock_universe_complete: bool
    limits: tuple[str, ...]


def analyse_observed(
    catalogue: CatalogueView,
    snapshot: KnowledgeSnapshot,
    target: Amount,
    *,
    id_countable=(),
    recipe_choices=None,
    ingredient_choices=None,
    diagnose_recipe=None,
    machine_key=None,
):
    """ID-only stocks require an explicit equivalence decision from the caller's data contract."""
    if catalogue.catalog is None or catalogue.status not in ("ready", "stale"):
        raise ValueError("Catalogue de production vérifié indisponible.")
    eligible = {}
    for resource in id_countable:
        if not isinstance(resource, Resource) or resource.kind != "item" or resource.variant:
            raise ValueError(
                "Seuls les objets sans variante peuvent utiliser un relevé par identifiant."
            )
        eligible[resource.id] = resource

    stock, proofs = [], []
    for observation in snapshot.availability_observations():
        quantities = tuple(
            Amount(eligible[item], amount)
            for item, amount in observation.value.get("items", {}).items()
            if item in eligible and amount > 0
        )
        if quantities:
            source_id = f"{observation.kind}:{observation.key}"
            stock.append(StockSource(source_id, quantities))
            proofs.append(
                StockProof(
                    source_id,
                    tuple(e for e in observation.evidence if e.freshness == Freshness.LIVE),
                )
            )

    recipe = None
    choices = dict(recipe_choices or {})
    if diagnose_recipe is not None:
        recipe = next((row for row in catalogue.catalog.recipes if row.id == diagnose_recipe), None)
        if recipe is None or not any(
            output.amount.resource == target.resource for output in recipe.outputs
        ):
            raise ValueError("La recette de diagnostic ne produit pas la cible.")
        if choices.setdefault(target.resource, recipe.id) != recipe.id:
            raise ValueError("Le diagnostic ne correspond pas à la variante choisie.")

    result = plan(
        catalogue.catalog,
        target,
        StockLedger(sources=tuple(stock)),
        recipe_choices=choices,
        ingredient_choices=ingredient_choices,
    )
    diagnosis = None
    if recipe is not None:
        diagnosis = diagnose(
            snapshot,
            recipe,
            catalog_generation=catalogue.catalog.generation,
            machine_key=machine_key,
            planning=result,
            planning_world=snapshot.scope.world if snapshot.scope is not None else None,
            planning_generation=catalogue.catalog.generation,
            planning_evidence=tuple(evidence for proof in proofs for evidence in proof.evidence),
        )

    limits = list(snapshot.limitations)
    if not snapshot.stock_universe_complete:
        limits.append(
            "Les manques sont calculés sur les stocks fournis. Le stock total est inconnu."
        )
    if catalogue.status == "stale":
        limits.append("Calcul fondé sur la dernière génération validée du catalogue.")
    return ProductionAnalysis(
        result, diagnosis, catalogue, tuple(proofs), snapshot.stock_universe_complete, tuple(limits)
    )
