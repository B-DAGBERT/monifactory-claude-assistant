package fr.assistantia;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Dans un établi (ou tout écran qu'EMI sait remplir), au-dessus de l'interface (les côtés sont pris par EMI) :
 * la fabrication que le plan attend et que tu peux faire tout de suite, avec « Remplir » (une fois) et « Tout »
 * (le nombre qu'il faut), exactement comme le bouton « + » d'EMI. Rien ne s'affiche quand il n'y a rien d'utile
 * à proposer.
 */
public final class AideEcran {
    private static Button remplir, tout;
    private static AbstractContainerScreen<?> ecranActuel;
    private static Object recette;          // recette EMI proposée
    private static String cleRecette = "";  // ce sur quoi elle a été calculée (recette du plan + écran)
    private static int fois = 1;

    private AideEcran() {
    }

    @SubscribeEvent
    public static void initialisation(ScreenEvent.Init.Post event) {
        Screen ecran = event.getScreen();
        if (!(ecran instanceof AbstractContainerScreen<?> conteneur) || ecran instanceof CreativeModeInventoryScreen
                || !Emi.present()) {
            ecranActuel = null;
            return;
        }
        ecranActuel = conteneur;
        cleRecette = "";
        int x = conteneur.getGuiLeft();
        int y = Math.max(16, conteneur.getGuiTop() - 20); // l'en-tête (icône + nom) se dessine juste au-dessus
        remplir = Button.builder(Component.literal("Remplir"), b -> agir(1)).bounds(x, y, 70, 18).build();
        tout = Button.builder(Component.literal("Tout"), b -> agir(fois)).bounds(x + 74, y, 80, 18).build();
        remplir.visible = tout.visible = false;
        event.addListener(remplir);
        event.addListener(tout);
    }

    private static void agir(int nombre) {
        if (recette != null && ecranActuel != null) {
            Emi.remplir(recette, ecranActuel, nombre);
        }
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (event.getScreen() != ecranActuel || remplir == null) {
            return;
        }
        JsonObject r = Pont.partie("recette");
        String item = Pont.texte(r, "item");
        String cle = Pont.texte(r, "id") + "|" + item;
        if (!cle.equals(cleRecette)) { // nouvelle fabrication proposée : on cherche la recette EMI une seule fois
            cleRecette = cle;
            recette = item.isEmpty() ? null : Emi.recette(Pont.texte(r, "id"), Pont.objet(item), ecranActuel);
            fois = (int) Math.max(1, Pont.nombre(r, "fois", 1));
        }
        boolean utile = recette != null;
        remplir.visible = tout.visible = utile;
        if (!utile) {
            return;
        }
        tout.setMessage(Component.literal("Tout (×" + fois + ")"));
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int x = remplir.getX(), y = remplir.getY() - 16;
        ItemStack produit = Pont.objet(item);
        g.fill(x - 2, y - 1, x + 156, y + 15, 0xC80D1013); // en-tête seulement : les boutons restent visibles
        g.renderItem(produit, x, y);
        g.drawString(font, font.plainSubstrByWidth(Pont.texte(r, "nom"), 136), x + 18, y + 4, Hud.OR, false);
    }
}
