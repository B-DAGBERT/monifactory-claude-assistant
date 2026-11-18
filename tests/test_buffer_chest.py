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
        "net.minecraft.client.multiplayer.ClientLevel": """
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

public class ClientLevel {
    private static final ChestBlock CHEST = new ChestBlock();
    public final Map<BlockPos, BlockEntity> blocks = new HashMap<>();
    public final Map<BlockPos, ChestType> types = new HashMap<>();
    public final Set<BlockPos> unloaded = new HashSet<>();

    public BlockEntity getBlockEntity(BlockPos pos) { return blocks.get(pos); }
    public State getBlockState(BlockPos pos) { return new State(types.getOrDefault(pos, ChestType.SINGLE)); }
    public boolean hasChunkAt(BlockPos pos) { return !unloaded.contains(pos); }
    public Dim dimension() { return new Dim(); }
    public boolean isClientSide() { return true; }
    public record Dim() { public String location() { return "test:world"; } }
    public record State(ChestType type) {
        public Object getBlock() { return CHEST; }
        public boolean hasProperty(Object property) { return true; }
        public ChestType getValue(Object property) { return type; }
    }
}""",
        "net.minecraft.core.BlockPos": """public record BlockPos(int getX,int getY,int getZ) {
     public BlockPos immutable(){return this;}
     public BlockPos relative(int x){return new BlockPos(getX+x,getY,getZ);}}""",
        "net.minecraft.world.level.block.entity.BlockEntity": "public class BlockEntity {}",
        "net.minecraft.world.level.block.ChestBlock": """public class ChestBlock {
     public static int getConnectedDirection(net.minecraft.client.multiplayer.ClientLevel.State state){
         return state.type() == net.minecraft.world.level.block.state.properties.ChestType.LEFT ? 1 : -1;}}""",
        "net.minecraft.world.level.block.ShulkerBoxBlock": "public class ShulkerBoxBlock {}",
        "fr.assistantia.TraitsReperes": "public class TraitsReperes {public static boolean tiroir(String id){return false;}}",
        "net.minecraft.world.level.block.state.properties.ChestType": "public enum ChestType {SINGLE, LEFT, RIGHT}",
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
        "net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu": """
     public class StorageContainerMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
       private final net.minecraft.core.BlockPos position;
       public StorageContainerMenu(net.minecraft.core.BlockPos position){this.position=position;}
       public java.util.Optional<net.minecraft.core.BlockPos> getBlockPosition(){
         return java.util.Optional.of(position);
       }
     }""",
        "net.p3pp3rf1y.sophisticatedstorage.common.gui.LimitedBarrelContainerMenu": """
     public class LimitedBarrelContainerMenu extends StorageContainerMenu {
       public LimitedBarrelContainerMenu(net.minecraft.core.BlockPos position){super(position);}
     }""",
        "fr.assistantia.RetraitCoffre": """public class RetraitCoffre {
     static java.util.List<net.minecraft.world.item.ItemStack> piles=java.util.List.of();
     public static boolean retrait;
     public static boolean occupe(){return retrait;}
     public static boolean estCoffre(net.minecraft.world.inventory.AbstractContainerMenu menu){
       return menu instanceof net.minecraft.world.inventory.ChestMenu
           || menu instanceof net.minecraft.world.inventory.ShulkerBoxMenu
           || menu.getClass() == net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu.class;
     }
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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.p3pp3rf1y.sophisticatedstorage.common.gui.LimitedBarrelContainerMenu;
import net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu;

public class CoffreTamponTest {
    private static final Minecraft MC = Minecraft.getInstance();
    private static long maintenant = 100_000_000_000L;

    static void tick() { CoffreTampon.tick(new TickEvent.ClientTickEvent()); }
    static int stock() { return CoffreTampon.piles().stream().mapToInt(ItemStack::getCount).sum(); }
    static int stockOutsideMenu() { return CoffreTampon.pilesHorsMenu().stream().mapToInt(ItemStack::getCount).sum(); }

    static List<BlockPos> positions() {
        return CoffreTampon.reperes().stream().map(CoffreTampon.Repere::position).toList();
    }

    static CoffreTampon.EtatReleve etat(BlockPos pos) {
        return CoffreTampon.reperes().stream().filter(r -> r.position().equals(pos))
                .findFirst().orElseThrow().etat();
    }

    static void toggle(BlockPos pos) {
        MC.hitResult = new BlockHitResult(pos);
        CoffreTampon.basculer(MC);
    }

    static void exclude(BlockPos pos) {
        MC.hitResult = new BlockHitResult(pos);
        CoffreTampon.basculerExclusion(MC);
    }

    static AbstractContainerScreen<?> open(AbstractContainerMenu menu) {
        MC.player.containerMenu = menu;
        var screen = new AbstractContainerScreen<>(menu);
        CoffreTampon.ouvrir(new ScreenEvent.Init.Post(screen));
        return screen;
    }

    static AbstractContainerScreen<?> openChest(BlockPos pos) {
        CoffreTampon.clic(new PlayerInteractEvent.RightClickBlock(MC.level, MC.player, pos));
        return open(new ChestMenu());
    }

    static void close(AbstractContainerScreen<?> screen) {
        MC.player.containerMenu = new AbstractContainerMenu();
        CoffreTampon.fermer(new ScreenEvent.Closing(screen));
        tick();
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        var horloge = CoffreTampon.class.getDeclaredField("horloge");
        horloge.setAccessible(true);
        horloge.set(null, (java.util.function.LongSupplier) () -> maintenant);
        var pos = new BlockPos(7, 90, -4);
        var left = new BlockPos(10, 90, -4);
        var right = left.relative(1);
        for (var block : List.of(pos, left, right)) MC.level.blocks.put(block, new BlockEntity());
        MC.level.types.put(left, ChestType.LEFT);
        MC.level.types.put(right, ChestType.RIGHT);

        var autreMenu = new AbstractContainerMenu();
        CoffreTampon.clic(new PlayerInteractEvent.RightClickBlock(MC.level, MC.player, pos));
        var autreEcran = open(autreMenu);
        assert !CoffreTampon.stockageOuvert() : "an unrelated screen does not attest chest contents";
        close(autreEcran);
        var tonneau = new LimitedBarrelContainerMenu(pos);
        var autreCoffre = open(tonneau);
        assert pos.equals(CoffreTampon.positionOuverte()) && CoffreTampon.stockageOuvert();
        assert !RetraitCoffre.estCoffre(tonneau) : "native storage proof does not enable withdrawals";
        close(autreCoffre);
        var screen = open(new StorageContainerMenu(pos));
        MC.hitResult = new BlockHitResult(right);
        assert pos.equals(CoffreTampon.positionOuverte()) && CoffreTampon.stockageOuvert();
        MC.level.unloaded.add(pos);
        assert !CoffreTampon.blocCharge(7, 90, -4) && CoffreTampon.positionOuverte() == null;
        MC.level.unloaded.clear();
        var item = new Item("test:iron");
        var stored = new ItemStack(item, 5);
        RetraitCoffre.piles = List.of(stored);
        StockProduction.initialise = true;
        tick();
        close(screen);
        toggle(pos);
        assert stock() == 5 : "reuse the last authorized menu when selecting after close";
        assert positions().equals(List.of(pos));
        assert CoffreTampon.selectionne("test:world", 7, 90, -4);
        assert !CoffreTampon.selectionne("test:another_world", 7, 90, -4);

        toggle(right);
        assert stock() == 5 : "marking a second chest keeps existing stock";
        assert positions().equals(List.of(pos, right));
        assert CoffreTampon.selectionne("test:world", 10, 90, -4);
        assert CoffreTampon.selectionne("test:world", 11, 90, -4);
        assert etat(right) == CoffreTampon.EtatReleve.NON_RELEVE;
        var doubleChest = openChest(left);
        assert CoffreTampon.menuObserve() : "the other half identifies the same marked menu";
        RetraitCoffre.piles = List.of(new ItemStack(item, 13));
        StockProduction.initialise = false;
        tick();
        assert stock() == 5 : "wait for native initialization";
        StockProduction.initialise = true;
        tick();
        assert stock() == 18 && stockOutsideMenu() == 5 : "omit only the double chest's snapshot";
        assert etat(right) == CoffreTampon.EtatReleve.DIRECT;
        var revision = CoffreTampon.revision();
        tick();
        assert CoffreTampon.revision() == revision : "unchanged snapshots keep the revision";
        close(doubleChest);
        assert !CoffreTampon.menuObserve() && stockOutsideMenu() == 18;
        toggle(left);
        assert stock() == 5 && positions().equals(List.of(pos));
        assert !CoffreTampon.selectionne("test:world", 11, 90, -4);
        toggle(left);
        assert stock() == 18 : "reselecting a recently observed double chest counts it once";

        exclude(left);
        assert stock() == 5 : "excluding a buffer removes only its contribution";
        assert CoffreTampon.exclu("test:world", 10, 90, -4);
        assert CoffreTampon.exclu("test:world", 11, 90, -4);
        assert !CoffreTampon.exclu("test:another_world", 11, 90, -4);
        assert positions().equals(List.of(pos));
        MC.level.unloaded.add(right);
        tick();
        MC.level.blocks.put(right, new BlockEntity());
        MC.level.unloaded.clear();
        tick();
        assert CoffreTampon.exclu("test:world", 11, 90, -4) : "exclusion survives chunk reload";
        doubleChest = openChest(right);
        assert CoffreTampon.menuExclu() && !CoffreTampon.menuObserve();
        tick();
        close(doubleChest);
        exclude(right);
        assert !CoffreTampon.exclu("test:world", 10, 90, -4);
        toggle(left);
        assert stock() == 5 : "an excluded menu never seeds new buffer stock";
        doubleChest = openChest(right);
        tick();
        close(doubleChest);
        assert stock() == 18;

        var reopened = open(new StorageContainerMenu(pos));
        assert stockOutsideMenu() == 13;
        RetraitCoffre.piles = List.of(stored);
        stored.count = 8;
        tick();
        assert stock() == 21 && stockOutsideMenu() == 13 : "deposit into a live native menu";
        stored.count = 3;
        tick();
        assert stock() == 16 && stockOutsideMenu() == 13 : "withdrawal updates just that chest";
        RetraitCoffre.retrait = true;
        close(reopened);
        assert stock() == 13 : "escape during transfer invalidates only the affected snapshot";
        assert positions().size() == 2 : "selections remain visible";
        RetraitCoffre.retrait = false;
        toggle(pos);
        toggle(pos);
        assert stock() == 13 : "the invalidated snapshot cannot be reused";
        reopened = open(new StorageContainerMenu(pos));
        tick();
        assert stock() == 16 : "a native observation restores the authoritative stock";
        close(reopened);

        MC.level.unloaded.add(right);
        tick();
        assert stock() == 3 && positions().equals(List.of(pos));
        MC.level.unloaded.clear();
        assert stock() == 3 : "loading a chunk does not resurface its stale snapshot";
        var reopenedDouble = openChest(right);
        RetraitCoffre.piles = List.of(new ItemStack(item, 13));
        tick();
        close(reopenedDouble);
        assert stock() == 16 && positions().size() == 2;

        MC.level.types.put(left, ChestType.SINGLE);
        tick();
        assert stock() == 3 && positions().equals(List.of(pos)) : "split double";
        MC.level.blocks.put(pos, new BlockEntity());
        tick();
        assert stock() == 0 && positions().isEmpty() : "replacement clears its stock";

        toggle(pos);
        reopened = open(new StorageContainerMenu(pos));
        tick();
        assert stock() == 13;
        close(reopened);
        MC.connection = new ClientPacketListener();
        assert stock() == 0 && positions().isEmpty() : "session scopes snapshots";
        tick();
        toggle(pos);
        assert stock() == 0 && positions().equals(List.of(pos));
        exclude(pos);
        assert CoffreTampon.exclu("test:world", 7, 90, -4);
        var ajoute = pos.relative(1);
        MC.level.blocks.put(ajoute, new BlockEntity());
        MC.level.types.put(pos, ChestType.LEFT);
        MC.level.types.put(ajoute, ChestType.RIGHT);
        var revisionSimple = CoffreTampon.revision();
        tick();
        assert CoffreTampon.exclu("test:world", 8, 90, -4);
        assert CoffreTampon.revision() > revisionSimple : "forming a double refreshes search";
        toggle(pos);
        assert !CoffreTampon.exclu("test:world", 7, 90, -4);
        assert !CoffreTampon.exclu("test:world", 8, 90, -4);
        exclude(pos);
        MC.connection = new ClientPacketListener();
        tick();
        assert !CoffreTampon.exclu("test:world", 7, 90, -4);
        verifierPermanence(pos, item);
    }

    static void verifierPermanence(BlockPos pos, Item item) {
        toggle(pos);
        var menu = open(new StorageContainerMenu(pos));
        RetraitCoffre.piles = List.of(new ItemStack(item, 5));
        tick();
        assert etat(pos) == CoffreTampon.EtatReleve.DIRECT && stock() == 5;
        close(menu);
        assert etat(pos) == CoffreTampon.EtatReleve.DERNIERE_OUVERTURE;
        long derniereRevision = CoffreTampon.revision();
        RetraitCoffre.piles = List.of(new ItemStack(item, 2)); // Changement sans menu observé.
        maintenant += 720_000_000_000L;
        tick();
        assert stock() == 5 && etat(pos) == CoffreTampon.EtatReleve.DERNIERE_OUVERTURE;
        assert CoffreTampon.selectionne("test:world", 7, 90, -4);
        assert !CoffreTampon.selectionne("test:other_world", 7, 90, -4);
        assert CoffreTampon.revision() == derniereRevision : "le temps seul ne change rien";
        toggle(pos);
        maintenant += 720_000_000_000L;
        tick();
        toggle(pos);
        assert stock() == 5 : "reselection reuses the last observed content";

        StockProduction.initialise = false;
        menu = open(new StorageContainerMenu(pos));
        tick();
        assert stock() == 5 && etat(pos) == CoffreTampon.EtatReleve.DERNIERE_OUVERTURE;
        StockProduction.initialise = true;
        tick();
        assert stock() == 2 && etat(pos) == CoffreTampon.EtatReleve.DIRECT;
        RetraitCoffre.piles = List.of(new ItemStack(item, 3));
        tick();
        assert stock() == 3 && etat(pos) == CoffreTampon.EtatReleve.DIRECT;
        close(menu);
        assert stock() == 3 && etat(pos) == CoffreTampon.EtatReleve.DERNIERE_OUVERTURE;
    }
}
"""


class BufferChestTests(unittest.TestCase):
    def test_native_menu_lifecycle_for_multiple_marked_chests(self):
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
