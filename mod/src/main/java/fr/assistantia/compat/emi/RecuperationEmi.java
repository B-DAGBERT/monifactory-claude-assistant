package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import fr.assistantia.RetraitCoffre;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

/** Entrée optionnelle appelée seulement quand EMI est chargé. */
public final class RecuperationEmi {
    public static RetraitCoffre.Plan planArbre(AbstractContainerScreen<?> screen) {
        if (screen == null) return new RetraitCoffre.Plan(List.of(), 0);
        return RetraitCoffre.plan(screen.getMenu(), CoffresArbreEmi.besoinsTransfert());
    }

    /** Remplace le + de craft uniquement lorsque la recette a été ouverte depuis un coffre. */
    public static final class Bouton extends Widget {
        private final Bounds bounds;
        private final Widget original;

        public Bouton(Widget original) {
            this.original = original;
            this.bounds = original.getBounds();
        }

        public Widget original() {
            return original;
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.right(),
                    bounds.bottom(),
                    bounds.contains(mouseX, mouseY) ? 0xFF417486 : 0xFF293C44);
            g.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0xFF40E0FF);
            g.drawCenteredString(
                    Minecraft.getInstance().font,
                    RetraitCoffre.occupe() ? "…" : "↓",
                    bounds.x() + bounds.width() / 2,
                    bounds.y() + 2,
                    0xFFFFFFFF);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int x, int y) {
            var p = planArbre(EmiApi.getHandledScreen());
            return List.of(
                    texte(
                            p.total() > 0
                                    ? "Tout récupérer · " + p.total() + " objets de l’arbre EMI"
                                    : "Aucun objet manquant de l’arbre EMI dans ce coffre"));
        }

        private ClientTooltipComponent texte(String s) {
            return ClientTooltipComponent.create(Component.literal(s).getVisualOrderText());
        }

        @Override
        public boolean mouseClicked(int x, int y, int button) {
            if (button != 0 || !bounds.contains(x, y)) return false;
            var screen = EmiApi.getHandledScreen();
            if (screen != null && RetraitCoffre.lancer(screen, planArbre(screen)))
                Minecraft.getInstance().setScreen(screen);
            return true;
        }
    }
}
