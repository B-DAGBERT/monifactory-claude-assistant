"""Exercise the real EMI ordering adapter at its native evidence/preflight boundaries.

The packet reader and machine preflight are deterministic fixtures here; their actual
implementations are separately replayed by test_native_stock and test_machine_inputs.
No game, AI provider, world files, or production reservations are modified.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_machine_inputs import STUBS as MACHINE_STUBS

ROOT = Path(__file__).resolve().parents[1]
KEEP = (
    "net.minecraft.world.item.ItemStack",
    "net.minecraft.world.inventory.AbstractContainerMenu",
    "net.minecraft.world.inventory.Slot",
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
    "net.minecraft.network.chat.Component",
    "net.minecraft.world.level.material.Fluid",
    "net.minecraftforge.fluids.FluidStack",
    "net.minecraftforge.fluids.FluidUtil",
    "net.minecraftforge.fluids.capability.IFluidHandler",
    "net.minecraftforge.fluids.capability.templates.FluidTank",
    "dev.emi.emi.api.stack.EmiIngredient",
    "dev.emi.emi.api.stack.EmiStack",
    "dev.emi.emi.api.stack.Comparison",
    "dev.emi.emi.api.recipe.EmiRecipe",
    "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe",
)
STUBS = {key: MACHINE_STUBS[key] for key in KEEP}
STUBS["dev.emi.emi.api.recipe.EmiRecipe"] = STUBS["dev.emi.emi.api.recipe.EmiRecipe"].replace(
    "public class EmiRecipe {",
    'public class EmiRecipe { public String category="minecraft:crafting"; '
    "public record Category(String getId){} public Category getCategory(){return new Category(category);}",
)
STUBS.update(
    {
        "net.minecraft.world.entity.player.Player": """public class Player {
 public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=new net.minecraft.world.inventory.AbstractContainerMenu();
 public final Inventory inventory=new Inventory();public Inventory getInventory(){return inventory;}
 public static class Inventory {public java.util.List<net.minecraft.world.item.ItemStack> items=new java.util.ArrayList<>(),armor=new java.util.ArrayList<>(),offhand=new java.util.ArrayList<>();}}""",
        "net.minecraft.client.Minecraft": """public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();public static Minecraft getInstance(){return INSTANCE;}
 public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player(); public Object connection=new Object(),level=new Object();public Object getConnection(){return connection;}}""",
        "fr.assistantia.InventaireJoueur": """public class InventaireJoueur {
 public static boolean synced=true;public static java.util.List<net.minecraft.world.item.ItemStack> bags=java.util.List.of();
 public static boolean disponible(){return synced;}public static java.util.List<net.minecraft.world.item.ItemStack> sacs(){return bags;}}""",
        "fr.assistantia.Pont": """public class Pont {public static net.minecraft.world.item.ItemStack objet(String id){return new net.minecraft.world.item.ItemStack(id,1);}}""",
        "fr.assistantia.StockProduction": """public class StockProduction {
 public static com.google.gson.JsonObject packet;public static int reads;
 public static com.google.gson.JsonObject capturer(long t){reads++;return packet;}}""",
        "fr.assistantia.compat.emi.InstantaneArbreEmi": """public record InstantaneArbreEmi(fr.assistantia.PlanMachines.Noeud<dev.emi.emi.api.stack.EmiStack,dev.emi.emi.api.recipe.EmiRecipe> machines) {}""",
        "fr.assistantia.compat.emi.GregTechMachines": """public class GregTechMachines {
 public static Interface ui;public static boolean compatible=true;
 public record Interface(Object machine,java.util.List<net.minecraft.world.inventory.Slot> entrees,java.util.List<Cuve> cuves){}
 public record Cuve(Object handler,int index,net.minecraftforge.fluids.FluidStack contenu){}
 public static Interface ouvrir(net.minecraft.world.inventory.AbstractContainerMenu m)throws ReflectiveOperationException{return ui;}
 public static boolean compatible(Object m,dev.emi.emi.api.recipe.EmiRecipe r){return compatible;}}
""",
        "fr.assistantia.compat.emi.EnvoiMachine": """public class EnvoiMachine {
 public record Plan(boolean possible,String probleme){}public static Plan plan=new Plan(true,"");public static int reads;
 public static Plan preparer(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s,dev.emi.emi.api.recipe.EmiRecipe r,int n){if(n!=1)throw new AssertionError("preflight must check one craft");reads++;return plan;}}
""",
    }
)

HARNESS = r"""package fr.assistantia.compat.emi;
import java.util.*;
import com.google.gson.*;
import fr.assistantia.*;
import dev.emi.emi.api.stack.*;
import dev.emi.emi.api.recipe.EmiRecipe;
import com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
public class OrderIntegrationTest {
 static final Minecraft mc=Minecraft.getInstance();
 static EmiStack item(String id,int n){return EmiStack.of(new ItemStack(id,n));}
 static EmiStack fluid(String id,int n){return EmiStack.of(new Fluid(id),null,n);}
 static EmiRecipe recipe(EmiIngredient... input){return new EmiRecipe(List.of(input),List.of());}
 static GTEmiRecipe gt(EmiIngredient... input){return new GTEmiRecipe(List.of(input),List.of());}
 static PlanMachines.Noeud<EmiStack,EmiRecipe> node(EmiRecipe r){return new PlanMachines.Noeud<>(s->false,1,1,false,r,List.of());}
 static OrdreMachines.Resultat<EmiRecipe> calculate(AbstractContainerScreen<?> screen,EmiRecipe... recipes){
  var nodes=Arrays.stream(recipes).map(OrderIntegrationTest::node).toList();
  var tree=new PlanMachines.Noeud<EmiStack,EmiRecipe>(s->false,1,1,false,null,nodes);
  var operations=Arrays.stream(recipes).map(r->new PlanMachines.Operation<>(r,7)).toList();
  var result=OrdonnancementEmi.calculer(new InstantaneArbreEmi(tree),operations,screen);
  assert result.lignes().size()==recipes.length;assert result.lignes().stream().allMatch(l->l.operation().fois()==7):"need counts must not change";
  return result;
 }
 static OrdreMachines.Ligne<EmiRecipe> row(OrdreMachines.Resultat<EmiRecipe> r,EmiRecipe recipe){return r.lignes().stream().filter(l->l.operation().recette()==recipe).findFirst().orElseThrow();}
 static void mode(OrdreMachines.Resultat<EmiRecipe> r,EmiRecipe recipe,OrdreMachines.Mode mode){assert row(r,recipe).mode()==mode:row(r,recipe);}
 static AbstractContainerScreen<?> machine(){GregTechMachines.ui=new GregTechMachines.Interface(new Object(),List.of(),List.of());return new AbstractContainerScreen<>(mc.player.containerMenu);}
 static void chest(String id,int n){var items=new JsonObject();items.addProperty(id,n);var source=new JsonObject();source.addProperty("id","coffre");source.add("items",items);var sources=new JsonArray();sources.add(source);var player=new JsonObject();player.addProperty("id","inventaire");var duplicate=new JsonObject();duplicate.addProperty(id,999);player.add("items",duplicate);sources.add(player);StockProduction.packet=new JsonObject();StockProduction.packet.add("sources",sources);}
 public static void main(String[] args){
  switch(args[0]){
   case "pockets_and_conflict" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",4));var a=recipe(item("test:iron",3));var b=recipe(item("test:iron",3));
    var r=calculate(null,a,b);assert r.lignes().stream().filter(l->l.mode()==OrdreMachines.Mode.PRET).count()==1;
    assert r.lignes().stream().filter(OrdreMachines.Ligne::conflit).count()==1;assert mc.player.inventory.items.get(0).count==4;
    assert calculate(null,a,b).equals(r):"stable repeated projection";
   }
   case "bag_and_cursor" -> {
    InventaireJoueur.bags=List.of(new ItemStack("test:iron",3));var a=recipe(item("test:iron",3));mode(calculate(null,a),a,OrdreMachines.Mode.PREPARER);
    InventaireJoueur.bags=List.of();mc.player.containerMenu.carried=new ItemStack("test:iron",3);mode(calculate(null,a),a,OrdreMachines.Mode.PREPARER);
    mc.player.containerMenu.carried=ItemStack.EMPTY;mc.player.inventory.offhand.add(new ItemStack("test:iron",3));mode(calculate(null,a),a,OrdreMachines.Mode.PREPARER);
   }
   case "chest_coherent_sources" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));chest("test:iron",5);InventaireJoueur.bags=List.of(new ItemStack("test:iron",100));var a=recipe(item("test:iron",8));
    mode(calculate(null,a),a,OrdreMachines.Mode.PREPARER);var tooMuch=recipe(item("test:iron",9));mode(calculate(null,tooMuch),tooMuch,OrdreMachines.Mode.BLOQUE);
    StockProduction.packet=null;mode(calculate(null,a),a,OrdreMachines.Mode.PREPARER);InventaireJoueur.bags=List.of();mode(calculate(null,a),a,OrdreMachines.Mode.BLOQUE);
   }
   case "inventory_unknown" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));InventaireJoueur.synced=false;var a=recipe(item("test:iron",3));mode(calculate(null,a),a,OrdreMachines.Mode.INCONNU);assert StockProduction.reads==0;
   }
   case "fluid_units_and_no_double_container" -> {
    var cell=new ItemStack("test:cell",1);cell.fluid="test:water";cell.amount=1000;mc.player.inventory.items.add(cell);var water=recipe(fluid("test:water",1000));mode(calculate(null,water),water,OrdreMachines.Mode.PRET);
    var two=recipe(fluid("test:water",1001));mode(calculate(null,two),two,OrdreMachines.Mode.BLOQUE);
    var object=recipe(item("test:cell",1));object.getInputs().get(0).getEmiStacks().get(0).getItemStack().fluid=cell.fluid;object.getInputs().get(0).getEmiStacks().get(0).getItemStack().amount=cell.amount;
    var r=calculate(null,object,water);mode(r,object,OrdreMachines.Mode.PRET);mode(r,water,OrdreMachines.Mode.BLOQUE);assert cell.amount==1000&&cell.count==1;
   }
   case "fluid_catalyst_no_double_container" -> {
    var cell=new ItemStack("test:cell",1);cell.fluid="test:water";cell.amount=1000;mc.player.inventory.items.add(cell);
    var catalyst=new EmiRecipe(List.of(),List.of(EmiStack.of(cell)));var water=recipe(fluid("test:water",1000));var r=calculate(null,catalyst,water);mode(r,catalyst,OrdreMachines.Mode.PRET);mode(r,water,OrdreMachines.Mode.BLOQUE);
   }
   case "native_preflight_and_circuit" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));var circuit=item("gtceu:programmed_circuit",1);circuit.getItemStack().tag=88;
    var a=new GTEmiRecipe(List.of(item("test:iron",3)),List.of(circuit));mode(calculate(null,a),a,OrdreMachines.Mode.INCONNU);assert EnvoiMachine.reads==0;
    var screen=machine();mode(calculate(screen,a),a,OrdreMachines.Mode.PRET);assert EnvoiMachine.reads==1;
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"Circuit machine : attendu 7, actuel 1");mode(calculate(screen,a),a,OrdreMachines.Mode.PREPARER);
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"Machine occupée");mode(calculate(screen,a),a,OrdreMachines.Mode.BLOQUE);
    mc.player.containerMenu=new net.minecraft.world.inventory.AbstractContainerMenu();mode(calculate(screen,a),a,OrdreMachines.Mode.INCONNU);
   }
   case "machine_missing_items_in_bag" -> {
    InventaireJoueur.bags=List.of(new ItemStack("test:iron",3));var a=gt(item("test:iron",3));var screen=machine();
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"À collecter : 3 × fer");
    mode(calculate(screen,a),a,OrdreMachines.Mode.PREPARER);assert EnvoiMachine.reads==1;
    InventaireJoueur.bags=List.of();mode(calculate(screen,a),a,OrdreMachines.Mode.BLOQUE);
   }
   case "repeated_catalyst_max_quantity" -> {
    var catalyst=new ItemStack("test:mold",1);mc.player.inventory.items.add(catalyst);
    var a=new EmiRecipe(List.of(),List.of(item("test:mold",1),item("test:mold",2)));
    mode(calculate(null,a),a,OrdreMachines.Mode.BLOQUE);
    catalyst.count=2;mode(calculate(null,a),a,OrdreMachines.Mode.PRET);
    var reverse=new EmiRecipe(List.of(),List.of(item("test:mold",2),item("test:mold",1)));
    mode(calculate(null,reverse),reverse,OrdreMachines.Mode.PRET);catalyst.count=1;mode(calculate(null,reverse),reverse,OrdreMachines.Mode.BLOQUE);
   }
   case "unreadable_circuit_never_ready" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));var a=gt(item("test:iron",3));var screen=machine();
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"Circuit machine : configuration de la recette illisible");mode(calculate(screen,a),a,OrdreMachines.Mode.INCONNU);
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"Circuit machine : lecture indisponible (requis : 7)");mode(calculate(screen,a),a,OrdreMachines.Mode.INCONNU);
   }
   case "consumed_damageable_not_shared_tool" -> {
    var hammer=new ItemStack("test:hammer",1);assert hammer.isDamageableItem();mc.player.inventory.items.add(hammer);
    var a=recipe(item("test:hammer",1));var b=recipe(item("test:hammer",1));
    assert a.getInputs().get(0).getChance()==1;assert a.getInputs().get(0).getEmiStacks().get(0).getRemainder().isEmpty();
    var result=calculate(null,a,b);assert result.lignes().stream().filter(l->l.mode()==OrdreMachines.Mode.PRET).count()==1:result;
    assert result.lignes().stream().filter(OrdreMachines.Ligne::conflit).count()==1;
    assert hammer.count==1:"projection must not consume the real item";
   }
   case "unknown_non_gregtech_station" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));var a=recipe(item("test:iron",3));
    a.category="minecraft:smelting";mode(calculate(null,a),a,OrdreMachines.Mode.INCONNU);
    a.category="thermal:smelter";mode(calculate(null,a),a,OrdreMachines.Mode.INCONNU);
    a.category="minecraft:stonecutting";mode(calculate(null,a),a,OrdreMachines.Mode.INCONNU);
    a.category="minecraft:crafting";mode(calculate(null,a),a,OrdreMachines.Mode.PRET);
   }
   case "cache_explicit_lifecycle_invalidation" -> {
    mc.player.inventory.items.add(new ItemStack("test:iron",3));var a=gt(item("test:iron",3));var screen=machine();
    var vue=new InstantaneArbreEmi(node(a));var ops=List.of(new PlanMachines.Operation<EmiRecipe>(a,7));
    var first=OrdonnancementEmi.calculer(vue,ops,screen);mode(first,a,OrdreMachines.Mode.PRET);
    assert OrdonnancementEmi.calculer(vue,ops,screen)==first;
    int reads=StockProduction.reads,preflight=EnvoiMachine.reads;
    OrdonnancementEmi.invalider();OrdonnancementEmi.invalider();
    var rebuilt=OrdonnancementEmi.calculer(vue,ops,screen);assert rebuilt!=first:"lifecycle invalidation discards the cached result";
    mode(rebuilt,a,OrdreMachines.Mode.PRET);assert StockProduction.reads==reads+1&&EnvoiMachine.reads==preflight+1:"rebuild requires fresh native evidence";
   }
   case "cache_rechecks_current_evidence" -> {
    var iron=new ItemStack("test:iron",3);mc.player.inventory.items.add(iron);var a=gt(item("test:iron",3));var screen=machine();
    var vue=new InstantaneArbreEmi(node(a));var ops=List.of(new PlanMachines.Operation<EmiRecipe>(a,7));
    var first=OrdonnancementEmi.calculer(vue,ops,screen);mode(first,a,OrdreMachines.Mode.PRET);
    int captures=StockProduction.reads,preflights=EnvoiMachine.reads;
    assert OrdonnancementEmi.calculer(vue,ops,screen)==first:"unchanged inputs reuse calculation";
    assert StockProduction.reads==captures+1&&EnvoiMachine.reads==preflights+1:"proof and preflight rechecked before cache";
    iron.count=2;var changed=OrdonnancementEmi.calculer(vue,ops,screen);assert changed!=first;mode(changed,a,OrdreMachines.Mode.BLOQUE);
    iron.count=3;var restored=OrdonnancementEmi.calculer(vue,ops,screen);mode(restored,a,OrdreMachines.Mode.PRET);
    EnvoiMachine.plan=new EnvoiMachine.Plan(false,"Circuit machine : attendu 7, actuel 1");var circuit=OrdonnancementEmi.calculer(vue,ops,screen);assert circuit!=restored;mode(circuit,a,OrdreMachines.Mode.PREPARER);
    mc.connection=new Object();var reconnect=OrdonnancementEmi.calculer(vue,ops,screen);assert reconnect!=circuit:"new connection invalidates";
    mc.level=new Object();var dimension=OrdonnancementEmi.calculer(vue,ops,screen);assert dimension!=reconnect:"new level invalidates";
    InventaireJoueur.synced=false;mode(OrdonnancementEmi.calculer(vue,ops,screen),a,OrdreMachines.Mode.INCONNU);
   }
   case "machine_inputs_scoped_and_deduplicated" -> {
    var screen=machine();var input=new Slot(new Object(),0);input.item=new ItemStack("test:iron",3);
    var water=new GregTechMachines.Cuve(new Object(),0,new FluidStack(new Fluid("test:water"),1000));GregTechMachines.ui=new GregTechMachines.Interface(new Object(),List.of(input,input),List.of(water,water));
    var a=gt(item("test:iron",3));mode(calculate(screen,a),a,OrdreMachines.Mode.PRET);var twice=gt(item("test:iron",4));mode(calculate(screen,twice),twice,OrdreMachines.Mode.BLOQUE);
    var ordinary=recipe(item("test:iron",3));mode(calculate(screen,ordinary),ordinary,OrdreMachines.Mode.BLOQUE);
    var fluid=gt(fluid("test:water",1001));mode(calculate(screen,fluid),fluid,OrdreMachines.Mode.BLOQUE);assert water.contenu().getAmount()==1000;
   }
   default -> throw new AssertionError(args[0]);
  }
  System.out.println("PASS "+args[0]);
 }
}"""


class MachineOrderIntegrationTests(unittest.TestCase):
    def test_native_sources_and_machine_preflight_feed_real_scheduler(self):
        java, javac = shutil.which("java"), shutil.which("javac")
        if not java or not javac:
            self.skipTest("JDK 17 requis")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "2.10/*/gson-2.10.jar"
            ),
            None,
        )
        if gson is None:
            self.skipTest("Gson 2.10 en cache requis")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sources = []
            for name, body in STUBS.items():
                path = directory / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(path))
            harness = directory / "OrderIntegrationTest.java"
            harness.write_text(HARNESS)
            sources.append(str(harness))
            sources.extend(
                str(ROOT / "mod/src/main/java/fr/assistantia" / (name + ".java"))
                for name in (
                    "PlanRetrait",
                    "PlanMachines",
                    "OrdreMachines",
                    "compat/emi/ComparaisonEmi",
                    "compat/emi/OrdonnancementEmi",
                )
            )
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", temporary, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            for case in (
                "pockets_and_conflict",
                "bag_and_cursor",
                "chest_coherent_sources",
                "inventory_unknown",
                "fluid_units_and_no_double_container",
                "fluid_catalyst_no_double_container",
                "native_preflight_and_circuit",
                "machine_inputs_scoped_and_deduplicated",
                "cache_rechecks_current_evidence",
                "machine_missing_items_in_bag",
                "repeated_catalyst_max_quantity",
                "unreadable_circuit_never_ready",
                "consumed_damageable_not_shared_tool",
                "unknown_non_gregtech_station",
                "cache_explicit_lifecycle_invalidation",
            ):
                with self.subTest(case=case):
                    result = subprocess.run(
                        [
                            java,
                            "-ea",
                            "-cp",
                            os.pathsep.join((temporary, str(gson))),
                            "fr.assistantia.compat.emi.OrderIntegrationTest",
                            case,
                        ],
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn("PASS " + case, result.stdout)
