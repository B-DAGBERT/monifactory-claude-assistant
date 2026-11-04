"""Local objective snapshot for EMI; does not modify progression or choose recipes."""


def objective_tree(plan, project="", version=""):
    if not plan:
        return None
    targets = {}
    for target in plan.get("cibles", []):
        item = target.get("item")
        if not item:
            continue
        quantity = max(1, int(target.get("quantite") or 1)) + max(0, int(target.get("deja") or 0))
        targets[item] = targets.get(item, 0) + quantity
    if not targets:
        return None
    return {
        "titre": plan.get("objectif", "Objectif"),
        "projet": str(project),
        "version": str(version),
        "cibles": [{"item": item, "quantite": quantity} for item, quantity in targets.items()],
        "recettes": [
            {"item": recipe["item"], "id": recipe.get("id") or ""}
            for recipe in plan.get("recettes", [])
            if recipe.get("item")
        ],
    }
