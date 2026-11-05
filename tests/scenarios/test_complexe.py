"""Parcours avancés : retards, longue fabrication, lots concurrents et sources contradictoires."""

import unittest
from unittest.mock import patch

from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import World
from tests.scenarios.test_basique import Parcours, coffre, noeud


class ScenariosComplexes(Parcours, unittest.TestCase):
    def test_01_fabrication_plus_longue_que_dix_minutes(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        self.rafraichir([])
        r = self.client.step(temps=1_720_000)
        self.assertTrue(
            r["actif"], "un scan choisi doit encore chercher une sortie machine après douze minutes"
        )
        r = self.rafraichir([coffre(8)])
        self.assertEqual(r["coffres"], [{"x": 8, "jaune": True}])

    def test_02_assistant_lent_puis_reponse(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        r = self.client.step(temps=1_012_000)
        self.assertTrue(r["actif"], "un retard ne doit pas désactiver définitivement le scan")
        self.assertGreaterEqual(r["envois"], 2)
        self.assertEqual(self.rafraichir([coffre(4)])["coffres"], [{"x": 4, "jaune": True}])

    def test_03_deux_lots_meme_sortie_sans_double_comptage(self):
        self.demarrer(200)
        self.client.step(lot={"item": "test:plate", "n": 64})
        self.client.step(lot={"item": "test:plate", "n": 136})
        r = self.rafraichir([coffre(2, n=64), coffre(5, n=136)])
        self.assertEqual(r["selection"], {"test:plate": 200})
        self.assertEqual(len(r["coffres"]), 2)
        self.assertEqual(
            self.client.step(inventaire={"test:plate": 64})["restants"], {"test:plate": 136}
        )
        self.assertEqual(self.client.step(inventaire={"test:plate": 200})["restants"], {})
        self.client.step(inventaire={})
        r = self.rafraichir([coffre(2, n=200)])
        self.assertEqual(r["selection"], {})

    def test_04_ancienne_reponse_apres_changement_de_besoins(self):
        self.demarrer()
        old = self.client.last["demande"]
        self.client.step(lot={"item": "test:plate", "n": 2})
        from monifactory_assistant.game.emi_search import chest_search

        reply = chest_search(old, [coffre(3, "test:dust", 64)], self.state, 1000.1)
        r = self.client.step(reponse=reply)
        self.assertEqual(r["coffres"], [])
        self.assertEqual(self.rafraichir([coffre(3)])["selection"], {"test:plate": 2})

    def test_05_pause_et_autre_dimension_ne_relancent_rien(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        self.rafraichir([coffre(2)])
        r = self.client.step(actif=False, temps=1_720_000)
        self.assertFalse(r["actif"])
        self.assertEqual(r["coffres"], [])
        r = self.client.step(actif=True)
        self.assertFalse(r["actif"], "la reprise du scan reste explicite")

    def test_06_sac_en_cours_de_synchronisation(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        self.rafraichir([coffre(2)])
        r = self.client.step(sync=False)
        self.assertEqual(r["coffres"], [])
        self.assertEqual(r["selection"], {})
        r = self.client.step(sync=True)
        self.assertTrue(r["coffres"])

    def test_07_coffre_ouvert_plus_recent_que_le_serveur(self):
        world = object.__new__(World)
        world.blocks = {"test:world|3|64|0": coffre(3, n=0, t=1000)}
        client = Storage([coffre(3, n=8, t=2000)])
        merged = world.merged(client)
        self.assertEqual(merged.raw[0]["objets"], [{"item": "test:plate", "n": 8}])
        self.demarrer(8)
        self.client.step(lot={"item": "test:plate", "n": 8})
        self.assertEqual(self.rafraichir(merged.raw)["coffres"], [{"x": 3, "jaune": True}])

    def test_08_serveur_plus_recent_annule_un_vieux_stock(self):
        world = object.__new__(World)
        world.blocks = {"test:world|3|64|0": coffre(3, n=0, t=3000)}
        merged = world.merged(Storage([coffre(3, n=8, t=2000)]))
        self.assertEqual(merged.raw[0]["objets"], [{"item": "test:plate", "n": 0}])

    def test_09_mise_a_jour_serveur_recue_dans_le_desordre(self):
        world = object.__new__(World)
        world.blocks, world.last_t, world.players, world.errors = {}, 0, [], []
        frame = {
            "t": 3000,
            "dimension": "test:world",
            "zone": [0, 0, 0, 0],
            "blocs": [{"p": [3, 64, 0], "b": "minecraft:chest", "o": [["test:plate", 8]]}],
        }
        with patch("monifactory_assistant.game.world.atomic_json"):
            self.assertTrue(world.update(frame))
            self.assertFalse(world.update({**frame, "t": 2000, "blocs": []}))
        self.assertEqual(world.blocks["test:world|3|64|0"]["objets"][0]["n"], 8)

    def test_10_deconnexion_ne_garde_pas_les_coffres(self):
        self.demarrer()
        self.rafraichir([coffre(3)])
        self.assertFalse(self.client.step(reconnecter=True)["actif"])
        self.assertEqual(self.client.last["coffres"], [])

    def test_11_reessais_bornes_puis_retour_assistant(self):
        self.demarrer()
        precedent = self.client.last["envois"]
        for now in (1_009_000, 1_026_000, 1_057_000, 1_088_000):
            r = self.client.step(temps=now)
            self.assertEqual(r["envois"], precedent + 1)
            precedent = r["envois"]
            self.assertEqual(self.client.step(temps=now + 1)["envois"], precedent)
        r = self.rafraichir([coffre(8)])
        self.assertTrue(r["actif"])
        self.assertTrue(r["coffres"])

    def test_12_ecriture_refusee_puis_retablie(self):
        self.client.step(ecriture=False)
        self.demarrer()
        self.assertTrue(self.client.last["actif"])
        r = self.client.step(ecriture=True, temps=1_009_000)
        self.assertTrue(r["demande"]["actif"])
        self.assertTrue(self.rafraichir([coffre(8)])["coffres"])

    def test_13_cent_coffres_et_livraison_par_etapes(self):
        import random

        self.demarrer(320)
        self.client.step(lot={"item": "test:plate", "n": 320})
        coffres = [coffre(3 * i, n=8) for i in range(1, 41)]
        coffres += [coffre(3 * i, "test:unrelated", 64) for i in range(41, 101)]
        random.Random(731).shuffle(coffres)
        r = self.rafraichir(coffres)
        self.assertEqual(r["selection"], {"test:plate": 320})
        self.assertEqual(len(r["coffres"]), 40)
        for step in range(1, 9):
            self.client.step(inventaire={"test:plate": 40 * step})
            remaining = 320 - step * 40
            r = self.rafraichir(coffres)
            self.assertEqual(r["restants"], {"test:plate": remaining} if remaining else {})
            self.assertEqual(r["selection"].get("test:plate", 0), remaining)
        self.client.step(inventaire={})
        self.assertEqual(self.rafraichir(coffres)["coffres"], [])

    def test_14_composant_disponible_coupe_les_enfants_et_leur_moule(self):
        arbre = noeud(
            "test:machine",
            enfants=[
                noeud("test:gear", 2, [noeud("test:ingot", 4), noeud("test:mold", outil=True)])
            ],
        )
        self.demarrer(arbre=arbre)
        r = self.rafraichir(
            [coffre(3, "test:gear", 2), coffre(6, "test:ingot", 64), coffre(9, "test:mold", 8)]
        )
        self.assertEqual(r["selection"], {"test:gear": 2})
        self.assertEqual([c["x"] for c in r["coffres"]], [3])

    def test_15_un_meme_coffre_dans_deux_dimensions_ne_se_fusionne_pas(self):
        world = object.__new__(World)
        world.blocks = {"test:world|3|64|0": coffre(3, n=8, t=3000)}
        other = coffre(3, n=12, t=4000, dimension="test:nether")
        merged = world.merged(Storage([other]))
        self.assertEqual(len(merged.raw), 2)
        self.demarrer(8)
        r = self.rafraichir(merged.raw)
        self.assertEqual(r["selection"], {"test:plate": 8})
        self.assertEqual(len(r["coffres"]), 1)

    def test_16_coffre_vide_omis_par_le_script_serveur(self):
        world = object.__new__(World)
        world.blocks, world.last_t, world.players, world.errors = {}, 0, [], []
        frame = {
            "t": 1000,
            "dimension": "test:world",
            "zone": [0, 0, 0, 0],
            "blocs": [{"p": [3, 64, 0], "b": "minecraft:chest", "o": [["test:plate", 8]]}],
        }
        with patch("monifactory_assistant.game.world.atomic_json"):
            world.update(frame)
            world.update({**frame, "t": 3000, "blocs": []})
        merged = world.merged(Storage([coffre(3, n=8, t=2000)]))
        self.assertEqual(merged.containers, [])
        self.assertEqual(merged.raw[0]["objets"], [])
        self.demarrer(8)
        self.assertEqual(self.rafraichir(merged.raw)["coffres"], [])
        # Une réouverture plus récente reste prioritaire sur cette absence.
        merged = world.merged(Storage([coffre(3, n=4, t=4000)]))
        self.assertEqual(merged.containers[0]["objets"][0]["n"], 4)

    def test_17_machine_remplacant_un_coffre_ne_compte_pas_son_ancien_stock(self):
        world = object.__new__(World)
        machine = coffre(3, n=0, t=3000, etat={"s": "IDLE"}, bloc="gtceu:mixer")
        world.blocks = {"test:world|3|64|0": machine}
        merged = world.merged(Storage([coffre(3, n=8, t=2000)]))
        self.assertEqual(merged.containers, [])
        self.assertEqual(len(merged.machines), 1)
