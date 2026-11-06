"""Execute the actual native exporter against controlled EMI/GT contracts.

These fixtures are synthetic API replays. Installed jar API inspection and a real
client comparison remain separate qualification requirements.
"""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "fr.assistantia.InventaireProduction": 'public class InventaireProduction {public static String expected=""; public static boolean captureCourante(String id,String sha){return id.equals("capture-qa")&&sha.equals(expected);} public static boolean valideCapture(String id,String sha){return captureCourante(id,sha);}}',
    "net.minecraft.client.Minecraft": """public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public Object player=new Object();public Level level=new Level();public Connection connection=new Connection();public static Minecraft getInstance(){return INSTANCE;}public boolean isSameThread(){return !Thread.currentThread().getName().equals("assistant-catalogue-production");}public Connection getConnection(){return connection;}public static class Connection{private final Object handle=new Object();public Object getConnection(){return handle;}}public static class Level{public Dimension dimension(){return new Dimension();}}public static class Dimension{public net.minecraft.resources.ResourceLocation location(){return new net.minecraft.resources.ResourceLocation("minecraft:overworld");}}}""",
    "net.minecraftforge.client.event.ClientPlayerNetworkEvent": "public class ClientPlayerNetworkEvent {public static class LoggingOut {}}",
    "net.minecraftforge.event.TickEvent": "public class TickEvent {public enum Phase{START,END};public static class ClientTickEvent{public Phase phase=Phase.END;}}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.fml.loading.FMLPaths": 'public enum FMLPaths {GAMEDIR;public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("catalogue.test.dir"));}}',
    "fr.assistantia.IdentiteMonde": 'public class IdentiteMonde {public static String world="m1_"+"a".repeat(64),session="b".repeat(32);public static String monde(){return world;}public static String session(){return session;}}',
    "net.minecraft.resources.ResourceLocation": "public record ResourceLocation(String value){public String toString(){return value;}}",
    "net.minecraft.nbt.Tag": "public interface Tag {}",
    "net.minecraft.nbt.CompoundTag": """public class CompoundTag implements Tag {public final java.util.Map<String,Tag> entries=new java.util.HashMap<>();public boolean isEmpty(){return entries.isEmpty();}public java.util.Set<String> getAllKeys(){return entries.keySet();}public Tag get(String k){return entries.get(k);}public boolean contains(String k){return entries.containsKey(k);}}""",
    "net.minecraft.nbt.ListTag": "public class ListTag extends java.util.ArrayList<Tag> implements Tag {}",
    "net.minecraft.nbt.IntTag": "public record IntTag(int value) implements Tag {public String toString(){return Integer.toString(value);}}",
    "net.minecraft.world.item.Item": "public class Item {public final String id;public Item(String id){this.id=id;}}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {public static final ItemStack EMPTY=new ItemStack(null,0);public final Item item;public final int count;public net.minecraft.nbt.CompoundTag tag;public ItemStack(Item i,int n){item=i;count=n;}public boolean isEmpty(){return item==null||count<=0;}public boolean hasTag(){return tag!=null&&!tag.isEmpty();}public net.minecraft.nbt.CompoundTag getTag(){return tag;}public Item getItem(){return item;}public int getCount(){return count;}}""",
    "net.minecraft.world.level.material.Fluid": "public record Fluid(String id) {}",
    "net.minecraftforge.fluids.FluidStack": """public class FluidStack {public final net.minecraft.world.level.material.Fluid fluid;public final int n;public net.minecraft.nbt.CompoundTag tag;public FluidStack(net.minecraft.world.level.material.Fluid f,int n){fluid=f;this.n=n;}public int getAmount(){return n;}public net.minecraft.world.level.material.Fluid getFluid(){return fluid;}public net.minecraft.nbt.CompoundTag getTag(){return tag;}public boolean hasTag(){return tag!=null&&!tag.isEmpty();}}""",
    "net.minecraft.world.item.crafting.ShapelessRecipe": "public class ShapelessRecipe {public java.util.List<Ingredient> getIngredients(){return java.util.List.of();}}",
    "com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic": "public class ChanceLogic {public static final ChanceLogic OR=new ChanceLogic();}",
    "net.minecraft.world.item.crafting.Ingredient": """public class Ingredient {public net.minecraft.world.item.ItemStack[] stacks;public Ingredient(net.minecraft.world.item.ItemStack... s){stacks=s;}public net.minecraft.world.item.ItemStack[] getItems(){return stacks;}}""",
    "dev.emi.emi.api.stack.EmiIngredient": "public interface EmiIngredient {java.util.List<EmiStack> getEmiStacks();long getAmount();float getChance();default boolean isEmpty(){return getEmiStacks().isEmpty();}}",
    "dev.emi.emi.api.stack.EmiStack": """public class EmiStack implements EmiIngredient {
 public Object key;public long amount;public float chance=1;public net.minecraft.nbt.CompoundTag tag;public EmiStack remainder;
 public EmiStack(Object k,long n){key=k;amount=n;}public static EmiStack of(net.minecraft.world.item.ItemStack s){var v=new EmiStack(s.item,s.count);v.tag=s.tag;return v;}
 public static EmiStack of(net.minecraft.world.level.material.Fluid f,net.minecraft.nbt.CompoundTag tag,long n){var v=new EmiStack(f,n);v.tag=tag;return v;}
 public Object getKey(){return key;}public long getAmount(){return amount;}public float getChance(){return chance;}public boolean isEmpty(){return key==null||amount<=0;}
 public net.minecraft.resources.ResourceLocation getId(){return new net.minecraft.resources.ResourceLocation(key instanceof net.minecraft.world.item.Item i?i.id:((net.minecraft.world.level.material.Fluid)key).id());}
 public net.minecraft.nbt.CompoundTag getNbt(){return tag;}public EmiStack getRemainder(){return remainder==null?new EmiStack(null,0):remainder;}
 public net.minecraft.world.item.ItemStack getItemStack(){if(!(key instanceof net.minecraft.world.item.Item i))return net.minecraft.world.item.ItemStack.EMPTY;var s=new net.minecraft.world.item.ItemStack(i,(int)amount);s.tag=tag;return s;}
 public java.util.List<EmiStack> getEmiStacks(){return java.util.List.of(this);}}
""",
    "dev.emi.emi.api.recipe.EmiRecipeCategory": "public record EmiRecipeCategory(net.minecraft.resources.ResourceLocation getId) {}",
    "dev.emi.emi.api.recipe.EmiRecipe": """public class EmiRecipe {public String id="test:recipe";public java.util.List<dev.emi.emi.api.stack.EmiIngredient> inputs=java.util.List.of(),catalysts=java.util.List.of();public java.util.List<dev.emi.emi.api.stack.EmiStack> outputs=java.util.List.of();public net.minecraft.resources.ResourceLocation getId(){return new net.minecraft.resources.ResourceLocation(id);}public EmiRecipeCategory getCategory(){return new EmiRecipeCategory(new net.minecraft.resources.ResourceLocation("test:machine"));}public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getInputs(){return inputs;}public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getCatalysts(){return catalysts;}public java.util.List<dev.emi.emi.api.stack.EmiStack> getOutputs(){return outputs;}public boolean supportsRecipeTree(){return true;}public net.minecraft.world.item.crafting.ShapelessRecipe getBackingRecipe(){return new net.minecraft.world.item.crafting.ShapelessRecipe();}}""",
    "dev.emi.emi.api.recipe.EmiResolutionRecipe": "public class EmiResolutionRecipe extends EmiRecipe {public final dev.emi.emi.api.stack.EmiStack stack;public EmiResolutionRecipe(dev.emi.emi.api.stack.EmiStack s){stack=s;outputs=java.util.List.of(s);}public net.minecraft.resources.ResourceLocation getId(){return null;}}",
    "dev.emi.emi.recipe.EmiShapelessRecipe": "public class EmiShapelessRecipe extends dev.emi.emi.api.recipe.EmiRecipe {}",
    "dev.emi.emi.api.recipe.EmiRecipeManager": """public class EmiRecipeManager {public final java.util.Map<String,EmiRecipe> recipes=new java.util.HashMap<>();public java.util.List<dev.emi.emi.api.stack.EmiIngredient> workstations=java.util.List.of();public EmiRecipe getRecipe(net.minecraft.resources.ResourceLocation id){return recipes.get(id.toString());}public java.util.List<dev.emi.emi.api.stack.EmiIngredient> getWorkstations(EmiRecipeCategory c){return workstations;}}""",
    "dev.emi.emi.api.EmiApi": "public class EmiApi {public static dev.emi.emi.api.recipe.EmiRecipeManager manager=new dev.emi.emi.api.recipe.EmiRecipeManager();public static dev.emi.emi.api.recipe.EmiRecipeManager getRecipeManager(){return manager;}}",
    "dev.emi.emi.runtime.EmiReloadManager": "public class EmiReloadManager {public static boolean loaded=true;public static boolean isLoaded(){return loaded;}}",
    "fr.assistantia.ArbreEmi": "public class ArbreEmi {public static Object tree;public static Object consulte(){return tree;}}",
    "fr.assistantia.compat.emi.InstantaneArbreEmi": "public class InstantaneArbreEmi {public static long revision;public static long revisionProduction(){return revision;}}",
    "fr.assistantia.compat.emi.ComparaisonEmi": """public class ComparaisonEmi {public static boolean circuitVirtuel(dev.emi.emi.api.recipe.EmiRecipe r,dev.emi.emi.api.stack.EmiIngredient i,boolean c){return r instanceof com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe&&c&&i.getEmiStacks().stream().allMatch(s->s.getId().toString().equals("gtceu:programmed_circuit"));}}""",
    "fr.assistantia.compat.emi.GregTechMachines": """public class GregTechMachines {public static Object recette(dev.emi.emi.api.recipe.EmiRecipe r){return r instanceof com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe gt?gt.recipe:null;}}""",
    "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe": "public class GTEmiRecipe extends dev.emi.emi.api.recipe.EmiRecipe {public Object recipe;public GTEmiRecipe(Object r){recipe=r;}}",
    "com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient": """public class SizedIngredient extends net.minecraft.world.item.crafting.Ingredient {public final net.minecraft.world.item.crafting.Ingredient inner;public SizedIngredient(net.minecraft.world.item.crafting.Ingredient i){super(i.getItems());inner=i;}public net.minecraft.world.item.crafting.Ingredient getInner(){return inner;}}""",
    "com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient": "public class IntCircuitIngredient extends net.minecraft.world.item.crafting.Ingredient {private final int configuration;public IntCircuitIngredient(int c){configuration=c;}}",
    "com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient": "public class FluidIngredient {public net.minecraftforge.fluids.FluidStack[] stacks;public FluidIngredient(net.minecraftforge.fluids.FluidStack... s){stacks=s;}public net.minecraftforge.fluids.FluidStack[] getStacks(){return stacks;}}",
    "com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability": "public class ItemRecipeCapability {}",
    "com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability": "public class FluidRecipeCapability {}",
    "com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability": "public class EURecipeCapability {}",
}


class NativeProductionCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        javac, java = shutil.which("javac"), shutil.which("java")
        if not javac or not java:
            raise unittest.SkipTest("JDK requis")
        gson = next(
            iter(
                (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                    "2.10/*/gson-2.10.jar"
                )
            ),
            None,
        )
        if gson is None:
            raise unittest.SkipTest("Gson 2.10 en cache requis")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        directory = Path(cls.temp.name)
        sources = []
        for name, body in STUBS.items():
            path = directory / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
            sources.append(str(path))
        sources.extend(
            str(ROOT / "mod/src/main/java/fr/assistantia" / name)
            for name in (
                "PublicationCatalogue.java",
                "CatalogueProduction.java",
                "compat/emi/RecetteProduction.java",
                "compat/emi/CatalogueEmi.java",
            )
        )
        sources.append(str(ROOT / "tests/java/fr/assistantia/ProductionCatalogueTest.java"))
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", str(directory), *sources],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.command = [
            java,
            "-ea",
            "-cp",
            os.pathsep.join((str(directory), str(gson))),
            "fr.assistantia.ProductionCatalogueTest",
        ]

    def run_case(self, name):
        result = subprocess.run([*self.command, name], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def test_native_gt_exact_quantities_circuit_fluids_chance_and_limits(self):
        self.run_case("gt")

    def test_on_demand_service_cache_invalidation_and_disconnect(self):
        self.run_case("service")

    def test_tree_target_choices_scope_reload_and_generation(self):
        self.run_case("tree")

    def test_canonical_json_and_atomic_publication_match_python_reader(self):
        from monifactory_assistant.production.catalog import CatalogueReader

        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [*self.command, "publish", directory], capture_output=True, text=True
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            document = json.loads((Path(directory) / "catalogue.json").read_text())
            raw = json.dumps(
                document["catalogue"], ensure_ascii=True, sort_keys=True, separators=(",", ":")
            )
            self.assertEqual(document["sha256"], hashlib.sha256(raw.encode("ascii")).hexdigest())
            view = CatalogueReader(Path(directory) / "catalogue.json", "fixture-native").refresh()
            self.assertEqual(view.status, "ready", view.reason)
            self.assertEqual(view.catalog.recipes[0].unsupported, ('Unicode é 日本語 😀\n"<>&',))
