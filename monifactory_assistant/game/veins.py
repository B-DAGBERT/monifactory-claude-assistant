"""Import explicite des anciens repérages dans le monde actuellement connecté.

Le fichier préparé est lu par le mod au chargement du monde. Il reste disponible
pour reprendre un import interrompu ; les observations récentes ont priorité.
"""

import hashlib
import json
import math
import re
import time
from pathlib import Path

from monifactory_assistant.config import write_json


def validated_block(block):
    resource = r"[a-z0-9_.-]+:[a-z0-9_./-]+"
    if not isinstance(block, dict) or not all(
        isinstance(block.get(k), str) and re.fullmatch(resource, block[k])
        for k in ("bloc", "dimension")
    ):
        raise ValueError("Identifiant de minerai ou de dimension invalide.")
    result = {k: block[k] for k in ("bloc", "dimension")}
    for key, limit in (("x", 30_000_000), ("y", 4096), ("z", 30_000_000), ("t", 10**16)):
        value = block.get(key)
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
        ):
            raise ValueError("Coordonnée ou date invalide.")
        if value != int(value) or abs(value) > limit or (key == "t" and value < 0):
            raise ValueError("Coordonnée ou date hors limites.")
        result[key] = int(value)
    if not isinstance(block.get("mine", False), bool):
        raise ValueError("État de minage invalide.")
    result["mine"] = block.get("mine", False)
    return result


def prepare_import(instance, dry_run=False):
    folder = Path(instance) / "kubejs/assistant"
    active = folder / "filons_mod.json"
    data = json.loads(active.read_text(encoding="utf-8"))
    if data.get("source") == "gregtech":
        raise RuntimeError(
            "Les anciens blocs ne sont pas des gisements GregTech. Utilise le journal de prospection GregTech puis Scanner dans la fiche du filon."
        )
    world = data.get("monde", "")
    if data.get("v") != 1 or not re.fullmatch(r"[0-9a-f]{24}", world):
        raise RuntimeError("Le mod doit avoir identifié le monde actuel.")
    age = time.time() - active.stat().st_mtime
    if not -5 <= age <= 90:
        raise RuntimeError("Entre dans ton monde pour identifier la destination de l'import.")
    original = (folder / "filons.json").read_bytes()
    old = json.loads(original).get("blocs", [])
    if not isinstance(old, list):
        raise ValueError("La liste des anciens minerais est invalide.")
    target = folder / "filons/imports" / (world + ".json")
    previous = json.loads(target.read_text()) if target.exists() else {}
    if previous and previous.get("monde") != world:
        raise RuntimeError("La demande existante vise un autre monde.")
    merged = {}
    for entry in [*previous.get("blocs", []), *old]:
        b = validated_block(entry)
        key = (b["dimension"], b["x"], b["y"], b["z"])
        if key not in merged or b["t"] > merged[key]["t"]:
            merged[key] = b
    blocks = sorted(merged.values(), key=lambda b: (b["t"], b["dimension"], b["x"], b["y"], b["z"]))
    if len(blocks) > 100_000:
        raise RuntimeError("Import trop grand pour la mémoire du mod (100 000 positions).")
    result = {
        "v": 1,
        "monde": world,
        "source": "filons.json",
        "source_sha256": hashlib.sha256(original).hexdigest(),
        "blocs": blocks,
    }
    if not dry_run:
        target.parent.mkdir(parents=True, exist_ok=True)
        write_json(target, result)
    return {
        "monde": world,
        "blocs": len(blocks),
        "restants_historiques": sum(not b["mine"] for b in blocks),
        "fichier": str(target),
        "simulation": dry_run,
    }
