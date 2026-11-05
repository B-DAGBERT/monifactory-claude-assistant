package fr.assistantia;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Repérage local des objets portés dans le backpack, y compris fermé. */
public final class EcranSac extends Screen {
    private final Screen parent;
    private final BarreDefilement barre = new BarreDefilement();
    private EditBox recherche;
    private String filtre = "";
    private List<ListeSac.Entree> entrees = List.of();
    private long actualise;

    public EcranSac(Screen parent) {
        super(Component.literal("Dans le backpack"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int l = Math.min(440, width - 24), x = (width - l) / 2;
        recherche =
                addRenderableWidget(
                        new EditBox(font, x, 46, l, 20, Component.literal("Chercher dans le sac")));
        recherche.setMaxLength(100);
        recherche.setHint(Component.literal("Chercher dans le sac…"));
        recherche.setValue(filtre);
        recherche.setResponder(
                s -> {
                    filtre = s;
                    actualise = 0;
                });
        addRenderableWidget(
                Button.builder(Component.literal("Retour à EMI"), b -> onClose())
                        .bounds(x, height - 28, l, 20)
                        .build());
    }

    @Override
    public void tick() {
        recherche.tick();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        boolean disponible = InventaireJoueur.disponible();
        long maintenant = System.currentTimeMillis();
        if (!disponible) {
            entrees = List.of();
            actualise = 0;
        } else if (maintenant - actualise > 250) {
            entrees = ListeSac.lire(InventaireJoueur.sacs(), filtre);
            actualise = maintenant;
        }
        int l = Math.min(440, width - 24), x = (width - l) / 2, haut = 74, bas = height - 38;
        barre.regler(x + l - 4, haut, bas - haut, entrees.size() * 24);
        g.drawCenteredString(font, title, width / 2, 10, Hud.OR);
        g.drawCenteredString(
                font, "Déjà sur toi · comptés dans EMI · retrait manuel", width / 2, 27, Hud.GRIS);
        g.enableScissor(x, haut, x + l - 10, bas);
        ItemTooltip survol = null;
        for (int i = 0; i < entrees.size(); i++) {
            int y = haut + i * 24 - barre.position();
            if (y + 24 <= haut || y >= bas) continue;
            var e = entrees.get(i);
            g.fill(x, y, x + l - 12, y + 22, 0x44325978);
            g.renderItem(e.pile(), x + 3, y + 3);
            String quantite = "× " + e.nombre();
            g.drawString(
                    font,
                    font.plainSubstrByWidth(
                            e.pile().getHoverName().getString(), l - 45 - font.width(quantite)),
                    x + 25,
                    y + 7,
                    Hud.BLANC,
                    false);
            g.drawString(
                    font, quantite, x + l - 18 - font.width(quantite), y + 7, Hud.BLANC, false);
            if (mx >= x && mx < x + l - 12 && my >= Math.max(y, haut) && my < Math.min(y + 22, bas))
                survol = new ItemTooltip(e.pile());
        }
        g.disableScissor();
        if (barre.visible()) {
            g.fill(barre.x(), haut, barre.x() + 4, bas, 0x50333333);
            g.fill(
                    barre.x(),
                    barre.curseur(),
                    barre.x() + 4,
                    barre.curseur() + barre.taille(),
                    0xBBAAAAAA);
        }
        if (entrees.isEmpty())
            g.drawCenteredString(
                    font,
                    disponible ? "Aucun objet trouvé dans le sac" : "Lecture du sac…",
                    width / 2,
                    haut + 10,
                    Hud.GRIS);
        super.render(g, mx, my, delta);
        if (survol != null) g.renderTooltip(font, survol.pile(), mx, my);
    }

    private record ItemTooltip(net.minecraft.world.item.ItemStack pile) {}

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        barre.defiler(delta);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        return barre.cliquer(x, y, bouton) || super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        return barre.glisser(y) || super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        barre.relacher();
        return super.mouseReleased(x, y, bouton);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
