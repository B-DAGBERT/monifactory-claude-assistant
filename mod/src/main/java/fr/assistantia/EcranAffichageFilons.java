package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EcranAffichageFilons extends Screen {
    private final Screen parent;
    private EditBox nombre;
    private Button appliquer, force;
    private int intensite;
    private String brouillon, erreur = "";

    public EcranAffichageFilons(Screen parent) {
        super(Component.literal("Affichage des filons"));
        this.parent = parent;
        intensite = AffichageFilons.style().intensite();
        brouillon = String.valueOf(AffichageFilons.style().maximum());
    }

    private int valeur() {
        try {
            return Integer.parseInt(brouillon);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    protected void init() {
        int l = Math.min(360, width - 24), x = (width - l) / 2;
        nombre =
                addRenderableWidget(
                        new EditBox(
                                font, x, 62, l, 20, Component.literal("Blocs surlignés maximum")));
        nombre.setMaxLength(4);
        nombre.setFilter(s -> s.isEmpty() || s.matches("[0-9]{1,4}"));
        nombre.setValue(brouillon);
        nombre.setResponder(s -> brouillon = s);
        int[] presets = {64, 192, 512, 1024};
        int w = (l - 18) / 4;
        for (int i = 0; i < presets.length; i++) {
            final int n = presets[i];
            addRenderableWidget(
                    Button.builder(
                                    Component.literal(String.valueOf(n)),
                                    b -> nombre.setValue(String.valueOf(n)))
                            .bounds(x + i * (w + 6), 88, w, 20)
                            .build());
        }
        force =
                addRenderableWidget(
                        Button.builder(Component.literal(""), b -> intensite = (intensite + 1) % 3)
                                .bounds(x, 115, l, 20)
                                .build());
        appliquer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Appliquer"),
                                        b -> {
                                            if (valeur() < 1 || valeur() > 4096) return;
                                            if (AffichageFilons.sauver(
                                                    new StyleFilons(valeur(), intensite)))
                                                onClose();
                                            else erreur = "Enregistrement impossible · réessaie";
                                        })
                                .bounds(x, height - 28, (l - 6) / 2, 20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(x + (l + 6) / 2, height - 28, (l - 6) / 2, 20)
                        .build());
        tick();
    }

    @Override
    public void tick() {
        nombre.tick();
        appliquer.active = valeur() >= 1 && valeur() <= 4096;
        force.setMessage(Component.literal("Intensité : " + new StyleFilons(1, intensite).nom()));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        g.drawCenteredString(font, "Blocs surlignés maximum · 1 à 4 096", width / 2, 43, Hud.BLANC);
        g.drawCenteredString(
                font, "Les blocs les plus proches sont prioritaires.", width / 2, 151, Hud.GRIS);
        g.drawCenteredString(
                font, "Les scans et les relevés sont conservés.", width / 2, 166, Hud.GRIS);
        if (!erreur.isEmpty())
            g.drawCenteredString(font, erreur, width / 2, height - 45, Hud.JAUNE);
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
