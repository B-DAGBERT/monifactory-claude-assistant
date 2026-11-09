"""Vues exportées vers le HUD et les menus du mod."""

import fnmatch
import math
import re
import sys
import time
from collections import defaultdict

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.game.world import block_name, machine_color, machine_status
from monifactory_assistant.paths import ROOT as HERE
from monifactory_assistant.settings import SETTINGS


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
    - hud : réglage du panneau en haut à gauche, statut et direction du guidage ;
    - bulle : la dernière réponse de l'assistant ou du coach, qui s'efface toute seule ;
    - guidage, recherche : faisceaux dans le monde et objets cherchés dans les interfaces ;
    - ecran : le tableau de bord de la touche Y."""
    state, storage = app.game, app.storage
    have, now = app.have(), time.time()
    dimension = (state or {}).get("dimension")
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
        "direction": None,
    }
    beams = []
    search = (
        find_search(app.search, storage, have, state)
        if app.search and now < app.search["fin"]
        else None
    )
    if search:
        hud["recherche"] = search.pop("resume")
    # La destination demandée (« guide-moi à la base ») : faisceau dans le monde.
    guide = app.guide
    if state and guide and guide.get("x") is not None and guide.get("z") is not None:
        target = {
            "x": guide["x"],
            "y": guide.get("y"),
            "z": guide["z"],
            "couleur": "bleu",
            "label": guide["label"],
            "dimension": guide.get("dimension") or dimension,
        }
        beams.append(target)
        hud["direction"] = target
    live = app.message if app.message and not app.message_until else ""
    bound_message = getattr(app, "production_message_proof", None)
    message_catalogue = (
        bound_message[2].get("catalogue")
        if bound_message and bound_message[:2] == (app.message, app.message_until)
        else None
    )
    bubble = None
    if app.message and (not app.message_until or now < app.message_until):
        bubble = {
            "texte": app.message,
            "en_cours": bool(live),
            "fin": int(app.message_until * 1000),
        }
        if message_catalogue is not None:
            bubble["catalogue_production"] = message_catalogue
    machines = []
    for m in app.world.machines():
        if re.search(r"hatch|_bus$", m["bloc"]):
            continue
        status = machine_status(m)
        machine_live = app.world.observation_live(m)
        machines.append(
            {
                "nom": m["nom"],
                "etat": status,
                "x": m["x"],
                "y": m["y"],
                "z": m["z"],
                "couleur": machine_color(status) if machine_live else "gris",
                "item": m["bloc"],
                "dimension": m.get("dimension", ""),
                "vu": m.get("t", 0),
                "actuel": machine_live,
            }
        )
    info = dict(app.info) if app.info else None
    if info and getattr(app, "info_request", None):
        status = app.machine_at(app.info_request)
        color = "gris" if status.startswith("Dernier relevé") else machine_color(status)
        info.update(machine=status, machine_couleur=color)
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
        "occupe": app.busy,
        "reglages": [
            {"cle": key, "nom": name, "description": text, "valeur": app.settings[key]}
            for key, (name, text, _) in SETTINGS.items()
        ],
        "conversation": app.history[-30:],
        "machines": machines,
        "coffres": len(storage.containers),
        "joueurs": [f"{p['nom']} en {p['x']} {p['y']} {p['z']}" for p in app.world.players],
        "serveur": app.world.active,
    }
    if live:
        message = {"de": "claude", "texte": live}
        if message_catalogue is not None:
            message["catalogue_production"] = message_catalogue
        screen["conversation"].append(message)
    ai_settings = getattr(app, "ai_settings", None)
    if ai_settings is not None:
        screen["configuration_ia"] = ai_settings.view(app)
    production = getattr(app, "production", None)
    if production is not None:
        screen["production_v2"] = production.view()
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
        "guidage": beams if on["faisceaux"] else [],
        "ecran": screen,
        "retrait_coffres": on["coffres"],
        "recherche": search,
        "recherche_emi": app.emi_search.view(app.bridge, storage, state, now)
        if hasattr(app, "emi_search")
        else None,
        "info": info,
    }  # une copie : sinon la fiche complétée semble inchangée
