"""Replay the production machine planner and sender with inventory and fluid interactions."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from machine_panel_contract import compiler_panneau, doublures_panneau
from test_chest_transfers import STUBS as CHEST_STUBS
from test_me_terminal_panel import corps

ROOT = Path(__file__).resolve().parents[1]
STUBS = dict(CHEST_STUBS)
STUBS["net.minecraft.world.inventory.ClickType"] = (
    "public enum ClickType {PICKUP, QUICK_MOVE, SWAP}"
)
STUBS["net.minecraft.world.item.Item"] = (
    "public class Item {public boolean canBeDepleted(){return false;}}"
)
STUBS["net.minecraft.world.item.ItemStack"] = """
public class ItemStack {
    public static final ItemStack EMPTY=new ItemStack("",0);
    public String id, fluid=""; public int count, amount, tag;
    public ItemStack(String id,int count){this.id=id;this.count=count;}
    public ItemStack(String id){this(id,1);}public String getItem(){return id;}
    public boolean hasTag(){return tag!=0;} public net.minecraft.nbt.CompoundTag getTag(){return new net.minecraft.nbt.CompoundTag(tag);}
    public boolean is(String item){return id.equals(item);}
    public boolean isDamageableItem(){return id.equals("test:hammer");}
    public boolean isEmpty(){return count<=0;} public int getCount(){return Math.max(0,count);}
    public int getMaxStackSize(){return 64;} public void setCount(int n){count=n;}
    public void shrink(int n){count-=n;}
    public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag){return tag;}
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
    .replace(
        "public static class ClientConnection {",
        """public Object getSingleplayerServer(){return null;}
        public static class ClientConnection {
            public java.util.function.Consumer<Object> sender;
            public void send(Object packet){sender.accept(packet);}""",
    )
)
STUBS["net.minecraft.world.entity.player.Player"] = STUBS[
    "net.minecraft.world.entity.player.Player"
].replace(
    "private final Object inventory=new Object();\n    public Object getInventory(){return inventory;}",
    """private final Inventory inventory=new Inventory();
    public Inventory getInventory(){return inventory;}""",
)
STUBS["net.minecraft.world.entity.player.Inventory"] = """
public class Inventory {
    private final java.util.Map<Integer, net.minecraft.world.item.ItemStack> cases = new java.util.HashMap<>();
    public net.minecraft.world.item.ItemStack getItem(int n){
        return cases.getOrDefault(n, net.minecraft.world.item.ItemStack.EMPTY);
    }
    public void setItem(int n, net.minecraft.world.item.ItemStack item){cases.put(n, item);}
    public int getSlotWithRemainingSpace(net.minecraft.world.item.ItemStack pile){return -1;}
    public int getFreeSlot(){return -1;}
}
"""
STUBS["net.minecraft.world.inventory.AbstractContainerMenu"] = STUBS[
    "net.minecraft.world.inventory.AbstractContainerMenu"
].replace(
    "public int containerId=1;", "public int containerId=1; public int getStateId(){return 0;}"
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
 public String getId(){return "test:recipe";}
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
        "fr.assistantia.RechercheEmi": 'public class RechercheEmi {public static java.util.Map<String,Integer> disponibles=java.util.Map.of();public static java.util.Map<String,Integer> resultatsDisponibles(){return disponibles;}public static String objetCollecte(net.minecraft.world.item.ItemStack s){return s.tag==0?s.id:s.id+"|"+s.tag;}}',
        "fr.assistantia.InventaireJoueur": """
public class InventaireJoueur {
    public static boolean synchronise() { return true; }
    public static net.minecraft.world.item.ItemStack caseRelevee(int index) {
        return net.minecraft.client.Minecraft.getInstance().player.getInventory().getItem(index);
    }
}""",
        "fr.assistantia.RetraitCoffre": "public class RetraitCoffre {public static boolean busy;public static boolean occupe(){return busy;}}",
        "fr.assistantia.compat.emi.CoffresArbreEmi": "public class CoffresArbreEmi {public static Object arbreActif(){return fr.assistantia.ArbreEmi.tree;}}",
        "net.minecraftforge.registries.ForgeRegistries": "public class ForgeRegistries {public static final Registry ITEMS=new Registry(); public static class Registry {public String getKey(String item){return item;}}}",
        "net.minecraftforge.event.entity.player.PlayerEvent": "public class PlayerEvent {public record ItemCraftedEvent(net.minecraft.world.entity.player.Player getEntity,net.minecraft.world.item.ItemStack getCrafting) {}}",
        "fr.assistantia.ArbreEmi": "public class ArbreEmi {public static Object tree=new Object();public static Object actif(){return tree;}}",
        "net.minecraft.nbt.Tag": "public class Tag {}",
        "net.minecraft.nbt.CompoundTag": """
public class CompoundTag extends Tag {
    private final int n;
    public CompoundTag(){this(0);}
    public CompoundTag(int n){this.n=n;}
    public Tag get(String key){return null;}
    public boolean contains(String key){return n==88&&key.equals("assistantia_objectif");}
    public boolean equals(Object o){return o instanceof CompoundTag t&&n==t.n;}
    public int hashCode(){return n;}
    public String toString(){return String.valueOf(n);}
}""",
        "fr.assistantia.CoffreTampon": """public class CoffreTampon {
    public static java.util.List<net.minecraft.world.item.ItemStack> piles=java.util.List.of();
    public static java.util.List<net.minecraft.world.item.ItemStack> piles(){return piles;}
}""",
        "fr.assistantia.compat.emi.CycleArbreEmi": """public class CycleArbreEmi {
    public static java.util.List<fr.assistantia.PlanRetrait.Stock<net.minecraft.world.item.ItemStack>> credits(){return java.util.List.of();}
}""",
        "fr.assistantia.compat.emi.MachinesEmi": """
import java.util.*;
import fr.assistantia.PlanRetrait.Stock;
import dev.emi.emi.api.stack.EmiStack;
public class MachinesEmi {
    static SuiviLotsEmi suivi;
    static SuiviLotsEmi.ReceptionCollecte receptionCollecte(
        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> ecran,
        List<net.minecraft.world.item.ItemStack> variantes
    ) { return suivi==null ? (pile,n,depot) -> {} : suivi.preparerCollecte(variantes); }
    static List<Stock<EmiStack>> reservationsArbre() {
        return suivi==null ? List.of() : suivi.actifs().stream()
                .flatMap(lot -> lot.suivi().restants().stream()).toList();
    }
    static List<Stock<EmiStack>> creditsAcquis(List<Stock<EmiStack>> physiques, List<Stock<EmiStack>> credits) {
        return List.of();
    }
    static List<Stock<EmiStack>> completerOutils(List<Stock<EmiStack>> deja){return List.of();}
}""",
        "fr.assistantia.compat.emi.GregTechMachines": """
import java.util.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
public class GregTechMachines {
    public static Interface ui;public static boolean busy;public static int configuration=7,machineCircuit=-2;
    public static dev.emi.emi.api.recipe.EmiRecipe recetteActive;
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
    public static List<Cuve> outputTanks=List.of();
    public static List<Cuve> cuvesSortie(AbstractContainerMenu menu){return outputTanks;}
    public static Interface ouvrir(AbstractContainerMenu menu)throws ReflectiveOperationException{return ui;}
    public static boolean compatible(Object m,dev.emi.emi.api.recipe.EmiRecipe r){return true;}
    public static boolean occupe(Object m){return busy;}
    public static boolean enMarche(Object m){return busy;}
    public record Execution(boolean active,boolean recetteAttendue,boolean autreRecette) {}
    public static Execution execution(Object m,dev.emi.emi.api.recipe.EmiRecipe r){return new Execution(busy,recetteActive==r,recetteActive!=null&&recetteActive!=r);}
    public static java.util.function.Consumer<Boolean> recetteSynchronisee;
    static Runnable ecouterRecette(Object m,dev.emi.emi.api.recipe.EmiRecipe r,java.util.function.Consumer<Boolean> reception){
        recetteSynchronisee=reception;
        return ()->{if(recetteSynchronisee==reception)recetteSynchronisee=null;};
    }
    public static int circuit(ItemStack s){return s.tag;}
    public static int circuitConfigure(Interface ui) throws ReflectiveOperationException {return machineCircuit!=-2?machineCircuit:ui.circuit() instanceof ItemStack s&&!s.isEmpty()?s.tag:-1;}
    public static void reglerCircuit(Object w,int v){throw new AssertionError("Le réglage machine ne doit pas être modifié");}
    public static Object appeler(Object w,String name){return w;}
    public static class Cuve {
        public FluidTank tank=new FluidTank(16000);public int calls,lectures;
        public Object widget(){return this;}
        public net.minecraftforge.fluids.FluidStack contenu(){return tank.getFluid().copy();}
        public FluidTank simulation(){lectures++;var t=new FluidTank(tank.capacity);t.setFluid(tank.getFluid().copy());return t;}
        public boolean remplissable;
        public boolean peutRemplirContenant() throws ReflectiveOperationException{return remplissable;}
        public void viderContenant(){calls++;var menu=net.minecraft.client.Minecraft.getInstance().player.containerMenu;var r=FluidUtil.tryEmptyContainer(menu.getCarried(),tank,Integer.MAX_VALUE,null,true);if(r.isSuccess())menu.carried=r.getResult();}
    }
}
""",
    }
)


def compiler_retrait_me(dossier):
    source = (ROOT / "mod/src/main/java/fr/assistantia/compat/emi/TerminalMe.java").read_text(
        encoding="utf-8"
    )
    debut = source.index("private static AbstractContainerMenu menu;")
    fin = source.index("private static boolean erreur;", debut)
    champs = source[debut:fin] + "\n".join(
        corps(source, signature) for signature in ("private record Lot(", "private record Acces(")
    )
    actions = "\n".join(
        corps(source, signature)
        for signature in (
            "private static void lancer(",
            "private static void avancer(",
            "private static int volumeRetrait(",
            "private static void clicCase(",
            "private static String bilan(",
            "private static void attendre(",
            "private static void terminer(",
        )
    )
    fixture = (ROOT / "tests/java/fr/assistantia/compat/emi/RetraitMeCycle.java").read_text(
        encoding="utf-8"
    )
    fichier = Path(dossier) / "RetraitMeCycle.java"
    fichier.write_text(
        fixture.replace("// CHAMPS_TERMINAL_ME", champs).replace(
            "// ACTIONS_TERMINAL_ME", actions.replace("TerminalMe.class", "RetraitMeCycle.class")
        ),
        encoding="utf-8",
    )
    return str(fichier)


class MachineInputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK 17 required")
        dossier = tempfile.TemporaryDirectory()
        cls.addClassCleanup(dossier.cleanup)
        cls.classes = dossier.name
        sources = []
        stubs = doublures_panneau(STUBS)
        machine = stubs.pop("fr.assistantia.compat.emi.MachinesEmi")
        for name, body in stubs.items():
            path = Path(cls.classes) / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
            sources.append(str(path))
        sources += [
            str(ROOT / "mod/src/main/java/fr/assistantia" / f"{name}.java")
            for name in (
                "PlanRetrait",
                "RessourceCollecte",
                "PlanArbre",
                "ArbreCollecte",
                "MemoireCollecte",
                "LotFabrication",
                "ReceptionLots",
                "ReceptionManuelle",
                "PlanMachines",
                "SequenceEnvoi",
                "ReceptionSortie",
                "DispositionCoffre",
                "OrdreMachines",
                "compat/emi/EnvoiMachine",
                "compat/emi/OrigineFabrication",
                "compat/emi/ComparaisonEmi",
                "compat/emi/ConfigurationCircuit",
                "compat/emi/CatalyseursArbreEmi",
                "compat/emi/InstantaneArbreEmi",
                "compat/emi/DiagnosticMoules",
                "compat/emi/SuiviLotsEmi",
                "compat/emi/LotMachine",
                "compat/emi/StockFabricationEmi",
            )
        ]
        sources.append(compiler_retrait_me(cls.classes))
        sources.append(compiler_panneau(cls.classes, machine))
        sources += [
            str(ROOT / "tests/java/fr/assistantia" / f"{name}.java")
            for name in (
                "EnvoiMachineTest",
                "OrigineFabricationTest",
                "InstantaneArbreEmiTest",
                "compat/emi/CycleMachineTest",
                "compat/emi/PanneauMachineClicTest",
            )
        ]
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", cls.classes, *sources],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def rejouer(self, classe, *args):
        result = subprocess.run(
            [self.java, "-ea", "-cp", self.classes, classe, *args],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(
            result.returncode, 0, f"{' '.join((classe, *args))}\n{result.stdout}{result.stderr}"
        )
        return result.stdout

    def test_machine_panel_uses_the_rendered_recipe_and_revalidates_the_click(self):
        sortie = self.rejouer("fr.assistantia.compat.emi.PanneauMachineClicTest")
        self.assertIn("PanneauMachineClic OK", sortie)

    def test_machine_inputs_and_fluids_use_confirmed_native_interactions(self):
        for scenario in (
            "fr.assistantia.EnvoiMachineTest",
            "fr.assistantia.OrigineFabricationTest",
            "fr.assistantia.compat.emi.InstantaneArbreEmiTest",
        ):
            with self.subTest(scenario=scenario):
                self.rejouer(scenario)

    def test_envoyer_respecte_les_variantes_nbt_des_branches_et_des_cases(self):
        self.rejouer("fr.assistantia.EnvoiMachineTest", "variantes-nbt")

    def rejouer_cycle(self, *scenarios):
        for scenario in scenarios:
            with self.subTest(scenario=scenario):
                sortie = self.rejouer("fr.assistantia.compat.emi.CycleMachineTest", scenario)
                self.assertIn("PASS " + scenario, sortie)

    def test_machine_cycle_from_confirmed_send_to_partial_and_final_receipt(self):
        self.rejouer_cycle(
            "objets-puis-cuves",
            "poches-puis-sortie",
            "annulation-reception-partielle",
            "deux-lots",
            "reception-autre-stock",
            "reception-apres-fermeture",
            "collecte-confirmee",
            "lot-hors-besoins",
        )

    def test_sac_retrait_me_machine_demarrage_immediat_un_seul_lot(self):
        self.rejouer_cycle("sac-me-machine")

    def test_integrated_stock_mold_fluid_and_split_receipts(self):
        self.rejouer_cycle("stock-reparti-moule-fluide-receptions")

    def test_machine_receipts_continue_while_the_backpack_is_synchronizing(self):
        self.rejouer_cycle("reception-pendant-sync-sac", "fluide-premier-clic")

    def test_mold_diagnostic_uses_real_coverage_for_each_plan(self):
        self.rejouer_cycle("diagnostic-moule")

    def test_machine_interruption_between_container_pickup_and_fluid_send(self):
        self.rejouer_cycle(
            "interface", "arbre", "connexion", "cuve", "arret", "fermeture-apres-vidage"
        )

    def test_machine_plan_limits_and_explicit_surplus(self):
        self.rejouer_cycle("63", "64", "65", "65-avec-surplus")

    def test_machine_preview_refuses_blocked_destinations_without_moving_inputs(self):
        self.rejouer_cycle("preconditions")

    def test_machine_requires_destination_confirmation_or_the_expected_recipe_started(self):
        self.rejouer_cycle(
            "destination-corrigee",
            "cuve-corrigee",
            "consommation-immediate",
            "autre-recette-demarree",
        )

    def test_machine_confirms_a_short_recipe_with_fresh_matching_evidence(self):
        self.rejouer_cycle(
            "recette-deux-ticks",
            "recette-entre-observations",
            "recette-rapide-deux-cycles",
            "recette-rapide-sortie-retardee",
            "recette-rapide-sortie-retardee-id-change",
            "recette-deux-ticks-entree-partielle",
            "recette-deux-ticks-autre",
            "recette-deux-ticks-source-corrigee",
            "sortie-corrigee-apres-observation",
            "sortie-preexistante-seule",
            "sortie-preexistante-masquee",
            "fluide-entre-observations",
            "fluide-sortie-preexistante",
        )

    def test_machine_continues_after_proven_consumption_of_an_intermediate_deposit(self):
        self.rejouer_cycle(
            "absorption-avant-depot-suivant",
            "absorption-avant-depot-suivant-autre",
            "absorption-ancien-demarrage",
        )

    def test_machine_replans_explicitly_after_a_partial_send(self):
        self.rejouer_cycle(
            "reprise-apres-premier-depot",
            "reprise-serveur-corrige",
            "reprise-apres-tri",
            "reprise-nouvelle-sortie",
            "reprise-incertaine",
            "reprise-consommation-incertaine",
            "reprise-entree-modifiee",
            "reprise-clic-perime",
            "reprise-nouveau-contexte",
            "reprise-cycle-prouve",
        )

    def test_logout_forgets_send_recoveries_and_the_machine_screen(self):
        # Reprises, their scope and the machine panel's screen do not outlive the session.
        self.rejouer_cycle("reprise-oubliee-deconnexion")
        sortie = self.rejouer("fr.assistantia.compat.emi.PanneauMachineClicTest", "deconnexion")
        self.assertIn("PASS deconnexion", sortie)

    def test_machine_confirms_only_an_exact_isolated_cycle_without_a_recipe_frame(self):
        self.rejouer_cycle(
            "sans-id-cycle-court",
            "sans-id-sortie-ancienne",
            "sans-id-sortie-etrangere",
            "sans-id-sortie-excedentaire",
            "sans-id-recette-concurrente",
            "sans-id-deux-entrees",
            "rafale-id-attendu",
            "rafale-id-concurrent",
            "rafale-sortie-insuffisante",
        )

    def test_machine_circuit_changes_preserve_items_and_stop_unconfirmed_transfers(self):
        self.rejouer_cycle(
            "circuit-avant-vidage",
            "circuit-apres-vidage",
            "circuit-retour-bloque",
            "circuit-entre-objets",
        )
