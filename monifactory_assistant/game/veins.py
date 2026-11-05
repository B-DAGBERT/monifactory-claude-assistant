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


STONE_PREFIXES = (
    "deepslate_",
    "tuff_",
    "andesite_",
    "diorite_",
    "granite_",
    "red_granite_",
    "marble_",
    "basalt_",
    "netherrack_",
    "endstone_",
    "sand_",
    "red_sand_",
    "gravel_",
    "blackstone_",
    "calcite_",
    "stone_",
)


def ore_material(block):
    """« gtceu:deepslate_galena_ore » -> « galena » : le minerai, quelle que soit la pierre qui l'entoure."""
    name = block.split(":")[-1].removesuffix("_ore")
    for prefix in STONE_PREFIXES:
        name = name.removeprefix(prefix)
    return name


def find_veins(bridge, radius=24):
    """Regroupe les minerais vus ou minés par le joueur en filons (même minerai, à moins de radius blocs)."""
    # Le mod scanne et revérifie les blocs chargés ; sa mémoire est séparée par monde.
    scanned = bridge.read("filons_mod.json")
    data = (
        scanned
        if isinstance(scanned, dict) and scanned.get("v") == 1 and scanned.get("monde")
        else bridge.read("filons.json")
    )
    blocks = (data or {}).get("blocs") or []
    if isinstance(scanned, dict) and scanned.get("source") == "gregtech":
        # Les gisements existent indépendamment des relevés de blocs, y compris sans scan.
        releves = {}
        for block in blocks:
            key = (block.get("filon"), ore_material(block["bloc"]))
            counts = releves.setdefault(key, [0, 0])
            counts[1 if block.get("mine") else 0] += 1
        veins = []
        for g in scanned.get("gisements", []):
            for material in sorted({ore_material(item) for item in g.get("composition", [])}):
                present, mined = releves.get((g["id"], material), (0, 0))
                veins.append(
                    {
                        "minerai": material,
                        "gisement": g.get("nom", g.get("definition", "")),
                        "dimension": g["dimension"],
                        "x": g["x"],
                        "y": g["y"],
                        "z": g["z"],
                        "vus": present,
                        "mines": mined,
                        "t": scanned.get("t", 0),
                        "partiel": True,
                        "epuise": bool(g.get("epuise") or g.get("epuise_manuel")),
                        "epuise_manuel": bool(g.get("epuise_manuel")),
                    }
                )
        return veins
    if data is scanned and isinstance(scanned, dict):
        # L'ancre stable du scanner évite un regroupement quadratique de 20 000 positions.
        grouped = {}
        for b in blocks:
            anchor = b.get("ancre") or b
            material = ore_material(b["bloc"])
            key = (b["dimension"], anchor["x"], anchor["y"], anchor["z"], material)
            vein = grouped.setdefault(
                key,
                {
                    "minerai": material,
                    "dimension": b["dimension"],
                    "x": b["x"],
                    "y": b["y"],
                    "z": b["z"],
                    "vus": 0,
                    "mines": 0,
                    "t": b["t"],
                },
            )
            if not b.get("mine") and not vein["vus"]:
                vein.update(x=b["x"], y=b["y"], z=b["z"])
            vein["mines" if b.get("mine") else "vus"] += 1
            vein["t"] = max(vein["t"], b["t"])
        return list(grouped.values())
    veins = []
    for b in sorted(blocks, key=lambda b: b["t"]):
        material = ore_material(b["bloc"])
        vein = next(
            (
                v
                for v in veins
                if v["minerai"] == material
                and v["dimension"] == b["dimension"]
                and math.dist((v["x"], v["y"], v["z"]), (b["x"], b["y"], b["z"])) <= radius
            ),
            None,
        )
        if vein is None:
            vein = {
                "minerai": material,
                "dimension": b["dimension"],
                "x": b["x"],
                "y": b["y"],
                "z": b["z"],
                "vus": 0,
                "mines": 0,
                "t": b["t"],
            }
            veins.append(vein)
        vein["mines" if b.get("mine") else "vus"] += 1
        vein["t"] = max(vein["t"], b["t"])
    return veins


def describe_veins(veins, state, limit=25):
    if not veins:
        return "Filons repérés : aucun pour l'instant."

    def distance(v):
        return (
            math.dist((v["x"], v["z"]), (state["x"], state["z"]))
            if state and v["dimension"] == state["dimension"]
            else 1e9
        )

    parts = []
    for v in sorted(veins, key=distance)[:limit]:
        position = f"{int(v['x'])} {int(v['y'])} {int(v['z'])} ({v['dimension']})"
        if v.get("partiel"):
            etat = (
                "marqué épuisé par le joueur"
                if v.get("epuise_manuel")
                else "épuisé selon GregTech"
                if v.get("epuise")
                else "quantité totale restante inconnue"
            )
            parts.append(
                f"{v.get('gisement', '')} : {v['minerai']} en {position}, {etat}, "
                f"{v['vus']} blocs compatibles observés à proximité (limites du gisement non vérifiées)"
            )
        else:
            parts.append(
                f"{v['minerai']} en {position}, {v['vus']} blocs encore là, {v['mines']} minés"
            )
    return "Filons repérés (les plus proches d'abord) : " + " ; ".join(parts)


def nearest_vein(item, veins, state):
    """Le filon connu le plus proche dont le minerai figure dans l'id de l'objet (galena -> raw_galena)."""
    if not state:
        return None
    path = item.split(":")[-1]
    candidates = [
        v
        for v in veins
        if v["vus"]
        and not v.get("epuise")  # épuisé selon GregTech ou marqué par le joueur, comme dans le mod
        and v["minerai"] in path
        and v["dimension"] == state["dimension"]
    ]
    return min(
        candidates,
        key=lambda v: math.dist((v["x"], v["z"]), (state["x"], state["z"])),
        default=None,
    )
