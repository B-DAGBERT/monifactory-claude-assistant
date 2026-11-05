"""Vues exportées vers le HUD et les menus du mod."""

import fnmatch
import math
import re
import sys
import time
from collections import defaultdict

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.game.context import where_hint
from monifactory_assistant.game.emi import objective_tree
from monifactory_assistant.game.objectives import (
    craft_suggestions,
    item_names,
    machine_label,
    needed_machines,
    step_count,
    step_recipe,
    step_text,
)
from monifactory_assistant.game.veins import nearest_vein
from monifactory_assistant.game.world import block_name, machine_color, machine_status
from monifactory_assistant.paths import ROOT as HERE
from monifactory_assistant.settings import FOOTER, HOTKEY_PLAN, MAX_MISSING, MAX_TO_MAKE, SETTINGS


def build_view(
    plan,
    progress,
    state,
    storage,
    have,
    status,
    message,
    guide=None,
    outdated=False,
    roadmap=(),
    veins=(),
    alerts=(),
    server=False,
):
    """Les lignes affichées dans le jeu (le script KubeJS les dessine telles quelles)."""
    lines = []

    def add(text, color="blanc", item="", direction=None, indent=0, icons=()):
        lines.append(
            {
                "t": text,
                "c": color,
                "item": item,
                "dir": direction,
                "indent": indent,
                "icones": list(icons),
            }
        )

    if outdated:
        add("⚠ Script du jeu à recharger : appuie sur F3 + T", "rouge")
    for alert in alerts:
        add(alert, "rouge" if alert.startswith("⚠") else "jaune")

    if plan:
        names = item_names(plan)
        recipes = {r["item"]: r for r in plan["recettes"]}
        add("✦ " + plan["objectif"], "or")
        upcoming = [o["titre"] for o in roadmap if o.get("etat", "a_faire") == "a_faire"]
        if upcoming:
            add(
                "Ensuite : "
                + " · ".join(upcoming[:2])
                + (f" (+{len(upcoming) - 2})" if len(upcoming) > 2 else ""),
                "gris",
                indent=8,
            )
        steps, states = plan["etapes"], progress["etapes"]
        current = next((i for i, done in enumerate(states) if not done), None)
        if steps and current is None:
            add(f"✔ Les {len(steps)} étapes sont faites", "vert")
        elif steps:
            if any(states):
                add(f"✔ {sum(states)}/{len(steps)} étapes faites", "vert")
            add("▶ " + step_text(steps[current], False, progress, have), "jaune", indent=8)
            for i in range(current + 1, min(current + 3, len(steps))):
                if not states[i]:
                    add("   " + steps[i]["texte"], "gris", indent=8)
        for t in plan["cibles"]:
            ok = t["item"] in progress["atteints"]
            goal = progress["buts"][t["item"]]
            count = goal if ok else min(progress["comptes"][t["item"]], goal)
            add(
                f"{'✔ ' if ok else ''}{t['nom']}  {count}/{goal}",
                "vert" if ok else "blanc",
                t["item"],
            )
        recipe = step_recipe(plan, progress)
        if recipe and recipe["entrees"]:
            machine = recipe["machine"] if recipe["machine"].lower() != "crafting table" else ""
            add(
                f"Recette{' (' + machine + ')' if machine else ''} :",
                "gris",
                icons=[{"item": e["item"], "n": e["quantite"]} for e in recipe["entrees"][:9]],
            )
        targets = {t["item"] for t in plan["cibles"]}
        intermediates = [(i, n) for i, n in progress["a_fabriquer"].items() if i not in targets]
        if intermediates:
            add("À fabriquer :", "gris")
            for item, count in intermediates[:MAX_TO_MAKE]:
                machine = recipes[item]["machine"]
                where = f" · {machine}" if machine and machine.lower() != "crafting table" else ""
                add(f"{names.get(item, item)} ×{count}{where}", "blanc", item)
            if len(intermediates) > MAX_TO_MAKE:
                add(f"   +{len(intermediates) - MAX_TO_MAKE} autres", "gris")
        go_to = None  # le prochain endroit utile : un coffre où récupérer, sinon un filon connu
        if progress["a_recuperer"] and state:
            add("À récupérer dans tes coffres :", "jaune")
            pickups = sorted(progress["a_recuperer"].items(), key=lambda kv: -kv[1])
            for item, count in pickups[:MAX_MISSING]:
                _, chest = storage.find(item, state)
                hint = f" · {where_hint(chest, state)}" if chest else ""
                if chest and go_to is None:
                    go_to = (f"Coffre ({names.get(item, item)})", chest)
                add(f"{names.get(item, item)} ×{count}{hint}", "jaune", item)
            if len(pickups) > MAX_MISSING:
                add(f"   +{len(pickups) - MAX_MISSING} autres", "gris")
        if progress["manque"]:
            add("À récolter :", "rouge")
            missing = sorted(progress["manque"].items(), key=lambda kv: -kv[1])
            for item, count in missing[:MAX_MISSING]:
                vein = nearest_vein(item, veins, state)
                hint = f" · filon à {where_hint(vein, state)}" if vein else ""
                if vein and go_to is None:
                    go_to = (f"Filon de {vein['minerai'].replace('_', ' ').title()}", vein)
                add(f"{names.get(item, item)} ×{count}{hint}", "rouge", item)
            if len(missing) > MAX_MISSING:
                add(f"   +{len(missing) - MAX_MISSING} autres", "gris")
        if plan.get("cible"):
            add("➜ " + plan["cible"]["label"], "bleu", direction=plan["cible"])
        elif go_to:
            label, where = go_to
            # y à None : pas de « monte de … » dans le panneau ; yb : hauteur du faisceau dessiné par le mod
            add(
                "➜ " + label,
                "bleu",
                direction={
                    "x": where["x"],
                    "y": None,
                    "z": where["z"],
                    "yb": where["y"],
                    "dimension": where.get("dimension"),
                },
            )
    elif state:
        add(f"Appuie sur {HOTKEY_PLAN.upper()} pour ton prochain objectif.", "gris")
    if guide:
        add("➜ " + guide["label"], "bleu", direction=guide)
    if status:
        add(status, "gris")
    if message:
        add("", "gris")
        for part in message.split("\n"):
            if part.strip():
                add(part.strip(), "blanc")
    chests, machines = len(storage.containers), len(storage.machines)
    add(
        f"{FOOTER} · {chests} coffre{'s' if chests > 1 else ''} · {machines} machine{'s' if machines > 1 else ''}"
        + (" · serveur ✔" if server else ""),
        "gris",
    )
    return lines


def find_search(search, storage, have, state):
    """Les coffres connus qui contiennent les objets cherchés (les plus proches d'abord), avec ce qu'il y a dedans,
    et le résumé pour le panneau : « Torches : 34 dans 2 coffres · 12 sur toi »."""
    patterns = search["objets"]

    def wanted(item):
        return any(fnmatch.fnmatchcase(item, p) for p in patterns)

    chests = []
    for c in storage.containers:
        hits = defaultdict(int)
        for o in c["objets"]:
            if wanted(o["item"]):
                hits[o["item"]] += int(o["n"])
        if hits:
            best = sorted(hits.items(), key=lambda kv: -kv[1])
            chests.append(
                {
                    "x": c["x"],
                    "y": c["y"],
                    "z": c["z"],
                    "dimension": c["dimension"],
                    "n": sum(hits.values()),
                    "texte": ", ".join(f"{block_name(i)} ×{n}" for i, n in best[:2])
                    + (" …" if len(best) > 2 else ""),
                }
            )
    if state:
        chests.sort(
            key=lambda c: (
                c["dimension"] != state.get("dimension"),
                math.dist((c["x"], c["y"], c["z"]), (state["x"], state["y"], state["z"])),
            )
        )
    on_me = sum(n for i, n in have.items() if wanted(i))
    total = sum(c["n"] for c in chests)
    summary = (
        f"{total} dans {len(chests)} coffre{'s' if len(chests) > 1 else ''}"
        if chests
        else "dans aucun coffre connu"
    ) + (f" · {on_me} sur toi" if on_me else "")
    return {
        "label": search["label"],
        "objets": patterns,
        "coffres": chests[:16],
        "resume": f"{search['label']} : {summary}",
    }


def build_mod_view(app):
    """Tout ce que le mod Minecraft affiche, sous forme de données (le mod s'occupe de la mise en forme) :
    - hud : le panneau compact (objectif, progression, étape en cours, 3 prochains objets, direction, alertes) ;
    - bulle : la dernière réponse de Codex ou du coach, qui s'efface toute seule ;
    - besoins, guidage, marqueurs : surbrillance dans les interfaces et faisceaux dans le monde ;
    - ecran : le tableau de bord de la touche Y ; recette : la fabrication en cours, pour le bouton « Remplir »."""
    plan, progress, state, storage = app.plan, app.progress(), app.game, app.storage
    paused = app.objective_paused
    have, now = app.have(), time.time()
    names = item_names(plan) if plan else {}
    recipes = {r["item"]: r for r in plan["recettes"]} if plan else {}
    dimension = (state or {}).get("dimension")

    def where(item):
        """Où trouver un objet qui manque : coffre le plus proche, ou filon connu."""
        if not state:
            return "", None
        _, chest = storage.find(item, state)
        if chest:
            return f"coffre · {where_hint(chest, state)}", chest
        vein = nearest_vein(item, app.veins, state)
        if vein:
            return f"filon · {where_hint(vein, state)}", vein
        return "", None

    hud = {
        "visible": app.visible,
        "recharger": app.bridge.script_outdated(state),
        "statut": app.status,
        "dimension_actuelle": (state or {}).get("dimension", ""),
        "demandes": app.request_queue.view()
        if getattr(app, "request_queue", None) is not None
        else {"protocole": 1},
        "mise_a_jour": getattr(app, "update_notice", ""),
        "mise_a_jour_id": getattr(app, "update_notice_id", ""),
        "alertes": [],
        "prochains": [],
        "direction": None,
    }
    current = app.current_objective()
    if app.objectives:  # « Plan : Passer en LV · 2/6 » au-dessus de l'objectif
        position = f" · {current + 1}/{len(app.objectives)}" if current is not None else ""
        hud["plan"] = f"Plan : {app.plan_title or 'en cours'}{position}"
    sections, needed, beams, recipe_now, crafts = [], set(), [], None, []
    search = (
        find_search(app.search, storage, have, state)
        if app.search and now < app.search["fin"]
        else None
    )
    if search:
        hud["recherche"] = search.pop("resume")
    if plan and progress:
        steps, states = plan["etapes"], progress["etapes"]
        current = next((i for i, done in enumerate(states) if not done), None)
        targets = plan["cibles"]
        target_share = sum(
            min(1, have.get(t["item"], 0) / t["quantite"])
            if t["item"] not in progress["atteints"]
            else 1
            for t in targets
        ) / max(1, len(targets))

        def step_share(i):
            """Une étape pas encore faite compte pour ce que tu as déjà de son objet (159/250 Bronze Ingots)."""
            counts = None if states[i] else step_count(steps[i], progress, have)
            if not counts:
                return float(states[i])
            return min(1, counts[0] / counts[1]) if counts[1] else 1.0

        def how(item):
            return "à fabriquer" if item in recipes or item in app.craftable else "à récolter"

        hud.update(
            {
                "objectif": plan["objectif"],
                "progres": round(sum(map(step_share, range(len(states)))) / len(states), 3)
                if states
                else round(target_share, 3),
                "etape": step_text(steps[current], False, progress, have)
                if current is not None
                else "Toutes les étapes sont faites",
                "etape_num": f"{(current if current is not None else len(steps)) + (1 if current is not None else 0)}"
                f"/{len(steps)}",
            }
        )
        # Les 3 prochains objets utiles : à récupérer d'abord (le plus simple), puis l'objet de l'étape, puis à récolter
        upcoming = []
        for item, count in sorted(progress["a_recuperer"].items(), key=lambda kv: -kv[1]):
            hint, _ = where(item)
            upcoming.append(
                {
                    "item": item,
                    "nom": names.get(item, item),
                    "a": have.get(item, 0),
                    "besoin": have.get(item, 0) + count,
                    "ou": hint or "dans tes coffres",
                    "c": "jaune",
                }
            )
        step_item = steps[current].get("item") if current is not None else None
        if step_item and step_item not in {u["item"] for u in upcoming}:
            got, wanted = step_count(steps[current], progress, have)
            upcoming.append(
                {
                    "item": step_item,
                    "nom": names.get(step_item, block_name(step_item)),
                    "a": got,
                    "besoin": wanted,
                    "ou": how(step_item),
                    "c": "blanc",
                }
            )
        for item, count in sorted(progress["manque"].items(), key=lambda kv: -kv[1]):
            if item in {u["item"] for u in upcoming}:
                continue
            hint, _ = where(item)
            upcoming.append(
                {
                    "item": item,
                    "nom": names.get(item, item),
                    "a": have.get(item, 0),
                    "besoin": have.get(item, 0) + count,
                    "ou": hint or how(item),
                    "c": "rouge",
                }
            )
        hud["prochains"] = upcoming[:3]
        needed |= {t["item"] for t in targets if t["item"] not in progress["atteints"]}
        needed |= (
            set(progress["a_fabriquer"]) | set(progress["a_recuperer"]) | set(progress["manque"])
        )

        def section(title, color, entries):
            if entries:
                sections.append({"titre": title, "couleur": color, "objets": entries})

        section(
            "Objectif",
            "or",
            [
                {
                    "item": t["item"],
                    "nom": t["nom"],
                    "texte": ("✔ " if t["item"] in progress["atteints"] else "")
                    + f"{min(progress['comptes'][t['item']], progress['buts'][t['item']])}/{progress['buts'][t['item']]}",
                }
                for t in targets
            ],
        )
        # Les machines dont l'objectif a besoin : sans elles, impossible d'avancer, donc on le dit tout de suite
        machines_needed = needed_machines(plan, progress, app.recipe_index, have, storage.placed())
        lacking = [m for m in machines_needed if not m["a"]]
        section(
            "Machines nécessaires",
            "rouge" if lacking else "vert",
            [
                {
                    "item": m["a"] or m["machines"][0],
                    "nom": machine_label(m["a"] or m["machines"][0]),
                    "texte": ("✔ tu en as une" if m["a"] else "✘ à fabriquer d'abord")
                    + " · pour "
                    + ", ".join(m["pour"][:2]),
                }
                for m in machines_needed
            ],
        )
        hud["alertes"] = (
            [
                f"⚠ Il te faut un {machine_label(m['machines'][0])} (pour {', '.join(m['pour'][:2])})"
                for m in lacking
            ]
            + hud["alertes"]
        )[:3]
        needed |= {m["machines"][0] for m in lacking}  # elle brille si elle traîne dans un coffre
        section(
            "À fabriquer",
            "blanc",
            [
                {
                    "item": i,
                    "nom": names.get(i, i),
                    "texte": f"×{n}"
                    + (
                        f" · {recipes[i]['machine']}"
                        if recipes[i]["machine"]
                        and recipes[i]["machine"].lower() != "crafting table"
                        else ""
                    ),
                }
                for i, n in progress["a_fabriquer"].items()
                if i not in {t["item"] for t in targets}
            ]
            + [
                {
                    "item": i,
                    "nom": names.get(i, i),
                    "texte": f"×{n}",
                }  # fabricables, mais hors des recettes du plan
                for i, n in progress["manque"].items()
                if i in app.craftable
            ],
        )
        section(
            "À récupérer dans tes coffres",
            "jaune",
            [
                {"item": i, "nom": names.get(i, i), "texte": f"×{n} · " + (where(i)[0] or "coffre")}
                for i, n in progress["a_recuperer"].items()
            ],
        )
        section(
            "À récolter",
            "rouge",
            [
                {
                    "item": i,
                    "nom": names.get(i, i),
                    "texte": f"×{n}" + (f" · {where(i)[0]}" if where(i)[0] else ""),
                }
                for i, n in progress["manque"].items()
                if i not in app.craftable
            ],
        )
        crafts = craft_suggestions(plan, progress)
        recipe_now = crafts[0] if crafts else None  # pour un mod d'avant les propositions multiples
    # La destination principale : celle que montre aussi le panneau du script du jeu (lignes « ➜ »)
    for line in (
        build_view(
            None if paused else plan,
            None if paused else progress,
            state,
            storage,
            have,
            "",
            "",
            app.guide,
            False,
            app.objectives,
            app.veins,
        )
        if state
        else []
    ):
        d = line.get("dir")
        if d and d.get("x") is not None and d.get("z") is not None:
            target = {
                "x": d["x"],
                "y": d.get("yb", d.get("y")),
                "z": d["z"],
                "couleur": "bleu",
                "label": line["t"].removeprefix("➜ "),
                "dimension": d.get("dimension") or dimension,
            }
            beams.append(target)
            hud["direction"] = hud["direction"] or target
    live = app.message if app.message and not app.message_until else ""
    bubble = None
    if app.message and (not app.message_until or now < app.message_until):
        bubble = {
            "texte": app.message,
            "en_cours": bool(live),
            "fin": int(app.message_until * 1000),
        }
    machines = [
        {
            "nom": m["nom"],
            "etat": machine_status(m),
            "x": m["x"],
            "y": m["y"],
            "z": m["z"],
            "couleur": machine_color(machine_status(m)),
            "item": m["bloc"],
            "dimension": m.get("dimension", ""),
            "vu": m.get("t", 0),
        }
        for m in app.world.machines()
        if not re.search(r"hatch|_bus$", m["bloc"])
    ]
    screen = {
        "statut": app.status,
        "dimension_actuelle": (state or {}).get("dimension", ""),
        "demandes": app.request_queue.view()
        if getattr(app, "request_queue", None) is not None
        else {"protocole": 1},
        "mise_a_jour": getattr(app, "update_notice", ""),
        "mise_a_jour_id": getattr(app, "update_notice_id", ""),
        "question_recue": getattr(app, "question_ack", {}),
        "derniere_demande": getattr(app, "mod_request_n", None),
        "erreur_connexion": getattr(app, "connection_problem", ""),
        "objectif_en_pause": paused,
        "objectif_version": str(app.plan_version),
        "occupe": app.busy,
        "projets": app.project_view(),
        "plan": {
            "titre": app.plan_title,
            "pourquoi": app.plan_why,
            "objectifs": [
                {
                    "titre": o["titre"],
                    "etat": "en_pause" if paused and o["etat"] == "en_cours" else o["etat"],
                }
                for o in app.objectives
            ],
        },
        "reglages": [
            {"cle": key, "nom": name, "description": text, "valeur": app.settings[key]}
            for key, (name, text, _) in SETTINGS.items()
        ],
        "conversation": app.history[-30:] + ([{"de": "claude", "texte": live}] if live else []),
        "sections": sections,
        "machines": machines,
        "coffres": len(storage.containers),
        "joueurs": [f"{p['nom']} en {p['x']} {p['y']} {p['z']}" for p in app.world.players],
        "serveur": app.world.active,
    }
    ai_settings = getattr(app, "ai_settings", None)
    if ai_settings is not None:
        screen["configuration_ia"] = ai_settings.view(app)
    if plan and progress:
        current = next((i for i, done in enumerate(progress["etapes"]) if not done), None)
        screen["objectif"] = plan["objectif"]
        screen["pourquoi"] = plan.get("pourquoi", "")
        screen["progres"] = hud.get("progres", 0)
        screen["etapes"] = [
            {"texte": step_text(s, done, progress, have), "fait": done, "courant": i == current}
            for i, (s, done) in enumerate(zip(plan["etapes"], progress["etapes"]))
        ]
        main = next((recipes[t["item"]] for t in plan["cibles"] if t["item"] in recipes), None)
        screen["arbre"] = {"item": main["item"], "id": main.get("id")} if main else None
        screen["objectif_emi"] = objective_tree(
            plan,
            screen["projets"].get("actif", ""),
            app.plan_version,
            progress=progress,
            inventory=have,
        )
    if paused and plan:
        hud.update(
            {
                "objectif": plan["objectif"] + " · En pause",
                "etape": "Reprendre dans Y > Objectif",
                "etape_num": "",
                "prochains": [],
                "alertes": [],
            }
        )
        needed.clear()
        recipe_now, crafts = None, []
    # vie : le fichier est réécrit au moins toutes les 5 s, le mod sait ainsi que l'assistant tourne ;
    # lanceur : de quoi le relancer depuis le jeu s'il s'arrête
    on = app.settings
    return {
        "v": 2,
        "moteur": ai_provider.label(),
        "vie": int(now // 5),
        "lanceur": str(
            HERE / ("Lancer Mac.command" if sys.platform == "darwin" else "Lancer assistant.bat")
        ),
        "hud": hud,
        "bulle": bubble,
        "besoins": sorted(needed) if on["surbrillance"] else [],
        "guidage": beams if on["faisceaux"] else [],
        "ecran": screen,
        "recette": recipe_now if on["remplir"] else None,
        "fabrications": crafts if on["remplir"] else [],
        "retrait_coffres": on["coffres"],
        "recherche": search,
        "recherche_emi": app.emi_search.view(app.bridge, storage, state, now)
        if hasattr(app, "emi_search")
        else None,
        "info": dict(app.info) if app.info else None,
    }  # une copie : sinon la fiche complétée semble inchangée
