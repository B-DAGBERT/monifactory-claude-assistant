package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import fr.assistantia.RetraitCoffre;
import fr.assistantia.Theme;
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
        return CoffresArbreEmi.planTransfert(screen);
    }

    /**
     * Remplace le + de craft uniquement lorsque la recette a été ouverte depuis un coffre. Même
     * rendu que le bouton « Tout » du panneau Collecte, dont il partage l'action ; il reste à la
     * taille du bouton d'EMI qu'il remplace.
     */
    public static final class Bouton extends Widget {
        /**
         * Total à prendre, relu au plus deux fois par seconde : le plan n'est pas calculé par
         * image.
         */
        private static long releve;

        private static int total;
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

        private static int total() {
            long maintenant = System.currentTimeMillis();
            if (maintenant - releve > 500) {
                total = planArbre(EmiApi.getHandledScreen()).total();
                releve = maintenant;
            }
            return total;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            if (!fr.assistantia.RechercheEmi.actif()) return;
            // Grisé pendant un retrait ou sans objet du scan ici, comme le bouton du panneau.
            Theme.bouton(
                    g,
                    Minecraft.getInstance().font,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    "↓",
                    Theme.Genre.PRINCIPAL,
                    !RetraitCoffre.occupe() && total() > 0,
                    bounds.contains(mouseX, mouseY),
                    false);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int x, int y) {
            if (!fr.assistantia.RechercheEmi.actif()) return List.of();
            if (RetraitCoffre.occupe()) return List.of(texte("Retrait en cours…"));
            var p = planArbre(EmiApi.getHandledScreen());
            return List.of(
                    texte(
                            p.total() > 0
                                    ? "Tout récupérer · " + p.total() + " objets du scan actif"
                                    : "Aucun objet du scan à prendre ici"));
        }

        private ClientTooltipComponent texte(String s) {
            return ClientTooltipComponent.create(Component.literal(s).getVisualOrderText());
        }

        @Override
        public boolean mouseClicked(int x, int y, int button) {
            if (button != 0 || !bounds.contains(x, y)) return false;
            if (!fr.assistantia.RechercheEmi.actif()) return true;
            var screen = EmiApi.getHandledScreen();
            var plan = planArbre(screen);
            releve = 0;
            if (screen != null && plan.total() > 0 && RetraitCoffre.lancer(screen, plan))
                Minecraft.getInstance().setScreen(screen);
            return true;
        }
    }
}
