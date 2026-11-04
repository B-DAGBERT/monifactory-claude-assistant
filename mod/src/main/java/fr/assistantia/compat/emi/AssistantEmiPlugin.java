package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import fr.assistantia.RechercheEmi;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.RetraitCoffre;
import dev.emi.emi.api.EmiApi;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Chargé uniquement par EMI. Aucun lien obligatoire depuis le point d'entrée Forge. */
@EmiEntrypoint
public final class AssistantEmiPlugin implements EmiPlugin {
    private static boolean branche;
    private static boolean compatibiliteSignalee;
    private static Field pages, widgetsDeGroupe, recetteDeGroupe;

    @Override
    public void register(EmiRegistry registry) {
        brancher();
        registry.addRecipeDecorator(
                (recipe, widgets) -> {
                    if (recipe.getInputs().isEmpty() && recipe.getCatalysts().isEmpty()) return;
                    // EMI 1.1 réserve 10 px entre deux recettes. Ne recouvre aucun slot ni bouton
                    // +.
                    widgets.add(
                            new Coffres(
                                    recipe,
                                    Math.max(0, widgets.getWidth() - 62),
                                    widgets.getHeight() + 1));
                });
    }

    private static synchronized void brancher() {
        if (!branche) {
            MinecraftForge.EVENT_BUS.register(AssistantEmiPlugin.class);
            branche = true;
        }
    }

    /**
     * Le pack peut masquer les décorateurs EMI. Ajouter seulement notre bouton, sans changer sa
     * configuration et sans dupliquer le décorateur lorsqu'il est déjà présent.
     */
    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (!event.getScreen().getClass().getName().equals("dev.emi.emi.screen.RecipeScreen"))
            return;
        try {
            if (pages == null) {
                pages = event.getScreen().getClass().getDeclaredField("currentPage");
                pages.setAccessible(true);
                Class<?> group = Class.forName("dev.emi.emi.screen.WidgetGroup");
                widgetsDeGroupe = group.getField("widgets");
                recetteDeGroupe = group.getField("recipe");
            }
            for (Object group : (List<?>) pages.get(event.getScreen())) {
                EmiRecipe recipe = (EmiRecipe) recetteDeGroupe.get(group);
                if (recipe == null
                        || recipe.getInputs().isEmpty() && recipe.getCatalysts().isEmpty())
                    continue;
                @SuppressWarnings("unchecked")
                List<Widget> children = (List<Widget>) widgetsDeGroupe.get(group);
                var container = EmiApi.getHandledScreen();
                if (container != null && RetraitCoffre.estCoffre(container.getMenu())) {
                    for (int i = 0; i < children.size(); i++) {
                        Widget child = children.get(i);
                        if (child.getClass()
                                .getName()
                                .equals("dev.emi.emi.api.widget.RecipeFillButtonWidget"))
                            children.set(i, new RecuperationEmi.Bouton(recipe, child));
                    }
                }
                if (container == null || !RetraitCoffre.estCoffre(container.getMenu())) {
                    for (int i = 0; i < children.size(); i++)
                        if (children.get(i) instanceof RecuperationEmi.Bouton retrait)
                            children.set(i, retrait.original());
                }
                if (children.stream().anyMatch(w -> w instanceof Coffres)) continue;
                WidgetHolder holder = (WidgetHolder) group;
                holder.add(
                        new Coffres(
                                recipe,
                                Math.max(0, holder.getWidth() - 62),
                                holder.getHeight() + 1));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!compatibiliteSignalee) {
                LogUtils.getLogger()
                        .warn("Assistant : bouton Coffres EMI indisponible avec cette version", e);
                compatibiliteSignalee = true;
            }
        }
    }

    private static final class Coffres extends Widget {
        private final EmiRecipe recipe;
        private final String id;
        private final Bounds bounds;

        Coffres(EmiRecipe recipe, int x, int y) {
            this.recipe = recipe;
            this.id =
                    recipe.getId() != null
                            ? recipe.getId().toString()
                            : recipe.getCategory().getId()
                                    + ":"
                                    + recipe.getInputs()
                                    + ":"
                                    + recipe.getOutputs();
            this.bounds = new Bounds(x, y, 62, 9);
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean selected = RechercheEmi.selectionnee(id),
                    hover = bounds.contains(mouseX, mouseY);
            g.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.right(),
                    bounds.bottom(),
                    selected ? 0xFF174B59 : hover ? 0xFF53616B : 0xFF343D44);
            g.renderOutline(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    selected ? 0xFF40E0FF : 0xFF9AA4AA);
            String text = RechercheEmi.bouton(id);
            var font = Minecraft.getInstance().font;
            g.pose().pushPose();
            g.pose().translate(bounds.x() + bounds.width() / 2F, bounds.y() + 1, 0);
            g.pose().scale(0.75F, 0.75F, 1);
            g.drawString(font, text, -font.width(text) / 2, 0, 0xFFFFFF, false);
            g.pose().popPose();
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            return List.of(
                    ClientTooltipComponent.create(
                            Component.literal(
                                            RechercheEmi.selectionnee(id)
                                                    ? "Arrêter le repérage de cette recette"
                                                    : "Chercher ses ingrédients et outils dans les coffres connus · variantes incluses · sans IA")
                                    .getVisualOrderText()));
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0 || !bounds.contains(mouseX, mouseY)) return false;
            List<MemoireCollecte.Ingredient> besoins = new ArrayList<>();
            List<EmiIngredient> ingredients = new ArrayList<>(recipe.getInputs());
            ingredients.addAll(recipe.getCatalysts());
            for (EmiIngredient ingredient : ingredients) {
                Set<String> ids = new LinkedHashSet<>();
                for (EmiStack stack : ingredient.getEmiStacks()) {
                    ItemStack item = stack.getItemStack();
                    if (item.isEmpty()) continue; // Pas de faux repérage d'un fluide comme objet.
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(item.getItem());
                    if (key != null) ids.add(key.toString());
                }
                besoins.add(
                        new MemoireCollecte.Ingredient(
                                ids,
                                (int) Math.min(1_000_000L, Math.max(1L, ingredient.getAmount()))));
            }
            RechercheEmi.basculer(id, new MemoireCollecte(besoins));
            return true;
        }
    }
}
