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
        self.assertTrue(any("MachineInputTests" in n for n in noms))

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
