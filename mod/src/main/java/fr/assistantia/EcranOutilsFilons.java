package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Entretien occasionnel, séparé de la liste des filons. */
public final class EcranOutilsFilons extends Screen {
    private final Screen parent;
    private Button masquer, vider;

    public EcranOutilsFilons(Screen parent) {
        super(Component.literal("Options des filons"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int l = Math.min(300, width - 24), x = (width - l) / 2;
        masquer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Tout masquer"),
                                        b -> Filons.afficher("", false))
                                .bounds(x, 55, l, 20)
                                .build());
        vider =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Vider les relevés"),
                                        b -> Filons.viderReleves())
                                .bounds(x, 85, l, 20)
                                .build());
        vider.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Libérer les blocs relevés. Les filons du journal GregTech sont conservés. Arrête le scan avant de vider.")));
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(x, height - 28, l, 20)
                        .build());
        tick();
    }

    @Override
    public void tick() {
        masquer.active = !Filons.affiche().isEmpty() && Filons.affichageDisponible();
        vider.active =
                !Filons.monde().isEmpty()
                        && !Filons.scanActif()
                        && Filons.boutonScanDisponible()
                        && !Filons.memoire().blocs().isEmpty();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 16, Hud.OR);
        g.drawCenteredString(
                font,
                Filons.memoire().blocs().size() + " / 100 000 blocs mémorisés",
                width / 2,
                120,
                Hud.GRIS);
        String message = Filons.message();
        if (!message.isEmpty())
            g.drawCenteredString(
                    font, font.plainSubstrByWidth(message, width - 24), width / 2, 141, Hud.GRIS);
        super.render(g, mx, my, delta);
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
