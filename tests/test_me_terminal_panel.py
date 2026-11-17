"""Terminal AE2 : même panneau que la Collecte, utilisable d'un simple clic."""

import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia"


def source(chemin):
    return (JAVA / f"{chemin}.java").read_text(encoding="utf-8")


def corps(texte, signature):
    debut = texte.index(signature)
    fin = texte.index("{", debut)
    profondeur = 1
    while profondeur:
        fin += 1
        profondeur += (texte[fin] == "{") - (texte[fin] == "}")
    return texte[debut : fin + 1]


class MeTerminalPanelTests(unittest.TestCase):
    def setUp(self):
        self.terminal = source("compat/emi/TerminalMe")

    def test_single_click_keeps_its_release_away_from_the_terminal(self):
        # Player report: the button only worked while held. Released over the terminal, the
        # click put down or threw the stack that the withdrawal held on the cursor.
        clic = corps(self.terminal, "public static void clic(ScreenEvent.MouseButtonPressed.Pre")
        self.assertIn("RelachementSouris.consommer(event);", clic)
        self.assertIn("priority = EventPriority.HIGHEST", self.terminal)
        self.assertIn('terminer("Retrait ME arrêté.")', clic)
        self.assertIn('terminer("Retrait ME interrompu.")', clic)
        self.assertNotIn("BoutonTheme", self.terminal)
        self.assertNotIn("event.addListener", self.terminal)

    def test_panel_matches_the_chest_collecte(self):
        self.assertIn("DispositionCoffre.calculer(", self.terminal)
        self.assertIn("PanneauAssistant.fond(", self.terminal)
        self.assertIn("PanneauAssistant.titre(", self.terminal)
        self.assertIn('NOM = "Réseau ME"', self.terminal)
        for libelle in ('PRENDRE = "Prendre"', 'TOUT = "Tout"', 'ARRETER = "Arrêter"'):
            self.assertIn(libelle, self.terminal)
        rendu = corps(self.terminal, "public static void rendu(ScreenEvent.Render.Post")
        self.assertIn("Theme.Genre.PRINCIPAL", rendu)
        self.assertIn("Theme.Genre.DANGER", rendu)
        zone = corps(self.terminal, "static void zoneEmi(")
        self.assertIn("d.cadre()", zone)

    def test_click_reads_the_terminal_again_and_can_take_one_item(self):
        lancer = corps(self.terminal, "private static void lancer(")
        self.assertIn("actualiser();", lancer)
        self.assertIn("selection == null", lancer)
        self.assertIn("ItemStack.isSameItemSameTags(", lancer)
        self.assertNotIn("clique à nouveau", lancer)

    def test_end_of_withdrawal_refreshes_without_hiding_the_panel(self):
        terminer = corps(self.terminal, "private static void terminer(String texte)")
        self.assertIn("actualiser();", terminer)

    def test_panel_allocates_buffer_before_withdrawable_me_entries(self):
        actualiser = corps(self.terminal, "private static void actualiser()")
        self.assertIn("vue.tampon() == revisionTampon", actualiser)
        self.assertIn("InventaireArbreEmi.horsTampon(stocks(stock), pilesTampon)", actualiser)
        self.assertLess(
            actualiser.index("physiques.addAll(tampon)"),
            actualiser.index("int debut = physiques.size()"),
        )
        self.assertIn("physiques.addAll(stockMe)", actualiser)
        self.assertIn("stock.get(prise.index() - debut)", actualiser)
        self.assertIn("new Lot(entree.serial(), entree.pile(), prise.quantite())", actualiser)

    def test_crafting_panel_avoids_the_me_panel(self):
        self.assertIn("PanneauAssistant.poser(affiche, disposition.cadre());", self.terminal)
        self.assertIn("PanneauAssistant.occupeeEtPanneau(ecranActuel)", source("AideEcran"))


if __name__ == "__main__":
    unittest.main()
