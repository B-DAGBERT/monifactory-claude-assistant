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
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.settings import SETTINGS


class Fixture(ConversationController, GameController, InterfaceController):
    pass


# Actions of the removed projects and objectives: an old model reply may still propose them.
REMOVED_ACTIONS = (
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
    {"type": "pause_objectif"},
    {"type": "reprendre_objectif"},
    {"type": "ajouter_objectif", "titre": "Moteur", "etat": "en_cours", "position": 1},
)


class AIActionGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        # An old project save stays on disk: no action may read, rewrite or delete it.
        self.path = self.directory / "plan_ia.json"
        self.path.write_text(
            json.dumps(
                {
                    "objectif_en_pause": True,
                    "version": 41,
                    "plan": {"objectif": "Passer en LV", "cibles": [], "etapes": []},
                    "plan_principal": {"titre": "Métallurgie", "objectifs": []},
                }
            ),
            encoding="utf-8",
        )
        self.app = Fixture()
        app = self.app
        app.load_places(self.directory / "lieux.json")
        app.places = {"Base": {"x": 1, "y": 64, "z": 2, "dimension": "minecraft:overworld"}}
        app.save_places()
        app.game = {"x": 20, "y": 64, "z": 30, "dimension": "minecraft:overworld"}
        app.storage = Storage([])
        app.settings = {key: default for key, (_, _, default) in SETTINGS.items()}
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
            *REMOVED_ACTIONS,
            {"type": "oublier_coffre", "proche": True},
            {"type": "oublier_lieu", "nom": "Base"},
            {"type": "memoriser_lieu", "nom": "Base"},
            {"type": "reglage", "cle": "enchainer", "valeur": True},
            {"type": "reglage", "cle": "surbrillance", "valeur": False},
        ]
        for provider in ("codex", "claude"):
            for question in ("Comment fabriquer un marteau ?", "Efface mon plan", "oui"):
                for action in actions:
                    with self.subTest(provider=provider, question=question, action=action):
                        before, settings, places = (
                            self.files(),
                            dict(self.app.settings),
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
                        # Even a handler of that name could never be selected by the model.
                        with patch.object(
                            self.app,
                            method,
                            wraps=getattr(self.app, method, None),
                            create=not hasattr(self.app, method),
                        ) as handler:
                            self.answer([payload], question, provider=provider)
                        handler.assert_not_called()
                        self.assertEqual(self.files(), before)
                        self.assertEqual(self.app.settings, settings)
                        self.assertEqual(self.app.places, places)
                        self.app.root.after.assert_not_called()
                        self.assertTrue(
                            any(row["texte"] == "Voici la recette." for row in self.app.history)
                        )
                        self.assertIn("n’ont pas été exécutées", self.app.history[-1]["texte"])

    def test_unknown_action_cannot_select_a_new_handler_by_name(self):
        self.app.action_supprimer_projet = Mock()
        before = self.files()
        self.answer(
            [{"type": "supprimer_projet", "id": "principal", "confirmation": "Métallurgie"}]
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

    def test_blocked_replays_leave_original_files_untouched(self):
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
        self.answer([{"type": "effacer_plan", "requete": "same", "n": 123}])
        self.assertEqual(self.files(), before)

    def test_display_search_markers_and_safe_settings_keep_working(self):
        before = self.path.read_bytes()
        self.answer(
            [
                {"type": "surligner", "objets": ["minecraft:iron_ingot"]},
                {"type": "guider", "nom": "Base"},
                {"type": "voix", "active": False},
                {"type": "affichage", "visible": False},
                {"type": "reglage", "cle": "coffres", "valeur": False},
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

    def test_mixed_batch_is_rejected_before_any_persistent_or_display_effect(self):
        before = self.files()
        additions = [
            {"type": "guider", "nom": "Base"},
            {"type": "memoriser_lieu", "nom": "Mine"},
            {"type": "surligner", "objets": ["minecraft:iron_ingot"]},
        ]
        for refused in ({"type": "effacer_plan"}, {"type": "ajouter_objectif", "titre": "Moteur"}):
            for actions in (additions + [refused], [refused] + additions):
                with self.subTest(actions=actions):
                    self.answer(actions)
                    self.assertEqual(self.files(), before)
                    self.assertIsNone(self.app.search)
                    self.assertIsNone(self.app.guide)
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

    def test_stale_world_reply_cannot_add_memory_or_markers(self):
        before = self.files()
        self.app.question_scope = "old-session"
        self.app.bridge = SimpleNamespace(
            isolated=True, scope="new-session", scope_valid=Mock(return_value=True)
        )
        self.answer(
            [
                {"type": "memoriser_lieu", "nom": "Mine"},
                {"type": "surligner", "objets": ["minecraft:iron_ingot"]},
            ]
        )
        self.assertEqual(self.files(), before)
        self.assertIsNone(self.app.search)
        self.assertIn("connexion précédente", self.app.history[-1]["texte"])


if __name__ == "__main__":
    unittest.main()
