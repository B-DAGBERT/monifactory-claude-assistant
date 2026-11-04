package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import fr.assistantia.AideCoffre;
import fr.assistantia.Pont;
import fr.assistantia.RetraitCoffre;
import dev.emi.emi.api.EmiApi;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.List;

/** Chargé uniquement par EMI. Aucun lien obligatoire depuis le point d'entrée Forge. */
@EmiEntrypoint
public final class AssistantEmiPlugin implements EmiPlugin {
    private static boolean branche;
    private static boolean compatibiliteSignalee;
    private static Field pages, widgetsDeGroupe, recetteDeGroupe;

    @Override
    public void register(EmiRegistry registry) {
        brancher();
        // EMI range ses listes d'objets autour du panneau de retrait au lieu de passer dessous.
        registry.addGenericExclusionArea(
                (screen, zones) -> {
                    var zone = AideCoffre.zoneEmi(screen);
                    if (zone == null) zone = MachinesEmi.zoneEmi(screen);
                    if (zone != null)
                        zones.accept(
                                new Bounds(zone.x(), zone.y(), zone.largeur(), zone.hauteur()));
                });
    }

    private static synchronized void brancher() {
        if (!branche) {
            MinecraftForge.EVENT_BUS.register(AssistantEmiPlugin.class);
            MinecraftForge.EVENT_BUS.register(CoffresArbreEmi.class);
            MinecraftForge.EVENT_BUS.register(CycleArbreEmi.class);
            MinecraftForge.EVENT_BUS.register(AideCoffre.class);
            MinecraftForge.EVENT_BUS.register(MachinesEmi.class);
            MinecraftForge.EVENT_BUS.register(EnvoiMachine.class);
            branche = true;
        }
    }

    /** Remplace le remplissage de recette par un retrait lorsque l'écran parent est un coffre. */
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
                boolean coffre =
                        container != null
                                && RetraitCoffre.estCoffre(container.getMenu())
                                && Pont.retraitCoffres();
                if (coffre) {
                    for (int i = 0; i < children.size(); i++) {
                        Widget child = children.get(i);
                        if (child.getClass()
                                .getName()
                                .equals("dev.emi.emi.api.widget.RecipeFillButtonWidget"))
                            children.set(i, new RecuperationEmi.Bouton(child));
                    }
                }
                if (!coffre) {
                    for (int i = 0; i < children.size(); i++)
                        if (children.get(i) instanceof RecuperationEmi.Bouton retrait)
                            children.set(i, retrait.original());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!compatibiliteSignalee) {
                LogUtils.getLogger()
                        .warn("Assistant : bouton Coffres EMI indisponible avec cette version", e);
                compatibiliteSignalee = true;
            }
        }
    }
}
