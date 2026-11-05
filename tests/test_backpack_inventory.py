"""Carried backpack snapshots, synchronization and consumption by the assistant."""

import ast
import copy
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from monifactory_assistant.game.inventory import personal_inventory, recent_state

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "com.mojang.logging.LogUtils": """
public class LogUtils {public static boolean expectedFailure;public static Logger getLogger(){return new Logger();}
 public static class Logger {public void warn(String s,Throwable e){if(!expectedFailure)throw new AssertionError(s,e);}}}
""",
    "net.minecraft.resources.ResourceLocation": """
public record ResourceLocation(String value) {public String getNamespace(){return value.split(":")[0];} public String toString(){return value;}}
""",
    "net.minecraftforge.registries.ForgeRegistries": """
public class ForgeRegistries {public static final Registry ITEMS=new Registry(); public static class Registry {
 public net.minecraft.resources.ResourceLocation getKey(String id){return new net.minecraft.resources.ResourceLocation(id);}}}
""",
    "net.minecraft.nbt.Tag": "public class Tag {public static final int TAG_COMPOUND=10,TAG_ANY_NUMERIC=99;}",
    "net.minecraft.nbt.CompoundTag": """
public class CompoundTag extends Tag {
 private final java.util.Map<String,Object> data=new java.util.HashMap<>();
 public void put(String k,Tag v){data.put(k,v);}public Tag get(String k){return data.get(k) instanceof Tag t?t:null;}
 public void putInt(String k,int n){data.put(k,n);}public int getInt(String k){return data.get(k) instanceof Number n?n.intValue():0;}
 public void putString(String k,String s){data.put(k,s);}public String getString(String k){return String.valueOf(data.getOrDefault(k,""));}
 public void putUUID(String k,java.util.UUID id){data.put(k,id);}public boolean hasUUID(String k){return data.get(k) instanceof java.util.UUID;}
 public java.util.UUID getUUID(String k){return (java.util.UUID)data.get(k);}
 public boolean contains(String k,int t){return t==99?data.get(k) instanceof Number:data.containsKey(k);}
 public CompoundTag getCompound(String k){return data.get(k) instanceof CompoundTag c?c:new CompoundTag();}
 public ListTag getList(String k,int t){return data.get(k) instanceof ListTag l?l:new ListTag();}
 public CompoundTag copy(){var c=new CompoundTag();c.data.putAll(data);return c;}
 public boolean equals(Object o){return o instanceof CompoundTag c&&data.equals(c.data);}public int hashCode(){return data.hashCode();}
}
""",
    "net.minecraft.nbt.ListTag": """
public class ListTag extends Tag {private final java.util.List<CompoundTag> items=new java.util.ArrayList<>();
 public int size(){return items.size();}public CompoundTag getCompound(int i){return items.get(i);}public void add(CompoundTag t){items.add(t);}}
""",
    "net.minecraft.world.item.ItemStack": """
import net.minecraft.nbt.*;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack("",0); public final String id;private int count;private CompoundTag tag;
 public ItemStack(String id,int n){this.id=id;count=n;}public int getCount(){return count;}public void setCount(int n){count=n;}
 public boolean isEmpty(){return count<=0||id.isEmpty();} public boolean hasTag(){return tag!=null;}public CompoundTag getTag(){return tag;}
 public String getItem(){return id;}public void setTag(CompoundTag t){tag=t;}
 public ItemStack copy(){var s=new ItemStack(id,count);s.tag=tag==null?null:tag.copy();return s;}
 public static boolean matches(ItemStack a,ItemStack b){return a.id.equals(b.id)&&a.count==b.count&&java.util.Objects.equals(a.tag,b.tag);}
 public static ItemStack of(CompoundTag n){var s=new ItemStack(n.getString("id"),n.getInt("Count")); if(n.get("tag")!=null)s.tag=n.getCompound("tag").copy(); return s;}
}
""",
    "net.minecraft.world.entity.player.Player": """
public class Player {public Object containerMenu=new Object();public java.util.List<net.minecraft.world.item.ItemStack> items=new java.util.ArrayList<>(),extra=new java.util.ArrayList<>();}
""",
    "net.minecraftforge.items.IItemHandler": "public interface IItemHandler {int getSlots();net.minecraft.world.item.ItemStack getStackInSlot(int i);}",
    "net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider": """
public class PlayerInventoryProvider {
 public static final PlayerInventoryProvider INSTANCE=new PlayerInventoryProvider();public static PlayerInventoryProvider get(){return INSTANCE;}
 public interface BackpackInventorySlotConsumer {boolean accept(net.minecraft.world.item.ItemStack s,String handler,String id,int slot);}
 public void runOnBackpacks(net.minecraft.world.entity.player.Player p,BackpackInventorySlotConsumer c){
  for(var s:p.items)c.accept(s,"main","",0);for(var s:p.extra)c.accept(s,"curios","back",0);}
}
""",
    "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage": """
public class BackpackStorage {
 private static final BackpackStorage INSTANCE=new BackpackStorage();public static BackpackStorage get(){return INSTANCE;}
 public final java.util.Map<java.util.UUID,net.minecraft.nbt.CompoundTag> contents=new java.util.HashMap<>();
 public net.minecraft.nbt.CompoundTag getOrCreateBackpackContents(java.util.UUID id){return contents.computeIfAbsent(id,k->new net.minecraft.nbt.CompoundTag());}
}
""",
    "net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler": """
public class SBPPacketHandler {
 public static final SBPPacketHandler INSTANCE=new SBPPacketHandler();public static final java.util.List<java.util.UUID> requested=new java.util.ArrayList<>();
 public <M> void sendToServer(M msg){requested.add(((RequestBackpackInventoryContentsMessage)msg).id());}}
""",
    "net.p3pp3rf1y.sophisticatedbackpacks.network.RequestBackpackInventoryContentsMessage": "public record RequestBackpackInventoryContentsMessage(java.util.UUID id) {}",
    "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer": """
public class BackpackContainer {
 private final Wrapper wrapper;
 public BackpackContainer(java.util.UUID id,java.util.List<net.minecraft.world.item.ItemStack> contents){wrapper=new Wrapper(id,contents);}
 public Wrapper getStorageWrapper(){return wrapper;}
 public record Wrapper(java.util.UUID id,java.util.List<net.minecraft.world.item.ItemStack> contents){
  public java.util.Optional<java.util.UUID> getContentsUuid(){return java.util.Optional.of(id);}
  public net.minecraftforge.items.IItemHandler getInventoryHandler(){return new net.minecraftforge.items.IItemHandler(){
   public int getSlots(){return contents.size();}public net.minecraft.world.item.ItemStack getStackInSlot(int i){return contents.get(i);}};}}
}
""",
}


class BackpackInventoryTests(unittest.TestCase):
    def setUp(self):
        self.state = {
            "t": 100000,
            "joueur": "Damien",
            "dimension": "minecraft:overworld",
            "inventaire": {"iron": 4},
        }
        self.snapshot = {
            **self.state,
            "actif": True,
            "synchronise": True,
            "inventaire": {"iron": 12, "plate": 2},
        }

    def test_complete_snapshot_replaces_inventory_and_does_not_accumulate(self):
        original = copy.deepcopy(self.state)
        merged = personal_inventory(self.state, self.snapshot, 100500)
        self.assertEqual(merged["inventaire"], {"iron": 12, "plate": 2})
        self.assertEqual(personal_inventory(merged, self.snapshot, 100600), merged)
        self.assertEqual(self.state, original)

    def test_unknown_stale_and_foreign_backpacks_are_not_counted(self):
        for update in (
            {"synchronise": False},
            {"actif": False},
            {"t": 97000},
            {"t": 110000},
            {"joueur": "Collègue"},
            {"dimension": "minecraft:the_nether"},
        ):
            with self.subTest(update=update):
                self.assertIs(
                    personal_inventory(self.state, {**self.snapshot, **update}, 100500),
                    self.state,
                )

    def test_invalid_counts_do_not_replace_a_valid_inventory(self):
        for inventory in ({"iron": -1}, {"iron": True}, {"iron": 1.5}, [], {"iron": 2**32}):
            self.assertIs(
                personal_inventory(self.state, {**self.snapshot, "inventaire": inventory}, 100500),
                self.state,
            )

    def test_empty_backpack_is_a_valid_snapshot(self):
        snapshot = {**self.snapshot, "inventaire": {"iron": 4}}
        self.assertEqual(
            personal_inventory(self.state, snapshot, 100500)["inventaire"], {"iron": 4}
        )

    def test_bridge_uses_snapshot_without_modifying_last_raw_frame(self):
        tree = ast.parse((ROOT / "monifactory_assistant/app.py").read_text())
        bridge = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Bridge")
        bridge.body = [
            m for m in bridge.body if isinstance(m, ast.FunctionDef) and m.name == "state"
        ]
        namespace = {
            "personal_inventory": personal_inventory,
            "recent_state": recent_state,
            "time": SimpleNamespace(time=lambda: 100.5),
        }
        exec(
            compile(
                ast.fix_missing_locations(ast.Module(body=[bridge], type_ignores=[])),
                "bridge",
                "exec",
            ),
            namespace,
        )
        b = namespace["Bridge"]()
        b.last_state = None
        b.read = lambda name: self.state if name == "etat.json" else self.snapshot
        self.assertEqual(b.state()["inventaire"]["iron"], 12)
        self.assertEqual(b.state()["inventaire"]["iron"], 12)
        self.assertEqual(b.last_state["inventaire"]["iron"], 4)

    def test_native_backpack_reader_and_progress(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory() as directory:
            fixtures = []
            for name, body in STUBS.items():
                package, cls = name.rsplit(".", 1)
                source = Path(directory) / (name.replace(".", "/") + ".java")
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text(f"package {package};\n{body}")
                fixtures.append(str(source))
            sources = [
                "mod/src/main/java/fr/assistantia/compat/backpacks/SacsPortes.java",
                "mod/src/main/java/fr/assistantia/PlanRetrait.java",
                "mod/src/main/java/fr/assistantia/SuiviFabrication.java",
                "tests/java/fr/assistantia/SacsPortesTest.java",
            ]
            compiled = subprocess.run(
                [
                    javac,
                    "-encoding",
                    "UTF-8",
                    "-d",
                    directory,
                    *fixtures,
                    *[str(ROOT / s) for s in sources],
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            ran = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.SacsPortesTest"],
                capture_output=True,
                text=True,
            )
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
