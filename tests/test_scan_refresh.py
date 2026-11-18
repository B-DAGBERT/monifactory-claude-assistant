"""Repérage : panneau de coffre à jour sans attendre, machines seulement pendant le scan."""

import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia"


def source(nom):
    return (JAVA / f"{nom}.java").read_text(encoding="utf-8")


def corps(texte, signature):
    debut = texte.index(signature)
    fin = texte.index("{", debut)
    profondeur = 1
    while profondeur:
        fin += 1
        profondeur += (texte[fin] == "{") - (texte[fin] == "}")
    return texte[debut : fin + 1]


class ScanRefreshTests(unittest.TestCase):
    def test_new_request_keeps_the_last_survey(self):
        # Player report: the result panel lagged, and taking the result early broke the recipe.
        recherche = source("RechercheEmi")
        transmettre = corps(recherche, "private static void transmettre(boolean nouvelEssai)")
        self.assertNotIn("quantites = Map.of()", transmettre)
        self.assertNotIn("reperes = List.of()", transmettre)
        self.assertIn("appliquer();", transmettre)
        # Each acquisition recomputes the selection from the kept survey, in the tick.
        tick = corps(recherche, "public static void tick(TickEvent.ClientTickEvent event)")
        self.assertIn("versionAppliquee", tick)
        self.assertIn("appliquer();", tick)

    def test_open_chest_uses_live_slots_before_other_ordinary_chests(self):
        recherche = source("RechercheEmi")
        retrait = corps(recherche, "private static List<PlanRetrait.Prise> retraitDirect(")
        self.assertIn("CoffreTampon.pilesHorsMenu()", retrait)
        self.assertLess(retrait.index("tous.addAll(debut, locaux)"), retrait.index("stocksConnus("))
        disponibles = corps(recherche, "public static Map<String, Integer> resultatsDisponibles()")
        self.assertIn("RetraitCoffre.coffreOuvert(", disponibles)
        panneau = corps(source("AideCoffre"), "public static void avantRendu(")
        self.assertIn("RechercheEmi.revision()", panneau)
        self.assertIn("signature(ecran)", panneau)

    def test_machines_glow_only_while_scanning(self):
        machines = source("compat/emi/MachinesEmi")
        tick = corps(machines, "public static void tick(TickEvent.ClientTickEvent event)")
        self.assertIn("CoffresArbreEmi.scanActif()", tick)
        monde = corps(machines, "public static void monde(RenderLevelStageEvent event)")
        self.assertIn("!surlignage", monde)
        barre = source("compat/emi/CoffresArbreEmi")
        self.assertIn('"Repérage ON"', barre)
        self.assertNotIn('"Coffres ON"', barre)

    def test_buffer_beam_is_independent_of_ordinary_search_results(self):
        reperage = source("Reperage")
        rendu = corps(reperage, "public static void rendu(RenderLevelStageEvent event)")
        self.assertIn("rechercheActive ? CoffreTampon.reperes()", rendu)
        self.assertIn("if (coffresTampons.isEmpty() && reperes.isEmpty())", rendu)
        self.assertLess(
            rendu.index("for (var tampon : coffresTampons)"), rendu.index("faisceauTampon(")
        )
        self.assertLess(rendu.index("faisceauTampon("), rendu.index("if (reperes.isEmpty())"))
        self.assertIn(
            "CoffreTampon.selectionne(", corps(reperage, "private static boolean visible(")
        )
        self.assertIn("BeaconRenderer.renderBeaconBeam(", reperage)
        self.assertIn('case A_REVOIR -> "Tampon · à revérifier"', rendu)


if __name__ == "__main__":
    unittest.main()
