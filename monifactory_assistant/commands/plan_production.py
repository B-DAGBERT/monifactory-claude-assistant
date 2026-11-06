"""Calculate a V2 production plan from explicit local files, without opening the game."""

import argparse
import json
import sys
from pathlib import Path

from monifactory_assistant.game.world_scope import scope_file
from monifactory_assistant.production.catalog import CatalogueReader, decode_document
from monifactory_assistant.production.planner import ProductionLot, StockLedger, StockSource, plan
from monifactory_assistant.production.recipes import Amount, Resource


def _records(data, key):
    values = data.get(key, [])
    if not isinstance(values, list):
        raise ValueError(f"{key} doit être une liste.")
    return values


def _inventory(data, key, constructor, field):
    values = []
    for row in _records(data, key):
        if not isinstance(row, dict) or row.keys() != {"id", field}:
            raise ValueError(f"Entrée {key} invalide.")
        values.append(
            constructor(row["id"], tuple(Amount.from_dict(value) for value in _records(row, field)))
        )
    return tuple(values)


def _request(data):
    required = {"schema", "version", "target"}
    optional = {
        "sources",
        "reserved",
        "expected",
        "confirmed",
        "recipe_choices",
        "ingredient_choices",
    }
    if (
        not isinstance(data, dict)
        or required - data.keys()
        or data.keys() - required - optional
        or data["schema"] != "monifactory.production.request"
        or type(data["version"]) is not int
        or data["version"] != 1
    ):
        raise ValueError("Contrat de demande de production inconnu.")
    stock = StockLedger(
        sources=_inventory(data, "sources", StockSource, "contents"),
        reserved=tuple(Amount.from_dict(value) for value in _records(data, "reserved")),
        expected=_inventory(data, "expected", ProductionLot, "outputs"),
        confirmed=_inventory(data, "confirmed", ProductionLot, "outputs"),
    )
    recipes = {}
    for entry in _records(data, "recipe_choices"):
        if not isinstance(entry, dict) or entry.keys() != {"resource", "recipe"}:
            raise ValueError("Choix de recette invalide.")
        resource = Resource.from_dict(entry["resource"])
        if resource in recipes:
            raise ValueError("Choix de recette en double.")
        recipes[resource] = entry["recipe"]
    ingredients = {}
    for entry in _records(data, "ingredient_choices"):
        if not isinstance(entry, dict) or entry.keys() != {"recipe", "index", "resource"}:
            raise ValueError("Choix d’ingrédient invalide.")
        recipe, index = entry["recipe"], entry["index"]
        if not isinstance(recipe, str) or type(index) is not int or index < 0:
            raise ValueError("Identité du choix d’ingrédient invalide.")
        key = f"{recipe}#{index}"
        if key in ingredients:
            raise ValueError("Identité du choix d’ingrédient invalide ou répétée.")
        ingredients[key] = Resource.from_dict(entry["resource"])
    return Amount.from_dict(data["target"]), stock, recipes, ingredients


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalogue", type=Path, help="Document catalogue versionné et vérifié")
    parser.add_argument("demande", type=Path, help="Produit, stock et choix explicites")
    parser.add_argument("--source", required=True, help="Nom de la source attendue du catalogue")
    args = parser.parse_args(argv)
    try:
        view = CatalogueReader(args.catalogue, args.source).refresh()
        if view.catalog is None:
            raise ValueError(view.reason)
        with scope_file(args.demande.parent, args.demande.name).open("rb") as source:
            raw = source.read(4_194_305)
        if len(raw) > 4_194_304:
            raise ValueError("Demande trop volumineuse.")
        target, stock, recipes, ingredients = _request(decode_document(raw))
        result = plan(
            view.catalog, target, stock, recipe_choices=recipes, ingredient_choices=ingredients
        )
    except (OSError, TypeError, ValueError, RecursionError) as error:
        print(f"Calcul indisponible. {error}", file=sys.stderr)
        return 2
    result = result.to_dict()
    result["catalogue"] = {"source": view.source, "sha256": view.sha256, "status": view.status}
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
