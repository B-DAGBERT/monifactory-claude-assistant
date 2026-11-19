"""The view written for the mod: no project or objective data, guidance and reply kept."""

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from monifactory_assistant.game.storage import Storage
from monifactory_assistant.settings import SETTINGS
from monifactory_assistant.ui.views import build_mod_view

NOW = 1000.0

OBJECTIVE_KEYS = {
    "objectif",
    "objectif_en_pause",
    "objectif_version",
    "objectif_emi",
    "projets",
    "plan",
    "pourquoi",
    "progres",
    "etape",
    "etape_num",
    "etapes",
    "prochains",
    "alertes",
    "sections",
    "arbre",
    "besoins",
    "recette",
    "fabrications",
}


def app(**changes):
    world = SimpleNamespace(
        machines=lambda: [], observation_live=lambda m: True, players=[], active=False
    )
    values = dict(
        game={"x": 20, "y": 64, "z": 30, "dimension": "minecraft:overworld", "inventaire": {}},
        storage=Storage([]),
        have=dict,
        visible=True,
        bridge=SimpleNamespace(script_outdated=lambda state: False),
        status="",
        search=None,
        guide=None,
        message="Voici la recette.",
        message_until=NOW + 60,
        world=world,
        info=None,
        busy=False,
        settings={key: default for key, (_, _, default) in SETTINGS.items()},
        history=[],
    )
    values.update(changes)
    return SimpleNamespace(**values)


class ModViewTests(unittest.TestCase):
    def test_no_project_or_objective_data_is_published(self):
        view = build_mod_view(app(), have={}, now=NOW)
        json.dumps(view)  # written as is in vue_mod.json
        self.assertFalse(OBJECTIVE_KEYS & view.keys())
        self.assertFalse(OBJECTIVE_KEYS & view["hud"].keys())
        self.assertFalse(OBJECTIVE_KEYS & view["ecran"].keys())
        keys = {row["cle"] for row in view["ecran"]["reglages"]}
        self.assertNotIn("enchainer", keys)
        self.assertNotIn("surbrillance", keys)
        self.assertIn("panneau", keys)

    def test_requested_guidance_still_draws_its_beam(self):
        guide = {"label": "Base", "x": 1, "y": 64, "z": 2}
        view = build_mod_view(app(guide=guide), have={}, now=NOW)
        beam = {
            "x": 1,
            "y": 64,
            "z": 2,
            "couleur": "bleu",
            "label": "Base",
            "dimension": "minecraft:overworld",
        }
        self.assertEqual(view["guidage"], [beam])
        self.assertEqual(view["hud"]["direction"], beam)
        hidden = app(guide=guide)
        hidden.settings["faisceaux"] = False
        self.assertEqual(build_mod_view(hidden, have={}, now=NOW)["guidage"], [])
        self.assertEqual(
            build_mod_view(app(guide=guide, game=None), have={}, now=NOW)["guidage"], []
        )
        self.assertEqual(
            build_mod_view(app(guide={**guide, "x": None}), have={}, now=NOW)["guidage"], []
        )

    def test_reply_bubble_does_not_depend_on_the_panel_setting(self):
        for shown in (True, False):
            view = build_mod_view(app(visible=shown), have={}, now=NOW)
            self.assertIs(view["hud"]["visible"], shown)
            self.assertEqual(view["bulle"]["texte"], "Voici la recette.")

    def test_recipe_view_uses_only_the_supplied_search_result(self):
        dimension = "minecraft:overworld"
        result = (
            {"recette": "test:plate_recipe"},
            (42, NOW + 10, "test-world", dimension, frozenset({"test:plate"})),
            [],
        )
        with (
            patch("pathlib.Path.stat", side_effect=AssertionError),
            patch("pathlib.Path.read_text", side_effect=AssertionError),
            patch(
                "monifactory_assistant.game.emi_search._matching_chests", side_effect=AssertionError
            ),
        ):
            view = build_mod_view(app(), have={}, now=NOW, emi_search=result)
        search = view["recherche_emi"]
        self.assertEqual(search["n"], 42)
        self.assertEqual(search["recette"], "test:plate_recipe")
        self.assertEqual(search["fin"], NOW + 10)
        self.assertEqual(search["objets"], ["test:plate"])
        self.assertEqual(search["coffres"], [])


if __name__ == "__main__":
    unittest.main()
