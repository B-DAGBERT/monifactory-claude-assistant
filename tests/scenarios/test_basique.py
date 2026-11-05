"""Parcours simples, rejoués sur le vrai contrôleur client et la vraie recherche Python."""

import unittest

from monifactory_assistant.game.emi_search import chest_search
from tests.scenarios.fixtures import ClientJava, ContratJava


def noeud(item, n=1, enfants=(), *, outil=False):
    return {
        "ids": [item] if item else [],
        "quantite": n,
        "production": 1,
        "outil": outil,
        "enfants": list(enfants),
    }


def coffre(x, item="test:plate", n=2, **kw):
    return {
        "cle": f"test:world|{x}|64|0",
        "x": x,
        "y": 64,
        "z": 0,
        "t": 1_000_000,
        "dimension": "test:world",
        "bloc": "minecraft:chest",
        "objets": [{"item": item, "n": n}],
        **kw,
    }


class Parcours(ContratJava):
    def setUp(self):
        self.client = ClientJava(self)
        self.addCleanup(self.client.close)
        self.state = {"dimension": "test:world", "x": 0, "y": 64, "z": 0}

    def demarrer(self, n=2, *, arbre=None):
        if arbre is None:
            arbre = noeud("test:machine", enfants=[noeud("test:plate", n, [noeud("test:dust")])])
        return self.client.step(arbre=arbre)

    def rafraichir(self, coffres, **event):
        last = self.client.last
        reply = chest_search(last["demande"], coffres, self.state, last["temps"] / 1000)
        return self.client.step(reponse=reply or {}, **event)


class ScenariosBasiques(Parcours, unittest.TestCase):
    def test_01_craft_machine_coffre_retrait_puis_pose(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        r = self.rafraichir([coffre(3)])
        self.assertEqual(r["selection"], {"test:plate": 2})
        self.assertEqual(r["coffres"], [{"x": 3, "jaune": True}])
        r = self.client.step(inventaire={"test:plate": 2})
        self.assertEqual(r["restants"], {})
        r = self.rafraichir([coffre(3)])
        self.assertEqual(r["coffres"], [])
        self.client.step(inventaire={})
        r = self.rafraichir([coffre(3)])
        self.assertEqual(r["selection"], {})
        self.assertEqual(r["coffres"], [])

    def test_02_livraison_partielle_puis_solde(self):
        self.demarrer(10)
        self.client.step(lot={"item": "test:plate", "n": 10})
        self.assertEqual(self.rafraichir([coffre(3, n=4)])["selection"], {"test:plate": 4})
        r = self.client.step(inventaire={"test:plate": 4})
        self.assertEqual(r["restants"], {"test:plate": 6})
        self.assertEqual(self.rafraichir([coffre(3, n=6)])["selection"], {"test:plate": 6})
        self.assertEqual(self.client.step(inventaire={"test:plate": 10})["restants"], {})

    def test_03_outil_unique_pour_un_lot(self):
        arbre = noeud("test:gear", 20, [noeud("test:ingot", 2), noeud("test:mold", outil=True)])
        self.demarrer(arbre=arbre)
        r = self.rafraichir([coffre(2, "test:mold", 12), coffre(4, "test:ingot", 40)])
        self.assertEqual(r["selection"], {"test:mold": 1, "test:ingot": 40})

    def test_04_arret_retirant_tous_les_contours(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        self.assertTrue(self.rafraichir([coffre(3)])["coffres"])
        r = self.client.step(arreter=True)
        self.assertFalse(r["actif"])
        self.assertEqual(r["coffres"], [])
        self.assertEqual(r["selection"], {})

    def test_05_un_resultat_arrive_apres_un_coffre_vide(self):
        self.demarrer()
        self.client.step(lot={"item": "test:plate", "n": 2})
        self.assertEqual(self.rafraichir([])["coffres"], [])
        self.assertEqual(self.rafraichir([coffre(3)])["coffres"], [{"x": 3, "jaune": True}])

    def test_06_moule_installe_disponibilite_reversible(self):
        arbre = noeud("test:gear", 20, [noeud("test:ingot", 2), noeud("test:mold", outil=True)])
        self.demarrer(arbre=arbre)
        self.client.step(outils={"test:mold": 1})
        r = self.rafraichir([coffre(2, "test:mold", 3), coffre(4, "test:ingot", 40)])
        self.assertEqual(r["selection"], {"test:ingot": 40})
        self.client.step(outils={})
        r = self.rafraichir([coffre(2, "test:mold", 3), coffre(4, "test:ingot", 40)])
        self.assertEqual(r["selection"], {"test:mold": 1, "test:ingot": 40})
