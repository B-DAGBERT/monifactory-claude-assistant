"""Calcul local des besoins, des étapes et des recettes des objectifs."""

import math
from collections import defaultdict

from monifactory_assistant.game.world import block_name


def objective_entry(data, state="a_faire"):
    """Un objectif du plan : titre court, ce qu'il faudra faire, et où on en est (a_faire, en_cours, fait)."""
    title = str(data.get("titre") or data.get("demande") or "Objectif")
    return {
        "titre": title[:60],
        "demande": str(data.get("demande") or title),
        "etat": data.get("etat") or state,
    }


def item_names(plan):
    names = {}
    for r in plan["recettes"]:
        names[r["item"]] = r["nom"]
        for e in r["entrees"] + r["outils"]:
            names.setdefault(e["item"], e["nom"])
    for t in plan["cibles"]:
        names[t["item"]] = t["nom"]
    return names


TOOL_TYPES = [
    "wire_cutter",
    "screwdriver",
    "hammer",
    "file",
    "saw",
    "wrench",
    "mortar",
    "knife",
    "crowbar",
    "mallet",
    "plunger",
]


def has_tool(tool, have):
    """Vrai si le joueur a cet outil, ou le même type d'outil dans un autre matériau (Diamond Hammer pour Iron Hammer)."""
    if have.get(tool, 0) >= 1:
        return True
    kind = next((k for k in TOOL_TYPES if tool.endswith("_" + k)), None)
    namespace = tool.split(":")[0]
    return kind is not None and any(
        i.endswith("_" + kind) and i.split(":")[0] == namespace and n > 0 for i, n in have.items()
    )


def evaluate(plan, have, reached, done_steps, stored=None, placed=None, undone=(), obtained=None):
    """Où en est l'objectif : objets encore à fabriquer, à récupérer dans les coffres, matières premières qui
    manquent, étapes faites, et combien il faut encore de chaque objet (ça baisse à chaque fabrication : une Bronze
    Plate faite, c'est 2 Bronze Ingots de moins à trouver).

    reached (cibles déjà obtenues) et done_steps (étapes cochées, à la main ou toutes seules) sont mémorisés :
    une cible posée dans le monde, ou un objet intermédiaire consommé, ne fait pas revenir en arrière. undone :
    les étapes que le joueur a décochées à la main. stored : objets dans les coffres ; placed : machines déjà
    posées. obtained : ce que le joueur a obtenu de chaque cible depuis le début de l'objectif, même s'il l'a déjà
    posée ailleurs (les blocs d'un multibloc, par exemple). Une cible « deja » (le joueur en avait déjà assez quand
    l'objectif a été créé) en demande autant de plus : « un Solid Boiler » quand deux sont posés, c'est un troisième."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    placed, stored, obtained = placed or {}, stored or {}, obtained or {}

    def goal(t):
        return t["quantite"] + t.get("deja", 0)

    def count(
        t,
    ):  # sur lui + dans ses coffres + posé, ou ce qu'il en a obtenu s'il l'a posé ailleurs depuis
        item = t["item"]
        return max(
            have.get(item, 0) + stored.get(item, 0) + placed.get(item, 0), obtained.get(item, 0)
        )

    reached = set(reached)
    stock, chests = dict(have), dict(stored)
    to_make, from_chests, missing, tools = defaultdict(int), defaultdict(int), defaultdict(int), {}
    from_stock = defaultdict(int)  # ce que la suite de l'objectif prend dans son inventaire

    def need(item, quantity, depth=0):
        used = min(stock.get(item, 0), quantity)  # d'abord ce que le joueur a sur lui
        stock[item] = stock.get(item, 0) - used
        if used:
            from_stock[item] += used
        quantity -= used
        used = min(chests.get(item, 0), quantity)  # puis ce qu'il a dans ses coffres
        if used:
            chests[item] -= used
            from_chests[item] += used
            quantity -= used
        if quantity <= 0:
            return
        recipe = recipes.get(item)
        if recipe is None or depth > 15:
            missing[item] += quantity
            return
        to_make[item] += quantity
        crafts = math.ceil(quantity / recipe["sortie"])
        for tool in recipe["outils"]:
            tools.setdefault(tool["item"], tool)
        for e in recipe["entrees"]:
            need(e["item"], crafts * e["quantite"], depth + 1)

    # Une cible qui sert aussi à en fabriquer une autre (6 Steam Machine Casings dans la recette de la Steam Foundry,
    # en plus des 20 du multibloc) passe en dernier : ce que l'autre fabrication prend ne compte pas pour elle.
    ingredients = {e["item"] for r in plan["recettes"] for e in r["entrees"]}
    counts = {}
    for target in sorted(plan["cibles"], key=lambda t: t["item"] in ingredients):
        item = target["item"]
        counts[item] = max(0, count(target) - from_stock.get(item, 0) - from_chests.get(item, 0))
        if item in reached:
            continue
        if counts[item] >= goal(target):
            reached.add(item)
        else:  # need() reprend ce qui lui reste sur lui et dans ses coffres, puis fabrique le reste
            need(item, goal(target) - counts[item] + stock.get(item, 0) + chests.get(item, 0))
    for tool in list(tools):  # un outil sert à toutes les fabrications : il en faut un seul
        if not has_tool(tool, have):
            need(tool, 1)

    complete = bool(plan["cibles"]) and all(t["item"] in reached for t in plan["cibles"])
    tree = (
        set(recipes)
        | {t["item"] for t in plan["cibles"]}
        | {e["item"] for r in plan["recettes"] for e in r["entrees"]}
    )
    targets = {t["item"] for t in plan["cibles"]}
    steps = []
    for i, step in enumerate(plan["etapes"]):
        item = step.get("item")
        if item in targets:  # l'étape qui fabrique une cible est faite quand la cible l'est
            made = item in reached
        else:
            made = bool(item) and (
                have.get(item, 0) >= (step.get("quantite") or 1)
                or (item in tree and item not in to_make and item not in missing)
            )
        steps.append(complete or i in done_steps or made)
    # Une étape qui ne produit rien est faite dès qu'une étape suivante l'est
    later_done = False
    for i in range(len(steps) - 1, -1, -1):
        if steps[i]:
            later_done = True
        elif later_done and not plan["etapes"][i].get("item"):
            steps[i] = True
    steps = [done and i not in undone for i, done in enumerate(steps)]
    # Toutes les étapes cochées (à la main ou non) : l'objectif est terminé, même sans cible vérifiable
    complete = complete or bool(steps) and all(steps)
    # Combien il faut encore de chaque objet pour finir (sur lui compris) : recalculé à chaque fabrication
    needs = {
        item: sum(d.get(item, 0) for d in (from_stock, from_chests, to_make, missing))
        for item in set(from_stock) | set(from_chests) | set(to_make) | set(missing)
    }
    return {
        "a_fabriquer": dict(to_make),
        "a_recuperer": dict(from_chests),
        "manque": dict(missing),
        "atteints": reached,
        "etapes": steps,
        "termine": complete,
        "besoins": needs,
        "comptes": counts,
        "buts": {t["item"]: goal(t) for t in plan["cibles"]},
    }


def machine_label(machine_id):
    """« gtceu:lp_steam_compressor » -> « LP Steam Compressor »."""
    words = block_name(machine_id).split(" ")
    return " ".join(
        w.upper() if w.lower() in ("lp", "hp", "lv", "mv", "hv", "ev", "iv") else w for w in words
    )


def needed_machines(plan, progress, index, have, placed):
    """Les machines dont les fabrications qui restent ont besoin (un Compressor pour la Compressed Fireclay), et
    celle que le joueur a déjà (posée ou sur lui), sinon None : sans elle, l'objectif ne peut pas avancer.
    L'établi et le four (catégories minecraft:) ne sont pas comptés : il en a toujours."""
    needed = {}
    for recipe in plan["recettes"]:
        if recipe["item"] not in progress["a_fabriquer"]:
            continue
        category, machines = index.machines_for(recipe.get("id"))
        if not machines or category.startswith("minecraft:"):
            continue
        entry = needed.setdefault(category, {"machines": machines, "pour": [], "a": None})
        entry["pour"].append(recipe["nom"])
        entry["a"] = entry["a"] or next((m for m in machines if placed.get(m) or have.get(m)), None)
    return list(needed.values())


def step_count(step, progress, have):
    """L'objet d'une étape : (ce qu'il en a, ce qu'il en faut), recalculé à chaque fabrication. « 276 Bronze
    Ingots » au départ devient 274 dès qu'une Bronze Plate est faite. None si l'étape ne produit rien."""
    item = step.get("item")
    if not item:
        return None
    if (
        item in progress["buts"]
    ):  # une cible : ce qu'il en a obtenu (même déjà posé) sur ce qu'il en veut
        return min(progress["comptes"][item], progress["buts"][item]), progress["buts"][item]
    if (
        item in progress["besoins"]
    ):  # un objet utilisé plus loin : ce qu'il en faut encore, sur lui compris
        need = progress["besoins"][item]
        return min(have.get(item, 0), need), need
    return min(have.get(item, 0), step.get("quantite") or 1), step.get("quantite") or 1


def step_text(step, done, progress, have):
    """Le texte de l'étape, avec ce qu'il reste à obtenir tant qu'elle n'est pas faite (« · encore 117 »)."""
    counts = None if done else step_count(step, progress, have)
    left = counts[1] - counts[0] if counts else 0
    return step["texte"] + (f" · encore {left}" if left > 0 else "")


def step_recipe(plan, progress):
    """La recette à faire maintenant : celle de l'étape en cours, sinon du premier objet à fabriquer."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    current = next((s for s, done in zip(plan["etapes"], progress["etapes"]) if not done), None)
    if current and current.get("item") in recipes:
        return recipes[current["item"]]
    return next((recipes[i] for i in progress["a_fabriquer"] if i in recipes), None)


def craft_suggestions(plan, progress, limit=12):
    """Les fabrications de l'objectif encore à faire, les plus utiles d'abord : l'objet de l'étape en cours, puis ce
    qu'il demande (du plus proche au plus lointain), puis les étapes suivantes et le reste de l'objectif. Le mod ne
    garde que celles que l'écran ouvert sait remplir (l'établi : pas les recettes de machines) et que l'inventaire
    permet tout de suite, en tenant compte des équivalents (n'importe quelles planches, n'importe quel Hammer)."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    todo = progress["a_fabriquer"]
    order, seen = [], set()

    def add_tree(item):
        waiting = [item]
        while waiting:
            current = waiting.pop(0)
            if current in seen or current not in recipes:
                continue
            seen.add(current)
            if todo.get(current):
                order.append(current)
            waiting += [e["item"] for e in recipes[current]["entrees"]]

    for step, done in zip(plan["etapes"], progress["etapes"]):
        if not done and step.get("item"):
            add_tree(step["item"])
    for item in [t["item"] for t in plan["cibles"]] + list(todo):
        add_tree(item)
    return [
        {
            "item": i,
            "nom": recipes[i]["nom"],
            "id": recipes[i].get("id"),
            "machine": recipes[i]["machine"],
            "fois": max(1, math.ceil(todo[i] / recipes[i]["sortie"])),
        }
        for i in order[:limit]
    ]
