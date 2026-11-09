"""Lecture des réponses structurées et des actions du moteur IA."""

import json

from monifactory_assistant.runtime import log
from monifactory_assistant.settings import ACTION_TAG, ITEM_ID


def split_actions(text):
    """Sépare la réponse (affichée et lue) des actions demandées par Codex dans <actions>[...]</actions>."""
    match = ACTION_TAG.search(text)
    if not match:
        return text.strip(), []
    try:
        actions = json.loads(match.group(1))
    except ValueError:
        log(f"Actions illisibles : {match.group(1)}")
        actions = []
    if isinstance(actions, dict):
        actions = [actions]
    elif not isinstance(actions, list):
        actions = []
    return text[: match.start()].strip(), [a for a in actions if isinstance(a, dict)]


def visible_text(partial):
    """Le texte à afficher et à lire pendant que la réponse arrive : sans la ligne d'actions, même entamée."""
    cut = partial.find("<actions")
    if cut >= 0:
        return partial[:cut]
    for n in range(min(len(partial), len("<actions")), 0, -1):
        if partial.endswith("<actions"[:n]):
            return partial[:-n]
    return partial


def readable(text):
    """Remplace les identifiants qui échappent à Codex (minecraft:torch) par le nom du jeu (Torch)."""
    return ITEM_ID.sub(lambda m: m.group(1).split("/")[-1].replace("_", " ").title(), text)


def parse_info(text):
    """La fiche « à quoi ça sert » renvoyée par Codex, nettoyée et bornée pour tenir dans l'écran du jeu."""
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise RuntimeError("L'assistant n'a pas renvoyé de fiche lisible : " + text[:200])
    data = json.loads(text[start : end + 1])
    chains = []
    for chain in data.get("schema") or []:
        nodes = (
            [
                {"item": str(n["item"]), "texte": readable(str(n.get("texte") or ""))[:40]}
                for n in chain
                if isinstance(n, dict) and n.get("item")
            ]
            if isinstance(chain, list)
            else []
        )
        if len(nodes) >= 2:
            chains.append(nodes[:6])
    return {
        "sert": readable(str(data.get("sert") or "")),
        "utilise": [readable(str(s)) for s in data.get("utilise") or []][:5],
        "schema": chains[:2],
        "astuce": readable(str(data.get("astuce") or "")),
    }
