"""Replay actual native chest evidence against small, synthetic Minecraft contracts.

The fixture verifies the producer, not Forge injection at runtime. A real client
remains required to qualify vanilla and Sophisticated packet hooks.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "net.minecraft.client.Minecraft": """public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();public Level level=new Level();public Listener listener=new Listener();public net.minecraft.client.gui.screens.Screen screen;public boolean main=true;
 public static Minecraft getInstance(){return INSTANCE;}public boolean isSameThread(){return main;}public Listener getConnection(){return listener;}
 public void setScreen(net.minecraft.client.gui.screens.Screen s){if(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c&&!fr.assistantia.StockProduction.garderMenu(c))c.removed++;screen=s;}
 public static class Listener{public net.minecraft.network.Connection connection=new net.minecraft.network.Connection();public net.minecraft.network.Connection getConnection(){return connection;}}
 public static class Level{public String dim="minecraft:overworld";public Dimension dimension(){return new Dimension(dim);}}public record Dimension(String id){public net.minecraft.resources.ResourceLocation location(){return new net.minecraft.resources.ResourceLocation(id);}}}""",
    "net.minecraft.network.Connection": "public class Connection {public boolean connected=true;public boolean isConnected(){return connected;}}",
    "net.minecraft.resources.ResourceLocation": "public record ResourceLocation(String value){public String toString(){return value;}}",
    "net.minecraft.world.entity.player.Player": """public class Player {public net.minecraft.world.inventory.AbstractContainerMenu containerMenu;public final Object inventory=new Object();public String message="";public Object getInventory(){return inventory;}public void displayClientMessage(net.minecraft.network.chat.Component c,boolean action){message=c.text();}}""",
    "net.minecraft.world.inventory.AbstractContainerMenu": """public class AbstractContainerMenu {public int containerId=2,stateId=7;public java.util.List<Slot> slots=new java.util.ArrayList<>();public net.minecraft.world.item.ItemStack carried=net.minecraft.world.item.ItemStack.EMPTY;public Slot getSlot(int i){return slots.get(i);}public int getStateId(){return stateId;}public net.minecraft.world.item.ItemStack getCarried(){return carried;}}""",
    "net.minecraft.world.inventory.ChestMenu": "public class ChestMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.inventory.ShulkerBoxMenu": "public class ShulkerBoxMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.inventory.Slot": """public class Slot {public final Object container;public final int index;public net.minecraft.world.item.ItemStack stack=net.minecraft.world.item.ItemStack.EMPTY;public boolean active=true,pickup=true;public Slot(Object c,int i){container=c;index=i;}public int getContainerSlot(){return index;}public net.minecraft.world.item.ItemStack getItem(){return stack;}public boolean isActive(){return active;}public boolean mayPickup(net.minecraft.world.entity.player.Player p){return pickup;}}""",
    "net.minecraft.world.item.Item": "public record Item(String id) {}",
    "net.minecraft.nbt.CompoundTag": "public record CompoundTag(String value){public boolean isEmpty(){return value.isEmpty();}}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {public static final ItemStack EMPTY=new ItemStack(null,0);public Item item;public int count;public net.minecraft.nbt.CompoundTag tag;public ItemStack(Item i,int n){item=i;count=n;}public boolean isEmpty(){return item==null||count<=0;}public net.minecraft.nbt.CompoundTag getTag(){return tag;}public Item getItem(){return item;}public int getCount(){return count;}public ItemStack copy(){var c=new ItemStack(item,count);c.tag=tag;return c;}public static boolean matches(ItemStack a,ItemStack b){return a.isEmpty()&&b.isEmpty()||java.util.Objects.equals(a.item,b.item)&&a.count==b.count&&java.util.Objects.equals(a.tag,b.tag);}}""",
    "net.minecraftforge.registries.ForgeRegistries": "public class ForgeRegistries {public static final Registry ITEMS=new Registry();public static class Registry {public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.item.Item i){return i==null?null:new net.minecraft.resources.ResourceLocation(i.id());}}}",
    "net.minecraft.client.gui.screens.Screen": "public class Screen {public Object focused;public Object getFocused(){return focused;}}",
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": "public class AbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu> extends net.minecraft.client.gui.screens.Screen {public int removed;public final T menu;public AbstractContainerScreen(T m){menu=m;}public T getMenu(){return menu;}}",
    "net.minecraft.client.gui.components.EditBox": "public class EditBox {}",
    "net.minecraft.network.chat.Component": "public record Component(String text){public static Component literal(String t){return new Component(t);}}",
    "net.minecraftforge.client.event.ClientPlayerNetworkEvent": "public class ClientPlayerNetworkEvent {public static class LoggingOut {}}",
    "net.minecraftforge.event.TickEvent": "public class TickEvent {public enum Phase{START,END};public static class ClientTickEvent{public Phase phase=Phase.END;}}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.client.event.ScreenEvent": """public class ScreenEvent {public net.minecraft.client.gui.screens.Screen screen;public net.minecraft.client.gui.screens.Screen getScreen(){return screen;}public static class KeyPressed{public static class Post extends ScreenEvent {public int key=89;public int getKeyCode(){return key;}public int getScanCode(){return 0;}}}public static class MouseButtonPressed{public static class Pre extends ScreenEvent{}}}""",
    "fr.assistantia.IdentiteMonde": 'public class IdentiteMonde {public static String world="m1_"+"a".repeat(64),session="b".repeat(32);public static String monde(){return world;}public static String session(){return session;}}',
    "fr.assistantia.Emi": "public class Emi {public static boolean search;public static boolean rechercheActive(){return search;}}",
    "fr.assistantia.Touches": "public class Touches {public static final Key OUVRIR=new Key();public static class Key {public boolean matches(int k,int s){return k==89;}}}",
    "fr.assistantia.EcranProduction": "public class EcranProduction extends net.minecraft.client.gui.screens.Screen {public static EcranProduction depuisCoffre(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> p){return new EcranProduction();}}",
    "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot": "public class StorageInventorySlot extends net.minecraft.world.inventory.Slot {public StorageInventorySlot(Object c,int i){super(c,i);}}",
    "net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu": """public class StorageContainerMenu extends net.minecraft.world.inventory.AbstractContainerMenu {public java.util.List<net.minecraft.world.inventory.Slot> extra=new java.util.ArrayList<>();public java.util.Set<Integer> infinite=new java.util.HashSet<>();public int getTotalSlotsNumber(){return slots.size()+extra.size();}public net.minecraft.world.inventory.Slot getSlot(int i){return i<slots.size()?slots.get(i):extra.get(i-slots.size());}public boolean isInfiniteSlot(int i){return infinite.contains(i);}}""",
}


class NativeStockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java, javac = shutil.which("java"), shutil.which("javac")
        if not java or not javac:
            raise unittest.SkipTest("JDK requis")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "2.10/*/gson-2.10.jar"
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
        sources += [
            str(ROOT / "mod/src/main/java/fr/assistantia/StockProduction.java"),
            str(ROOT / "tests/java/fr/assistantia/StockProductionTest.java"),
        ]
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
            "fr.assistantia.StockProductionTest",
        ]

    def run_case(self, case):
        result = subprocess.run([*self.command, case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_common_packet_sources_counts_cursor_and_nbt(self):
        self.run_case("sources")

    def test_snapshot_copy_delta_local_click_and_current_state(self):
        self.run_case("mutations")

    def test_old_connection_world_dimension_and_menu_reuse(self):
        self.run_case("scope")

    def test_full_packet_shape_slot_alias_and_ttl(self):
        self.run_case("bounds")

    def test_sophisticated_extended_counts_ghost_infinite_and_metadata(self):
        self.run_case("sophisticated")

    def test_screen_transition_no_removal_and_old_parent_rejected(self):
        self.run_case("screen")

    def test_packet_and_click_hooks_are_registered_and_read_only(self):
        import json

        config = json.loads((ROOT / "mod/src/main/resources/assistantia.mixins.json").read_text())
        for name in (
            "PaquetsStockMixin",
            "ContenuStockSophistiqueMixin",
            "VariationStockSophistiqueMixin",
            "ClicStockMixin",
            "ClicStockSophistiqueMixin",
            "EcranStockMixin",
        ):
            self.assertIn(name, config["client"])
            source = (ROOT / f"mod/src/main/java/fr/assistantia/mixin/{name}.java").read_text()
            self.assertNotIn("sendPacket", source)
            self.assertNotIn("getBlockEntity", source)
        source = (ROOT / "mod/src/main/java/fr/assistantia/StockProduction.java").read_text()
        self.assertNotIn("getBlockEntity", source)
        self.assertNotIn("InventaireJoueur.piles", source)
        # This project has no refmap processor: deployed Forge uses the SRG selectors.
        for name, names in {
            "PaquetsStockMixin": ("m_6837_", "m_5735_", "m_5980_", "m_7776_"),
            "ClicStockMixin": ("m_171799_",),
            "EcranStockMixin": ("m_7861_",),
        }.items():
            source = (ROOT / f"mod/src/main/java/fr/assistantia/mixin/{name}.java").read_text()
            for selector in names:
                self.assertIn(f'"{selector}"', source)
            self.assertNotIn("@Shadow", source)


if __name__ == "__main__":
    unittest.main()
