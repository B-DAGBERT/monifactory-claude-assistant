"""Replay the production machine planner and sender with inventory and fluid interactions."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_chest_transfers import STUBS as CHEST_STUBS

ROOT = Path(__file__).resolve().parents[1]
STUBS = dict(CHEST_STUBS)
STUBS["net.minecraft.world.item.Item"] = (
    "public class Item {public boolean canBeDepleted(){return false;}}"
)
STUBS["net.minecraft.world.item.ItemStack"] = """
public class ItemStack {
    public static final ItemStack EMPTY=new ItemStack("",0);
    public String id, fluid=""; public int count, amount, tag;
    public ItemStack(String id,int count){this.id=id;this.count=count;}
    public ItemStack(String id){this(id,1);}public String getItem(){return id;}
    public record Tag(int n){public boolean contains(String key){return n==88&&key.equals("assistantia_objectif");}}
    public boolean hasTag(){return tag!=0;} public Tag getTag(){return new Tag(tag);}
    public boolean is(String item){return id.equals(item);}
    public boolean isDamageableItem(){return id.equals("test:hammer");}
    public boolean isEmpty(){return count<=0;} public int getCount(){return Math.max(0,count);}
    public int getMaxStackSize(){return 64;} public void setCount(int n){count=n;}
    public void shrink(int n){count-=n;}
    public ItemStack copy(){var s=new ItemStack(id,count);s.fluid=fluid;s.amount=amount;s.tag=tag;return s;}
    public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return !a.isEmpty()&&!b.isEmpty()&&a.id.equals(b.id)&&a.tag==b.tag&&a.fluid.equals(b.fluid)&&a.amount==b.amount;}
}
"""
STUBS["net.minecraft.client.Minecraft"] = (
    STUBS["net.minecraft.client.Minecraft"]
    .replace(
        "menu.carried=new ItemStack(slot.item.id,n);slot.item.count-=n;",
        "menu.carried=slot.item.copy();menu.carried.count=n;slot.item.count-=n;",
    )
    .replace(
        "slot.item=new ItemStack(cursor.id,slot.item.getCount()+n);cursor.count-=n;",
        "int total=slot.item.getCount()+n;slot.item=cursor.copy();slot.item.count=total;cursor.count-=n;",
    )
)
STUBS["net.minecraft.network.chat.Component"] = """
public record Component(String text){public static Component literal(String s){return new Component(s);} public String getString(){return text;}}
"""
STUBS["dev.emi.emi.bom.MaterialNode"] = """
public class MaterialNode {public dev.emi.emi.api.stack.EmiIngredient ingredient;
 public java.util.List<MaterialNode> children=new java.util.ArrayList<>();public boolean catalyst;
 public float consumeChance=1;public long amount,divisor=1;public dev.emi.emi.api.recipe.EmiRecipe recipe;
 public MaterialNode(dev.emi.emi.api.stack.EmiIngredient i){ingredient=i;amount=i.getAmount();}}
"""
STUBS.update(
    {
        "net.minecraft.world.level.material.Fluid": "public record Fluid(String id) {}",
        "net.minecraftforge.fluids.FluidStack": """
public class FluidStack {
    public static final FluidStack EMPTY=new FluidStack(new net.minecraft.world.level.material.Fluid(""),0);
    private final net.minecraft.world.level.material.Fluid fluid; private int amount;
    public FluidStack(net.minecraft.world.level.material.Fluid f,int n){fluid=f;amount=n;}
    public boolean isEmpty(){return amount<=0;} public int getAmount(){return amount;}
    public void setAmount(int n){amount=n;} public net.minecraft.world.level.material.Fluid getFluid(){return fluid;}
    public Object getTag(){return null;} public FluidStack copy(){return new FluidStack(fluid,amount);}
    public boolean isFluidEqual(FluidStack other){return fluid.equals(other.fluid);}
}
""",
        "net.minecraftforge.fluids.capability.IFluidHandler": "public interface IFluidHandler {}",
        "net.minecraftforge.fluids.capability.templates.FluidTank": """
public class FluidTank implements net.minecraftforge.fluids.capability.IFluidHandler {
    public int capacity; private net.minecraftforge.fluids.FluidStack fluid=net.minecraftforge.fluids.FluidStack.EMPTY;
    public FluidTank(int cap){capacity=cap;} public net.minecraftforge.fluids.FluidStack getFluid(){return fluid;}
    public int getFluidAmount(){return fluid.getAmount();} public boolean isEmpty(){return fluid.isEmpty();}
    public void setFluid(net.minecraftforge.fluids.FluidStack f){fluid=f;}
}
""",
        "net.minecraftforge.fluids.FluidUtil": """
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
public class FluidUtil {
    public record Result(boolean isSuccess,ItemStack getResult){}
    public static java.util.Optional<FluidStack> getFluidContained(ItemStack s){return s.amount<=0?java.util.Optional.empty():java.util.Optional.of(new FluidStack(new net.minecraft.world.level.material.Fluid(s.fluid),s.amount));}
    public static Result tryFillContainer(ItemStack s,FluidTank t,int max,Object player,boolean execute){return new Result(false,s);}
    public static Result tryEmptyContainer(ItemStack s,FluidTank t,int max,Object player,boolean execute){
        var f=getFluidContained(s).orElse(FluidStack.EMPTY);
        if(f.isEmpty()||(!t.isEmpty()&&!t.getFluid().isFluidEqual(f))||t.capacity-t.getFluidAmount()<s.amount)return new Result(false,s);
        var empty=s.copy();empty.count=1;empty.amount=0;empty.fluid="";empty.id=s.id.equals("water_bucket")?"bucket":"empty_cell";
        if(execute)t.setFluid(new FluidStack(f.getFluid(),t.getFluidAmount()+s.amount));
        return new Result(true,empty);
    }
}
""",
        "dev.emi.emi.api.stack.EmiIngredient": """
public interface EmiIngredient {java.util.List<EmiStack> getEmiStacks();long getAmount();float getChance();boolean isEmpty();}
""",
        "dev.emi.emi.api.stack.EmiStack": """
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
public class EmiStack implements EmiIngredient {
    public Object key; public long amount=1;public float chance=1;
    public EmiStack(Object k,long n){key=k;amount=n;}
    public static EmiStack of(ItemStack s){return new EmiStack(s.copy(),s.getCount());}
    public static EmiStack of(Fluid f,Object tag,long n){return new EmiStack(f,n);}
    public ItemStack getItemStack(){return key instanceof ItemStack s?s:ItemStack.EMPTY;}
    public EmiStack copy(){var s=new EmiStack(key instanceof ItemStack i?i.copy():key,amount);s.chance=chance;return s;}
    public EmiStack comparison(java.util.function.Function<Comparison,Comparison> f){f.apply(Comparison.DEFAULT_COMPARISON);return this;}
    public EmiStack getRemainder(){return EmiStack.of(ItemStack.EMPTY);}
    public Object getNbt(){return key instanceof ItemStack s?s.getTag():null;} public Object getKey(){return key instanceof ItemStack s?s.getItem():key;} public Object getId(){return key instanceof ItemStack s?s.id:((Fluid)key).id();}
    public net.minecraft.network.chat.Component getName(){return net.minecraft.network.chat.Component.literal(getId().toString());}
    public boolean isEqual(EmiStack b){return key instanceof ItemStack s&&b.key instanceof ItemStack t?ItemStack.isSameItemSameTags(s,t):key.equals(b.key);}
    public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}public long getAmount(){return amount;}
    public float getChance(){return chance;}public boolean isEmpty(){return amount<=0;}
}
""",
        "dev.emi.emi.api.recipe.EmiRecipe": """
public class EmiRecipe {
 private final java.util.List<dev.emi.emi.api.stack.EmiIngredient> inputs,catalysts;
 public EmiRecipe(java.util.List<dev.emi.emi.api.stack.EmiIngredient> i,java.util.List<dev.emi.emi.api.stack.EmiIngredient> c){inputs=i;catalysts=c;}
 public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getInputs(){return inputs;}
 public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getCatalysts(){return catalysts;}
 public java.util.List<dev.emi.emi.api.stack.EmiStack> outputs=java.util.List.of();
 public java.util.List<dev.emi.emi.api.stack.EmiStack> getOutputs(){return outputs;}
}
""",
        "dev.emi.emi.api.stack.Comparison": "public class Comparison {public static final Comparison DEFAULT_COMPARISON=new Comparison();public static Comparison compareNbt(){return new Comparison();}}",
        "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe": """
public class GTEmiRecipe extends dev.emi.emi.api.recipe.EmiRecipe {
 public GTEmiRecipe(java.util.List<dev.emi.emi.api.stack.EmiIngredient> i,java.util.List<dev.emi.emi.api.stack.EmiIngredient> c){super(i,c);}
}
""",
        "com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient": "public class IntCircuitIngredient {private final int configuration;public IntCircuitIngredient(int n){configuration=n;}}",
        "com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient": "public record SizedIngredient(Object getInner) {}",
        "net.minecraft.core.BlockPos": 'public record BlockPos(int x,int y,int z){public BlockPos immutable(){return this;}public String toShortString(){return x+", "+y+", "+z;}}',
        "fr.assistantia.RechercheEmi": "public class RechercheEmi {public static java.util.Map<String,Integer> disponibles=java.util.Map.of();public static java.util.Map<String,Integer> resultatsDisponibles(){return disponibles;}}",
        "fr.assistantia.InventaireJoueur": "public class InventaireJoueur {public static boolean synchronise(){return true;}}",
        "fr.assistantia.RetraitCoffre": "public class RetraitCoffre {public static boolean busy;public static boolean occupe(){return busy;}}",
        "fr.assistantia.compat.emi.CoffresArbreEmi": "public class CoffresArbreEmi {public static Object arbreActif(){return fr.assistantia.ArbreEmi.tree;}}",
        "net.minecraftforge.registries.ForgeRegistries": "public class ForgeRegistries {public static final Registry ITEMS=new Registry(); public static class Registry {public String getKey(String item){return item;}}}",
        "net.minecraftforge.event.entity.player.PlayerEvent": "public class PlayerEvent {public record ItemCraftedEvent(net.minecraft.world.entity.player.Player getEntity,net.minecraft.world.item.ItemStack getCrafting) {}}",
        "fr.assistantia.ArbreEmi": "public class ArbreEmi {public static Object tree=new Object();public static Object actif(){return tree;}}",
        "fr.assistantia.compat.emi.GregTechMachines": """
import java.util.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
public class GregTechMachines {
    public static Interface ui;public static boolean busy;public static int configuration=7,machineCircuit=-2;
    public static List<Slot> outputSlots=List.of();
    public static List<Slot> sorties(AbstractContainerMenu menu)throws ReflectiveOperationException{return outputSlots;}
    public record Content(Object content) {}
    public record Recipe(java.util.Map<String,java.util.List<Content>> inputs) {}
    public static int configurationCircuit(dev.emi.emi.api.recipe.EmiRecipe r) throws ReflectiveOperationException {
        return ConfigurationCircuit.lire(new Recipe(java.util.Map.of("item",java.util.List.of(
            new Content(new com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient(
                new com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient(configuration)))))));
    }
    public record Interface(Object machine,List<Slot> entrees,List<Cuve> cuves,Object circuit){
        public net.minecraft.core.BlockPos position(){return new net.minecraft.core.BlockPos(0,64,0);}
    }
    public static List<Cuve> cuvesSortie(AbstractContainerMenu menu){return List.of();}
    public static Interface ouvrir(AbstractContainerMenu menu)throws ReflectiveOperationException{return ui;}
    public static boolean compatible(Object m,dev.emi.emi.api.recipe.EmiRecipe r){return true;}
    public static boolean occupe(Object m){return busy;}
    public static int circuit(ItemStack s){return s.tag;}
    public static int circuitConfigure(Interface ui) throws ReflectiveOperationException {return machineCircuit!=-2?machineCircuit:ui.circuit() instanceof ItemStack s&&!s.isEmpty()?s.tag:-1;}
    public static void reglerCircuit(Object w,int v){throw new AssertionError("Le réglage machine ne doit pas être modifié");}
    public static Object appeler(Object w,String name){return w;}
    public static class Cuve {
        public FluidTank tank=new FluidTank(16000);public int calls,lectures;
        public net.minecraftforge.fluids.FluidStack contenu(){return tank.getFluid().copy();}
        public FluidTank simulation(){lectures++;var t=new FluidTank(tank.capacity);t.setFluid(tank.getFluid().copy());return t;}
        public boolean peutRemplirContenant(){return false;}
        public void viderContenant(){calls++;var menu=net.minecraft.client.Minecraft.getInstance().player.containerMenu;var r=FluidUtil.tryEmptyContainer(menu.getCarried(),tank,Integer.MAX_VALUE,null,true);if(r.isSuccess())menu.carried=r.getResult();}
    }
}
""",
    }
)


class MachineInputTests(unittest.TestCase):
    def test_machine_inputs_and_fluids_use_confirmed_native_interactions(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as directory:
            sources = []
            for name, body in STUBS.items():
                path = Path(directory) / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}")
                sources.append(str(path))
            sources += [
                str(ROOT / "mod/src/main/java/fr/assistantia" / f"{name}.java")
                for name in (
                    "PlanRetrait",
                    "PlanArbre",
                    "ArbreCollecte",
                    "MemoireCollecte",
                    "LotFabrication",
                    "ReceptionLots",
                    "ReceptionManuelle",
                    "PlanMachines",
                    "SequenceEnvoi",
                    "ReceptionSortie",
                    "compat/emi/EnvoiMachine",
                    "compat/emi/OrigineFabrication",
                    "compat/emi/ComparaisonEmi",
                    "compat/emi/ConfigurationCircuit",
                    "compat/emi/CatalyseursArbreEmi",
                    "compat/emi/InstantaneArbreEmi",
                    "compat/emi/SuiviLotsEmi",
                    "compat/emi/LotMachine",
                )
            ]
            sources.append(str(ROOT / "tests/java/fr/assistantia/EnvoiMachineTest.java"))
            sources.append(str(ROOT / "tests/java/fr/assistantia/OrigineFabricationTest.java"))
            sources.append(str(ROOT / "tests/java/fr/assistantia/InstantaneArbreEmiTest.java"))
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for scenario in (
                "fr.assistantia.EnvoiMachineTest",
                "fr.assistantia.OrigineFabricationTest",
                "fr.assistantia.compat.emi.InstantaneArbreEmiTest",
            ):
                result = subprocess.run(
                    [java, "-ea", "-cp", directory, scenario],
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
