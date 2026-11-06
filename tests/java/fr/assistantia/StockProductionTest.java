package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.Connection;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot;
import net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu;

public final class StockProductionTest {
    private static final Minecraft MC = Minecraft.getInstance();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ItemStack pile(String id, int n) {
        return new ItemStack(new Item(id), n);
    }

    private static AbstractContainerMenu menu(boolean sophistique) {
        AbstractContainerMenu m = sophistique ? new StorageContainerMenu() : new ChestMenu();
        Object coffre = new Object();
        for (int i = 0; i < 27; i++)
            m.slots.add(sophistique ? new StorageInventorySlot(coffre, i) {} : new Slot(coffre, i));
        for (int i = 0; i < 36; i++) m.slots.add(new Slot(MC.player.getInventory(), i));
        MC.player.containerMenu = m;
        MC.screen = new AbstractContainerScreen<>(m);
        return m;
    }

    private static List<ItemStack> full(AbstractContainerMenu m) {
        int n = m instanceof StorageContainerMenu s ? s.getTotalSlotsNumber() : m.slots.size();
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(m.getSlot(i).getItem().copy());
        StockProduction.complet(
                MC.listener.getConnection(),
                m.containerId,
                m.stateId,
                result,
                m.getCarried().copy(),
                m instanceof StorageContainerMenu);
        return result;
    }

    private static JsonObject capture() {
        return StockProduction.capturer(System.currentTimeMillis());
    }

    private static long count(JsonObject json, int source, String id) {
        var items =
                json.getAsJsonArray("sources")
                        .get(source)
                        .getAsJsonObject()
                        .getAsJsonObject("items");
        return items.has(id) ? items.get(id).getAsLong() : 0;
    }

    private static void sources() {
        var m = menu(false);
        m.getSlot(0).stack = pile("test:iron", 32);
        m.getSlot(1).stack = pile("test:iron", 12);
        m.getSlot(27).stack = pile("test:iron", 3);
        m.getSlot(28).stack = pile("test:variant", 7);
        m.getSlot(28).stack.tag = new net.minecraft.nbt.CompoundTag("{variant:1}");
        m.carried = pile("test:iron", 2);
        full(m);
        var json = capture();
        check(json != null && StockProduction.valide(json), "full packet usable");
        check(count(json, 0, "test:iron") == 44, "chest sums only physical chest slots");
        check(
                count(json, 1, "test:iron") == 3 && count(json, 2, "test:iron") == 2,
                "player and cursor disjoint");
        check(count(json, 1, "test:variant") == 0, "NBT variant never flattened");
        check(
                json.getAsJsonArray("sources").size() == 3,
                "no backpack/offhand historical addition");
        json.getAsJsonArray("sources")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("items")
                .addProperty("test:iron", 999);
        check(count(capture(), 0, "test:iron") == 44, "export detached from producer");
    }

    private static void mutations() {
        var m = menu(false);
        m.getSlot(0).stack = pile("test:iron", 4);
        var packet = full(m);
        packet.get(0).count = 900;
        check(count(capture(), 0, "test:iron") == 4, "packet copy immutable");
        var old = capture();
        StockProduction.variation(MC.listener.getConnection());
        check(capture() == null && !StockProduction.valide(old), "delta revokes positive");
        full(m);
        StockProduction.clic();
        check(capture() == null, "predicted click revokes even if stack unchanged");
        full(m);
        m.getSlot(0).stack.count = 3;
        check(capture() == null, "unreported mutation refused");
        StockProduction.tick(new TickEvent.ClientTickEvent());
        m.getSlot(0).stack.count = 4;
        check(capture() == null, "observed mutation cannot restore stale positive");
        full(m);
        m.stateId++;
        check(capture() == null, "new menu revision refused");
        full(m);
        var click = new ScreenEvent.MouseButtonPressed.Pre();
        click.screen = MC.screen;
        StockProduction.clicEcran(click);
        check(capture() == null, "custom sorting/transfer mouse paths invalidate");
    }

    private static void scope() {
        var m = menu(false);
        m.getSlot(0).stack = pile("test:iron", 4);
        full(m);
        var old = capture();
        Connection ancien = MC.listener.getConnection();
        MC.listener = new Minecraft.Listener();
        check(!StockProduction.valide(old), "new actual connection rejects same identity token");
        StockProduction.complet(
                ancien,
                m.containerId,
                m.stateId,
                m.slots.stream().map(s -> s.stack.copy()).toList(),
                m.carried,
                false);
        check(capture() == null, "old queued full cannot restore proof");
        full(m);
        IdentiteMonde.session = "c".repeat(32);
        check(capture() == null, "session change refused");
        full(m);
        MC.level.dim = "minecraft:the_nether";
        check(capture() == null, "dimension change refused");
        full(m);
        IdentiteMonde.world = "m1_" + "d".repeat(64);
        check(capture() == null, "world change refused");
        full(m);
        menu(false);
        check(capture() == null, "same container id different instance refused");
        full(MC.player.containerMenu);
        StockProduction.deconnexion(new ClientPlayerNetworkEvent.LoggingOut());
        check(capture() == null, "logout revokes proof");
        IdentiteMonde.session = "";
        full(MC.player.containerMenu);
        check(capture() == null, "unknown identity fails closed");
    }

    private static void bounds() {
        var m = menu(false);
        var packet = full(m);
        long observed = capture().get("observe_ms").getAsLong();
        check(StockProduction.capturer(observed - 1) == null, "future receipt refused");
        check(StockProduction.capturer(observed + 60_001) == null, "expired full refused");
        StockProduction.complet(
                MC.listener.getConnection(),
                m.containerId,
                m.stateId,
                packet.subList(0, packet.size() - 1),
                m.carried,
                false);
        check(capture() == null, "partial full refused");
        m.slots.set(28, m.slots.get(27));
        full(m);
        check(capture() == null, "aliased physical player slot refused");
        m = menu(false);
        m.getSlot(0).stack = pile("test:iron", 800_000_000);
        m.getSlot(1).stack = pile("test:iron", 800_000_000);
        full(m);
        check(capture() == null, "overflow is not silently saturated");
        MC.player.containerMenu = new ChestMenu() {};
        full(MC.player.containerMenu);
        check(capture() == null, "unknown subclass not guessed storage");
        m = menu(false);
        MC.main = false;
        full(m);
        check(capture() == null, "network thread cannot capture");
    }

    private static void sophisticated() {
        var m = (StorageContainerMenu) menu(true);
        m.getSlot(0).stack = pile("test:iron", 4096);
        m.getSlot(1).stack = pile("test:creative", 64);
        m.infinite.add(1);
        m.getSlot(2).stack = pile("test:locked", 64);
        m.getSlot(2).pickup = false;
        Slot ghost = new Slot(new Object(), 90);
        ghost.stack = pile("test:ghost", 64);
        m.extra.add(ghost);
        full(m);
        check(capture() == null, "full awaits native infinite/access metadata");
        StockProduction.tick(new TickEvent.ClientTickEvent());
        StockProduction.informations(MC.listener.getConnection());
        var captured = capture();
        check(captured != null, "metadata after later tick enables same full");
        check(count(captured, 0, "test:iron") == 4096, "custom count is not truncated");
        check(count(captured, 0, "test:creative") == 0, "infinite slot excluded");
        check(
                count(captured, 0, "test:locked") == 0 && count(captured, 0, "test:ghost") == 0,
                "locked and upgrades/ghost excluded");
        StockProduction.informations(MC.listener.getConnection());
        check(!StockProduction.valide(captured), "metadata changes revoke exported revision");
        var packet = full(m);
        StockProduction.complet(
                MC.listener.getConnection(), m.containerId, m.stateId, packet, m.carried, false);
        check(capture() == null, "vanilla truncated full cannot attest sophisticated");
        full(m);
        StockProduction.variation(MC.listener.getConnection());
        check(capture() == null, "custom slot update revokes full");
    }

    private static void screen() {
        var m = menu(false);
        full(m);
        var parent = (AbstractContainerScreen<?>) MC.screen;
        var key = new ScreenEvent.KeyPressed.Post();
        key.screen = parent;
        parent.focused = new EditBox();
        StockProduction.touche(key);
        check(MC.screen == parent, "typing never opens Production");
        parent.focused = null;
        Emi.search = true;
        StockProduction.touche(key);
        check(MC.screen == parent, "EMI search never intercepted");
        Emi.search = false;
        StockProduction.touche(key);
        check(
                MC.screen instanceof EcranProduction && parent.removed == 0,
                "controlled transition skips chest removed and keeps menu");
        check(
                MC.player.containerMenu == m && StockProduction.retourPossible(parent),
                "same native menu retained");
        check(!StockProduction.garderMenu(parent), "exception guard only during transition");
        StockProduction.variation(MC.listener.getConnection());
        check(
                StockProduction.retourPossible(parent),
                "delta invalidates stock but still allows current menu return");
        MC.listener = new Minecraft.Listener();
        check(
                !StockProduction.retourPossible(parent),
                "reconnect rejects old parent even before tick");
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "sources" -> sources();
            case "mutations" -> mutations();
            case "scope" -> scope();
            case "bounds" -> bounds();
            case "sophisticated" -> sophisticated();
            case "screen" -> screen();
            default -> throw new AssertionError(args[0]);
        }
    }
}
