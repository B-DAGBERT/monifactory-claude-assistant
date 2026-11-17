"""Panneau machine : le clic sur un produit change la recette sans ouvrir EMI."""

import unittest
from pathlib import Path

EMI = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia/compat/emi"


def corps(texte, signature):
    debut = texte.index(signature)
    fin = texte.index("{", debut)
    profondeur = 1
    while profondeur:
        fin += 1
        profondeur += (texte[fin] == "{") - (texte[fin] == "}")
    return texte[debut : fin + 1]


class MachinePanelClickTests(unittest.TestCase):
    def test_left_click_selects_and_only_right_click_opens_emi(self):
        # Player report: with several crafts to do, choosing one opened EMI each time.
        machines = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
        clic = corps(machines, "public static void clic(ScreenEvent.MouseButtonPressed.Pre")
        self.assertEqual(clic.count("EmiApi.displayRecipe("), 1)
        droit = corps(clic, "if (bouton == 1)")
        self.assertIn("EmiApi.displayRecipe(produit)", droit)
        self.assertIn("return;", droit)
        gauche = clic[clic.index("else if (produit != null && selectionnerAffichee(produit))") :]
        self.assertNotIn("displayRecipe", gauche)
        self.assertIn("RelachementSouris.consommer(event)", clic)

    def test_tooltip_explains_both_clicks(self):
        panneau = (EMI / "PanneauMachineEmi.java").read_text(encoding="utf-8")
        self.assertIn("Clic : choisir pour Envoyer", panneau)
        self.assertIn("Clic droit : voir la recette dans EMI", panneau)
        self.assertNotIn("Clic : ouvrir cette recette dans EMI", panneau)


if __name__ == "__main__":
    unittest.main()
