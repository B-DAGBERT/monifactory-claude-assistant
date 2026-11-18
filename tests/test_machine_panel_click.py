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

    def test_machine_panel_calculations_run_on_tick_and_are_revalidated_at_click(self):
        machines = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
        tick = corps(machines, "public static void tick(TickEvent.ClientTickEvent event)")
        rendu = corps(machines, "public static void rendu(ScreenEvent.Render.Post")
        self.assertLess(
            tick.index("lots.actualiser();"), tick.index("if (panneau) actualiserInterface();")
        )
        self.assertNotIn("ScreenEvent.Render.Pre", machines)
        self.assertNotIn("actualiserInterface()", rendu)
        self.assertIn("contexteAffiche = contextePlan", rendu)
        clic = corps(machines, "private static boolean selectionnerAffichee(")
        self.assertLess(clic.index("actualiserInterface();"), clic.index("EnvoiMachine.preparer("))

    def test_machine_send_uses_demand_after_buffer_allocation(self):
        machines = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
        arbre = corps(machines, "private static void actualiserArbre()")
        demande = arbre.index("toutes = couverture.operations(vue.machines(), vue.lots())")
        self.assertIn("StockFabricationEmi.pourMachines(inventaire())", arbre[:demande])
        self.assertIn("operations = toutes.stream()", arbre[demande:])
        self.assertIn("stockCoffres(vue, horsPoches)", arbre[demande:])
        interface = corps(machines, "private static void actualiserInterface()")
        self.assertIn("operations.stream()", interface)
        self.assertIn(
            "EnvoiMachine.preparer(ecran, selected.recette(), selected.fois())", interface
        )
        selection = corps(machines, "private static boolean selectionnerAffichee(")
        self.assertLess(selection.index("actualiserInterface();"), selection.index("for (int i"))

    def test_observing_machine_inputs_does_not_require_an_emi_tree(self):
        machines = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
        tick = corps(machines, "public static void tick(TickEvent.ClientTickEvent event)")
        observation = tick.split("boolean panneau =", 1)[1].split(";", 1)[0]
        self.assertIn("affiche(mc.screen)", observation)
        self.assertNotIn("courant != null", observation)
        interface = corps(machines, "private static void actualiserInterface()")
        self.assertLess(
            interface.index("retenirEntrees(ui)"), interface.index("instantane == null")
        )
        arbre = corps(machines, "private static void actualiserArbre()")
        self.assertIn("entreesConnues.clear()", corps(arbre, "if (contexteChange)"))
        outils = corps(
            machines, "private static List<PlanRetrait.Stock<EmiStack>> outilsMachines()"
        )
        self.assertIn("!mc.level.hasChunkAt(e.getKey())", outils)


if __name__ == "__main__":
    unittest.main()
