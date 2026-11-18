"""Le lanceur rapporte un vrai échec, une erreur ou un prérequis absent."""

import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.commands.scenarios import charger, rejouer


class ScenarioRunnerTests(unittest.TestCase):
    def test_rapport_et_codes_de_retour(self):
        class Cas(unittest.TestCase):
            def test_ok(self):
                pass

            def test_echec(self):
                self.fail("stock incoherent")

            def test_erreur(self):
                raise RuntimeError("client indisponible")

            @unittest.skip("JDK absent")
            def test_ignore(self):
                pass

        with tempfile.TemporaryDirectory() as directory:
            for cas, valide in (
                ("ok", True),
                ("echec", False),
                ("erreur", False),
                ("ignore", False),
            ):
                with self.subTest(cas=cas):
                    rapport = Path(directory) / f"{cas}.json"
                    suite = unittest.TestSuite([Cas("test_" + cas)])
                    status = rejouer("basique", suite, rapport=rapport, stream=io.StringIO())
                    doc = json.loads(rapport.read_text())
                    self.assertEqual(status, 0 if valide else 1)
                    self.assertEqual(doc["valide"], valide)
                    self.assertEqual(doc["tests"], 1)
                    self.assertTrue(doc["cas"][0]["scenario"].endswith("test_" + cas))

    def test_suites_sans_doublon_machine(self):
        with patch("monifactory_assistant.commands.scenarios.unittest.defaultTestLoader") as loader:
            charger("tous")
            noms = loader.loadTestsFromNames.call_args.args[0]
        self.assertEqual(len(noms), len(set(noms)))
        self.assertTrue(any("basique" in n for n in noms))
        self.assertTrue(any("complexe" in n for n in noms))
        self.assertTrue(any("test_parcours_stock" in n for n in noms))
        self.assertIn("tests.scenarios.test_stock_craft", noms)
        self.assertTrue(any("MachineInputTests" in n for n in noms))

    def test_fabrication_lance_etabli_et_machines(self):
        with patch("monifactory_assistant.commands.scenarios.unittest.defaultTestLoader") as loader:
            charger("fabrication")
            noms = loader.loadTestsFromNames.call_args.args[0]
        self.assertEqual(
            noms,
            [
                "tests.scenarios.test_stock_craft",
                "tests.scenarios.test_parcours_stock",
                "tests.test_me_terminal_panel.MeTerminalPanelTests.test_panel_allocates_buffer_before_withdrawable_me_entries",
                "tests.test_me_terminal_panel.MeTerminalPanelTests.test_retrait_par_piles_attend_le_serveur_et_respecte_le_quota",
                "tests.test_emi_backpack_tree.EmiBackpackTreeTests.test_same_backpack_changes_emi_ae2_and_machine_plans_when_toggled",
                "tests.test_machine_inputs.MachineInputTests",
            ],
        )

    def test_rapport_identifie_les_etapes_en_echec(self):
        class Parcours(unittest.TestCase):
            def test_stock(self):
                with self.subTest(etape="avant"):
                    self.assertEqual(3, 3)
                with self.subTest(etape="double clic"):
                    self.fail("unité dupliquée")
                with self.subTest(etape="reconnexion"):
                    raise RuntimeError("session indisponible")

        with tempfile.TemporaryDirectory() as directory:
            rapport = Path(directory) / "rapport.json"
            suite = unittest.TestSuite([Parcours("test_stock")])
            status = rejouer("parcours-stock", suite, rapport=rapport, stream=io.StringIO())
            doc = json.loads(rapport.read_text(encoding="utf-8"))

        self.assertEqual(status, 1)
        self.assertFalse(doc["valide"])
        self.assertEqual((doc["tests"], doc["echecs"], doc["erreurs"]), (1, 1, 1))
        self.assertEqual([cas["etat"] for cas in doc["cas"]], ["echec", "erreur"])
        echec, erreur = doc["cas"]
        self.assertIn("etape='double clic'", echec["scenario"])
        self.assertIn("unité dupliquée", echec["detail"])
        self.assertIn("etape='reconnexion'", erreur["scenario"])
        self.assertIn("session indisponible", erreur["detail"])

    def test_parcours_stock_peut_se_lancer_seul(self):
        with patch("monifactory_assistant.commands.scenarios.unittest.defaultTestLoader") as loader:
            charger("parcours-stock")
            noms = loader.loadTestsFromNames.call_args.args[0]
        self.assertEqual(len(noms), len(set(noms)))
        self.assertIn("tests.scenarios.test_parcours_stock", noms)
        self.assertTrue(
            any("test_reads_are_scoped_to_the_active_connection_and_world" in n for n in noms)
        )
        self.assertTrue(
            any("test_native_and_legacy_chest_identities_match_python" in n for n in noms)
        )
        self.assertTrue(
            any(
                "test_nearby_stock_uses_exact_radius_without_replanning_unchanged_totals" in n
                for n in noms
            )
        )
        self.assertTrue(any("BufferChestTests." in n for n in noms))
        self.assertTrue(any("EmiBackpackTreeTests." in n for n in noms))
        self.assertTrue(any("MeTerminalPanelTests." in n for n in noms))
        self.assertTrue(any("MachinePanelClickTests." in n for n in noms))
        self.assertTrue(all("MachineInputTests" not in n for n in noms))

    def test_erreur_de_preparation_rapportee(self):
        class Cas(unittest.TestCase):
            @classmethod
            def setUpClass(cls):
                raise RuntimeError("Gson absent")

            def test_rien(self):
                pass

        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "rapport.json"
            status = rejouer(
                "complexe", unittest.TestSuite([Cas("test_rien")]), rapport=p, stream=io.StringIO()
            )
            self.assertEqual(status, 1)
            self.assertEqual(json.loads(p.read_text())["erreurs"], 1)
