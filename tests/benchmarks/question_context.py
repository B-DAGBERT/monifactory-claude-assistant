"""Measure prompt selection offline on the same synthetic party on either revision."""

import hashlib
import inspect
import json
import statistics
import time
from unittest.mock import patch

from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import World
from monifactory_assistant.prompts import ACTIONS_HELP

START = 1_790_000_000_000
PLATE = "gtceu:bronze_plate"
INGOT = "gtceu:bronze_ingot"
COPPER = "gtceu:copper_ingot"
TIN = "gtceu:tin_ingot"
BENDER = "gtceu:lv_bender"
RECIPE = (
    "gtceu:bender | 1x gtceu:bronze_ingot + 1x gtceu:programmed_circuit{Configuration:1} "
    "=> 1x gtceu:bronze_plate | gtceu:bender/bronze_plate"
)


class App(ConversationController):
    def describe_journal(self, limit):
        return "Journal complet : " + "; ".join(f"événement ancien {i}" for i in range(limit))


def fixture():
    app = App()
    recipes = RecipeIndex()
    recipes.by_output[PLATE] = [RECIPE]
    recipes.by_output[INGOT] = [
        "gtceu:alloy_smelter | 3x gtceu:copper_ingot + 1x gtceu:tin_ingot "
        "=> 4x gtceu:bronze_ingot | gtceu:alloy_smelter/bronze"
    ]
    recipes.machine_of[BENDER] = [("gtceu:bender", "Bender", "30")]
    recipes.machines["gtceu:bender"] = [BENDER]
    recipes.machines["gtceu:alloy_smelter"] = ["gtceu:lp_steam_alloy_smelter"]
    for item in (PLATE, INGOT, COPPER, TIN, BENDER):
        recipes.by_name[item.split(":")[1]].add(item)
    app.recipe_index = recipes
    app.game = {
        "x": 1,
        "y": 64,
        "z": 9,
        "dimension": "minecraft:overworld",
        "ecran": "Crafting Table",
        "en_main": "minecraft:iron_pickaxe",
        "inventaire": {**{f"test:unrelated_{i}": 64 for i in range(80)}, PLATE: 1, COPPER: 5},
    }
    chest = {
        "x": 30,
        "y": 64,
        "z": 40,
        "dimension": "minecraft:the_nether",
        "bloc": "minecraft:barrel",
        "nom": "Barrel",
        "t": START - 60_000,
        "v": 2,
        "objets": [{"item": f"test:unrelated_{i}", "n": 100} for i in range(180)]
        + [{"item": PLATE, "n": 1}, {"item": INGOT, "n": 4}, {"item": TIN, "n": 6}],
    }
    machine = {
        "x": 5,
        "y": 64,
        "z": 7,
        "dimension": "minecraft:overworld",
        "bloc": BENDER,
        "nom": "LV Bender",
        "t": START,
        "etat": {"s": "IDLE"},
        "objets": [],
    }
    other = {
        **machine,
        "x": 7,
        "bloc": "gtceu:steam_boiler",
        "nom": "Steam Boiler",
        "t": START - 60_000,
    }
    app.storage = Storage([chest], machines=[machine, other])
    world = World.__new__(World)
    world.blocks = {"machine": machine, "other": other}
    world.players = [
        {"nom": "Camille", "x": 14, "y": 68, "z": 15, "dimension": "minecraft:overworld"},
        {"nom": "Alex", "x": 20, "y": 70, "z": -20, "dimension": "minecraft:the_nether"},
    ]
    world.last_t, world.live_dimension = START, "minecraft:overworld"
    app.world = world
    app.guide = None
    app.removed_chests = []
    app.veins = []
    app.places = {"Base": {"x": 1, "y": 64, "z": 9, "dimension": "minecraft:overworld"}}
    return app


def measure():
    app = fixture()
    supports_selection = "question" in inspect.signature(app.context).parameters
    questions = (
        "Comment faire Bronze Plate ?",
        "Où sont mes Bronze Plate dans les coffres ?",
        "Pourquoi mon LV Bender est arrêté ?",
        "Quelle est la prochaine étape de mon objectif ?",
        "Comment désactiver la voix ?",
        "Ça ne marche pas, aide-moi",
        "Contexte complet : comment faire Bronze Plate ?",
    )
    results = []
    with patch("monifactory_assistant.game.world.time.time", return_value=START / 1000):
        for question in questions:
            options = {"question": question} if supports_selection else {}
            timings = []
            for _ in range(50):
                start = time.perf_counter()
                context = app.context(None, with_storage=True, **options)
                recipes = app.recipe_index.hint(question)
                prompt = (
                    f"{context}\n\n{ACTIONS_HELP}\n\n"
                    + (recipes + "\n\n" if recipes else "")
                    + f"Ma question : {question}"
                )
                timings.append((time.perf_counter() - start) * 1000)
            results.append(
                {
                    "question": question,
                    "context_chars": len(context),
                    "prompt_chars": len(prompt),
                    "estimated_tokens_chars_div4": round(len(prompt) / 4),
                    "prepare_median_ms": statistics.median(timings),
                    "prepare_p95_ms": sorted(timings)[47],
                    "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
                    "all_player_positions": all(
                        fragment in context
                        for fragment in ("Camille en 14 68 15", "Alex en 20 70 -20")
                    ),
                    "target_inventory": f"1x {PLATE}" in context,
                    "bronze_stock": f"4x {INGOT}" in context,
                    "exact_recipe": RECIPE in prompt,
                }
            )
    return results


if __name__ == "__main__":
    print(json.dumps(measure(), ensure_ascii=False, indent=2))
