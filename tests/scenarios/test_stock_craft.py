"""Du retrait au résultat reçu. Les cases et le gestionnaire natif sont des fixtures."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_chest_transfers import ROOT
from tests.test_chest_transfers import STUBS as COFFRES
from tests.test_crafting_panel import STUBS as ETABLI

JAVA = ROOT / "mod/src/main/java/fr/assistantia"
CONTRAT = ROOT / "tests/java/fr/assistantia/StockVersEtabliTest.java"


def methode(source, signature):
    debut = source.index(signature)
    fin = source.index("{", debut)
    niveau = 1
    while niveau:
        fin += 1
        niveau += (source[fin] == "{") - (source[fin] == "}")
    return source[debut : fin + 1]


class StockCraftTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        suffix = ".exe" if os.name == "nt" else ""
        javac, cls.java = (
            str(home / f"bin/{nom}{suffix}")
            if (home / f"bin/{nom}{suffix}").is_file()
            else shutil.which(nom)
            for nom in ("javac", "java")
        )
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK 17 requis pour le contrat coffre et établi")
        dossier = tempfile.TemporaryDirectory()
        cls.addClassCleanup(dossier.cleanup)
        cls.dossier = dossier.name
        racine = Path(dossier.name)

        sources = {
            f"{nom.replace('.', '/')}.java": f"package {nom.rsplit('.', 1)[0]};\n{corps}"
            for nom, corps in COFFRES.items()
        }
        sources.update(
            {
                nom: corps
                for nom, corps in ETABLI.items()
                if nom.startswith(("dev/", "fr/assistantia/compat/"))
            }
        )
        sources["net/minecraft/world/item/Item.java"] = (
            "package net.minecraft.world.item; public record Item(String id) {}"
        )
        sources["net/minecraft/network/chat/Component.java"] = """
            package net.minecraft.network.chat;
            public record Component(String text) {
                public static Component literal(String text){return new Component(text);}
                public String getString(){return text;}
            }
        """
        sources["net/minecraft/world/item/ItemStack.java"] = sources[
            "net/minecraft/world/item/ItemStack.java"
        ].replace(
            "public final String id;",
            """public static final ItemStack EMPTY=new ItemStack("",0);
            public Item getItem(){return new Item(id);}
            public boolean isDamageableItem(){return id.equals("wrench");}
            public net.minecraft.network.chat.Component getHoverName(){
                return net.minecraft.network.chat.Component.literal(id);}
            public final String id;""",
        )
        sources["dev/emi/emi/api/recipe/EmiRecipe.java"] = sources[
            "dev/emi/emi/api/recipe/EmiRecipe.java"
        ].replace(
            "List<EmiStack> getOutputs();",
            "List<EmiStack> getOutputs();List<EmiStack> getInputs();",
        )
        sources["dev/emi/emi/api/stack/EmiStack.java"] = """
            package dev.emi.emi.api.stack;
            import java.util.List;
            import net.minecraft.world.item.ItemStack;
            public record EmiStack(ItemStack stack) {
                public ItemStack getItemStack(){return stack;}
                public boolean isEmpty(){return stack.isEmpty();}
                public long getAmount(){return stack.getCount();}
                public List<EmiStack> getEmiStacks(){return List.of(this);}
            }
        """
        sources["net/minecraft/world/inventory/ResultSlot.java"] = """
            package net.minecraft.world.inventory;
            public class ResultSlot extends Slot {
                public ResultSlot(Object owner, int slot){super(owner,slot);}
            }
        """
        ecran = (JAVA / "AideEcran.java").read_text(encoding="utf-8")
        sources["fr/assistantia/AideEcran.java"] = (
            """
            package fr.assistantia;
            import java.util.*;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.world.inventory.*;
            import net.minecraft.world.item.*;
            import fr.assistantia.compat.emi.AtelierEmi;

            public final class AideEcran {
                static AbstractContainerScreen<?> ecranActuel;
                static List<Proposition> propositions=List.of();
                static String cle="";
                static String message="";
                static boolean autorise(){return true;}
                static void informer(String texte){message=texte;}
                static Proposition choisie(){return propositions.isEmpty()?null:propositions.get(0);}
                static List<Proposition> calculer(AbstractContainerScreen<?> ecran, Map<Item,Integer> stock){
                    try {
                        var resultat=AtelierEmi.propositions(ecran,stock,8);
                        return resultat==null?List.of():resultat;
                    } catch (ReflectiveOperationException e){throw new AssertionError(e);}
                }
                static void ouvrir(AbstractContainerScreen<?> ecran){
                    ecranActuel=ecran;propositions=calculer(ecran,stock(ecran));message="";
                }
                static void cliquer(boolean tout){agir(tout);}
            """
            + methode(ecran, "public record Proposition(")
            + methode(ecran, "private static void agir(boolean toutFaire)")
            + methode(ecran, "private static Map<Item, Integer> stock(")
            + "}"
        )
        emi = (JAVA / "Emi.java").read_text(encoding="utf-8")
        sources["fr/assistantia/Emi.java"] = (
            """
            package fr.assistantia;
            import java.lang.reflect.Method;
            import java.util.*;
            import dev.emi.emi.api.recipe.EmiRecipe;
            import dev.emi.emi.api.stack.EmiStack;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Item;

            public class Emi {
                private static final Object typeBouton=new Object(), destinationAucune=new Object();
                private static final Method getInputs, ingredientIsEmpty, getEmiStacks,
                    getAmount, getItemStack, performFill;
                static {
                    try {
                        getInputs=EmiRecipe.class.getMethod("getInputs");
                        ingredientIsEmpty=EmiStack.class.getMethod("isEmpty");
                        getEmiStacks=EmiStack.class.getMethod("getEmiStacks");
                        getAmount=EmiStack.class.getMethod("getAmount");
                        getItemStack=EmiStack.class.getMethod("getItemStack");
                        performFill=Emi.class.getMethod("remplirFixture",Object.class,
                            AbstractContainerScreen.class,Object.class,Object.class,int.class);
                    } catch (ReflectiveOperationException e){throw new AssertionError(e);}
                }
                static boolean pret(){return true;}
                static final class Journal {
                    void warn(String message,Throwable erreur){throw new AssertionError(message,erreur);}
                }
                static final Journal LOGGER=new Journal();
                public static boolean remplirFixture(Object recette,AbstractContainerScreen<?> ecran,
                        Object type,Object destination,int n){
                    if(type!=typeBouton||destination!=destinationAucune)
                        throw new AssertionError("le remplissage doit conserver le résultat dans la grille");
                    return StockVersEtabliTest.remplisseur.remplir(
                        (StockVersEtabliTest.Recette)recette,ecran,n);
                }
            """
            + methode(emi, "public static int possibles(")
            + methode(emi, "private static long disponible(")
            + methode(emi, "public static boolean remplir(")
            + "}"
        )
        fichiers = []
        for nom, source in sources.items():
            chemin = racine / nom
            chemin.parent.mkdir(parents=True, exist_ok=True)
            chemin.write_text(source, encoding="utf-8")
            fichiers.append(str(chemin))
        fichiers.extend(
            str(JAVA / nom)
            for nom in (
                "PlanRetrait.java",
                "RetraitCoffre.java",
                "RessourceCollecte.java",
                "ArbreCollecte.java",
                "MemoireCollecte.java",
                "PlanArbre.java",
                "SuiviFabrication.java",
                "compat/emi/AtelierEmi.java",
            )
        )
        fichiers.append(str(CONTRAT.with_name("CollecteCoffreFixture.java")))
        compilation = subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", cls.dossier, *fichiers, str(CONTRAT)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )
        if compilation.returncode:
            raise RuntimeError(compilation.stdout + compilation.stderr)

    def rejouer(self, scenario, *parametres):
        resultat = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                self.dossier,
                "fr.assistantia.StockVersEtabliTest",
                scenario,
                *map(str, parametres),
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=15,
        )
        self.assertEqual(resultat.returncode, 0, resultat.stdout + resultat.stderr)

    def test_retrait_cible_puis_complet_craft_et_retrait_du_resultat(self):
        self.rejouer("collecte")

    def test_quantites_pres_de_la_limite_de_la_grille(self):
        for quantite in (0, 1, 2, 63, 64, 65, 127, 128, 129):
            with self.subTest(quantite=quantite):
                self.rejouer("quantites", quantite)

    def test_interruption_entre_coffre_recette_et_grille(self):
        for interruption in ("echap", "retrait-partiel", "recette", "stock", "grille"):
            with self.subTest(interruption=interruption):
                self.rejouer("interruption", interruption)

    def test_rangement_et_reprise_du_produit_ne_le_creditent_qu_une_fois(self):
        self.rejouer("credit")
