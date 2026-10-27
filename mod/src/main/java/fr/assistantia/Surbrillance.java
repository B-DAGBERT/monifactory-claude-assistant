package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * Dans un coffre, une machine ou l'inventaire : ce que tu as demandé à chercher pulse en cyan (« trouve-moi mes
 * torches »), et les objets dont le plan a besoin brillent en vert.
 */
public final class Surbrillance {
    private static final int FOND = 0x5530FF50;
    private static final int CADRE = 0xFF40FF60;
    private static final int CADRE_RECHERCHE = 0xFF40E0FF;

    private Surbrillance() {
    }

    @SubscribeEvent
    public static void apresRendu(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran)) {
            return;
        }
        Set<String> besoins = Pont.besoins();
        boolean recherche = Pont.rechercheActive();
        if (besoins.isEmpty() && !recherche) {
            return;
        }
        int alpha = (int) (0x60 + 0x50 * Math.sin(System.currentTimeMillis() / 220.0)) << 24;
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300); // par-dessus les objets
        for (Slot slot : ecran.getMenu().slots) {
            if (!slot.hasItem()) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(slot.getItem().getItem());
            if (id == null) {
                continue;
            }
            int x = ecran.getGuiLeft() + slot.x;
            int y = ecran.getGuiTop() + slot.y;
            if (recherche && Pont.cherche(id.toString())) {
                g.fill(x, y, x + 16, y + 16, alpha | 0x30D0FF);
                g.renderOutline(x - 1, y - 1, 18, 18, CADRE_RECHERCHE);
            } else if (besoins.contains(id.toString())) {
                g.fill(x, y, x + 16, y + 16, FOND);
                g.renderOutline(x - 1, y - 1, 18, 18, CADRE);
            }
        }
        g.pose().popPose();
    }
}
