"""Local question selection: exact evidence, safe fallback, unchanged one-shot dispatch."""

import copy
from queue import Queue
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from monifactory_assistant.game.context import describe_state, describe_storage, question_context
from monifactory_assistant.game.world import observation_date
from monifactory_assistant.prompts import ACTIONS_HELP
from tests.benchmarks.question_context import (
    COPPER,
    INGOT,
    PLATE,
    RECIPE,
    START,
    TIN,
    fixture,
)


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
        self.assertIn("EN PAUSE", context)
        self.assertIn("Passer en LV", context)
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

    def test_players_and_pause_are_kept_for_settings_and_when_no_blocks_exist(self):
        self.app.world.blocks = {}
        for live in (True, False):
            self.app.world.live_dimension = "minecraft:overworld" if live else None
            context = self.app.context(
                "/tmp/screenshot.png", with_storage=True, question="Comment désactiver la voix ?"
            )
            for text in (
                "Camille en 14 68 15 (minecraft:overworld)",
                "Alex en 20 70 -20 (minecraft:the_nether)",
                "EN PAUSE",
                "Progression préservée",
                "x=1 y=64 z=9",
                "/tmp/screenshot.png",
            ):
                self.assertIn(text, context)
            self.assertNotIn("test:unrelated", context)
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
                app.objectives,
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
            app.objectives,
            app.places,
        )
        self.assertEqual(before, after)

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
