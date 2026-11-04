package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.PlanMachines;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Déclarer un travail déjà lancé, sans déplacer ni valider artificiellement ses résultats. */
public final class DeclarerFabricationEcran extends Screen {
    private final Screen parent;
    private final EmiRecipe recette;
    private EditBox nombre;
    private Button confirmer;
    private String erreur = "";

    public DeclarerFabricationEcran(Screen parent, EmiRecipe recette) {
        super(Component.literal("En cours de craft"));
        this.parent = parent;
        this.recette = recette;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 2;
        nombre = new EditBox(font, x, y, 80, 20, Component.literal("Nombre de fabrications"));
        nombre.setMaxLength(7);
        nombre.setFilter(s -> s.isEmpty() || s.matches("[0-9]{1,7}"));
        nombre.setValue("1");
        addRenderableWidget(nombre);
        addRenderableWidget(
                Button.builder(
                                Component.literal("Tout le reste"),
                                b ->
                                        nombre.setValue(
                                                String.valueOf(MachinesEmi.declarables(recette))))
                        .bounds(x + 86, y, 114, 20)
                        .build());
        confirmer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Confirmer"),
                                        b -> {
                                            if (MachinesEmi.declarer(recette, quantite()))
                                                minecraft.setScreen(parent);
                                            else erreur = "Besoins modifiés · vérifie la quantité";
                                        })
                                .bounds(x, y + 66, 97, 20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(x + 103, y + 66, 97, 20)
                        .build());
        setInitialFocus(nombre);
        tick();
    }

    private int quantite() {
        try {
            return Integer.parseInt(nombre.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void tick() {
        if (confirmer != null)
            confirmer.active = quantite() > 0 && quantite() <= MachinesEmi.declarables(recette);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        int y = height / 2;
        g.drawCenteredString(font, title, width / 2, y - 64, 0xFFFFC94A);
        String nom =
                recette.getOutputs().isEmpty()
                        ? "Recette"
                        : recette.getOutputs().get(0).getName().getString();
        g.drawCenteredString(
                font, font.plainSubstrByWidth(nom, width - 24), width / 2, y - 45, 0xFFFFFF);
        g.drawCenteredString(
                font,
                "Fabrications déjà lancées (1 à " + MachinesEmi.declarables(recette) + ")",
                width / 2,
                y - 22,
                0xAAAAAA);
        if (!recette.getOutputs().isEmpty()) {
            int n = PlanMachines.produit(recette.getOutputs().get(0).getAmount(), quantite());
            g.drawCenteredString(
                    font,
                    n + " résultat(s) réservés · récupère-les ensuite",
                    width / 2,
                    y + 30,
                    0xFFCC55);
        }
        if (!erreur.isEmpty()) g.drawCenteredString(font, erreur, width / 2, y + 48, 0xFF7777);
        super.render(g, mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
