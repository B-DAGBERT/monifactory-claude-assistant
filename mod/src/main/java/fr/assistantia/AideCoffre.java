package fr.assistantia;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Retrait de l'arbre EMI courant, indépendant de l'objectif et du bouton de repérage. */
public final class AideCoffre {
    private static final int PAGE = 8, ICONE = 20, LARGEUR = 232;
    private static AbstractContainerScreen<?> ecran;
    private static Button transferer, tout, avant, apres;
    private static RetraitCoffre.Plan plan = new RetraitCoffre.Plan(List.of(), 0);
    private static List<ItemStack> objets = List.of();
    private static ItemStack choisi = ItemStack.EMPTY;
    private static long actualise;
    private static int page, x, y;

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        ecran = null;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !RetraitCoffre.estCoffre(screen.getMenu())) return;
        ecran = screen;
        page = 0;
        actualise = 0;
        objets = List.of();
        choisi = ItemStack.EMPTY;
        x = Math.max(2, Math.min(screen.getGuiLeft(), screen.width - LARGEUR - 2));
        y = Math.max(2, screen.getGuiTop() - 54);
        transferer =
                Button.builder(Component.literal("Transférer les items"), b -> agir(false))
                        .bounds(x, y + 32, 124, 18)
                        .build();
        tout =
                Button.builder(Component.literal("Tout récupérer"), b -> agir(true))
                        .bounds(x + 128, y + 32, 104, 18)
                        .build();
        avant =
                Button.builder(Component.literal("‹"), b -> page = Math.max(0, page - 1))
                        .bounds(x + PAGE * ICONE + 4, y + 11, 28, 18)
                        .build();
        apres =
                Button.builder(
                                Component.literal("›"),
                                b -> page = Math.min((objets.size() - 1) / PAGE, page + 1))
                        .bounds(x + PAGE * ICONE + 36, y + 11, 28, 18)
                        .build();
        for (Button b : List.of(transferer, tout, avant, apres)) {
            b.visible = false;
            event.addListener(b);
        }
    }

    public static void vider() {
        plan = new RetraitCoffre.Plan(List.of(), 0);
        objets = List.of();
        choisi = ItemStack.EMPTY;
        page = 0;
        actualise = 0;
        if (transferer != null)
            for (Button bouton : List.of(transferer, tout, avant, apres))
                bouton.visible = bouton.active = false;
    }

    private static void actualiser() {
        plan = Emi.retraitArbre(ecran);
        objets = plan.objets();
        if (objets.stream().noneMatch(i -> ItemStack.isSameItemSameTags(i, choisi)))
            choisi = objets.isEmpty() ? ItemStack.EMPTY : objets.get(0);
        page = Math.min(page, Math.max(0, (objets.size() - 1) / PAGE));
        actualise = System.currentTimeMillis();
    }

    private static void agir(boolean tous) {
        if (!Emi.arbreActif()) {
            vider();
            return;
        }
        if (RetraitCoffre.occupe() || ecran == null) return;
        // Le clic utilise l'arbre et les slots actuels, jamais le plan mis en cache pour le rendu.
        ItemStack selection = choisi.copy();
        actualiser();
        RetraitCoffre.Plan retrait = tous ? plan : plan.pour(selection);
        if (retrait.total() > 0) RetraitCoffre.lancer(ecran, retrait);
    }

    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != ecran || transferer == null) return;
        if (!Emi.arbreActif()) {
            vider();
            return;
        }
        boolean occupe = RetraitCoffre.occupe();
        if (!occupe && System.currentTimeMillis() - actualise >= 400) actualiser();
        transferer.visible = tout.visible = !objets.isEmpty();
        transferer.active = !occupe && plan.pour(choisi).total() > 0;
        tout.active = !occupe && plan.total() > 0;
        avant.visible = apres.visible = objets.size() > PAGE;
        avant.active = !occupe && page > 0;
        apres.active = !occupe && (page + 1) * PAGE < objets.size();
        transferer.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Transférer "
                                        + plan.pour(choisi).total()
                                        + " objets sélectionnés")));
        tout.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Récupérer les "
                                        + plan.total()
                                        + " objets manquants de l’arbre disponibles ici")));
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (event.getScreen() != ecran || objets.isEmpty()) return;
        if (!Emi.arbreActif()) {
            vider();
            return;
        }
        var g = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        // Les boutons et les flèches sont rendus par Minecraft, hors de ces deux fonds.
        g.fill(x - 3, y - 3, x + LARGEUR + 3, y + 10, 0xD00D1013);
        g.fill(x - 3, y + 10, x + PAGE * ICONE + 2, y + 30, 0xD00D1013);
        String titre =
                RetraitCoffre.occupe()
                        ? "Transfert…"
                        : "Arbre EMI · " + plan.total() + " objets disponibles";
        g.drawString(font, font.plainSubstrByWidth(titre, LARGEUR), x, y, Hud.OR, false);
        for (int n = page * PAGE; n < Math.min(objets.size(), (page + 1) * PAGE); n++) {
            ItemStack item = objets.get(n);
            int ix = x + (n % PAGE) * ICONE, iy = y + 11;
            g.fill(
                    ix - 1,
                    iy - 1,
                    ix + 17,
                    iy + 17,
                    ItemStack.isSameItemSameTags(item, choisi) ? 0x90FFC94A : 0x30FFFFFF);
            ItemStack icone = item.copy();
            icone.setCount(1);
            g.renderItem(icone, ix, iy);
            if (surIcone(event.getMouseX(), event.getMouseY(), n))
                g.renderComponentTooltip(
                        font,
                        List.of(
                                item.getHoverName(),
                                Component.literal(
                                        plan.pour(item).total()
                                                + " à récupérer · Clic : sélectionner")),
                        event.getMouseX(),
                        event.getMouseY());
        }
    }

    private static boolean surIcone(double mx, double my, int n) {
        int ix = x + (n % PAGE) * ICONE;
        return mx >= ix - 1 && mx < ix + 17 && my >= y + 10 && my < y + 28;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() != ecran || objets.isEmpty()) return;
        if (!Emi.arbreActif()) {
            vider();
            return;
        }
        for (Button b : List.of(transferer, tout, avant, apres))
            if (b.visible && b.isMouseOver(event.getMouseX(), event.getMouseY())) {
                if (b.active)
                    b.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
                event.setCanceled(true);
                return;
            }
        for (int n = page * PAGE; n < Math.min(objets.size(), (page + 1) * PAGE); n++)
            if (surIcone(event.getMouseX(), event.getMouseY(), n)) {
                if (event.getButton() == 0 && !RetraitCoffre.occupe()) choisi = objets.get(n);
                event.setCanceled(true);
                return;
            }
    }
}
