package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Dans un coffre, une machine ou l'inventaire : ce que tu as demandé à chercher pulse en cyan («
 * trouve-moi mes torches ») ou les objets de la recherche EMI explicite pulsent en cyan, la couleur
 * de recherche du thème.
 */
public final class Surbrillance {
    private static final int CYAN = Theme.CYAN & 0xFFFFFF;

    private Surbrillance() {}

    @SubscribeEvent
    public static void apresRendu(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran)) {
            return;
        }
        boolean recherche = RechercheEmi.actif() || Pont.rechercheActive();
        if (!recherche) return;
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
            if (RechercheEmi.actif()
                    ? RechercheEmi.cherche(id.toString())
                    : recherche && Pont.cherche(id.toString())) {
                g.fill(x, y, x + 16, y + 16, alpha | CYAN);
                g.renderOutline(x - 1, y - 1, 18, 18, Theme.CYAN);
            }
        }
        g.pose().popPose();
    }
}
