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
STUBS["net.minecraft.world.item.ItemStack"] = """
public class ItemStack {
    public static final ItemStack EMPTY=new ItemStack("",0);
    public String id, fluid=""; public int count, amount, tag;
    public ItemStack(String id,int count){this.id=id;this.count=count;}
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
        var empty=s.copy();empty.count=1;empty.amount=0;empty.fluid="";empty.id="empty_cell";
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
    public Object getNbt(){return null;} public Object getKey(){return key;} public Object getId(){return key instanceof ItemStack s?s.id:((Fluid)key).id();}
    public net.minecraft.network.chat.Component getName(){return net.minecraft.network.chat.Component.literal(getId().toString());}
    public boolean isEqual(EmiStack b){return key instanceof ItemStack s&&b.key instanceof ItemStack t?ItemStack.isSameItemSameTags(s,t):key.equals(b.key);}
    public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}public long getAmount(){return amount;}
    public float getChance(){return chance;}public boolean isEmpty(){return amount<=0;}
}
""",
        "dev.emi.emi.api.recipe.EmiRecipe": """
public record EmiRecipe(java.util.List<dev.emi.emi.api.stack.EmiIngredient> getInputs,java.util.List<dev.emi.emi.api.stack.EmiIngredient> getCatalysts){}
""",
        "com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient": "public class IntCircuitIngredient {private final int configuration;public IntCircuitIngredient(int n){configuration=n;}}",
        "com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient": "public record SizedIngredient(Object getInner) {}",
        "fr.assistantia.RetraitCoffre": "public class RetraitCoffre {public static boolean busy;public static boolean occupe(){return busy;}}",
        "fr.assistantia.ArbreEmi": "public class ArbreEmi {public static Object tree=new Object();public static Object actif(){return tree;}}",
        "fr.assistantia.compat.emi.GregTechMachines": """
import java.util.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
public class GregTechMachines {
    public static Interface ui;public static boolean busy;public static int configuration=7;
    public record Content(Object content) {}
    public record Recipe(java.util.Map<String,java.util.List<Content>> inputs) {}
    public static int configurationCircuit(dev.emi.emi.api.recipe.EmiRecipe r) throws ReflectiveOperationException {
        return ConfigurationCircuit.lire(new Recipe(java.util.Map.of("item",java.util.List.of(
            new Content(new com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient(
                new com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient(configuration)))))));
    }
    public record Interface(Object machine,List<Slot> entrees,List<Cuve> cuves,Object circuit){}
    public static Interface ouvrir(AbstractContainerMenu menu)throws ReflectiveOperationException{return ui;}
    public static boolean compatible(Object m,dev.emi.emi.api.recipe.EmiRecipe r){return true;}
    public static boolean occupe(Object m){return busy;}
    public static int circuit(ItemStack s){return s.tag;}
    public static void reglerCircuit(Object w,int v){((ItemStack)w).tag=v;((ItemStack)w).count=1;}
    public static Object appeler(Object w,String name){return w;}
    public static class Cuve {
        public FluidTank tank=new FluidTank(16000);public int calls;
        public FluidTank simulation(){var t=new FluidTank(tank.capacity);t.setFluid(tank.getFluid().copy());return t;}
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
                    "PlanMachines",
                    "SequenceEnvoi",
                    "compat/emi/EnvoiMachine",
                    "compat/emi/ComparaisonEmi",
                    "compat/emi/ConfigurationCircuit",
                )
            ]
            sources.append(str(ROOT / "tests/java/fr/assistantia/EnvoiMachineTest.java"))
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.EnvoiMachineTest"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
