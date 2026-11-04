package fr.assistantia;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;

/** Rejoue les vrais clics de RetraitCoffre dans le conteneur simulé par le test Python. */
public final class RetraitCoffreTest {
    private static Minecraft mc = Minecraft.getInstance();
    private static ChestMenu menu;
    private static AbstractContainerScreen<ChestMenu> screen;

    private static void setup(int chest, int owned) throws Exception {
        // Tester le debounce sans attendre dans la suite.
        var f = RetraitCoffre.class.getDeclaredField("dernierClic");
        f.setAccessible(true);
        f.setLong(null, 0);
        menu = new ChestMenu();
        screen = new AbstractContainerScreen<>(menu);
        mc.player.containerMenu = menu;
        mc.screen = screen;
        mc.gameMode.clicks = 0;
        mc.gameMode.refuse = false;
        for (int i = 0; i < 4; i++)
            menu.slots.add(new Slot(i == 0 ? new Object() : mc.player.getInventory(), i));
        menu.slots.get(0).item = new ItemStack("tin", chest);
        menu.slots.get(1).item = new ItemStack("tin", owned);
    }

    private static RetraitCoffre.Plan plan(int needed) {
        return RetraitCoffre.plan(
                menu, List.of(new PlanRetrait.Besoin<>(i -> i.id.equals("tin"), needed)));
    }

    private static void tick() {
        RetraitCoffre.tick(new TickEvent.ClientTickEvent());
    }

    private static void finish() {
        for (int i = 0; i < 1000 && RetraitCoffre.occupe(); i++) tick();
        assert !RetraitCoffre.occupe();
    }

    private static int owned() {
        return menu.slots.subList(1, 4).stream()
                .filter(s -> s.item.id.equals("tin"))
                .mapToInt(s -> s.item.getCount())
                .sum();
    }

    public static void main(String[] args) throws Exception {
        setup(64, 2);
        assert RetraitCoffre.lancer(screen, plan(5));
        assert !RetraitCoffre.lancer(screen, plan(5)) : "double clic";
        finish();
        assert owned() == 5 && menu.slots.get(0).item.getCount() == 61 && menu.carried.isEmpty();
        assert plan(5).total() == 0 : "second clic ne reprend pas la même quantité";
        assert !RetraitCoffre.lancer(screen, plan(8)) : "debounce après transfert";
        setup(1000, 63);
        assert RetraitCoffre.lancer(screen, plan(130));
        finish();
        assert owned() == 130 && menu.slots.get(0).item.getCount() == 933 && menu.carried.isEmpty();
        setup(64, 63);
        menu.slots.get(2).item = new ItemStack("stone", 64);
        menu.slots.get(3).item = new ItemStack("stone", 64);
        assert RetraitCoffre.lancer(screen, plan(70));
        finish();
        assert owned() == 64 && menu.slots.get(0).item.getCount() == 63 && menu.carried.isEmpty();
        setup(64, 0);
        for (int i = 1; i < 4; i++) menu.slots.get(i).item = new ItemStack("stone", 64);
        assert RetraitCoffre.lancer(screen, plan(4));
        finish();
        assert mc.gameMode.clicks == 0;
        setup(64, 0);
        menu.carried = new ItemStack("stone", 1);
        assert !RetraitCoffre.lancer(screen, plan(4));
        assert mc.gameMode.clicks == 0;
        setup(64, 0);
        assert RetraitCoffre.lancer(screen, plan(4));
        mc.screen = null;
        finish();
        assert mc.gameMode.clicks == 0;
        setup(64, 0);
        assert RetraitCoffre.lancer(screen, plan(4));
        menu.slots.get(0).item = new ItemStack("stone", 64);
        finish();
        assert mc.gameMode.clicks == 0;
        setup(1000, 0);
        assert RetraitCoffre.lancer(screen, plan(130));
        tick();
        int deja = owned();
        assert deja == 64;
        menu.slots.get(1).item = new ItemStack("stone", 1); // Clic manuel ou correction du serveur.
        int clicks = mc.gameMode.clicks;
        finish();
        assert mc.gameMode.clicks == clicks;
        setup(64, 0);
        menu.slots.get(0).pickup = false;
        assert plan(4).total() == 0;
        setup(64, 0);
        assert RetraitCoffre.lancer(screen, plan(4));
        mc.gameMode.refuse = true;
        finish();
        assert owned() == 0 && mc.gameMode.clicks == 1;
        setup(2, 0);
        assert RetraitCoffre.lancer(screen, plan(4));
        finish();
        assert owned() == 2 && menu.carried.isEmpty();
        System.out.println(
                "Transferts exacts, grandes piles, double clic, inventaire plein, fermeture, refus et changement de stock : OK");
    }
}
