package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import fr.assistantia.PlanRetrait;
import fr.assistantia.RetraitCoffre;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Entrée optionnelle appelée seulement quand EMI est chargé. */
public final class RecuperationEmi {
    public static RetraitCoffre.Plan plan(
            Object value, AbstractContainerScreen<?> screen, int times) {
        if (!(value instanceof EmiRecipe recipe) || screen == null)
            return new RetraitCoffre.Plan(List.of(), 0);
        List<PlanRetrait.Besoin<ItemStack>> needs = new ArrayList<>();
        for (EmiIngredient ingredient : recipe.getInputs())
            ajouter(needs, ingredient, Math.max(1, times), false);
        for (EmiIngredient ingredient : recipe.getCatalysts()) ajouter(needs, ingredient, 1, true);
        return RetraitCoffre.plan(screen.getMenu(), needs);
    }

    private static void ajouter(
            List<PlanRetrait.Besoin<ItemStack>> needs,
            EmiIngredient ingredient,
            int times,
            boolean catalyst) {
        List<EmiStack> variants =
                ingredient.getEmiStacks().stream()
                        .filter(s -> !s.getItemStack().isEmpty())
                        .toList();
        if (variants.isEmpty())
            return; // Pas de tentative d'extraire un fluide depuis un slot d'objet.
        boolean reusable =
                catalyst
                        || variants.stream()
                                .allMatch(
                                        s ->
                                                s.getItemStack().isDamageableItem()
                                                        || !s.getRemainder().isEmpty()
                                                                && s.getItemStack()
                                                                        .is(
                                                                                s.getRemainder()
                                                                                        .getItemStack()
                                                                                        .getItem()));
        long amount = Math.max(1, ingredient.getAmount());
        int quantity =
                (int) Math.min(1_000_000L, Math.min(1_000_000L, amount) * (reusable ? 1L : times));
        needs.add(
                new PlanRetrait.Besoin<>(
                        stack -> variants.stream().anyMatch(v -> EmiStack.of(stack).isEqual(v)),
                        quantity));
    }

    /** Remplace le + de craft uniquement lorsque la recette a été ouverte depuis un coffre. */
    public static final class Bouton extends Widget {
        private final EmiRecipe recipe;
        private final Bounds bounds;
        private final Widget original;

        public Bouton(EmiRecipe recipe, Widget original) {
            this.recipe = recipe;
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
            var p = plan(recipe, EmiApi.getHandledScreen(), 1);
            return List.of(texte("Récupérer " + p.total() + " objet(s)"));
        }

        private ClientTooltipComponent texte(String s) {
            return ClientTooltipComponent.create(Component.literal(s).getVisualOrderText());
        }

        @Override
        public boolean mouseClicked(int x, int y, int button) {
            if (button != 0 || !bounds.contains(x, y)) return false;
            var screen = EmiApi.getHandledScreen();
            if (screen != null && RetraitCoffre.lancer(screen, plan(recipe, screen, 1)))
                Minecraft.getInstance().setScreen(screen);
            return true;
        }
    }
}
