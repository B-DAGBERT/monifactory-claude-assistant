"""Vérifie les cases de « Tout » en reliant les deux projections natives de l'arbre."""

import subprocess
import unittest
from pathlib import Path

from scenarios.fixtures import ROOT, ContratJava
from scenarios.fixtures import STUBS as SCAN
from test_craft_proof_cost import STUBS as PROUVES
from test_machine_inputs import STUBS as MACHINE
from test_me_terminal_panel import corps


class CollecteVariantesTests(ContratJava, unittest.TestCase):
    def test_branches_nbt_dans_les_cases_du_coffre(self):
        directory = Path(self.temp.name)
        src = ROOT / "mod/src/main/java/fr/assistantia"
        stubs = {
            key: value for key, value in MACHINE.items() if key.startswith(("net.", "dev.emi.emi."))
        }
        for name in (
            "net.minecraft.core.BlockPos",
            "fr.assistantia.InventaireJoueur",
            "fr.assistantia.CoffreTampon",
        ):
            stubs[name] = SCAN[name]
        stubs["net.minecraft.core.BlockPos"] = stubs["net.minecraft.core.BlockPos"].replace(
            "public int getX()", "public BlockPos immutable(){return this;}public int getX()"
        )
        level = corps(SCAN["net.minecraft.client.Minecraft"], "public static class Level")
        dimension = corps(SCAN["net.minecraft.client.Minecraft"], "public record Dimension")
        stubs["net.minecraft.client.Minecraft"] = stubs["net.minecraft.client.Minecraft"].replace(
            "public Object level=new Object();",
            f"public Level level=new Level();{level}{dimension}",
        )
        # Ce seul objet a une comparaison EMI par défaut souple et une variante stricte explicite.
        comparison = "dev.emi.emi.api.stack.Comparison"
        stubs[comparison] = stubs[comparison].replace(
            "public static Comparison compareNbt(){return new Comparison();}",
            "public static final Comparison NBT=new Comparison();public static Comparison compareNbt(){return NBT;}",
        )
        stack = "dev.emi.emi.api.stack.EmiStack"
        stubs[stack] = (
            stubs[stack]
            .replace(
                "public Object key;",
                "private Comparison mode=Comparison.DEFAULT_COMPARISON; public Object key;",
            )
            .replace("s.chance=chance;", "s.chance=chance;s.mode=mode;")
            .replace("f.apply(Comparison.DEFAULT_COMPARISON);", "mode=f.apply(mode);")
            .replace(
                "public boolean isEqual(EmiStack b){",
                """public boolean isEqual(EmiStack b){
                if (getKey().equals("test:comparateur") && getKey().equals(b.getKey()) &&
                    mode==Comparison.DEFAULT_COMPARISON && b.mode==Comparison.DEFAULT_COMPARISON) return true;""",
            )
        )
        # Démarrer par le vrai bouton de Repérage, avec les menus d'EMI simulés.
        basculer = corps(
            (src / "compat/emi/CoffresArbreEmi.java").read_text(), "private static void basculer()"
        )
        stubs["fr.assistantia.ArbreEmi"] = """public class ArbreEmi {
            public static Object arbre;
            public static Object consulte(){return arbre;}
            public static Object actif(){return arbre;}
            public static boolean activer(){return true;}}"""
        stubs["fr.assistantia.compat.emi.GregTechMachines"] = PROUVES[
            "fr.assistantia.compat.emi.GregTechMachines"
        ]
        emi = (src / "Emi.java").read_text()
        facade = "import java.util.Map;\n" + SCAN["fr.assistantia.Emi"]
        for methode in ("fabricationsConfirmees", "fabricationsCollecte"):
            facade = facade.replace(
                corps(facade, f"public static java.util.Map<String,Integer> {methode}()"),
                corps(emi, f"public static Map<String, Integer> {methode}()"),
            )
        stubs["fr.assistantia.Emi"] = facade.rsplit("}", 1)[0] + (
            "private static boolean pret(){return true;}\n"
            + corps(emi, "private static Map<String, Integer> fabrications(String")
            + "}"
        )
        stubs["fr.assistantia.compat.emi.CoffresArbreEmi"] = (
            """
            import fr.assistantia.*;
            import net.minecraft.client.Minecraft;
            import net.minecraft.network.chat.Component;
            public class CoffresArbreEmi {
                static final String ID = "test:scan";
                static long dernierClic;
                static InstantaneArbreEmi dernierArbre;
                public static Object arbreActif(){return ArbreEmi.actif();}
                static void ouvrir(Object arbre){ArbreEmi.arbre=arbre;basculer();}
                static void charger() {}
                static void signaler(Exception e){throw new AssertionError(e);}
            """
            + basculer
            + "}"
        )
        sources = []
        for name, body in stubs.items():
            path = directory / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}")
            sources.append(path)
        sources.extend(
            src / f"{name}.java"
            for name in (
                "PlanMachines",
                "ReceptionSortie",
                "MemoireCollecte",
                "RessourceCollecte",
                "RechercheEmi",
                "RetraitCoffre",
                "compat/emi/InstantaneArbreEmi",
                "compat/emi/ComparaisonEmi",
                "compat/emi/OrigineFabrication",
            )
        )
        sources.append(ROOT / "tests/java/fr/assistantia/compat/emi/CollecteVariantesTest.java")
        compiled = subprocess.run(
            [
                str(Path(self.java).with_name("javac")),
                "-cp",
                self.classpath,
                "-d",
                str(directory),
                *map(str, sources),
            ],
            capture_output=True,
            text=True,
            timeout=45,
        )
        self.assertEqual(compiled.returncode, 0, compiled.stderr)
        for scenario in (
            "aa",
            "aa-repartis",
            "comparateurs-distincts",
            "releve-ferme",
            "ab",
            "ba",
            "ab-unites",
            "ba-unites",
            "poches",
            "tampon",
            "alternative-a",
            "alternative-b",
            "outil",
            "comparaison-commune",
            "preuve-variante",
        ):
            with self.subTest(scenario=scenario):
                result = subprocess.run(
                    [
                        self.java,
                        "-ea",
                        "-cp",
                        self.classpath,
                        "fr.assistantia.compat.emi.CollecteVariantesTest",
                        scenario,
                    ],
                    capture_output=True,
                    text=True,
                    timeout=20,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
