"""Local question selection: exact evidence, safe fallback, unchanged one-shot dispatch."""

import copy
import json
import tempfile
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from monifactory_assistant.game.context import describe_state, describe_storage, question_context
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.world import observation_date
from monifactory_assistant.prompts import ACTIONS_HELP, INFO_REQUEST, SYSTEM_PROMPT
from tests.benchmarks.question_context import (
    COPPER,
    INGOT,
    PLATE,
    RECIPE,
    START,
    TIN,
    fixture,
)

# Lignes de l'export réel : le fluide gtceu:chromium ne vient que du recyclage de pièces.
MATERIALS = [
    "gtceu:extractor_recycling | gtceu:chromium_rod => 72x gtceu:chromium | gtceu:extractor/extract_chromium_rod",
    "gtceu:arc_furnace_recycling | gtceu:chromium_plate + 52x gtceu:oxygen => gtceu:chromium_ingot | gtceu:arc_furnace/arc_chromium_plate",
    "gtceu:electric_blast_furnace | gtceu:chromium_dust => gtceu:chromium_ingot | gtceu:electric_blast_furnace/blast_chromium",
    "gtceu:macerator | gtceu:chromium_ingot => gtceu:chromium_dust | gtceu:macerator/macerate_chromium_ingot",
    "gtceu:bender | gtceu:chromium_ingot => gtceu:chromium_plate | gtceu:bender/bend_chromium_to_plate",
    "gtceu:mixer | gtceu:tungsten_dust + gtceu:steel_dust => 2x gtceu:tungsten_steel_dust | gtceu:mixer/tungstensteel",
    "gtceu:vacuum_freezer | gtceu:hot_tungsten_steel_ingot => gtceu:tungsten_steel_ingot | gtceu:vacuum_freezer/cool_hot_tungsten_steel_ingot",
    "gtceu:extractor_recycling | gtceu:tungsten_steel_rod => 72x gtceu:tungsten_steel | gtceu:extractor/extract_tungsten_steel_rod",
    "gtceu:ore_forging | gtceu:refined_cassiterite_ore => gtceu:cassiterite_dust | gtceu:forge_hammer/hammer_cassiterite_refined_ore_to_dust",
    "gtceu:macerator_recycling | gtceu:ruby_gem => gtceu:ruby_dust | gtceu:macerator/macerate_ruby_gem",
    "minecraft:smelting | gtceu:raw_ruby => gtceu:ruby_gem | gtceu:smelting/smelt_raw_ruby_ore_to_ingot",
    "gtceu:atomic_reconstruction | minecraft:diamond => gtceu:diamatine_gem | kubejs:atomic_reconstruction/diamatine",
    "gtceu:extractor_recycling | minecraft:glass => 144x gtceu:glass | gtceu:extractor/extract_glass",
    "gtceu:forming_press | gtceu:glass_dust => minecraft:glass | gtceu:forming_press/form_glass",
    "gtceu:macerator | minecraft:glass => gtceu:glass_dust | gtceu:macerator/macerate_glass",
]


def published(folder, lines):
    """Un catalogue publié en version 3, groupe a, d'une seule partie."""
    (folder / "recettes_a_0.json").write_text(
        json.dumps({"generation": "g", "recettes": lines}), encoding="utf-8"
    )
    (folder / "recettes_info.json").write_text(
        json.dumps(
            {"version": 3, "groupe": "a", "generation": "g", "parties": 1, "nombre": len(lines)}
        ),
        encoding="utf-8",
    )
    return RecipeIndex(folder)


class QuestionContextTests(TestCase):
    def setUp(self):
        self.app = fixture()
        self.enterContext(
            patch("monifactory_assistant.game.world.time.time", return_value=START / 1000)
        )

    def test_rare_target_and_two_levels_of_ingredients_survive_old_quantity_limits(self):
        app = self.app
        self.assertNotIn(
            "on:1", question_context("Comment faire Bronze Plate ?", app.recipe_index).items
        )
        self.assertNotIn(PLATE, describe_state(app.game))
        self.assertNotIn(PLATE, describe_storage(app.storage))
        context = app.context(None, with_storage=True, question="Comment faire Bronze Plate ?")
        for needed in (
            PLATE,
            INGOT,
            COPPER,
            TIN,
            "30 64 40",
            "minecraft:the_nether",
            observation_date(START - 60_000),
        ):
            self.assertIn(needed, context)
        self.assertIn("stock actuel à vérifier", context)
        self.assertIn("LV Bender en 5 64 7", context)
        self.assertNotIn("test:unrelated_0", context)
        self.assertNotIn("événement ancien", context)
        self.assertNotIn("objectif", context.casefold())
        self.assertIn("Base (1 64 9", context)
        self.assertIn(RECIPE, app.recipe_index.hint("Comment faire Bronze Plate ?"))

    def test_storage_lookup_selects_target_without_claiming_unrelated_items_are_absent(self):
        context = self.app.context(
            None, with_storage=True, question="Où sont mes Bronze Plate dans les coffres ?"
        )
        self.assertIn("1x " + PLATE, context)
        self.assertNotIn(INGOT, context)
        self.assertIn("Ne conclus pas qu'un objet absent de cet extrait n'existe pas", context)
        self.assertIn("stockage.txt", context)
        self.assertIn("contexte complet", context)
        self.app.storage.containers[0]["objets"] = []
        missing = self.app.context(
            None, with_storage=True, question="Où sont mes Bronze Plate dans les coffres ?"
        )
        self.assertIn("aucun des objets ciblés dans les observations mémorisées", missing)
        self.assertNotIn("aucun mémorisé pour l'instant", missing)

    def test_players_are_kept_for_settings_and_when_no_blocks_exist(self):
        self.app.world.blocks = {}
        for live in (True, False):
            self.app.world.live_dimension = "minecraft:overworld" if live else None
            context = self.app.context(
                "/tmp/screenshot.png", with_storage=True, question="Comment désactiver la voix ?"
            )
            for text in (
                "Camille en 14 68 15 (minecraft:overworld)",
                "Alex en 20 70 -20 (minecraft:the_nether)",
                "x=1 y=64 z=9",
                "/tmp/screenshot.png",
            ):
                self.assertIn(text, context)
            self.assertNotIn("test:unrelated", context)
            self.assertNotIn("objectif", context.casefold())
            self.assertIn("connectés" if live else "Dernières positions connues", context)

    def test_ambiguous_diagnostics_projects_and_explicit_expansion_keep_full_context(self):
        full = self.app.context(None, with_storage=True)
        for question in (
            None,
            "Ça ne marche pas",
            "Pourquoi mon LV Bender est arrêté ?",
            "Mon objectif Bronze Plate",
            "Quelle est la prochaine étape ?",
            "Contexte complet : comment faire Bronze Plate ?",
            "Où sont mes plaques de bronze ?",
            "Bronze Plate",
            "Comment faire Bronze Plate et réparer la machine ?",
            "Comment faire Bronze Plate ? Où sont mes outils ?",
            "Comment faire other:bronze_plate ?",
        ):
            self.assertEqual(
                self.app.context(None, with_storage=True, question=question), full, question
            )
        self.app.recipe_index.by_name["bronze_plate"].add("other:bronze_plate")
        self.assertEqual(
            self.app.context(None, with_storage=True, question="Comment faire Bronze Plate ?"), full
        )
        self.assertTrue(
            question_context("Comment faire gtceu:bronze_plate ?", self.app.recipe_index).focused
        )

    def test_unsupported_recipe_and_expansion_budget_fall_back_without_partial_truncation(self):
        recipes = self.app.recipe_index
        recipes.by_output[PLATE] = ["unknown recipe format"]
        self.assertFalse(question_context("Comment faire Bronze Plate ?", recipes).focused)
        recipes.by_output[PLATE] = [
            "test:craft | "
            + " + ".join(f"1x test:item_{i}" for i in range(110))
            + " => 1x gtceu:bronze_plate | test:recipe"
        ]
        self.assertFalse(question_context("Comment faire Bronze Plate ?", recipes).focused)

    def test_missing_recipe_and_compound_questions_keep_extended_context(self):
        question = "Comment faire Bronze Plate ?"
        self.app.recipe_index.by_output.pop(PLATE)
        self.assertFalse(question_context(question, self.app.recipe_index).focused)
        self.app.recipe_index.by_output[PLATE] = [RECIPE]
        self.assertFalse(
            question_context(
                "Comment faire Bronze Plate puis la ranger ?", self.app.recipe_index
            ).focused
        )

    def test_selection_never_changes_local_data_or_recipe_variants(self):
        app = self.app
        before = copy.deepcopy(
            (
                app.game,
                app.storage.containers,
                app.storage.machines,
                app.world.blocks,
                app.world.players,
                app.recipe_index.__dict__,
                app.places,
            )
        )
        for question in ("Comment faire Bronze Plate ?", "Comment désactiver la voix ?"):
            app.context(None, with_storage=True, question=question)
        after = (
            app.game,
            app.storage.containers,
            app.storage.machines,
            app.world.blocks,
            app.world.players,
            app.recipe_index.__dict__,
            app.places,
        )
        self.assertEqual(before, after)

    def test_context_hint_and_prompts_name_only_the_active_recipe_group(self):
        recipes, focused = self.app.recipe_index, "Où sont mes Bronze Plate dans les coffres ?"
        unknown = self.app.context(None, with_storage=True, question=focused)
        self.assertIn("(stockage.txt, les parties du groupe actif de recettes_info.json,", unknown)
        self.assertNotIn("Recettes du pack", unknown)
        recipes.pattern = "recettes_b_*.json"
        for question in (focused, "Pourquoi mon LV Bender est arrêté ?"):
            context = self.app.context(None, with_storage=True, question=question)
            self.assertIn(
                "Recettes du pack : cherche-les seulement dans recettes_b_*.json", context
            )
            self.assertNotIn("recettes_*.json", context)
        self.assertIn(
            "(stockage.txt, recettes_b_*.json, machines_recettes.txt",
            self.app.context(None, with_storage=True, question=focused),
        )
        settings = self.app.context(
            None, with_storage=True, question="Comment désactiver la voix ?"
        )
        self.assertNotIn("Recettes du pack", settings)
        self.assertIn(
            "déjà cherché dans recettes_b_*.json", recipes.hint("Comment faire Bronze Plate ?")
        )
        info = INFO_REQUEST.format(nom="Bronze Plate", item=PLATE, recettes=recipes.catalog_files)
        self.assertEqual(info.count("recettes_b_*.json"), 2)
        for prompt in (SYSTEM_PROMPT, info):
            self.assertNotIn("recettes_*.json", prompt)

    def test_both_provider_names_receive_one_question_with_unchanged_actions_and_recipes(self):
        for label in ("Codex", "Claude"):
            app = fixture()
            app.events, app.speaker, app.speak = Queue(), Mock(), False
            app.conversation = SimpleNamespace(ask=Mock(return_value="Voici la recette."))
            with (
                patch(
                    "monifactory_assistant.application.conversation.ai_provider.label",
                    return_value=label,
                ),
                patch("monifactory_assistant.application.conversation.log"),
            ):
                app.work_question("Comment faire Bronze Plate ?", None, None)
            app.conversation.ask.assert_called_once()
            prompt = app.conversation.ask.call_args.args[0]
            self.assertIn(ACTIONS_HELP, prompt)
            self.assertIn(RECIPE, prompt)
            self.assertIn("Camille en 14 68 15", prompt)
            self.assertIn("Ma question : Comment faire Bronze Plate ?", prompt)
            self.assertTrue(any(event[0] == "answer" for event in list(app.events.queue)))


class MaterialHintTests(TestCase):
    def setUp(self):
        self.recipes = published(Path(self.enterContext(tempfile.TemporaryDirectory())), MATERIALS)

    def test_material_name_joins_its_ingot_and_dust_rather_than_its_fluid(self):
        ingot, dust = "gtceu:chromium_ingot", "gtceu:chromium_dust"
        steel = ["gtceu:tungsten_steel_ingot", "gtceu:tungsten_steel_dust"]
        for question, expected in (
            ("comment je fais du chromium", [ingot, dust]),
            ("comment faire de la tungstensteel", steel),
            ("comment faire du tungsten steel", steel),
            ("où trouver du cassiterite", ["gtceu:cassiterite_dust"]),
            ("comment je fais du ruby", ["gtceu:ruby_dust"]),  # la poudre avant la gemme
            ("comment je fais du diamatine", ["gtceu:diamatine_gem"]),
            ("que donne gtceu:chromium ?", ["gtceu:chromium"]),  # fluide cité par son id
        ):
            with self.subTest(question):
                self.assertEqual(self.recipes.items_in(question), expected)
        hint = self.recipes.hint("comment je fais du chromium")
        self.assertIn("déjà cherché dans recettes_a_*.json et machines_recettes.txt", hint)
        self.assertNotIn("extract_chromium_rod", hint)
        self.assertIn("macerate_chromium_ingot", hint)
        # Le recyclage d'une pièce passe après le four électrique.
        self.assertLess(hint.index("blast_chromium"), hint.index("arc_chromium_plate"))

    def test_exact_item_name_keeps_priority_over_material_forms(self):
        for question, expected in (
            ("comment faire du chromium plate", ["gtceu:chromium_plate"]),
            ("comment faire des chromium plates", ["gtceu:chromium_plate"]),
            ("chromium dust", ["gtceu:chromium_dust"]),
            # minecraft:glass est un objet ; gtceu:glass, à côté de gtceu:glass_dust, le fluide.
            ("comment faire du glass", ["minecraft:glass"]),
        ):
            with self.subTest(question):
                self.assertEqual(self.recipes.items_in(question), expected)

    def test_material_forms_share_the_limit_of_three_items(self):
        question = "chromium, tungstensteel, cassiterite ou ruby"
        self.assertEqual(
            self.recipes.items_in(question),
            ["gtceu:chromium_ingot", "gtceu:tungsten_steel_ingot", "gtceu:cassiterite_dust"],
        )
        self.assertEqual(self.recipes.hint(question).count(" se fabrique ainsi :"), 3)
        self.assertEqual(
            self.recipes.items_in(question, limit=8),
            [
                "gtceu:chromium_ingot",
                "gtceu:tungsten_steel_ingot",
                "gtceu:cassiterite_dust",
                "gtceu:ruby_dust",
                "gtceu:chromium_dust",
                "gtceu:tungsten_steel_dust",
            ],
        )
        self.assertEqual(self.recipes.items_in("chromium", limit=1), ["gtceu:chromium_ingot"])
        self.assertEqual(
            self.recipes.items_in("chromium ingot ou chromium"),
            ["gtceu:chromium_ingot", "gtceu:chromium_dust"],
        )
