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

    /** Un arbre libre, sans objectif assistant : sélection, retrait global, puis recalcul. */
    private static void treeSelection() throws Exception {
        setup(64, 2);
        Slot copper = new Slot(new Object(), 4);
        copper.item = new ItemStack("copper", 64);
        menu.slots.add(copper);
        Slot unrelated = new Slot(new Object(), 5);
        unrelated.item = new ItemStack("stone", 64);
        menu.slots.add(unrelated);
        var root =
                new ArbreCollecte.Noeud(
                        java.util.Set.of(),
                        1,
                        1,
                        false,
                        List.of(
                                new ArbreCollecte.Noeud(
                                        java.util.Set.of("tin"), 3, 1, false, List.of()),
                                new ArbreCollecte.Noeud(
                                        java.util.Set.of("copper"), 4, 1, false, List.of())));
        var besoins =
                ArbreCollecte.ingredients(root, 3).stream()
                        .map(
                                i ->
                                        new PlanRetrait.Besoin<ItemStack>(
                                                stack -> i.variantes().contains(stack.id),
                                                i.quantite()))
                        .toList();
        var all = RetraitCoffre.plan(menu, besoins);
        assert all.total() == 19 : "9 étains moins 2 déjà possédés, 12 cuivres";
        assert all.objets().size() == 2 : "le sélecteur exclut les objets sans rapport";
        assert all.pour(new ItemStack("stone", 1)).total() == 0;
        assert RetraitCoffre.lancer(screen, all.pour(new ItemStack("tin", 1)));
        finish();
        assert owned() == 9 && copper.item.getCount() == 64;
        var remaining = RetraitCoffre.plan(menu, besoins);
        assert remaining.total() == 12 && remaining.objets().size() == 1;
        var debounce = RetraitCoffre.class.getDeclaredField("dernierClic");
        debounce.setAccessible(true);
        debounce.setLong(null, 0);
        assert RetraitCoffre.lancer(screen, remaining);
        finish();
        assert copper.item.getCount() == 52 && unrelated.item.getCount() == 64;
        assert RetraitCoffre.plan(menu, besoins).total() == 0;
        assert RetraitCoffre.plan(menu, List.of()).objets().isEmpty()
                : "aucun arbre, aucun retrait";
        var other =
                List.of(new PlanRetrait.Besoin<ItemStack>(stack -> stack.id.equals("stone"), 5));
        assert RetraitCoffre.plan(menu, other).total() == 5 : "changer d’arbre change les besoins";
    }

    private static void componentBeforeMaterials() throws Exception {
        setup(64, 0);
        Slot casing = new Slot(new Object(), 4);
        casing.item = new ItemStack("casing", 1);
        menu.slots.add(casing);
        var raw = new ArbreCollecte.Noeud(java.util.Set.of("tin"), 8, 1, false, List.of());
        var component =
                new ArbreCollecte.Noeud(java.util.Set.of("casing"), 1, 1, false, List.of(raw));
        var root =
                new ArbreCollecte.Noeud(
                        java.util.Set.of("machine"), 1, 1, false, List.of(component));
        var retrait =
                RetraitCoffre.planArbre(menu, root, 1, ids -> stack -> ids.contains(stack.id));
        assert retrait.total() == 1 && retrait.objets().get(0).id.equals("casing");
        assert RetraitCoffre.lancer(screen, retrait);
        finish();
        assert casing.item.isEmpty() && menu.slots.get(0).item.getCount() == 64;
        assert RetraitCoffre.planArbre(menu, root, 1, ids -> stack -> ids.contains(stack.id))
                        .total()
                == 0;
    }

    private static void synchronization() throws Exception {
        setup(1000, 0);
        menu.slots.get(3).item = new ItemStack("electric_drill", 1);
        assert RetraitCoffre.lancer(screen, plan(120));
        for (int i = 0; i < 1000 && RetraitCoffre.occupe(); i++) {
            menu.slots.get(3).item.tag++; // Charge électrique actualisée par le serveur.
            tick();
        }
        assert owned() == 120 && !RetraitCoffre.occupe()
                : "ne pas surveiller le NBT des outils sans rapport";

        setup(1000, 0);
        assert RetraitCoffre.lancer(screen, plan(130));
        tick();
        var sourceConfirmee = menu.slots.get(0).item.copy();
        var destinationConfirmee = menu.slots.get(1).item.copy();
        int clicks = mc.gameMode.clicks;
        menu.slots.get(0).item = new ItemStack("tin", 970);
        menu.slots.get(1).item = new ItemStack("tin", 30);
        menu.carried = new ItemStack("tin", 34); // Paquet intermédiaire reçu avec retard.
        for (int i = 0; i < 15; i++) tick();
        assert RetraitCoffre.occupe() && mc.gameMode.clicks == clicks
                : "attendre sans renvoyer ni doubler les clics";
        menu.slots.get(0).item = sourceConfirmee;
        menu.slots.get(1).item = destinationConfirmee;
        menu.carried = new ItemStack("", 0);
        finish();
        assert owned() == 130 && menu.slots.get(0).item.getCount() == 870;

        setup(64, 0);
        assert RetraitCoffre.lancer(screen, plan(50));
        finish();
        assert owned() == 50 && menu.slots.get(0).item.getCount() == 14;
        assert mc.gameMode.clicks == 16 : "rendre 14 objets au lieu d'en poser 50 un par un";

        setup(1000, 0);
        assert RetraitCoffre.lancer(screen, plan(130));
        tick();
        clicks = mc.gameMode.clicks;
        menu.slots.get(0).item = new ItemStack("tin", 1000);
        menu.slots.get(1).item = new ItemStack("", 0); // Refus durable : ne jamais relancer seul.
        finish();
        assert owned() == 0 && mc.gameMode.clicks == clicks;

        setup(64, 0);
        assert RetraitCoffre.lancer(screen, plan(10));
        menu.slots.get(2).item = new ItemStack("tin", 10);
        finish();
        assert mc.gameMode.clicks == 0 : "un nouvel apport de l'objet demandé reste surveillé";
    }

    public static void main(String[] args) throws Exception {
        setup(64, 2);
        var selection = java.util.Map.of("tin", 3);
        var scan =
                RetraitCoffre.planRecherche(
                        menu,
                        stocks -> MemoireCollecte.retraitSelection(stocks, selection),
                        s -> s.id);
        assert scan.total() == 3 && scan.lots().get(0).source() == menu.slots.get(0)
                : "le scan a déjà déduit l'inventaire, ne pas le déduire une seconde fois";
        assert RetraitCoffre.lancer(screen, scan);
        finish();
        assert owned() == 5;
        menu.slots.get(0).item.tag = 7;
        var exact =
                RetraitCoffre.planRecherche(
                        menu,
                        stocks -> MemoireCollecte.retraitSelection(stocks, selection),
                        s -> s.tag == 42 ? s.id : "");
        assert exact.total() == 0
                : "le quota du scan ne rend pas une variante NBT incompatible acceptable";

        synchronization();
        treeSelection();
        componentBeforeMaterials();
        setup(1000, 0);
        assert RetraitCoffre.lancer(screen, plan(130));
        tick();
        int avantPause = owned(), clicsPause = mc.gameMode.clicks;
        RetraitCoffre.arreter();
        for (int i = 0; i < 30; i++) tick();
        assert !RetraitCoffre.occupe() && owned() == avantPause;
        assert mc.gameMode.clicks == clicsPause && menu.carried.isEmpty()
                : "pause sans déplacement supplémentaire ni objet perdu";

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
        setup(1000, 2);
        var special = new net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase(menu);
        mc.screen = special;
        mc.player.lastMessage = "";
        assert RetraitCoffre.lancer(special, plan(5));
        finish();
        assert owned() == 5 && menu.slots.get(0).item.getCount() == 997;
        assert special.customClicks > 0 && special.customClicks == mc.gameMode.clicks;
        assert mc.player.lastMessage.isEmpty()
                : "pas de message explicatif lors d'un transfert réussi";
        System.out.println(
                "Transferts exacts, grandes piles, double clic, inventaire plein, fermeture, refus et changement de stock : OK");
    }
}
