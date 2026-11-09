"""Panneaux EMI, coffres, machines et établi : le thème commun reste la seule source de style."""

import re
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia"

# Interfaces dessinées dans EMI, les coffres, les machines et l'établi.
PANNEAUX = [
    "AideCoffre",
    "AideEcran",
    "BarreOutilsEmi",
    "DispositionCoffre",
    "EcranSac",
    "ListeSac",
    "PanneauAssistant",
    "Surbrillance",
    "compat/emi/CoffresArbreEmi",
    "compat/emi/DeclarerFabricationEcran",
    "compat/emi/PanneauMachineEmi",
    "compat/emi/RecuperationEmi",
    "compat/emi/SuiviEmiEcran",
]


def source(nom):
    return (JAVA / f"{nom}.java").read_text(encoding="utf-8")


def constante(texte, nom):
    trouve = re.search(rf"\b{nom}\s*=\s*(\d+)", texte)
    if trouve is None:
        raise AssertionError(nom)
    return int(trouve.group(1))


def corps(texte, signature):
    debut = texte.index(signature)
    fin = texte.index("{", debut)
    profondeur = 1
    while profondeur:
        fin += 1
        profondeur += (texte[fin] == "{") - (texte[fin] == "}")
    return texte[debut : fin + 1]


class ThemePanelTests(unittest.TestCase):
    def test_visible_text_colours_and_widgets_come_from_the_theme(self):
        for nom in PANNEAUX:
            texte = source(nom)
            with self.subTest(nom=nom):
                # Texte coupé par « … » avec infobulle : Theme.couper / Theme.texte.
                self.assertNotIn("plainSubstrByWidth", texte)
                self.assertNotIn("drawCenteredString", texte)
                # Couleurs du thème uniquement ; un masque RGB d'une constante reste permis.
                litterales = re.findall(r"(?<!& )0x[0-9A-Fa-f]{6,8}\b", texte)
                self.assertEqual(litterales, [], "couleur écrite à la main")
                self.assertNotIn("Hud.", texte)
                # Boutons et champs du thème, jamais les widgets vanilla.
                self.assertNotIn("Button.builder(", texte)
                self.assertNotIn("new EditBox(", texte)

    def test_one_button_height_everywhere(self):
        hauteur = constante(source("Theme"), "BOUTON")
        self.assertEqual(hauteur, 16)
        self.assertEqual(constante(source("DispositionCoffre"), "BOUTON"), hauteur)
        self.assertEqual(constante(source("BarreOutilsEmi"), "HAUTEUR"), hauteur)
        # Barre dorée commune au thème et aux panneaux posés sur une interface.
        self.assertIn("BARRE = DispositionCoffre.BARRE", source("Theme"))

    def test_machine_title_reserves_every_fixed_state(self):
        machines = source("compat/emi/MachinesEmi")
        panneau = source("compat/emi/PanneauMachineEmi")
        liste = panneau[panneau.index("static final List<String> ETATS") :]
        reserves = set(re.findall(r'"([^"]+)"', liste[: liste.index(");")]))
        retours = set()
        for signature in ("private static String etat(", "private static String etatSelection("):
            retours |= set(re.findall(r'return "([^"]+)"', corps(machines, signature)))
            retours |= set(re.findall(r'\? "([^"]+)" :', corps(machines, signature)))
            retours |= set(re.findall(r'\.orElse\("([^"]+)"\)', corps(machines, signature)))
        lots = set(
            re.findall(r'[A-Z_]+\("([^"]+)"\)', corps(source("LotFabrication"), "enum Etat"))
        )
        self.assertGreater(len(retours), 12)
        self.assertEqual(len(lots), 4)
        self.assertLessEqual(retours | lots, reserves, "état absent de la largeur du titre")

    def test_scan_state_uses_the_chosen_theme_button(self):
        barre = source("compat/emi/CoffresArbreEmi")
        self.assertIn(".choisi(selection)", barre)
        self.assertIn(".choisi(RechercheEmi.selectionnee(ID))", barre)
        self.assertNotIn("accent(", barre)
        self.assertFalse((JAVA / "compat/emi/BoutonOutilEmi.java").exists())
        # Le rappel de la déclaration nomme le bouton actuel, plus l'ancien « Scanner coffres ».
        declaration = source("compat/emi/DeclarerFabricationEcran")
        self.assertNotIn("Scanner coffres", declaration)
        self.assertIn("« Coffres ON »", declaration)


if __name__ == "__main__":
    unittest.main()
