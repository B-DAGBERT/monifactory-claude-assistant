"""Replay the real client transfer code against a deterministic vanilla-click container.

The small fixture implements inventory mechanics, not the withdrawal algorithm. No Minecraft
client, server, personal saves or AI provider is started by this test.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "net.minecraft.world.item.ItemStack": """
public class ItemStack {
    public final String id; public int count;
    public ItemStack(String id, int count) {this.id=id;this.count=count;}
    public boolean isEmpty(){return count<=0;}
    public int getCount(){return Math.max(0,count);}
    public int getMaxStackSize(){return 64;}
    public ItemStack copy(){return new ItemStack(id,count);}
    public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return !a.isEmpty()&&!b.isEmpty()&&a.id.equals(b.id);}
}
""",
    "net.minecraft.network.chat.Component": """
public record Component(String text){public static Component literal(String s){return new Component(s);}}
""",
    "net.minecraft.world.entity.player.Player": """
public class Player {
    public net.minecraft.world.inventory.AbstractContainerMenu containerMenu;
    public String lastMessage=""; private final Object inventory=new Object();
    public Object getInventory(){return inventory;}
    public void displayClientMessage(net.minecraft.network.chat.Component c,boolean action){lastMessage=c.text();}
}
""",
    "net.minecraft.world.inventory.Slot": """
public class Slot {
    public Object container; public int index; public boolean active=true, pickup=true, place=true;
    public net.minecraft.world.item.ItemStack item=new net.minecraft.world.item.ItemStack("",0);
    public Slot(Object owner,int index){container=owner;this.index=index;}
    public int getContainerSlot(){return index;}
    public net.minecraft.world.item.ItemStack getItem(){return item;}
    public boolean isActive(){return active;}
    public boolean hasItem(){return !item.isEmpty();}
    public boolean mayPickup(net.minecraft.world.entity.player.Player p){return pickup;}
    public boolean mayPlace(net.minecraft.world.item.ItemStack i){return place;}
    public int getMaxStackSize(net.minecraft.world.item.ItemStack i){return 64;}
}
""",
    "net.minecraft.world.inventory.AbstractContainerMenu": """
public class AbstractContainerMenu {
    public int containerId=1; public java.util.List<Slot> slots=new java.util.ArrayList<>();
    public net.minecraft.world.item.ItemStack carried=new net.minecraft.world.item.ItemStack("",0);
    public net.minecraft.world.item.ItemStack getCarried(){return carried;}
}
""",
    "net.minecraft.world.inventory.ChestMenu": "public class ChestMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.inventory.ShulkerBoxMenu": "public class ShulkerBoxMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.inventory.ClickType": "public enum ClickType {PICKUP}",
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": """
public class AbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu> {
    private final T menu; public AbstractContainerScreen(T m){menu=m;} public T getMenu(){return menu;}
}
""",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.event.TickEvent": """
public class TickEvent {public enum Phase {START,END}
    public static class ClientTickEvent {public Phase phase=Phase.END;}}
""",
    "net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase": """
public class StorageScreenBase extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<net.minecraft.world.inventory.ChestMenu> {
    public int customClicks;
    public StorageScreenBase(net.minecraft.world.inventory.ChestMenu menu){super(menu);}
    private void handleInventoryMouseClick(int slot,int button,net.minecraft.world.inventory.ClickType type){
        customClicks++;
        var mc=net.minecraft.client.Minecraft.getInstance();
        mc.gameMode.handleInventoryMouseClick(getMenu().containerId,slot,button,type,mc.player);
    }
}
""",
    "net.minecraft.client.Minecraft": """
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.*;
public class Minecraft {
    private static final Minecraft INSTANCE=new Minecraft();
    public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();
    public Object screen; public GameMode gameMode=new GameMode();
    public static Minecraft getInstance(){return INSTANCE;}
    public static class GameMode {
        public int clicks; public boolean refuse;
        public void handleInventoryMouseClick(int id,int index,int button,ClickType type,net.minecraft.world.entity.player.Player p){
            clicks++;
            if(refuse)return;
            var menu=p.containerMenu; var slot=menu.slots.get(index); var cursor=menu.carried;
            if(cursor.isEmpty()){
                if(!slot.pickup)return;
                int n=Math.min(slot.item.getCount(),64);
                menu.carried=new ItemStack(slot.item.id,n);slot.item.count-=n;
            }else if(slot.place && (slot.item.isEmpty()||slot.item.id.equals(cursor.id))){
                int limit=slot.container==p.getInventory()?64:100000;
                int n=Math.min(button==1?1:cursor.getCount(),limit-slot.item.getCount());
                slot.item=new ItemStack(cursor.id,slot.item.getCount()+n);cursor.count-=n;
            }
        }
    }
}
""",
}


class ChestTransferTests(unittest.TestCase):
    def test_real_transfer_state_machine_with_container_clicks(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = Path.home() / (
                "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/"
                "mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
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
                for name in ("PlanRetrait", "RetraitCoffre")
            ]
            sources.append(str(ROOT / "tests/java/fr/assistantia/RetraitCoffreTest.java"))
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.RetraitCoffreTest"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
