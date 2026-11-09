"""Crafting-tree panel adapter with deterministic native-handler contracts, no game or AI."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java"

STUBS = {
    "net/minecraft/world/item/Item.java": """package net.minecraft.world.item; public class Item {}""",
    "net/minecraft/network/chat/Component.java": """package net.minecraft.network.chat;
        public record Component(String text) {public String getString(){return text;}}""",
    "net/minecraft/world/item/ItemStack.java": """package net.minecraft.world.item;
        import net.minecraft.network.chat.Component;
        public class ItemStack {public static final ItemStack EMPTY=new ItemStack("");
        private final String name; public ItemStack(String name){this.name=name;}
        public boolean isEmpty(){return name.isEmpty();}
        public Component getHoverName(){return new Component(name);}}""",
    "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java": """package net.minecraft.client.gui.screens.inventory;
        public class AbstractContainerScreen<T> {}""",
    "dev/emi/emi/api/stack/EmiStack.java": """package dev.emi.emi.api.stack;
        import net.minecraft.world.item.ItemStack;
        public record EmiStack(ItemStack stack){public ItemStack getItemStack(){return stack;}}""",
    "dev/emi/emi/api/recipe/EmiRecipeCategory.java": """package dev.emi.emi.api.recipe;
        public record EmiRecipeCategory(String id){public String getId(){return id;}}""",
    "dev/emi/emi/api/recipe/EmiRecipe.java": """package dev.emi.emi.api.recipe;
        import java.util.List; import dev.emi.emi.api.stack.EmiStack;
        public interface EmiRecipe {EmiRecipeCategory getCategory();List<EmiStack> getOutputs();}""",
    "dev/emi/emi/api/recipe/handler/EmiCraftContext.java": """package dev.emi.emi.api.recipe.handler;
        import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        public record EmiCraftContext(AbstractContainerScreen<?> screen,Object inventory,Type type){
        public enum Type {FILL_BUTTON}}""",
    "dev/emi/emi/api/recipe/handler/EmiRecipeHandler.java": """package dev.emi.emi.api.recipe.handler;
        import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        import dev.emi.emi.api.recipe.EmiRecipe;
        public interface EmiRecipeHandler {
        Object getInventory(AbstractContainerScreen<?> screen);
        boolean supportsRecipe(EmiRecipe recipe);
        boolean canCraft(EmiRecipe recipe,EmiCraftContext context);}""",
    "dev/emi/emi/registry/EmiRecipeFiller.java": """package dev.emi.emi.registry;
        import java.util.*; import dev.emi.emi.api.recipe.EmiRecipe;
        import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
        import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        public class EmiRecipeFiller {public static Map<EmiRecipe,EmiRecipeHandler> handlers=new IdentityHashMap<>();
        public static EmiRecipeHandler getFirstValidHandler(EmiRecipe recipe,AbstractContainerScreen<?> screen){return handlers.get(recipe);}}""",
    "fr/assistantia/AideEcran.java": """package fr.assistantia; import net.minecraft.world.item.ItemStack;
        public class AideEcran {public record Proposition(Object recette,ItemStack produit,String nom,int fois,int possibles){}}""",
    "fr/assistantia/Emi.java": """package fr.assistantia; import java.util.*; import net.minecraft.world.item.Item;
        public class Emi {public static Map<Object,Integer> counts=new IdentityHashMap<>();
        public static int possibles(Object recipe,Map<Item,Integer> stock){return counts.getOrDefault(recipe,0);}}""",
    "fr/assistantia/compat/emi/CoffresArbreEmi.java": """package fr.assistantia.compat.emi;
        public class CoffresArbreEmi {public static Object tree; public static Object arbreActif(){return tree;}}""",
    "fr/assistantia/compat/emi/MachinesEmi.java": """package fr.assistantia.compat.emi;
        import java.util.*; import dev.emi.emi.api.recipe.EmiRecipe;
        public class MachinesEmi {public record Ligne(int lot,EmiRecipe recette,int fois){}
        public static List<Ligne> rows=List.of(); public static List<Ligne> lignes(){return rows;}}""",
}

HARNESS = """package fr.assistantia.compat.emi;
import java.util.*;
import dev.emi.emi.api.recipe.*;
import dev.emi.emi.api.recipe.handler.*;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;
import fr.assistantia.Emi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
public class AtelierEmiTest {
    record Recipe(String category,String output) implements EmiRecipe {
        public EmiRecipeCategory getCategory(){return new EmiRecipeCategory(category);}
        public List<EmiStack> getOutputs(){return List.of(new EmiStack(new ItemStack(output)));}
    }
    static class Handler implements EmiRecipeHandler {
        boolean supported=true,available=true; Object slots=new Object();int checks;
        public Object getInventory(AbstractContainerScreen<?> screen){return slots;}
        public boolean supportsRecipe(EmiRecipe recipe){return supported;}
        public boolean canCraft(EmiRecipe recipe,EmiCraftContext context){
            if(context.inventory()!=slots||context.type()!=EmiCraftContext.Type.FILL_BUTTON)
                throw new AssertionError("must use the native accessible slots");
            checks++;return available;
        }
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var screen=new AbstractContainerScreen<>();
        require(AtelierEmi.propositions(screen,Map.of(),8)==null,"legacy fallback only without active tree");
        CoffresArbreEmi.tree=new Object();
        require(AtelierEmi.propositions(screen,Map.of(),8).isEmpty(),"empty tree must not fallback");
        var recipe=new Recipe("minecraft:crafting","Cable");var handler=new Handler();
        EmiRecipeFiller.handlers.put(recipe,handler);Emi.counts.put(recipe,6);
        MachinesEmi.rows=List.of(new MachinesEmi.Ligne(-1,recipe,4));
        var choices=AtelierEmi.propositions(screen,Map.of(),8);
        require(choices.size()==1&&choices.get(0).recette()==recipe,"tree recipe is selected");
        require(choices.get(0).fois()==4&&choices.get(0).possibles()==6,"quantities preserved");
        require(choices.get(0).nom().equals("Cable"),"output label");
        handler.available=false;
        require(AtelierEmi.propositions(screen,Map.of(),8).isEmpty(),"backpack-only or incompatible native slots cannot fill");
        handler.available=true;handler.supported=false;
        require(AtelierEmi.propositions(screen,Map.of(),8).isEmpty(),"unsupported grid must not be proposed");
        handler.supported=true;
        MachinesEmi.rows=List.of(new MachinesEmi.Ligne(12,recipe,4));
        require(AtelierEmi.propositions(screen,Map.of(),8).isEmpty(),"already running lot must not be proposed");
        var machine=new Recipe("gtceu:wiremill","Wire");
        var fluid=new Recipe("minecraft:crafting","");
        EmiRecipeFiller.handlers.put(machine,handler);EmiRecipeFiller.handlers.put(fluid,handler);
        Emi.counts.put(machine,9);Emi.counts.put(fluid,9);
        MachinesEmi.rows=List.of(new MachinesEmi.Ligne(-1,machine,2),new MachinesEmi.Ligne(-1,fluid,2));
        require(AtelierEmi.propositions(screen,Map.of(),8).isEmpty(),"machine and fluid outputs excluded");
        var second=new Recipe("minecraft:crafting","Plate");
        EmiRecipeFiller.handlers.put(second,handler);Emi.counts.put(second,1);
        MachinesEmi.rows=List.of(new MachinesEmi.Ligne(-1,recipe,4),new MachinesEmi.Ligne(-1,second,1));
        choices=AtelierEmi.propositions(screen,Map.of(),1);
        require(choices.size()==1&&choices.get(0).recette()==recipe,"panel cap and order");
        EmiRecipeFiller.handlers.remove(recipe);
        require(AtelierEmi.propositions(screen,Map.of(),8).size()==1,"missing native handler skipped");
        System.out.println("AtelierEmi contracts OK");
    }
}
"""


class CraftingPanelTests(unittest.TestCase):
    def test_native_handlers_validate_active_tree_choices(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        suffix = ".exe" if os.name == "nt" else ""
        javac = (
            str(home / f"bin/javac{suffix}")
            if (home / f"bin/javac{suffix}").is_file()
            else shutil.which("javac")
        )
        java = (
            str(home / f"bin/java{suffix}")
            if (home / f"bin/java{suffix}").is_file()
            else shutil.which("java")
        )
        if not javac or not java:
            self.skipTest("JDK required for Java contract tests")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = dict(STUBS)
            files["fr/assistantia/compat/emi/AtelierEmiTest.java"] = HARNESS
            for name, source in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
            target = root / "fr/assistantia/compat/emi/AtelierEmi.java"
            target.write_text((JAVA / "fr/assistantia/compat/emi/AtelierEmi.java").read_text())
            sources = [str(path) for path in root.rglob("*.java")]
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            checked = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.compat.emi.AtelierEmiTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)
            self.assertIn("contracts OK", checked.stdout)

    def test_click_revalidates_selected_recipe_and_uses_fresh_quantities(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK required for Java contract tests")
        source = (JAVA / "fr/assistantia/AideEcran.java").read_text()

        def block(marker):
            start = source.index(marker)
            end = source.index("{", start)
            depth = 1
            while depth:
                end += 1
                depth += (source[end] == "{") - (source[end] == "}")
            return source[start : end + 1]

        # Compile the real action and quantity record; replace only their game dependencies.
        action = block("private static void agir(boolean toutFaire)")
        record = block("public record Proposition(")
        harness = """import java.util.*;
            public class ClicEtabliTest {
                static class ItemStack {}
                static Object ecranActuel=new Object();
                static String cle="previous";
                static boolean allowed=true;
                static int reads;
                static List<Proposition> propositions=List.of(), next=List.of();
                static Proposition displayed;
                static List<String> messages=new ArrayList<>();
                static Proposition choisie(){return displayed;}
                static boolean autorise(){return allowed;}
                static Map<String,Integer> stock(Object screen){reads++;return Map.of("fresh",reads);}
                static List<Proposition> calculer(Object screen,Map<String,Integer> stock){
                    if(!stock.containsKey("fresh"))throw new AssertionError("fresh slots required");
                    return next;
                }
                static void informer(String message){messages.add(message);}
                static class Emi {
                    static int calls,amount;static Object recipe;static boolean success=true;
                    static boolean remplir(Object r,Object screen,int n){calls++;recipe=r;amount=n;return success;}
                }
                RECORD
                ACTION
                static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
                public static void main(String[] args){
                    Object old=new Object(),replacement=new Object();
                    displayed=new Proposition(old,new ItemStack(),"old",8,8);
                    next=List.of(new Proposition(replacement,new ItemStack(),"replacement",3,3));
                    agir(true);
                    require(Emi.calls==0,"a new branch must not be substituted at click time");
                    require(reads==1&&messages.size()==1,"revalidate slots and report changed choice");
                    next=List.of();agir(true);
                    require(Emi.calls==0,"declared or completed recipe must not fill again");
                    next=List.of(new Proposition(old,new ItemStack(),"same",2,1));
                    agir(true);
                    require(Emi.calls==1&&Emi.recipe==old&&Emi.amount==1,"fresh remaining quantity replaces stale quantity");
                    Emi.success=false;agir(false);
                    require(Emi.calls==2&&messages.size()==3,"native fill failure is surfaced");
                    allowed=false;agir(true);
                    require(Emi.calls==2,"disabled fill must not move items");
                    System.out.println("Click revalidation OK");
                }
            }""".replace("RECORD", record).replace("ACTION", action)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ClicEtabliTest.java"
            path.write_text(harness)
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, str(path)],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "ClicEtabliTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Click revalidation OK", result.stdout)

    def test_panel_refreshes_without_inventory_change_and_keeps_emi_optional(self):
        source = (JAVA / "fr/assistantia/AideEcran.java").read_text(encoding="utf-8")
        self.assertIn("maintenant >= prochainCalcul", source)
        self.assertIn("prochainCalcul = maintenant + 250_000_000L", source)
        self.assertIn('Class.forName("fr.assistantia.compat.emi.AtelierEmi")', source)
        self.assertIn("if (arbre != null) return arbre;", source)
        self.assertIn("if (!autorise()) return List.of();", source)
        # Projets et objectifs retirés : sans arbre EMI actif, aucun ancien craft du plan.
        self.assertNotIn('"fabrications"', source)
        self.assertNotIn("Pont.vue()", source)
        calcul = source[source.index("private static List<Proposition> calculer(") :]
        calcul = calcul[: calcul.index("\n    }\n")]
        self.assertTrue(
            calcul.rstrip().endswith("if (arbre != null) return arbre;\n        return List.of();"),
            calcul,
        )

    def test_panel_uses_shared_layout_and_reserves_its_place_in_emi(self):
        source = (JAVA / "fr/assistantia/AideEcran.java").read_text(encoding="utf-8")
        plugin = (JAVA / "fr/assistantia/compat/emi/AssistantEmiPlugin.java").read_text(
            encoding="utf-8"
        )
        # Même panneau que les coffres et machines : placé hors de l'établi, réservé auprès d'EMI.
        self.assertIn("DispositionCoffre.calculer(", source)
        self.assertIn("PanneauAssistant.occupee(", source)
        self.assertIn("AideEcran.zoneEmi(screen)", plugin)
        # Les clics sur le panneau ne traversent jamais vers l'établi ou EMI.
        self.assertIn("event.setCanceled(true); // jamais de clic qui traverse", source)
        self.assertNotIn("plainSubstrByWidth", source)
        self.assertNotIn("Button.builder", source)
