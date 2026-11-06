"""Pure, deterministic material planning. This module performs no game action or IO.

A plan describes conditional production, not machine readiness. Inventory sources
must already have canonical, scoped identities. Receipts never create inventory;
expected lots reduce future demand but remain visibly waiting. No native recipe
semantics, machine tiers, tool wear or NBT equivalence are inferred.
"""

from collections import defaultdict
from dataclasses import dataclass
from typing import Mapping

from .recipes import Amount, Catalog, Output, Resource, _integer, _sequence


def _amounts(values):
    counts = defaultdict(int)
    for value in _sequence(values, "Quantités"):
        if not isinstance(value, Amount):
            raise ValueError("Quantités structurées requises.")
        counts[value.resource] += value.quantity
    return tuple(
        Amount(resource, quantity) for resource, quantity in sorted(counts.items()) if quantity
    )


def _summary(counts):
    return tuple(
        Amount(resource, quantity) for resource, quantity in sorted(counts.items()) if quantity > 0
    )


def _identity(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Identifiant de source ou de lot obligatoire.")


@dataclass(frozen=True)
class StockSource:
    id: str
    contents: tuple[Amount, ...]

    def __post_init__(self):
        _identity(self.id)
        object.__setattr__(self, "contents", _amounts(self.contents))


@dataclass(frozen=True)
class ProductionLot:
    id: str
    outputs: tuple[Amount, ...]

    def __post_init__(self):
        _identity(self.id)
        object.__setattr__(self, "outputs", _amounts(self.outputs))


def _unique(values, cls):
    indexed = {}
    for value in _sequence(values, "Sources ou lots"):
        if not isinstance(value, cls):
            raise ValueError("Source ou lot structuré requis.")
        if value.id in indexed and indexed[value.id] != value:
            raise ValueError("Deux états contradictoires portent le même identifiant.")
        indexed[value.id] = value
    return tuple(indexed[key] for key in sorted(indexed))


@dataclass(frozen=True)
class StockLedger:
    """Reservations exclude physical stock; receipts only settle matching promises.

    An expected lot contains its original promised total, not its remaining total.
    A confirmed lot contains cumulative receipts for that same ID. Its objects
    are usable only when also present in a physical StockSource.
    """

    sources: tuple[StockSource, ...] = ()
    reserved: tuple[Amount, ...] = ()
    expected: tuple[ProductionLot, ...] = ()
    confirmed: tuple[ProductionLot, ...] = ()

    def __post_init__(self):
        object.__setattr__(self, "sources", _unique(self.sources, StockSource))
        object.__setattr__(self, "reserved", _amounts(self.reserved))
        object.__setattr__(self, "expected", _unique(self.expected, ProductionLot))
        object.__setattr__(self, "confirmed", _unique(self.confirmed, ProductionLot))
        expected = {lot.id: {a.resource: a.quantity for a in lot.outputs} for lot in self.expected}
        for lot in self.confirmed:
            if lot.id in expected and any(
                a.quantity > expected[lot.id].get(a.resource, 0) for a in lot.outputs
            ):
                raise ValueError("Réception supérieure au total déclaré pour ce lot.")


@dataclass(frozen=True)
class PlanIssue:
    code: str
    message: str
    resource: Resource | None = None
    recipe_id: str | None = None
    candidates: tuple[str, ...] = ()
    blocking: bool = True

    def to_dict(self):
        return {
            "code": self.code,
            "message": self.message,
            "resource": self.resource.to_dict() if self.resource else None,
            "recipe_id": self.recipe_id,
            "candidates": list(self.candidates),
            "blocking": self.blocking,
        }


@dataclass(frozen=True)
class Allocation:
    resource: Resource
    quantity: int
    source_kind: str
    source_id: str
    consumer: str

    def to_dict(self):
        return {
            "resource": self.resource.to_dict(),
            "quantity": self.quantity,
            "source_kind": self.source_kind,
            "source_id": self.source_id,
            "consumer": self.consumer,
        }


@dataclass(frozen=True)
class PlanStep:
    id: str
    recipe_id: str
    batches: int
    inputs: tuple[Amount, ...]
    catalysts: tuple[Amount, ...]
    outputs: tuple[Amount, ...]
    machines: tuple[str, ...]
    circuit: int | None
    duration_ticks: int | None
    energy_eu: int | None
    dependencies: tuple[str, ...]
    probabilistic_outputs_per_batch: tuple[Output, ...] = ()

    def to_dict(self):
        return {
            "id": self.id,
            "recipe_id": self.recipe_id,
            "batches": self.batches,
            "inputs": [a.to_dict() for a in self.inputs],
            "catalysts": [a.to_dict() for a in self.catalysts],
            "outputs": [a.to_dict() for a in self.outputs],
            "machines": list(self.machines),
            "circuit": self.circuit,
            "duration_ticks": self.duration_ticks,
            "energy_eu": self.energy_eu,
            "dependencies": list(self.dependencies),
            "probabilistic_outputs_per_batch": [
                o.to_dict() for o in self.probabilistic_outputs_per_batch
            ],
            "conditional": True,
        }


@dataclass(frozen=True)
class PlanResult:
    generation: int
    target: Amount
    status: str
    steps: tuple[PlanStep, ...]
    missing: tuple[Amount, ...]
    waiting: tuple[Amount, ...]
    issues: tuple[PlanIssue, ...]
    allocations: tuple[Allocation, ...]
    surplus: tuple[Amount, ...]

    def to_dict(self):
        return {
            "schema": "monifactory.production.plan",
            "version": 1,
            "generation": self.generation,
            "target": self.target.to_dict(),
            "status": self.status,
            "steps": [s.to_dict() for s in self.steps],
            "missing": [a.to_dict() for a in self.missing],
            "waiting": [a.to_dict() for a in self.waiting],
            "issues": [i.to_dict() for i in self.issues],
            "allocations": [a.to_dict() for a in self.allocations],
            "surplus": [a.to_dict() for a in self.surplus],
            "semantics": "conditional_material_plan",
            "execution_readiness": "not_evaluated",
        }


@dataclass
class _Balance:
    quantity: int
    kind: str
    source: str


class _Planner:
    def __init__(self, catalog, stock, recipe_choices, ingredient_choices, max_depth, max_steps):
        self.catalog = catalog
        try:
            self.recipe_choices = dict(recipe_choices or {})
            self.ingredient_choices = dict(ingredient_choices or {})
        except (TypeError, ValueError) as exc:
            raise ValueError("Choix de recette ou alternative invalides.") from exc
        self.max_depth, self.max_steps = max_depth, max_steps
        self.recipes = {recipe.id: recipe for recipe in catalog.recipes}
        self.index = defaultdict(list)
        for recipe in catalog.recipes:
            for resource in sorted({o.amount.resource for o in recipe.outputs}):
                self.index[resource].append(recipe)
        for resource, recipe_id in self.recipe_choices.items():
            if (
                not isinstance(resource, Resource)
                or not isinstance(recipe_id, str)
                or recipe_id not in self.recipes
                or not any(o.amount.resource == resource for o in self.recipes[recipe_id].outputs)
            ):
                raise ValueError("Choix de recette invalide ou incompatible avec la ressource.")
        self.choices = {}
        for recipe in catalog.recipes:
            for i, ingredient in enumerate(recipe.inputs):
                self.choices[f"{recipe.id}#{i}"] = ingredient
        for key, resource in self.ingredient_choices.items():
            if key not in self.choices or not any(
                a.resource == resource for a in self.choices[key].options
            ):
                raise ValueError(
                    "Choix d'alternative invalide ; utiliser recette#index (index à partir de 0)."
                )
        self.pool = defaultdict(list)
        self.held_catalysts = defaultdict(int)
        self.catalyst_dependencies = defaultdict(set)
        self.issues, self.steps, self.allocations = [], [], []
        self.missing, self.waiting = defaultdict(int), defaultdict(int)
        self.serial = 0
        for source in stock.sources:
            for amount in source.contents:
                self.pool[amount.resource].append(_Balance(amount.quantity, "physical", source.id))
        for amount in stock.reserved:
            remaining = amount.quantity
            for balance in self.pool[amount.resource]:
                n = min(remaining, balance.quantity)
                balance.quantity -= n
                remaining -= n
            if remaining:
                self.issue(
                    "reservation_shortage",
                    "Réservation supérieure au stock physique déclaré.",
                    amount.resource,
                )
        confirmed = {
            lot.id: {a.resource: a.quantity for a in lot.outputs} for lot in stock.confirmed
        }
        for lot in stock.expected:
            for amount in lot.outputs:
                remaining = amount.quantity - confirmed.get(lot.id, {}).get(amount.resource, 0)
                if remaining:
                    self.pool[amount.resource].append(_Balance(remaining, "expected", lot.id))

    def issue(self, code, message, resource=None, recipe_id=None, candidates=(), blocking=True):
        issue = PlanIssue(code, message, resource, recipe_id, tuple(candidates), blocking)
        if issue not in self.issues:
            self.issues.append(issue)

    def take(self, resource, quantity, consumer):
        dependencies = set()
        # Prefer held objects, then already planned coproducts, then promises.
        # Promises stay waiting and are never described as available inventory.
        for kind in ("physical", "planned", "expected"):
            for balance in self.pool[resource]:
                if not quantity:
                    break
                if balance.kind != kind or not balance.quantity:
                    continue
                n = min(quantity, balance.quantity)
                balance.quantity -= n
                quantity -= n
                self.allocations.append(Allocation(resource, n, kind, balance.source, consumer))
                if kind == "expected":
                    self.waiting[resource] += n
                elif kind == "planned":
                    dependencies.add(balance.source)
        return quantity, dependencies

    def fail(self, resource, quantity, code, message, recipe_id=None, candidates=()):
        self.missing[resource] += quantity
        self.issue(code, message, resource, recipe_id, candidates)

    def demand(self, resource, quantity, path, consumer):
        quantity, dependencies = self.take(resource, quantity, consumer)
        if not quantity:
            return dependencies
        if resource in path:
            self.fail(
                resource,
                quantity,
                "cycle",
                "Cycle de recettes sans stock initial suffisant.",
                candidates=tuple(r.id for r in path + (resource,)),
            )
            return dependencies
        if len(path) >= self.max_depth or self.serial >= self.max_steps:
            self.fail(
                resource,
                quantity,
                "plan_limit",
                "Limite de profondeur ou d'étapes atteinte ; plan partiel.",
            )
            return dependencies
        candidates = self.index.get(resource, ())
        choice = self.recipe_choices.get(resource)
        if choice:
            candidates = (self.recipes[choice],)
        if not candidates:
            self.fail(
                resource,
                quantity,
                "missing_recipe",
                "Aucune recette déclarée : cette quantité reste à obtenir.",
            )
            return dependencies
        if len(candidates) > 1:
            self.fail(
                resource,
                quantity,
                "ambiguous_recipe",
                "Plusieurs recettes : choisir explicitement une variante.",
                candidates=tuple(r.id for r in candidates),
            )
            return dependencies
        recipe = candidates[0]
        if recipe.unsupported:
            self.fail(
                resource,
                quantity,
                "unsupported_recipe",
                "Conditions non interprétées : " + "; ".join(recipe.unsupported),
                recipe.id,
            )
            return dependencies
        yield_per_batch = sum(
            o.amount.quantity
            for o in recipe.outputs
            if o.guaranteed and o.amount.resource == resource
        )
        if not yield_per_batch:
            self.fail(
                resource,
                quantity,
                "probabilistic_target",
                "Aucune quantité garantie pour cette sortie probabiliste.",
                recipe.id,
            )
            return dependencies
        chosen = []
        ambiguous = False
        for i, ingredient in enumerate(recipe.inputs):
            selected = self.ingredient_choices.get(f"{recipe.id}#{i}")
            if len(ingredient.options) > 1 and selected is None:
                self.issue(
                    "ambiguous_ingredient",
                    f"Choisir une alternative pour {recipe.id}#{i}.",
                    resource,
                    recipe.id,
                    tuple(
                        f"{a.resource.kind}:{a.resource.id}:{a.resource.variant}"
                        for a in ingredient.options
                    ),
                )
                ambiguous = True
            else:
                chosen.append(
                    (
                        next(
                            (a for a in ingredient.options if a.resource == selected),
                            ingredient.options[0],
                        ),
                        ingredient.reusable,
                    )
                )
        if ambiguous:
            self.missing[resource] += quantity
            return dependencies
        batches = (quantity + yield_per_batch - 1) // yield_per_batch
        inputs = _amounts(
            tuple(
                Amount(a.resource, a.quantity * batches) for a, reusable in chosen if not reusable
            )
        )
        catalysts = _amounts(tuple(a for a, reusable in chosen if reusable))
        self.serial += 1
        step_id = f"step-{self.serial:04d}"
        children = set()
        # Reserve reusable tools before consumables. A held tool can be shared by
        # sequential steps, but cannot simultaneously pay a consumable demand.
        for catalyst in catalysts:
            required = max(0, catalyst.quantity - self.held_catalysts[catalyst.resource])
            if required:
                found = self.demand(
                    catalyst.resource, required, path + (resource,), step_id + ":catalyst"
                )
                self.catalyst_dependencies[catalyst.resource].update(found)
                self.held_catalysts[catalyst.resource] += required
            children.update(self.catalyst_dependencies[catalyst.resource])
        for amount in inputs:
            children.update(
                self.demand(amount.resource, amount.quantity, path + (resource,), step_id)
            )
        outputs = _amounts(
            tuple(
                Amount(o.amount.resource, o.amount.quantity * batches)
                for o in recipe.outputs
                if o.guaranteed
            )
        )
        duration = recipe.duration_ticks * batches if recipe.duration_ticks is not None else None
        energy = duration * recipe.eut if duration is not None and recipe.eut is not None else None
        self.steps.append(
            PlanStep(
                step_id,
                recipe.id,
                batches,
                inputs,
                catalysts,
                outputs,
                recipe.machines,
                recipe.circuit,
                duration,
                energy,
                tuple(sorted(children)),
                tuple(o for o in recipe.outputs if not o.guaranteed),
            )
        )
        for amount in outputs:
            self.pool[amount.resource].append(_Balance(amount.quantity, "planned", step_id))
        if not recipe.machines:
            self.issue(
                "machines_unknown",
                "Aucune machine compatible déclarée ; ne pas en déduire une depuis la catégorie.",
                resource,
                recipe.id,
                blocking=False,
            )
        if any(not output.guaranteed for output in recipe.outputs):
            self.issue(
                "probabilistic_coproduct",
                "Les sorties aléatoires ne sont pas créditées au plan garanti.",
                resource,
                recipe.id,
                blocking=False,
            )
        remaining, produced = self.take(resource, quantity, consumer)
        assert not remaining  # The selected recipe has a strictly positive guaranteed yield.
        dependencies.update(produced)
        return dependencies


def plan(
    catalog: Catalog,
    target: Amount,
    stock: StockLedger = StockLedger(),
    *,
    recipe_choices: Mapping[Resource, str] | None = None,
    ingredient_choices: Mapping[str, Resource] | None = None,
    max_depth: int = 128,
    max_steps: int = 4096,
) -> PlanResult:
    """Plan an explicit quantity with exact integer batch rounding.

    Choices use exact resource identity and ``recipe_id#input_index`` (zero based).
    Ambiguities are returned as partial plans, never resolved by an undocumented
    priority or an optimality claim. ``complete`` means material arithmetic was
    resolved, not that the machines or expected outputs are ready to use.
    """
    if (
        not isinstance(catalog, Catalog)
        or not isinstance(target, Amount)
        or not isinstance(stock, StockLedger)
    ):
        raise ValueError("Catalogue, cible et registre structurés requis.")
    _integer(max_depth, "Profondeur maximale", 1)
    _integer(max_steps, "Nombre maximal d'étapes", 1)
    if max_depth > 128 or max_steps > 4096:
        raise ValueError("Limites maximales : profondeur 128 et 4096 étapes.")
    if (
        recipe_choices is not None
        and not isinstance(recipe_choices, Mapping)
        or ingredient_choices is not None
        and not isinstance(ingredient_choices, Mapping)
    ):
        raise ValueError("Les choix doivent être des tables explicites.")
    calculation = _Planner(catalog, stock, recipe_choices, ingredient_choices, max_depth, max_steps)
    calculation.demand(target.resource, target.quantity, (), "target")
    surplus = defaultdict(int)
    for resource, balances in calculation.pool.items():
        for balance in balances:
            if balance.kind == "planned":
                surplus[resource] += balance.quantity
    return PlanResult(
        catalog.generation,
        target,
        "partial" if any(i.blocking for i in calculation.issues) else "complete",
        tuple(calculation.steps),
        _summary(calculation.missing),
        _summary(calculation.waiting),
        tuple(calculation.issues),
        tuple(calculation.allocations),
        _summary(surplus),
    )
