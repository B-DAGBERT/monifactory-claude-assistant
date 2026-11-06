"""Production stats exporter retries failed publications without forgetting unchanged inputs."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "fr.assistantia.Pont": """public class Pont {
 public static final java.util.Map<String,Integer> essais=new java.util.HashMap<>();
 public static final java.util.Set<String> echecs=new java.util.HashSet<>();
 public static final java.util.Map<String,java.util.List<String>> publies=new java.util.HashMap<>();
 public static String cleContexte(){return "";}
 public static boolean ecrireTexte(String nom,java.util.List<String> lignes){essais.merge(nom,1,Integer::sum);
 if(echecs.remove(nom))return false;publies.put(nom,java.util.List.copyOf(lignes));return true;}}
""",
    "fr.assistantia.Emi": 'public class Emi {public static java.util.List<String> postesDeTravail(){return java.util.List.of("machine");}}',
    "fr.assistantia.InventaireJoueur": "public class InventaireJoueur {public static boolean synchronise(){return true;}public static java.util.List<net.minecraft.world.item.ItemStack> sacs(){return java.util.List.of();}}",
    "fr.assistantia.compat.backpacks.SacsPortes": "public class SacsPortes {public static SacsPortes client(){return new SacsPortes();}public boolean ouvertPorte(Object p){return false;}}",
    "net.minecraft.client.Minecraft": """public class Minecraft {
 public static final Minecraft INSTANCE=new Minecraft();public Object level=new Object();
 public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();
 public static Minecraft getInstance(){return INSTANCE;}}
""",
    "net.minecraft.network.chat.Component": "public record Component(String text){public String getString(){return text;}}",
    "net.minecraft.resources.ResourceLocation": "public record ResourceLocation(String text){public String toString(){return text;}}",
    "net.minecraft.world.entity.EquipmentSlot": "public enum EquipmentSlot {MAINHAND}",
    "net.minecraft.world.entity.ai.attributes.AttributeModifier": "public class AttributeModifier {public enum Operation {ADDITION};public Operation getOperation(){return Operation.ADDITION;}public double getAmount(){return 0;}}",
    "net.minecraft.world.entity.ai.attributes.Attributes": "public class Attributes {public static final Object ATTACK_DAMAGE=new Object();}",
    "net.minecraft.world.entity.player.Inventory": "public class Inventory {public int selected;public final java.util.List<net.minecraft.world.item.ItemStack> items=new java.util.ArrayList<>();public int getContainerSize(){return items.size();}public net.minecraft.world.item.ItemStack getItem(int i){return items.get(i);}}",
    "net.minecraft.world.entity.player.Player": "public class Player {public final Inventory inventory=new Inventory();public final net.minecraft.world.inventory.AbstractContainerMenu inventoryMenu=new net.minecraft.world.inventory.AbstractContainerMenu();public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=inventoryMenu;public Inventory getInventory(){return inventory;}}",
    "net.minecraft.world.inventory.AbstractContainerMenu": "public class AbstractContainerMenu {public java.util.List<Slot> slots=new java.util.ArrayList<>();}",
    "net.minecraft.world.inventory.Slot": "public class Slot {public Object container;public boolean hasItem(){return false;}public net.minecraft.world.item.ItemStack getItem(){return null;}}",
    "net.minecraft.world.item.Item": "public class Item {public final String id;public Item(String id){this.id=id;}}",
    "net.minecraft.world.item.TieredItem": "public class TieredItem extends Item {public TieredItem(String id){super(id);}}",
    "net.minecraft.world.item.ArmorItem": "public class ArmorItem extends Item {public ArmorItem(String id){super(id);}public int getDefense(){return 0;}public float getToughness(){return 0;}}",
    "net.minecraft.world.item.DiggerItem": "public class DiggerItem extends TieredItem {public DiggerItem(String id){super(id);}}",
    "net.minecraft.world.item.TooltipFlag": "public class TooltipFlag {public static final TooltipFlag ADVANCED=new TooltipFlag();}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
 private final Item item;public ItemStack(Item item){this.item=item;}public Item getItem(){return item;}
 public boolean isEmpty(){return item==null;}public int getCount(){return 1;}public int getDamageValue(){return 0;}
 public Object getTag(){return null;}public boolean isDamageableItem(){return false;}public int getMaxDamage(){return 0;}
 public float getDestroySpeed(Object b){return 1;}public boolean isCorrectToolForDrops(Object b){return false;}
 public net.minecraft.network.chat.Component getHoverName(){return new net.minecraft.network.chat.Component(item.id);}
 public java.util.List<net.minecraft.network.chat.Component> getTooltipLines(Object p,Object f){return java.util.List.of();}
 public java.util.Map<Object,java.util.List<net.minecraft.world.entity.ai.attributes.AttributeModifier>> getAttributeModifiers(Object slot){return java.util.Map.of(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,java.util.List.of());}}
""",
    "net.minecraft.world.item.enchantment.Enchantment": 'public class Enchantment {public net.minecraft.network.chat.Component getFullname(int n){return new net.minecraft.network.chat.Component("e");}}',
    "net.minecraft.world.item.enchantment.EnchantmentHelper": "public class EnchantmentHelper {public static java.util.Map<Enchantment,Integer> getEnchantments(Object s){return java.util.Map.of();}}",
    "net.minecraft.world.level.block.Block": "public class Block {public net.minecraft.world.level.block.state.BlockState defaultBlockState(){return new net.minecraft.world.level.block.state.BlockState();}}",
    "net.minecraft.world.level.block.Blocks": "public class Blocks {public static final Block STONE=new Block(),IRON_ORE=new Block(),DIAMOND_ORE=new Block(),OBSIDIAN=new Block(),ANCIENT_DEBRIS=new Block();}",
    "net.minecraft.world.level.block.state.BlockState": "public class BlockState {}",
    "net.minecraftforge.event.TickEvent": "public class TickEvent {public enum Phase {START,END};public static class ClientTickEvent {public Phase phase=Phase.END;}}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.registries.ForgeRegistries": """public class ForgeRegistries {
 public static final Registry ITEMS=new Registry();public static class Registry {public int lectures;
 public java.util.List<net.minecraft.world.item.Item> getValues(){lectures++;return java.util.List.of(new net.minecraft.world.item.TieredItem("test:pickaxe"));}
 public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.item.Item item){return new net.minecraft.resources.ResourceLocation(item.id);}}}
""",
}


class StatsPublicationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        cls.javac = (
            str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        )
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not cls.javac or not cls.java:
            raise unittest.SkipTest("JDK 17 required")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        sources = []
        for name, body in STUBS.items():
            source = cls.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
            sources.append(str(source))
        sources += [
            str(ROOT / p)
            for p in (
                "mod/src/main/java/fr/assistantia/Stats.java",
                "tests/java/fr/assistantia/StatsPublicationTest.java",
            )
        ]
        result = subprocess.run(
            [cls.javac, "-encoding", "UTF-8", "-d", str(cls.directory), *sources],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_case(self, case):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                str(self.directory),
                "fr.assistantia.StatsPublicationTest",
                case,
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_unchanged_inventory_retries_after_failed_write(self):
        self.run_case("inventory")

    def test_empty_inventory_is_published_on_first_launch(self):
        self.run_case("empty")

    def test_catalogue_and_machine_lists_are_acknowledged_only_after_success(self):
        self.run_case("catalogue")
