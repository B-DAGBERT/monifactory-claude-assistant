"""The view written for the mod: no project or objective data, guidance and reply kept."""

import json
import time
import unittest
from types import SimpleNamespace

from monifactory_assistant.game.storage import Storage
from monifactory_assistant.settings import SETTINGS
from monifactory_assistant.ui.views import build_mod_view

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
        message_until=time.time() + 60,
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
        view = build_mod_view(app())
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
        view = build_mod_view(app(guide=guide))
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
        self.assertEqual(build_mod_view(hidden)["guidage"], [])
        self.assertEqual(build_mod_view(app(guide=guide, game=None))["guidage"], [])
        self.assertEqual(build_mod_view(app(guide={**guide, "x": None}))["guidage"], [])

    def test_reply_bubble_does_not_depend_on_the_panel_setting(self):
        for shown in (True, False):
            view = build_mod_view(app(visible=shown))
            self.assertIs(view["hud"]["visible"], shown)
            self.assertEqual(view["bulle"]["texte"], "Voici la recette.")


if __name__ == "__main__":
    unittest.main()
