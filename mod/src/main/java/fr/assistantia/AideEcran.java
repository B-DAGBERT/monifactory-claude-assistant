package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
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

/** Fabrications possibles dans un établi. Les coffres utilisent exclusivement AideCoffre. */
public final class AideEcran {
    private static final int MAX = 8, ICONE = 20;

    /**
     * Une fabrication possible ici : la recette EMI, ce qu'elle fait, combien il en faut, combien
     * sont possibles.
     */
    public record Proposition(
            Object recette, ItemStack produit, String nom, int fois, int possibles) {
        int aFaire() {
            return Math.max(1, Math.min(fois, possibles));
        }
    }

    private static Button remplir, tout;
    private static AbstractContainerScreen<?> ecranActuel;
    private static List<Proposition> propositions = List.of();
    private static Item choisi; // gardé quand la liste change (après une fabrication)
    private static String cle =
            ""; // propositions de l'assistant + inventaire sur lesquels la liste est calculée
    private static int x, y; // coin de la zone : nom, icônes, puis boutons
    private static long prochainCalcul, dernierAvis;
    private static Method propositionsArbre;
    private static boolean erreurArbreSignalee;

    private AideEcran() {}

    @SubscribeEvent
    public static void initialisation(ScreenEvent.Init.Post event) {
        Screen ecran = event.getScreen();
        if (!(ecran instanceof AbstractContainerScreen<?> conteneur)
                || ecran instanceof CreativeModeInventoryScreen
                || !Emi.present()
                || RetraitCoffre.estCoffre(conteneur.getMenu())
                || conteneur
                        .getMenu()
                        .getClass()
                        .getName()
                        .equals("com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer")) {
            ecranActuel = null;
            return;
        }
        ecranActuel = conteneur;
        cle = "";
        prochainCalcul = 0;
        propositions = List.of();
        x = conteneur.getGuiLeft();
        y = Math.max(2, conteneur.getGuiTop() - 52);
        remplir =
                Button.builder(Component.literal("Remplir"), b -> agir(false))
                        .bounds(x, y + 32, 60, 18)
                        .build();
        tout =
                Button.builder(Component.literal("Tout"), b -> agir(true))
                        .bounds(x + 64, y + 32, 90, 18)
                        .build();
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
        if (ecranActuel == null || !autorise()) return;
        Proposition demandee = choisie();
        if (demandee == null) return;
        // L'arbre ou les réservations peuvent changer depuis la dernière image.
        // Garder la recette cliquée : ne jamais lui substituer le premier nouveau choix.
        propositions = calculer(ecranActuel, stock(ecranActuel));
        Proposition courante = null;
        for (Proposition p : propositions)
            if (p.recette() == demandee.recette()) {
                courante = p;
                break;
            }
        cle = "";
        if (courante == null) {
            informer("Recette modifiée ou ingrédients indisponibles · choix actualisés.");
            return;
        }
        if (!Emi.remplir(courante.recette(), ecranActuel, toutFaire ? courante.aFaire() : 1))
            informer("Remplissage impossible · vérifie les ingrédients et la grille.");
    }

    private static void informer(String message) {
        long maintenant = System.nanoTime();
        if (maintenant - dernierAvis < 2_000_000_000L) return;
        dernierAvis = maintenant;
        var joueur = Minecraft.getInstance().player;
        if (joueur != null) joueur.displayClientMessage(Component.literal(message), true);
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

    private static boolean autorise() {
        for (JsonElement element : Pont.liste(Pont.partie("ecran"), "reglages")) {
            JsonObject reglage = element.getAsJsonObject();
            if ("remplir".equals(Pont.texte(reglage, "cle"))) return Pont.vrai(reglage, "valeur");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Proposition> propositionsArbre(
            AbstractContainerScreen<?> ecran, Map<Item, Integer> stock) {
        try {
            if (propositionsArbre == null)
                propositionsArbre =
                        Class.forName("fr.assistantia.compat.emi.AtelierEmi")
                                .getMethod(
                                        "propositions",
                                        AbstractContainerScreen.class,
                                        Map.class,
                                        int.class);
            return (List<Proposition>) propositionsArbre.invoke(null, ecran, stock, MAX);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (!erreurArbreSignalee) {
                LogUtils.getLogger().warn("Assistant : propositions d’établi EMI indisponibles", e);
                erreurArbreSignalee = true;
            }
            // Ne pas substituer les crafts d'un ancien objectif à un arbre illisible.
            return List.of();
        }
    }

    /** Arbre EMI actif en priorité ; propositions historiques seulement sans arbre actif. */
    private static List<Proposition> calculer(
            AbstractContainerScreen<?> ecran, Map<Item, Integer> stock) {
        if (!autorise()) return List.of();
        List<Proposition> arbre = propositionsArbre(ecran, stock);
        if (arbre != null) return arbre;
        List<Proposition> liste = new ArrayList<>();
        for (JsonElement e : Pont.liste(Pont.vue(), "fabrications")) {
            JsonObject f = e.getAsJsonObject();
            ItemStack produit = Pont.objet(Pont.texte(f, "item"));
            if (produit.isEmpty()) {
                continue;
            }
            for (Object recette : Emi.recettes(Pont.texte(f, "id"), produit, ecran)) {
                int possibles = Emi.possibles(recette, stock);
                if (possibles > 0) {
                    liste.add(
                            new Proposition(
                                    recette,
                                    produit,
                                    Pont.texte(f, "nom"),
                                    (int) Pont.nombre(f, "fois", 1),
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
        long maintenant = System.nanoTime();
        if (!nouvelleCle.equals(cle) || maintenant >= prochainCalcul) {
            propositions = calculer(ecranActuel, stock);
            cle = nouvelleCle;
            // L'arbre, ses réservations et le chargement EMI évoluent sans changer le stock.
            prochainCalcul = maintenant + 250_000_000L;
        }
        Proposition p = choisie();
        remplir.visible = tout.visible = p != null;
        if (p == null) {
            return;
        }
        remplir.setMessage(Component.literal("Remplir"));
        tout.setMessage(Component.literal("Tout (×" + p.aFaire() + ")"));
        remplir.active = tout.active = true;
        remplir.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("Remplir la grille pour 1 fabrication")));
        tout.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(
                                "Remplir pour la recette avec les ingrédients disponibles")));
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int largeur = Math.max(ecranActuel.getXSize(), propositions.size() * ICONE);
        g.fill(
                x - 3,
                y - 3,
                x + largeur + 3,
                y + 30,
                0xC80D1013); // fond du nom et des icônes : les boutons restent visibles
        String encore = p.fois() > p.aFaire() ? " (il en faudra " + p.fois() + ")" : "";
        g.drawString(
                font,
                font.plainSubstrByWidth(
                        p.nom() + " · ×" + p.aFaire() + " possible" + encore, largeur),
                x,
                y,
                Hud.OR,
                false);
        Proposition survolee = null;
        for (int i = 0; i < propositions.size(); i++) {
            Proposition q = propositions.get(i);
            int ix = x + i * ICONE, iy = y + 11;
            g.fill(ix - 1, iy - 1, ix + 17, iy + 17, q == p ? 0x90FFC94A : 0x30FFFFFF);
            g.renderItem(q.produit(), ix, iy);
            if (event.getMouseX() >= ix - 1
                    && event.getMouseX() < ix + 17
                    && event.getMouseY() >= iy - 1
                    && event.getMouseY() < iy + 17) {
                survolee = q;
            }
        }
        if (survolee != null) {
            g.renderComponentTooltip(
                    font,
                    List.of(
                            Component.literal(survolee.nom()),
                            Component.literal(
                                            "×"
                                                    + survolee.aFaire()
                                                    + " possible maintenant, "
                                                    + survolee.fois()
                                                    + " à faire pour la recette")
                                    .withStyle(s -> s.withColor(Hud.GRIS)),
                            Component.literal("Clic : la choisir")
                                    .withStyle(s -> s.withColor(Hud.BLEU))),
                    event.getMouseX(),
                    event.getMouseY());
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
            if (event.getMouseX() >= ix - 1
                    && event.getMouseX() < ix + 17
                    && event.getMouseY() >= iy - 1
                    && event.getMouseY() < iy + 17) {
                choisi = propositions.get(i).produit().getItem();
                event.setCanceled(true);
                return;
            }
        }
    }
}
