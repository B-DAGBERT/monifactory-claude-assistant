"""Explicit immutable recipe facts for offline plans; never infer them from legacy text."""

import math
import re
from dataclasses import dataclass

SCHEMA = "monifactory.production.recipes"
_IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} doit être un entier >= {minimum}.")
    return value


def _identifier(value, name):
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ValueError(f"Identifiant invalide : {name}.")
    return value


def _fields(data, required, optional=()):
    if not isinstance(data, dict) or not set(required) <= data.keys():
        raise ValueError("Champs obligatoires absents du contrat recette.")
    if data.keys() - set(required) - set(optional):
        raise ValueError("Champ inconnu du contrat recette : version non prise en charge.")
    return data


def _sequence(value, name):
    if not isinstance(value, (tuple, list)):
        raise ValueError(f"{name} doit être une liste.")
    return tuple(value)


@dataclass(frozen=True, order=True)
class Resource:
    """The variant is an explicit canonical identity; no NBT/tool equivalence is guessed."""

    kind: str
    id: str
    unit: str
    variant: str = ""

    def __post_init__(self):
        if (
            self.kind not in ("item", "fluid")
            or self.unit != {"item": "item", "fluid": "mB"}[self.kind]
        ):
            raise ValueError("Ressource et unité incompatibles : item/item ou fluid/mB requis.")
        _identifier(self.id, "ressource")
        if not isinstance(self.variant, str):
            raise ValueError("Identité de variante invalide.")

    @classmethod
    def from_dict(cls, data):
        _fields(data, ("kind", "id", "unit"), ("variant",))
        return cls(data["kind"], data["id"], data["unit"], data.get("variant", ""))

    def to_dict(self):
        return {"kind": self.kind, "id": self.id, "unit": self.unit, "variant": self.variant}


@dataclass(frozen=True)
class Amount:
    resource: Resource
    quantity: int

    def __post_init__(self):
        if not isinstance(self.resource, Resource):
            raise ValueError("Ressource structurée requise.")
        _integer(self.quantity, "Quantité", 1)

    @classmethod
    def from_dict(cls, data):
        _fields(data, ("resource", "quantity"))
        return cls(Resource.from_dict(data["resource"]), data["quantity"])

    def to_dict(self):
        return {"resource": self.resource.to_dict(), "quantity": self.quantity}


@dataclass(frozen=True)
class Ingredient:
    options: tuple[Amount, ...]
    reusable: bool = False

    def __post_init__(self):
        options = _sequence(self.options, "Alternatives")
        if not options or any(not isinstance(a, Amount) for a in options):
            raise ValueError("Au moins une alternative structurée est requise.")
        if len({a.resource for a in options}) != len(options):
            raise ValueError("Alternatives répétées pour une même ressource.")
        if type(self.reusable) is not bool:
            raise ValueError("Le caractère réutilisable doit être explicite.")
        object.__setattr__(self, "options", options)

    @classmethod
    def from_dict(cls, data):
        _fields(data, ("options",), ("reusable",))
        return cls(
            tuple(Amount.from_dict(a) for a in _sequence(data["options"], "Alternatives")),
            data.get("reusable", False),
        )

    def to_dict(self):
        return {"options": [a.to_dict() for a in self.options], "reusable": self.reusable}


@dataclass(frozen=True)
class Output:
    amount: Amount
    chance_numerator: int = 1
    chance_denominator: int = 1

    def __post_init__(self):
        if not isinstance(self.amount, Amount):
            raise ValueError("Sortie structurée requise.")
        _integer(self.chance_numerator, "Numérateur de probabilité")
        _integer(self.chance_denominator, "Dénominateur de probabilité", 1)
        if self.chance_numerator > self.chance_denominator:
            raise ValueError("Probabilité supérieure à 1.")
        diviseur = math.gcd(self.chance_numerator, self.chance_denominator)
        object.__setattr__(self, "chance_numerator", self.chance_numerator // diviseur)
        object.__setattr__(self, "chance_denominator", self.chance_denominator // diviseur)

    @property
    def guaranteed(self):
        return self.chance_numerator == self.chance_denominator

    @classmethod
    def from_dict(cls, data):
        _fields(data, ("amount",), ("chance",))
        chance = data.get("chance", {"numerator": 1, "denominator": 1})
        _fields(chance, ("numerator", "denominator"))
        return cls(Amount.from_dict(data["amount"]), chance["numerator"], chance["denominator"])

    def to_dict(self):
        return {
            "amount": self.amount.to_dict(),
            "chance": {"numerator": self.chance_numerator, "denominator": self.chance_denominator},
        }


@dataclass(frozen=True)
class Recipe:
    id: str
    category: str
    inputs: tuple[Ingredient, ...]
    outputs: tuple[Output, ...]
    machines: tuple[str, ...] = ()
    duration_ticks: int | None = None
    eut: int | None = None
    circuit: int | None = None
    unsupported: tuple[str, ...] = ()

    def __post_init__(self):
        _identifier(self.id, "recette")
        _identifier(self.category, "catégorie")
        inputs = _sequence(self.inputs, "Entrées")
        outputs = _sequence(self.outputs, "Sorties")
        if (
            any(not isinstance(i, Ingredient) for i in inputs)
            or not outputs
            or any(not isinstance(o, Output) for o in outputs)
        ):
            raise ValueError("Entrées et sorties de recette invalides.")
        machines = _sequence(self.machines, "Machines")
        for machine in machines:
            _identifier(machine, "machine")
        unsupported = _sequence(self.unsupported, "Limites")
        if any(not isinstance(s, str) or not s.strip() for s in unsupported):
            raise ValueError("Limite de recette invalide.")
        for value, name in (
            (self.duration_ticks, "Durée"),
            (self.eut, "EU/t"),
            (self.circuit, "Circuit"),
        ):
            if value is not None:
                _integer(value, name)
        if self.circuit is not None and self.circuit > 32:
            raise ValueError("Circuit programmé hors de l'intervalle 0..32.")
        object.__setattr__(self, "inputs", inputs)
        object.__setattr__(self, "outputs", outputs)
        object.__setattr__(self, "machines", tuple(sorted(set(machines))))
        object.__setattr__(self, "unsupported", unsupported)

    @classmethod
    def from_dict(cls, data):
        _fields(
            data,
            ("id", "category", "inputs", "outputs"),
            ("machines", "duration_ticks", "eut", "circuit", "unsupported"),
        )
        return cls(
            data["id"],
            data["category"],
            tuple(Ingredient.from_dict(i) for i in _sequence(data["inputs"], "Entrées")),
            tuple(Output.from_dict(o) for o in _sequence(data["outputs"], "Sorties")),
            _sequence(data.get("machines", []), "Machines"),
            data.get("duration_ticks"),
            data.get("eut"),
            data.get("circuit"),
            _sequence(data.get("unsupported", []), "Limites"),
        )

    def to_dict(self):
        return {
            "id": self.id,
            "category": self.category,
            "inputs": [i.to_dict() for i in self.inputs],
            "outputs": [o.to_dict() for o in self.outputs],
            "machines": list(self.machines),
            "duration_ticks": self.duration_ticks,
            "eut": self.eut,
            "circuit": self.circuit,
            "unsupported": list(self.unsupported),
        }


@dataclass(frozen=True)
class Catalog:
    generation: int
    recipes: tuple[Recipe, ...]
    version: int = 1

    def __post_init__(self):
        _integer(self.generation, "Génération", 1)
        if type(self.version) is not int or self.version != 1:
            raise ValueError("Version du contrat recette inconnue.")
        recipes = _sequence(self.recipes, "Recettes")
        if any(not isinstance(r, Recipe) for r in recipes):
            raise ValueError("Recettes structurées requises.")
        if len({r.id for r in recipes}) != len(recipes):
            raise ValueError("Identifiant de recette répété dans une génération.")
        object.__setattr__(self, "recipes", tuple(sorted(recipes, key=lambda r: r.id)))

    @classmethod
    def from_dict(cls, data):
        _fields(data, ("schema", "version", "generation", "recipes"))
        if data["schema"] != SCHEMA:
            raise ValueError("Contrat recette inconnu.")
        return cls(
            data["generation"],
            tuple(Recipe.from_dict(r) for r in _sequence(data["recipes"], "Recettes")),
            data["version"],
        )

    def to_dict(self):
        return {
            "schema": SCHEMA,
            "version": self.version,
            "generation": self.generation,
            "recipes": [r.to_dict() for r in self.recipes],
        }
