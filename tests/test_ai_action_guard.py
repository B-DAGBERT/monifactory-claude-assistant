"""Model actions cannot erase local state or authorize their own destructive effects."""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.application.game import GameController
from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.application.projects import ProjectsController
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.settings import SETTINGS


class Fixture(ConversationController, ProjectsController, GameController, InterfaceController):
    pass


class AIActionGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.path = self.directory / "plan_ia.json"
        self.path.write_text(
            json.dumps(
                {
                    "objectif_en_pause": True,
                    "version": 41,
                    "plan": {
                        "objectif": "Passer en LV",
                        "cibles": [],
                        "etapes": [{"texte": "Préparer le four"}],
                        "recettes": [],
                    },
                    "etapes_faites": [0],
                    "obtenus": {"gtceu:steel_ingot": 4},
                    "derniers_totaux": {"gtceu:steel_ingot": 2},
                    "plan_principal": {
                        "titre": "Métallurgie",
                        "pourquoi": "Progression",
                        "objectifs": [
                            {"titre": "Four", "etat": "fait"},
                            {"titre": "Passer en LV", "etat": "en_cours"},
                            {"titre": "Câbles", "etat": "a_faire"},
                        ],
                    },
                }
            ),
            encoding="utf-8",
        )
        self.app = Fixture()
        app = self.app
        app.load_plan(self.path)
        app.save_plan()
        app.load_places(self.directory / "lieux.json")
        app.places = {"Base": {"x": 1, "y": 64, "z": 2, "dimension": "minecraft:overworld"}}
        app.save_places()
        app.game = {"x": 20, "y": 64, "z": 30, "dimension": "minecraft:overworld"}
        app.storage = Storage([])
        app.settings = {key: default for key, (_, _, default) in SETTINGS.items()}
        app.settings["enchainer"] = False
        app.bridge = SimpleNamespace(isolated=False, scope=None)
        app.history = []
        app.root = SimpleNamespace(after=Mock())
        app.refresh, app.show_overlay, app.journal = Mock(), Mock(), Mock()
        app.speaker = SimpleNamespace(stop=Mock())
        app.forgotten_keys = set()
        app.guide = app.search = None
        app.busy = False
        self.enterContext(patch("monifactory_assistant.application.conversation.log"))
        self.enterContext(
            patch(
                "monifactory_assistant.application.interface.SETTINGS_FILE",
                self.directory / "reglages.json",
            )
        )

    def files(self):
        return {p.name: p.read_bytes() for p in self.directory.iterdir() if p.is_file()}

    def answer(self, actions, question="Comment fabriquer un marteau ?", *, provider="codex"):
        # Both adapters hand their final text to this common controller. No CLI is started.
        responses = {
            "codex": json.dumps(actions),
            "claude": json.dumps(actions[0] if len(actions) == 1 else actions),
        }
        self.app.on_answer(
            question, "Voici la recette.<actions>" + responses[provider] + "</actions>"
        )

    def test_informative_and_explicit_questions_cannot_authorize_destructive_model_actions(self):
        actions = [
            {"type": "effacer_plan"},
            {"type": "effacer_objectif"},
            {"type": "retirer_objectif"},
            {"type": "modifier_plan", "objectifs": []},
            {"type": "feuille_de_route", "objectifs": []},
            {"type": "nouveau_plan", "demande": "Tout remplacer"},
            {"type": "nouvel_objectif", "demande": "Autre cible"},
            {"type": "planifier_usine", "demande": "Usine"},
            {"type": "objectif_suivant"},
            {"type": "refaire_objectif"},
            {"type": "decocher_etape", "numero": 1},
            {"type": "valider_etape", "numero": 1},
            {"type": "reprendre_objectif"},
            {"type": "oublier_coffre", "proche": True},
            {"type": "oublier_lieu", "nom": "Base"},
            {"type": "memoriser_lieu", "nom": "Base"},
            {"type": "reglage", "cle": "enchainer", "valeur": True},
        ]
        for provider in ("codex", "claude"):
            for question in ("Comment fabriquer un marteau ?", "Efface mon plan", "oui"):
                for action in actions:
                    with self.subTest(provider=provider, question=question, action=action):
                        before, state, places = (
                            self.files(),
                            self.app.project_snapshot(),
                            copy.deepcopy(self.app.places),
                        )
                        payload = dict(
                            action,
                            confirmation=True,
                            autorise=True,
                            origine="utilisateur",
                            session="courante",
                        )
                        method = "action_" + action["type"]
                        with patch.object(
                            self.app, method, wraps=getattr(self.app, method)
                        ) as handler:
                            self.answer([payload], question, provider=provider)
                        handler.assert_not_called()
                        self.assertEqual(self.files(), before)
                        self.assertEqual(self.app.project_snapshot(), state)
                        self.assertEqual(self.app.places, places)
                        self.app.root.after.assert_not_called()
                        self.assertTrue(
                            any(row["texte"] == "Voici la recette." for row in self.app.history)
                        )

    def test_unknown_action_cannot_select_a_new_handler_by_name(self):
        self.app.action_supprimer_projet = Mock()
        before = self.files()
        self.answer(
            [
                {
                    "type": "supprimer_projet",
                    "id": self.app.projects.active,
                    "confirmation": "Métallurgie",
                }
            ]
        )
        self.app.action_supprimer_projet.assert_not_called()
        self.assertEqual(self.files(), before)

    def test_malformed_model_json_keeps_the_text_and_local_files(self):
        before = self.files()
        for payload in (
            "{broken",
            "null",
            "true",
            "42",
            '"effacer_plan"',
            "[null, 42]",
            '[{"type": ["effacer_plan"]}]',
            '[{"type": {"action": "effacer_plan"}}]',
        ):
            with self.subTest(payload=payload):
                with patch("monifactory_assistant.providers.responses.log"):
                    self.app.on_answer(
                        "Recette ?", "Réponse utile.<actions>" + payload + "</actions>"
                    )
                self.assertEqual(self.files(), before)
                self.assertTrue(any(row["texte"] == "Réponse utile." for row in self.app.history))

    def test_blocked_replays_and_restart_leave_original_and_backups_untouched(self):
        before = self.files()
        for _ in range(3):
            self.answer(
                [
                    {
                        "type": "effacer_plan",
                        "requete": "same",
                        "n": 123,
                        "confirmation": "Métallurgie",
                    }
                ]
            )
        self.app.load_plan(self.path)
        self.answer([{"type": "effacer_plan", "requete": "same", "n": 123}])
        self.assertEqual(self.files(), before)

    def test_display_search_markers_pause_and_safe_settings_keep_working(self):
        before = self.path.read_bytes()
        self.answer(
            [
                {"type": "surligner", "objets": ["minecraft:iron_ingot"]},
                {"type": "guider", "nom": "Base"},
                {"type": "pause_objectif"},
                {"type": "voix", "active": False},
                {"type": "affichage", "visible": False},
                {"type": "reglage", "cle": "coffres", "valeur": False},
                {"type": "reglage", "cle": "enchainer", "valeur": False},
            ]
        )
        self.assertEqual(self.app.search["objets"], ["minecraft:iron_ingot"])
        self.assertEqual(self.app.guide["label"], "Base")
        self.assertFalse(self.app.settings["coffres"])
        self.assertFalse(self.app.settings["panneau"])
        self.assertEqual(self.path.read_bytes(), before)
        self.answer([{"type": "effacer_surbrillance"}, {"type": "arreter_guidage"}])
        self.assertIsNone(self.app.search)
        self.assertIsNone(self.app.guide)

    def test_new_place_is_saved_once_without_overwriting_known_names(self):
        original = copy.deepcopy(self.app.places["Base"])
        self.answer([{"type": "memoriser_lieu", "nom": "Mine"}])
        self.assertEqual(self.app.places["Mine"]["x"], 20)
        saved = self.files()
        self.app.game["x"] = 99
        for name in (
            "Mine",
            " mine ",
            "Base",
            " BASE ",
            "",
            "  ",
            None,
            12,
            "bad\nname",
            "x" * 101,
        ):
            with self.subTest(name=name):
                self.answer([{"type": "memoriser_lieu", "nom": name}])
                self.assertEqual(self.files(), saved)
        self.assertEqual(self.app.places["Base"], original)

    def test_appended_objective_cannot_forge_completion_or_active_state(self):
        before = copy.deepcopy(self.app.objectives)
        self.answer(
            [{"type": "ajouter_objectif", "titre": "Moteur", "etat": "en_cours", "position": 1}]
        )
        self.assertEqual([o for o in self.app.objectives if o["titre"] != "Moteur"], before)
        added = next(o for o in self.app.objectives if o["titre"] == "Moteur")
        self.assertEqual(added["etat"], "a_faire")
        self.answer([{"type": "ajouter_objectif", "titre": "Moteur"}])
        self.assertEqual(sum(o["titre"] == "Moteur" for o in self.app.objectives), 1)

    def test_native_clear_still_keeps_complete_previous_backup(self):
        before = self.path.read_bytes()
        self.app.action_effacer_plan({})
        self.assertIsNone(self.app.plan)
        self.assertEqual(self.path.with_suffix(".json.bak").read_bytes(), before)

    def test_mixed_batch_is_rejected_before_any_persistent_or_display_effect(self):
        before = self.files()
        additions = [
            {"type": "ajouter_objectif", "titre": "Moteur"},
            {"type": "memoriser_lieu", "nom": "Mine"},
            {"type": "surligner", "objets": ["minecraft:iron_ingot"]},
        ]
        for actions in (
            additions + [{"type": "effacer_plan"}],
            [{"type": "effacer_plan"}] + additions,
        ):
            self.answer(actions)
            self.assertEqual(self.files(), before)
            self.assertIsNone(self.app.search)
            self.assertNotIn("Mine", self.app.places)
            self.assertEqual(self.app.history[-2]["texte"], "Voici la recette.")
            self.assertIn("n’ont pas été exécutées", self.app.history[-1]["texte"])

    def test_two_place_additions_cannot_overwrite_each_other_in_the_same_batch(self):
        before = self.files()
        self.answer(
            [
                {"type": "memoriser_lieu", "nom": "Mine"},
                {"type": "memoriser_lieu", "nom": " mine "},
            ]
        )
        self.assertEqual(self.files(), before)

    def test_stale_world_reply_cannot_add_memory_or_objectives(self):
        before = self.files()
        self.app.question_scope = "old-session"
        self.app.bridge = SimpleNamespace(
            isolated=True, scope="new-session", scope_valid=Mock(return_value=True)
        )
        self.answer(
            [
                {"type": "memoriser_lieu", "nom": "Mine"},
                {"type": "ajouter_objectif", "titre": "Moteur"},
            ]
        )
        self.assertEqual(self.files(), before)
        self.assertIn("connexion précédente", self.app.history[-1]["texte"])

    def test_pause_can_preserve_an_active_project_without_resetting_it(self):
        self.app.objective_paused = False
        self.app.save_plan()
        state = self.app.project_snapshot()
        before = self.path.read_bytes()
        self.answer([{"type": "pause_objectif"}])
        state["objectif_en_pause"] = True
        self.assertEqual(self.app.project_snapshot(), state)
        self.assertEqual(self.path.with_suffix(".json.bak").read_bytes(), before)

    def test_empty_project_uses_native_creation_until_async_model_creation_is_qualified(self):
        self.app.load_plan(self.directory / "empty.json")
        self.app.save_plan()
        before = self.files()
        self.answer([{"type": "nouveau_plan", "demande": "LV"}])
        self.app.root.after.assert_not_called()
        self.assertEqual(self.files(), before)


if __name__ == "__main__":
    unittest.main()
