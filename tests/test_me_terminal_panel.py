"""Terminal AE2 : même panneau que la Collecte, utilisable d'un simple clic."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"


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
        self.assertIn("actualiser(true);", lancer)
        self.assertIn("selection == null", lancer)
        self.assertIn("ItemStack.isSameItemSameTags(", lancer)
        self.assertNotIn("clique à nouveau", lancer)

    def test_end_of_withdrawal_refreshes_without_hiding_the_panel(self):
        terminer = corps(self.terminal, "private static void terminer(String texte)")
        self.assertIn("actualiser(true);", terminer)

    def test_panel_allocates_buffer_before_withdrawable_me_entries(self):
        actualiser = corps(self.terminal, "private static void actualiser(boolean complet)")
        self.assertIn("vue.tampon() == revisionTampon", actualiser)
        self.assertIn("StockFabricationEmi.courant(", actualiser)
        self.assertIn(
            "couverture.retraitsReseau(instantane.machines(), instantane.lots())", actualiser
        )
        self.assertIn("stock.get(prise.index())", actualiser)
        self.assertIn("new Lot(entree.serial(), entree.pile(), prise.quantite())", actualiser)
        lecture = corps(self.terminal, "private static List<Entree> lire(")
        self.assertIn("entrees(repo, recherche.filtre(), complet)", lecture)
        self.assertIn("ComparaisonEmi.meme(emi, v)", lecture)
        entrees = corps(self.terminal, "private static Collection<?> entrees(")
        self.assertIn("!complet && index != null", entrees)
        self.assertIn("ACCES.entrees().invoke(repo)", entrees)

    def test_crafting_panel_avoids_the_me_panel(self):
        self.assertIn("PanneauAssistant.poser(affiche, disposition.cadre());", self.terminal)
        self.assertIn("PanneauAssistant.occupeeEtPanneau(ecranActuel)", source("AideEcran"))

    def test_retrait_par_piles_attend_le_serveur_et_respecte_le_quota(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        suffix = ".exe" if os.name == "nt" else ""
        javac, java = (
            str(home / f"bin/{nom}{suffix}")
            if (home / f"bin/{nom}{suffix}").is_file()
            else shutil.which(nom)
            for nom in ("javac", "java")
        )
        if not javac or not java:
            self.skipTest("JDK 17 requis pour le contrat de retrait ME")
        fixture = (ROOT / "tests/java/fr/assistantia/RetraitTerminalMeTest.java").read_text(
            encoding="utf-8"
        )
        debut = self.terminal.index("private static AbstractContainerMenu menu;")
        fin = self.terminal.index("private static boolean erreur;", debut)
        champs = (
            corps(self.terminal, "private record Lot(")
            + corps(self.terminal, "private record Acces(")
            + self.terminal[debut:fin]
        )
        traitement = "\n".join(
            corps(self.terminal, signature)
            for signature in (
                "private static void avancer(",
                "private static int volumeRetrait(",
                "private static void attendre(",
                "private static void terminer(",
            )
        )
        fixture = fixture.replace("// EXTRACTION_CHAMPS", champs).replace(
            "// EXTRACTION_TRAITEMENT", traitement
        )
        with tempfile.TemporaryDirectory() as dossier:
            chemin = Path(dossier) / "RetraitTerminalMeTest.java"
            chemin.write_text(fixture, encoding="utf-8")
            compilation = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", dossier, str(chemin)],
                capture_output=True,
                encoding="utf-8",
                timeout=30,
            )
            self.assertEqual(compilation.returncode, 0, compilation.stderr)
            for scenario in ("quantites", "capacites", "contexte", "confirmation"):
                with self.subTest(scenario=scenario):
                    resultat = subprocess.run(
                        [
                            java,
                            "-ea",
                            "-cp",
                            dossier,
                            "fr.assistantia.RetraitTerminalMeTest",
                            scenario,
                        ],
                        capture_output=True,
                        encoding="utf-8",
                        timeout=15,
                    )
                    self.assertEqual(resultat.returncode, 0, resultat.stdout + resultat.stderr)


if __name__ == "__main__":
    unittest.main()
