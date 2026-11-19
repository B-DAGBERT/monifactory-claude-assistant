"""Characterize the EMI request at the refresh and publication boundary."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, call, patch

from monifactory_assistant.application.game import GameController
from monifactory_assistant.application.interface import InterfaceController, build_mod_view
from monifactory_assistant.game.emi_search import RecipeChestSearch, _matching_chests, _ordered
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import World
from tests.test_mod_view import app
from tests.test_world_routing import (
    NETHER,
    OVERWORLD,
    SESSION_A,
    SESSION_B,
    WORLD_A,
    WORLD_B,
    dump,
    enter,
    setup_bridge,
    stored,
    tags,
)


class RefreshApp(InterfaceController, GameController):
    pass


class EmiViewRefreshTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.clock = self.enterContext(patch("time.time", return_value=1000.0))
        self.monotonic = self.enterContext(patch("time.monotonic", return_value=10.0))
        self.log = self.enterContext(patch("monifactory_assistant.application.interface.log"))
        self.render = self.enterContext(
            patch(
                "monifactory_assistant.application.interface.build_mod_view", wraps=build_mod_view
            )
        )
        self.enterContext(
            patch(
                "monifactory_assistant.application.interface.diagnostic_view",
                return_value={"inventaire_t": 999000, "rendu_ms": 1.25},
            )
        )
        self.enterContext(patch("monifactory_assistant.providers.label", return_value="Codex"))
        self.scan = self.enterContext(
            patch("monifactory_assistant.game.emi_search._matching_chests", wraps=_matching_chests)
        )
        self.order = self.enterContext(
            patch("monifactory_assistant.game.emi_search._ordered", wraps=_ordered)
        )

    def prepare(self, *, isolated=False):
        self.bridge = setup_bridge(self.root, active=isolated)
        self.path = self.bridge.dir / "recherche_emi.json"
        self.read_request = self.bridge.read
        self.read = self.enterContext(patch.object(self.bridge, "read", wraps=self.read_request))
        # Keep the real serialization and scope tagging. Observe enqueue events synchronously.
        self.publications = self.enterContext(
            patch.object(self.bridge, "_queue_snapshot", return_value=True)
        )
        self.app = RefreshApp()
        vars(self.app).update(
            vars(app(message_until=1050, bridge=self.bridge, world=World(enabled=False)))
        )
        del self.app.have  # Use the real inventory snapshot from GameController.
        self.app.emi_search = RecipeChestSearch()
        self.app.root = Mock()
        self.app.refresh_pending = False
        self.app.last_mod_view = None
        self.request = {
            "actif": True,
            "n": 1,
            "fin": 1010.0,
            "monde": "jeton-natif-emi",
            "dimension": OVERWORLD,
            "recette": "test:recipe",
            "objets": ["test:plate"],
        }

    def tearDown(self):
        self.log.assert_not_called()

    def refresh(self, expected, *, reads, scans, orders, writes, force=True):
        self.app.refresh(force=force)
        self.assertEqual(self.read.call_args_list.count(call("recherche_emi.json")), reads)
        self.assertEqual(self.scan.call_count, scans)
        self.assertEqual(self.order.call_count, orders)
        self.assertEqual(self.publications.call_count, writes)
        _, payload, _ = self.publications.call_args.args
        view = json.loads(payload)
        actual = view["recherche_emi"]
        if isinstance(expected, dict) or expected is None:
            self.assertEqual(actual, expected)
        else:
            number, *positions = expected
            self.assertEqual(actual["n"], number)
            self.assertEqual([(c["x"], c["n"]) for c in actual["coffres"]], positions)
        return view

    def test_request_lifecycle_uses_only_effective_refreshes_and_publishes_changes(self):
        self.prepare()
        chest = {**stored(7)["conteneurs"][0], "x": 5}
        far = {**chest, "x": 30, "objets": [{"item": "test:plate", "n": 2}]}
        self.app.storage = Storage([far, chest])
        self.app.game = {**self.app.game, "x": 0, "z": 0}
        self.refresh(None, reads=0, scans=0, orders=0, writes=1, force=False)

        dump(self.path, {**self.request, "actif": False})
        self.refresh(None, reads=1, scans=0, orders=0, writes=1)
        dump(self.path, self.request)
        self.refresh(None, reads=1, scans=0, orders=0, writes=1, force=False)
        self.app.refresh(force=False)
        self.app.root.after.assert_called_once()
        self.assertEqual(self.render.call_count, 2)
        delay, delayed = self.app.root.after.call_args.args
        self.assertEqual(delay, 300)
        self.monotonic.return_value = 10.31
        delayed()
        self.assertEqual(self.render.call_count, 3)
        self.assertEqual(self.publications.call_count, 2)
        expected = {
            "n": 1,
            "monde": "jeton-natif-emi",
            "dimension": OVERWORLD,
            "fin": 1010.0,
            "recette": "test:recipe",
            "objets": ["test:plate"],
            "coffres": [
                {
                    "x": x,
                    "y": 64,
                    "z": 0,
                    "dimension": OVERWORLD,
                    "n": count,
                    "objets": {"test:plate": count},
                    "stock": f"{OVERWORLD}|{x}|64|0",
                    "bloc": "minecraft:barrel",
                    "texte": f"plate ×{count}",
                }
                for x, count in ((5, 7), (30, 2))
            ],
            "total_coffres": 2,
            "message": "2 coffre(s) connu(s) contiennent des ingrédients de cette recette.",
        }
        self.refresh(expected, reads=2, scans=1, orders=1, writes=2)
        dump(self.path, {**self.request, "n": 2, "recette": "test:other_recipe"})
        view = self.refresh(
            {**expected, "n": 2, "recette": "test:other_recipe"},
            reads=3,
            scans=1,
            orders=1,
            writes=3,
        )
        self.assertEqual(view["vie"], 200)
        self.clock.return_value = 1005.0
        view = self.refresh((2, (5, 7), (30, 2)), reads=3, scans=1, orders=1, writes=4)
        self.assertEqual(view["vie"], 201)
        self.assertEqual(view["ecran"]["diagnostic"]["inventaire_t"], 999000)

        self.app.game = {**self.app.game, "x": 30}
        self.refresh((2, (30, 2), (5, 7)), reads=3, scans=1, orders=2, writes=5)
        self.app.storage = Storage([{**c, "t": c["t"] + 1000} for c in (far, chest)])
        self.refresh((2, (30, 2), (5, 7)), reads=3, scans=1, orders=2, writes=5)
        self.app.storage = Storage([far])
        self.refresh((2, (30, 2)), reads=3, scans=2, orders=3, writes=6)

        self.path.write_text('{"n":', encoding="utf-8")
        self.refresh(None, reads=4, scans=2, orders=3, writes=7)
        self.refresh(None, reads=5, scans=2, orders=3, writes=7)
        third = {**self.request, "n": 3, "objets": ["test:wire"]}
        fourth = {**self.request, "n": 4, "objets": ["test:gear"]}
        dump(self.path, third)

        def replaced_during_read(name):
            data = self.read_request(name)
            replacement = self.path.with_suffix(".new")
            dump(replacement, fourth)
            replacement.replace(self.path)
            return data

        self.read.side_effect = replaced_during_read
        self.refresh(None, reads=6, scans=2, orders=3, writes=7)
        self.read.side_effect = None
        empty = self.refresh((4,), reads=7, scans=3, orders=4, writes=8)["recherche_emi"]
        self.assertEqual(empty["total_coffres"], 0)
        self.assertEqual(
            empty["message"],
            "Aucun ingrédient trouvé dans les coffres connus de cette dimension. Ouvre les coffres à mémoriser.",
        )
        dump(self.path, {**self.request, "n": 5})
        self.refresh((5, (30, 2)), reads=8, scans=4, orders=5, writes=9)
        self.clock.return_value = 1010.0
        self.refresh(None, reads=8, scans=4, orders=5, writes=10)
        dump(self.path, {**self.request, "n": 6, "fin": 1020.0})
        self.refresh((6, (30, 2)), reads=9, scans=4, orders=5, writes=11)
        dump(self.path, {"n": 7, "actif": False})
        self.refresh(None, reads=10, scans=4, orders=5, writes=12)
        self.path.unlink()
        self.refresh(None, reads=10, scans=4, orders=5, writes=12)

    def test_scope_change_rejects_unchanged_request_and_acquires_new_request_in_same_refresh(self):
        self.prepare(isolated=True)
        self.enterContext(patch("monifactory_assistant.application.game.ROOT", self.root))

        def change_scope(world, session, dimension=OVERWORLD):
            state = enter(self.bridge, world, session, dimension)
            self.app.apply_world_scope(self.bridge.scope)
            self.app.game = state

        change_scope(WORLD_A, SESSION_A)
        dump(self.bridge.dir / "mondes" / WORLD_A / "stockage.json", stored(7))
        self.app.storage = self.app.storage_monitor.refresh(self.bridge, self.app.world)
        dump(self.path, {**self.request, **tags(WORLD_A, SESSION_A)})
        from_scope_a = self.refresh((1, (0, 7)), reads=1, scans=1, orders=1, writes=1)
        for key, value in tags(WORLD_A, SESSION_A).items():
            self.assertEqual(from_scope_a[key], value)
            self.assertEqual(from_scope_a["recherche_emi"][key], value)
        old_search = self.app.emi_search

        change_scope(WORLD_A, SESSION_A, NETHER)
        self.assertIsNot(self.app.emi_search, old_search)
        self.refresh(None, reads=2, scans=1, orders=1, writes=2)
        self.refresh(None, reads=3, scans=1, orders=1, writes=2)
        change_scope(WORLD_B, SESSION_B)
        dump(self.bridge.dir / "mondes" / WORLD_B / "stockage.json", stored(42))
        self.app.storage = self.app.storage_monitor.refresh(self.bridge, self.app.world)
        self.refresh(None, reads=4, scans=1, orders=1, writes=3)
        dump(self.path, {**self.request, **tags(WORLD_B, SESSION_B), "n": 2})
        from_scope_b = self.refresh((2, (0, 42)), reads=5, scans=2, orders=2, writes=4)
        for key, value in tags(WORLD_B, SESSION_B).items():
            self.assertEqual(from_scope_b[key], value)
            self.assertEqual(from_scope_b["recherche_emi"][key], value)
