package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dans un établi (ou tout écran qu'EMI sait remplir), au-dessus de l'interface (les côtés sont pris par EMI) : les
 * fabrications de l'objectif que tu peux faire ici et maintenant, la plus utile d'abord (celle de l'étape en cours,
 * puis ce qu'elle demande, puis la suite de l'objectif). Une icône par fabrication (clic : la choisir), puis
 * « Remplir » (une fois) ou « Tout » (ce qu'il en faut, dans la limite de ce que ton inventaire permet), comme le
 * bouton « + » d'EMI. Les recettes de machines n'y sont pas : l'établi ne sait pas les faire. Rien ne s'affiche
 * quand aucune fabrication de l'objectif n'est possible ici.
 */
public final class AideEcran {
    private static final int MAX = 8, ICONE = 20;

    /** Une fabrication possible ici : la recette EMI, ce qu'elle fait, combien il en faut, combien sont possibles. */
    private record Proposition(Object recette, ItemStack produit, String nom, int fois, int possibles) {
        int aFaire() {
            return Math.max(1, Math.min(fois, possibles));
        }
    }

    private static Button remplir, tout;
    private static AbstractContainerScreen<?> ecranActuel;
    private static List<Proposition> propositions = List.of();
    private static Item choisi;           // gardé quand la liste change (après une fabrication)
    private static String cle = "";       // propositions de l'assistant + inventaire sur lesquels la liste est calculée
    private static int x, y;              // coin de la zone : nom, icônes, puis boutons

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
        cle = "";
        x = conteneur.getGuiLeft();
        y = Math.max(2, conteneur.getGuiTop() - 52);
        remplir = Button.builder(Component.literal("Remplir"), b -> agir(false)).bounds(x, y + 32, 60, 18).build();
        tout = Button.builder(Component.literal("Tout"), b -> agir(true)).bounds(x + 64, y + 32, 90, 18).build();
        remplir.visible = tout.visible = false;
        event.addListener(remplir);
        event.addListener(tout);
    }

    private static Proposition choisie() {
        for (Proposition p : propositions) {
            if (p.produit().getItem() == choisi) {
                return p;
            }
        }
        return propositions.isEmpty() ? null : propositions.get(0);
    }

    private static void agir(boolean toutFaire) {
        Proposition p = choisie();
        if (p != null && ecranActuel != null) {
            Emi.remplir(p.recette(), ecranActuel, toutFaire ? p.aFaire() : 1);
            cle = ""; // la grille et l'inventaire changent : on recalcule
        }
    }

    /** Ce que la grille et l'inventaire contiennent (sans la case du résultat), par objet. */
    private static Map<Item, Integer> stock(AbstractContainerScreen<?> ecran) {
        Map<Item, Integer> stock = new HashMap<>();
        for (Slot slot : ecran.getMenu().slots) {
            if (!(slot instanceof ResultSlot) && slot.hasItem()) {
                stock.merge(slot.getItem().getItem(), slot.getItem().getCount(), Integer::sum);
            }
        }
        return stock;
    }

    /** Les fabrications proposées par l'assistant que cet écran sait remplir et que le stock permet. */
    private static List<Proposition> calculer(AbstractContainerScreen<?> ecran, Map<Item, Integer> stock) {
        List<Proposition> liste = new ArrayList<>();
        for (JsonElement e : Pont.liste(Pont.vue(), "fabrications")) {
            JsonObject f = e.getAsJsonObject();
            ItemStack produit = Pont.objet(Pont.texte(f, "item"));
            if (produit.isEmpty()) {
                continue;
            }
            for (Object recette : Emi.recettes(Pont.texte(f, "id"), produit, ecran)) {
                int possibles = Emi.possibles(recette, stock);
                if (possibles > 0) { // la première façon de la faire que l'inventaire permet
                    liste.add(new Proposition(recette, produit, Pont.texte(f, "nom"), (int) Pont.nombre(f, "fois", 1),
                            possibles));
                    break;
                }
            }
            if (liste.size() >= MAX) {
                break;
            }
        }
        return liste;
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (event.getScreen() != ecranActuel || remplir == null) {
            return;
        }
        Map<Item, Integer> stock = stock(ecranActuel);
        String nouvelleCle = Pont.liste(Pont.vue(), "fabrications") + "|" + stock;
        if (!nouvelleCle.equals(cle)) {
            cle = nouvelleCle;
            propositions = calculer(ecranActuel, stock);
        }
        Proposition p = choisie();
        remplir.visible = tout.visible = p != null;
        if (p == null) {
            return;
        }
        tout.setMessage(Component.literal("Tout (×" + p.aFaire() + ")"));
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int largeur = Math.max(ecranActuel.getXSize(), propositions.size() * ICONE);
        g.fill(x - 3, y - 3, x + largeur + 3, y + 30, 0xC80D1013); // fond du nom et des icônes : les boutons restent visibles
        String encore = p.fois() > p.aFaire() ? " (il en faudra " + p.fois() + ")" : "";
        g.drawString(font, font.plainSubstrByWidth(p.nom() + " · ×" + p.aFaire() + " possible" + encore, largeur),
                x, y, Hud.OR, false);
        Proposition survolee = null;
        for (int i = 0; i < propositions.size(); i++) {
            Proposition q = propositions.get(i);
            int ix = x + i * ICONE, iy = y + 11;
            g.fill(ix - 1, iy - 1, ix + 17, iy + 17, q == p ? 0x90FFC94A : 0x30FFFFFF);
            g.renderItem(q.produit(), ix, iy);
            if (event.getMouseX() >= ix - 1 && event.getMouseX() < ix + 17 && event.getMouseY() >= iy - 1
                    && event.getMouseY() < iy + 17) {
                survolee = q;
            }
        }
        if (survolee != null) {
            g.renderComponentTooltip(font, List.of(Component.literal(survolee.nom()),
                    Component.literal("×" + survolee.aFaire() + " possible maintenant, " + survolee.fois()
                            + " à faire pour l'objectif").withStyle(s -> s.withColor(Hud.GRIS)),
                    Component.literal("Clic : la choisir").withStyle(s -> s.withColor(Hud.BLEU))),
                    event.getMouseX(), event.getMouseY());
        }
    }

    /** Clic sur une icône : cette fabrication devient celle des boutons Remplir et Tout. */
    @SubscribeEvent
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() != ecranActuel) {
            return;
        }
        for (int i = 0; i < propositions.size(); i++) {
            int ix = x + i * ICONE, iy = y + 11;
            if (event.getMouseX() >= ix - 1 && event.getMouseX() < ix + 17 && event.getMouseY() >= iy - 1
                    && event.getMouseY() < iy + 17) {
                choisi = propositions.get(i).produit().getItem();
                event.setCanceled(true);
                return;
            }
        }
    }
}
