"""Local objective snapshot for EMI; does not modify progression or choose recipes."""


def objective_tree(plan, project="", version="", *, progress=None, inventory=None):
    if not plan:
        return None
    targets = {}
    for target in plan.get("cibles", []):
        item = target.get("item")
        if not item:
            continue
        # Les machines déjà posées ne comptent pas : le mod ne voit que l'inventaire du joueur.
        # Les anciens plans sans deja_inventaire gardent leur calcul.
        extra = target.get("deja_inventaire", target.get("deja"))
        quantity = max(1, int(target.get("quantite") or 1)) + max(0, int(extra or 0))
        targets[item] = targets.get(item, 0) + quantity
    if progress is not None:
        inventory = inventory or {}
        for item in list(targets):
            goal = progress.get("buts", {}).get(item, targets[item])
            remaining = max(0, goal - progress.get("comptes", {}).get(item, 0))
            if item in progress.get("atteints", ()) or remaining == 0:
                del targets[item]
            else:
                # EMI déduit l'inventaire courant lui-même. Le crédit rangé/posé reste acquis.
                targets[item] = remaining + max(0, int(inventory.get(item, 0)))
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
