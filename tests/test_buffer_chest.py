"""Exercise the buffer selection and native menu lifecycle against a synced storage view."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_native_stock import STUBS as NATIVE

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    key: NATIVE[key]
    for key in (
        "net.minecraft.world.entity.player.Player",
        "net.minecraft.world.inventory.AbstractContainerMenu",
        "net.minecraft.world.inventory.ChestMenu",
        "net.minecraft.world.inventory.ShulkerBoxMenu",
        "net.minecraft.world.inventory.Slot",
        "net.minecraft.world.item.Item",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.nbt.CompoundTag",
        "net.minecraft.client.gui.screens.Screen",
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
        "net.minecraft.network.chat.Component",
        "net.minecraftforge.event.TickEvent",
    )
}
STUBS.update(
    {
        "net.minecraft.client.Minecraft": """public class Minecraft {
     private static final Minecraft INSTANCE=new Minecraft();public static Minecraft getInstance(){return INSTANCE;}
     public net.minecraft.client.multiplayer.ClientLevel level=new net.minecraft.client.multiplayer.ClientLevel();
     public net.minecraft.client.multiplayer.ClientPacketListener connection=new net.minecraft.client.multiplayer.ClientPacketListener();
     public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player();
     public net.minecraft.world.phys.HitResult hitResult;public net.minecraft.client.multiplayer.ClientPacketListener getConnection(){return connection;}}""",
        "net.minecraft.client.multiplayer.ClientPacketListener": "public class ClientPacketListener {}",
        "net.minecraft.client.multiplayer.ClientLevel": """public class ClientLevel {
     public final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> blocks=new java.util.HashMap<>();
     public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos p){return blocks.get(p);}
     public State getBlockState(net.minecraft.core.BlockPos p){return new State();}
     public boolean hasChunkAt(net.minecraft.core.BlockPos p){return true;}public Dim dimension(){return new Dim();}
     public boolean isClientSide(){return true;}
     public record Dim(){public String location(){return "test:world";}}
     public record State(){public Object getBlock(){return new net.minecraft.world.level.block.ChestBlock();}
      public boolean hasProperty(Object p){return false;}
      public net.minecraft.world.level.block.state.properties.ChestType getValue(Object p){return net.minecraft.world.level.block.state.properties.ChestType.SINGLE;}}}""",
        "net.minecraft.core.BlockPos": """public record BlockPos(int getX,int getY,int getZ) {
     public BlockPos immutable(){return this;}public BlockPos relative(Object d){throw new AssertionError("single chest");}}""",
        "net.minecraft.world.level.block.entity.BlockEntity": "public class BlockEntity {}",
        "net.minecraft.world.level.block.ChestBlock": """public class ChestBlock {
     public static Object getConnectedDirection(Object state){return null;}}""",
        "net.minecraft.world.level.block.state.properties.ChestType": "public enum ChestType {SINGLE}",
        "net.minecraft.world.level.block.state.properties.BlockStateProperties": """public class BlockStateProperties {
     public static final Object CHEST_TYPE=new Object();}""",
        "net.minecraft.world.phys.HitResult": "public class HitResult {public enum Type{BLOCK} public Type getType(){return Type.BLOCK;}}",
        "net.minecraft.world.phys.BlockHitResult": """public class BlockHitResult extends HitResult {
     public final net.minecraft.core.BlockPos pos;public BlockHitResult(net.minecraft.core.BlockPos p){pos=p;}
     public net.minecraft.core.BlockPos getBlockPos(){return pos;}}""",
        "net.minecraftforge.eventbus.api.SubscribeEvent": """public @interface SubscribeEvent {
     EventPriority priority() default EventPriority.NORMAL;}""",
        "net.minecraftforge.eventbus.api.EventPriority": "public enum EventPriority {HIGH,LOWEST,NORMAL}",
        "net.minecraftforge.client.event.ScreenEvent": """public class ScreenEvent {
     public record Closing(net.minecraft.client.gui.screens.Screen getScreen){}
     public static class Init {public record Post(net.minecraft.client.gui.screens.Screen getScreen){}}}""",
        "net.minecraftforge.event.entity.player.PlayerInteractEvent": """public class PlayerInteractEvent {
     public record RightClickBlock(net.minecraft.client.multiplayer.ClientLevel getLevel,
          net.minecraft.world.entity.player.Player getEntity,net.minecraft.core.BlockPos getPos){}}""",
        "net.minecraftforge.registries.ForgeRegistries": """public class ForgeRegistries {
     public static final Registry BLOCKS=new Registry();
     public record Registry(){public Id getKey(Object b){return new Id();}}
     public record Id(){public String getNamespace(){return "sophisticatedstorage";}}}""",
        "fr.assistantia.RetraitCoffre": """public class RetraitCoffre {
     static java.util.List<net.minecraft.world.item.ItemStack> piles=java.util.List.of();
     public static boolean retrait;
     public static boolean occupe(){return retrait;}
     public static boolean estCoffre(net.minecraft.world.inventory.AbstractContainerMenu menu){return true;}
     public static java.util.List<net.minecraft.world.item.ItemStack> pilesTampon(net.minecraft.world.inventory.AbstractContainerMenu m){return piles;}}""",
        "fr.assistantia.StockProduction": """public class StockProduction {
     static boolean initialise;
     static boolean menuInitialise(net.minecraft.world.inventory.AbstractContainerMenu menu){return initialise;}}""",
    }
)

HARNESS = """package fr.assistantia;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

public class CoffreTamponTest {
    public static class StorageMenu extends AbstractContainerMenu {
        private final BlockPos position;

        StorageMenu(BlockPos position) { this.position = position; }
        public Optional<BlockPos> getBlockPosition() { return Optional.of(position); }
    }

    static void tick() { CoffreTampon.tick(new TickEvent.ClientTickEvent()); }
    static int stock() { return CoffreTampon.piles().stream().mapToInt(ItemStack::getCount).sum(); }

    public static void main(String[] args) {
        var mc = Minecraft.getInstance();
        var pos = new BlockPos(7, 90, -4);
        var other = new BlockPos(7, 91, -4);
        mc.level.blocks.put(pos, new BlockEntity());
        mc.level.blocks.put(other, new BlockEntity());
        var screen = new AbstractContainerScreen<>(new StorageMenu(pos));
        mc.player.containerMenu = screen.getMenu();
        CoffreTampon.ouvrir(new ScreenEvent.Init.Post(screen));
        var stored = new ItemStack(new Item("test:iron"), 5);
        RetraitCoffre.piles = List.of(stored);
        tick();
        assert stock() == 0 : "before initialization";
        StockProduction.initialise = true;
        tick();
        mc.player.containerMenu = new AbstractContainerMenu();
        CoffreTampon.fermer(new ScreenEvent.Closing(screen));
        tick();
        mc.hitResult = new BlockHitResult(pos);
        CoffreTampon.basculer(mc);
        assert stock() == 5 : "reuse the last authorized menu when selecting after close";
        assert CoffreTampon.positionRepere().equals(pos);
        assert CoffreTampon.selectionne("test:world", 7, 90, -4);
        assert !CoffreTampon.selectionne("test:world", 7, 91, -4);

        mc.player.containerMenu = new StorageMenu(pos);
        var reopened = new AbstractContainerScreen<>(mc.player.containerMenu);
        CoffreTampon.ouvrir(new ScreenEvent.Init.Post(reopened));
        stored.count = 8;
        tick();
        assert stock() == 8 : "deposit into a live native menu";
        stored.count = 3;
        tick();
        assert stock() == 3 : "withdrawal updates the exact amount";
        RetraitCoffre.retrait = true;
        mc.player.containerMenu = new AbstractContainerMenu();
        CoffreTampon.fermer(new ScreenEvent.Closing(reopened));
        assert stock() == 0 : "escape during transfer invalidates the cached stock";
        assert CoffreTampon.positionRepere().equals(pos) : "selection remains visible";
        RetraitCoffre.retrait = false;
        CoffreTampon.basculer(mc);
        CoffreTampon.basculer(mc);
        assert stock() == 0 : "the invalidated estimate cannot be reused";
        mc.player.containerMenu = new StorageMenu(pos);
        CoffreTampon.ouvrir(new ScreenEvent.Init.Post(new AbstractContainerScreen<>(mc.player.containerMenu)));
        tick();
        assert stock() == 3 : "a new native observation restores the authoritative stock";
        mc.level.blocks.put(pos, new BlockEntity());
        tick();
        assert stock() == 0 && CoffreTampon.positionRepere() == null : "replacement clears old stock";
    }
}
"""


class BufferChestTests(unittest.TestCase):
    def test_native_position_cached_selection_and_deposit(self):
        java, javac = shutil.which("java"), shutil.which("javac")
        if not java or not javac:
            self.skipTest("JDK 17 requis")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = []
            for name, body in STUBS.items():
                path = root / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(path))
            harness = root / "CoffreTamponTest.java"
            harness.write_text(HARNESS)
            sources.append(str(harness))
            sources.append(str(ROOT / "mod/src/main/java/fr/assistantia/CoffreTampon.java"))
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", tmp, *sources],
                text=True,
                capture_output=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", tmp, "fr.assistantia.CoffreTamponTest"],
                text=True,
                capture_output=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
