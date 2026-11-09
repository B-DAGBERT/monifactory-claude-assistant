"""Pièges du Rhino de KubeJS 6 vérifiés sans lancer le jeu.

Une déclaration ``const`` placée dans un bloc (``if``, ``for``, ``try``…) d'une fonction qui
contient une autre fonction (fléchée ou non) échoue à chaque appel : « TypeError: redeclaration of
var ». Reproduit avec rhino-forge 2001.2.3 du pack : ``let`` au même endroit fonctionne, et
``const`` dans un bloc d'une fonction sans fermeture aussi. En novembre 2026, ce piège a bloqué
toutes les données du serveur (coffres de sortie des machines, drawers…).
"""

import re
import unittest
from pathlib import Path

RACINE = Path(__file__).resolve().parents[1]
SCRIPTS = [RACINE / "jeu" / "assistant_ia.js", RACINE / "serveur" / "assistant_ia_serveur.js"]

MOTS_BLOC = {"if", "else", "for", "while", "do", "try", "catch", "finally", "switch"}
AVANT_REGEX = set("(,=:[!&|?{};+-*%<>~^") | {""}
MOTS_AVANT_REGEX = {"return", "typeof", "case", "in", "of", "delete", "void", "throw", "new"}


def _neutraliser(code):
    """Remplace commentaires, chaînes et expressions régulières par des espaces (lignes gardées)."""
    sortie = []
    i, n = 0, len(code)
    precedent = ""  # dernier caractère significatif, pour distinguer division et regex
    mot = ""
    while i < n:
        c = code[i]
        if code.startswith("//", i):
            fin = code.find("\n", i)
            fin = n if fin < 0 else fin
            sortie.append(" " * (fin - i))
            i = fin
            continue
        if code.startswith("/*", i):
            fin = code.find("*/", i + 2)
            fin = n if fin < 0 else fin + 2
            sortie.append(re.sub(r"[^\n]", " ", code[i:fin]))
            i = fin
            continue
        if c in "'\"`" or (c == "/" and (precedent in AVANT_REGEX or mot in MOTS_AVANT_REGEX)):
            fermeture = c
            j = i + 1
            classe = False
            while j < n:
                if code[j] == "\\":
                    j += 2
                    continue
                if fermeture == "/" and code[j] == "[":
                    classe = True
                elif fermeture == "/" and code[j] == "]":
                    classe = False
                elif code[j] == fermeture and not classe:
                    break
                elif code[j] == "\n" and fermeture != "`":
                    break
                j += 1
            if fermeture == "/":
                j += 1
                while j < n and code[j].isalpha():  # drapeaux de la regex
                    j += 1
            else:
                j += 1
            sortie.append(re.sub(r"[^\n]", " ", code[i:j]))
            precedent, mot = "a", ""
            i = j
            continue
        sortie.append(c)
        if c.isalnum() or c in "_$":
            mot = mot + c if precedent.isalnum() or precedent in "_$" else c
        elif not c.isspace():
            mot = ""
        if not c.isspace():
            precedent = c
        i += 1
    return "".join(sortie)


def _genre_accolade(code, position):
    """Ce qu'ouvre l'accolade : corps de fonction, bloc d'instructions ou objet littéral."""
    avant = code[:position].rstrip()
    if avant.endswith("=>"):
        return "fonction"
    if avant.endswith(")"):
        profondeur, j = 0, len(avant) - 1
        while j >= 0:
            if avant[j] == ")":
                profondeur += 1
            elif avant[j] == "(":
                profondeur -= 1
                if profondeur == 0:
                    break
            j -= 1
        mots = re.findall(r"[A-Za-z_$][\w$]*", avant[:j])
        dernier = mots[-1] if mots else ""
        if dernier in MOTS_BLOC:
            return "bloc"
        return "fonction"  # function f(...) {, function (...) {, méthode d'objet
    mot = re.search(r"([A-Za-z_$][\w$]*)$", avant)
    if mot and mot.group(1) in MOTS_BLOC:
        return "bloc"
    if mot and mot.group(1) == "return" or not avant or avant[-1] in "=(:,[?":
        return "objet"
    return "bloc"


def const_dans_un_bloc_de_fermeture(code):
    """Lignes des ``const`` déclarés dans un bloc d'une fonction qui contient une fermeture."""
    propre = _neutraliser(code)
    pile = []  # genres des accolades ouvertes
    fonctions = [{"fermeture": False, "consts": []}]  # niveau du script en bas
    fautes = []
    jeton = re.compile(r"=>|\bfunction\b|\bconst\b|[{}]")
    for m in jeton.finditer(propre):
        t = m.group()
        if t == "{":
            genre = _genre_accolade(propre, m.start())
            pile.append(genre)
            if genre == "fonction":
                fonctions[-1]["fermeture"] = True
                fonctions.append({"fermeture": False, "consts": []})
        elif t == "}":
            if pile and pile.pop() == "fonction" and len(fonctions) > 1:
                cadre = fonctions.pop()
                if cadre["fermeture"]:
                    fautes.extend(cadre["consts"])
        elif t in ("=>", "function"):
            fonctions[-1]["fermeture"] = True
        elif t == "const":
            interieur = [g for g in pile if g != "objet"]
            en_tete_de_boucle = re.search(r"\bfor\s*\(\s*$", propre[: m.start()]) is not None
            if en_tete_de_boucle or interieur and interieur[-1] == "bloc":
                fonctions[-1]["consts"].append(propre.count("\n", 0, m.start()) + 1)
    if fonctions[0]["fermeture"]:
        fautes.extend(fonctions[0]["consts"])
    return sorted(fautes)


class RhinoConstTests(unittest.TestCase):
    def test_detector_matches_the_reproduced_rhino_cases(self):
        # Cas exécutés avec rhino-forge 2001.2.3 : 1 et 4 échouent, 2 et 3 passent.
        echoue = "function f(x){ const n = [() => x].length; if (true) { const erreur = 1 } }"
        avec_let = "function f(x){ const n = [() => x].length; if (true) { let erreur = 1 } }"
        sans_fermeture = "function f(x){ if (true) { const erreur = 1; return 1 } }"
        self.assertEqual(const_dans_un_bloc_de_fermeture(echoue), [1])
        self.assertEqual(const_dans_un_bloc_de_fermeture(avec_let), [])
        self.assertEqual(const_dans_un_bloc_de_fermeture(sans_fermeture), [])
        # Par prudence, l'en-tête d'une boucle compte comme un bloc.
        boucle = "function f(l){ const g = () => 1; for (const x of l) { g() } }"
        self.assertEqual(const_dans_un_bloc_de_fermeture(boucle), [1])

    def test_detector_ignores_strings_regexes_objects_and_arrow_bodies(self):
        code = (
            "const R = /a|b{2}/\n"
            "function f(x) {\n"
            "  const o = { a: 1, b: () => { const ok = 2; return ok } }\n"
            "  const s = 'if (x) { const faux = 1 }'\n"
            "  x.forEach(v => { const aussi = v })\n"
            "  return o\n"
            "}\n"
        )
        self.assertEqual(const_dans_un_bloc_de_fermeture(code), [])

    def test_kubejs_scripts_have_no_block_const_in_functions_with_closures(self):
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                lignes = const_dans_un_bloc_de_fermeture(script.read_text(encoding="utf-8"))
                self.assertEqual(
                    lignes,
                    [],
                    f"{script.name} : remplacer const par let (piège Rhino) aux lignes {lignes}",
                )


if __name__ == "__main__":
    unittest.main()
