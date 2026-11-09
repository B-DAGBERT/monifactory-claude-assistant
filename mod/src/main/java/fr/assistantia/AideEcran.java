package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Libelles;
import fr.assistantia.DispositionCoffre.Zone;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Fabrications de l'arbre EMI possibles dans un établi : même panneau que les coffres et les
 * machines (voir DispositionCoffre), placé hors de l'interface et réservé auprès d'EMI. Les coffres
 * utilisent exclusivement AideCoffre.
 */
public final class AideEcran {
    private static final int MAX = 8, TITRE_MAX = 130;
    private static final String REMPLIR = "Remplir";

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

    private static AbstractContainerScreen<?> ecranActuel;
    private static List<Proposition> propositions = List.of();
    private static Item choisi; // gardé quand la liste change (après une fabrication)
    private static String cle = ""; // inventaire sur lequel la liste est calculée
    private static Zone occupee;
    private static Disposition disposition;
    private static int page;
    private static long prochainCalcul, dernierAvis;
    private static Method propositionsArbre;
    private static boolean erreurArbreSignalee;

    private AideEcran() {}

    @SubscribeEvent
    public static void initialisation(ScreenEvent.Init.Post event) {
        Screen ecran = event.getScreen();
        cle = "";
        prochainCalcul = 0;
        propositions = List.of();
        disposition = null;
        page = 0;
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
        occupee = PanneauAssistant.occupee(conteneur);
    }

    /** La place du panneau affiché, pour qu'EMI n'y pose pas ses listes d'objets. */
    public static Zone zoneEmi(Screen screen) {
        return screen == ecranActuel && disposition != null && !propositions.isEmpty()
                ? disposition.cadre()
                : null;
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
            return List.of();
        }
    }

    /** Recettes d'établi de l'arbre EMI actif seulement : sans arbre, aucune proposition. */
    private static List<Proposition> calculer(
            AbstractContainerScreen<?> ecran, Map<Item, Integer> stock) {
        if (!autorise()) return List.of();
        List<Proposition> arbre = propositionsArbre(ecran, stock);
        if (arbre != null) return arbre;
        return List.of();
    }

    /** Liste, place et page tenues à jour avant le rendu ; rien n'est calculé au clic. */
    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != ecranActuel) return;
        Map<Item, Integer> stock = stock(ecranActuel);
        String nouvelleCle = stock.toString();
        long maintenant = System.nanoTime();
        if (!nouvelleCle.equals(cle) || maintenant >= prochainCalcul) {
            propositions = calculer(ecranActuel, stock);
            cle = nouvelleCle;
            // L'arbre, ses réservations et le chargement EMI évoluent sans changer le stock.
            prochainCalcul = maintenant + 250_000_000L;
            occupee = PanneauAssistant.occupee(ecranActuel);
            disposition =
                    propositions.isEmpty()
                            ? null
                            : DispositionCoffre.calculer(
                                    ecranActuel.width,
                                    ecranActuel.height,
                                    occupee,
                                    propositions.size(),
                                    libelles(Minecraft.getInstance().font));
            page = disposition == null ? 0 : Math.min(page, derniere());
        }
    }

    /**
     * Titre et boutons gardent la même largeur quand la recette choisie change : le titre prend le
     * nom le plus long de la liste, borné ; au-delà, « … » et le nom complet au survol.
     */
    private static Libelles libelles(Font font) {
        var libelles =
                PanneauAssistant.libelles(
                        font,
                        List.of(possible(99, 999)),
                        List.of(REMPLIR),
                        List.of(tout(999)),
                        false);
        int nom = 0;
        for (Proposition p : propositions) nom = Math.max(nom, font.width(p.nom()));
        return new Libelles(
                Math.max(libelles.titre(), Math.min(TITRE_MAX, nom)),
                libelles.prendre(),
                libelles.tout(),
                false);
    }

    private static String possible(int maintenant, int total) {
        return "×" + maintenant + " possible" + (total > maintenant ? " sur " + total : "");
    }

    private static String tout(int fois) {
        return "Tout (×" + fois + ")";
    }

    private static int derniere() {
        return (propositions.size() - 1) / disposition.parPage();
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (event.getScreen() != ecranActuel || disposition == null) return;
        Proposition p = choisie();
        if (p == null) return;
        Disposition d = disposition;
        page = Math.min(page, derniere()); // la liste peut raccourcir après un remplissage
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        PanneauAssistant.fond(g, d.cadre());
        PanneauAssistant.titre(g, font, d.titre(), p.nom(), possible(p.aFaire(), p.fois()));
        List<Component> infobulle = null;
        if (d.titre().contient(mx, my))
            infobulle =
                    List.of(
                            Component.literal(p.nom()),
                            Component.literal(
                                            "×"
                                                    + p.aFaire()
                                                    + " possible maintenant, "
                                                    + p.fois()
                                                    + " à faire pour la recette")
                                    .withStyle(s -> s.withColor(PanneauAssistant.GRIS)));
        for (int rang = 0; rang < d.parPage(); rang++) {
            int n = page * d.parPage() + rang;
            if (n >= propositions.size()) break;
            Proposition q = propositions.get(n);
            Zone z = d.icone(rang);
            PanneauAssistant.caseIcone(g, z, q == p);
            g.renderItem(q.produit(), z.x() + 1, z.y() + 1);
            PanneauAssistant.nombre(g, font, z, q.aFaire());
            if (z.contient(mx, my))
                infobulle =
                        List.of(
                                Component.literal(q.nom()),
                                Component.literal(
                                                "×"
                                                        + q.aFaire()
                                                        + " possible maintenant, "
                                                        + q.fois()
                                                        + " à faire pour la recette")
                                        .withStyle(s -> s.withColor(PanneauAssistant.GRIS)),
                                Component.literal("Clic : la choisir")
                                        .withStyle(s -> s.withColor(PanneauAssistant.BLEU)));
        }
        if (d.pages()) {
            boolean avant =
                    PanneauAssistant.bouton(
                            g, font, d.precedent(), "‹", Theme.Genre.NORMAL, page > 0, mx, my);
            boolean apres =
                    PanneauAssistant.bouton(
                            g,
                            font,
                            d.suivant(),
                            "›",
                            Theme.Genre.NORMAL,
                            page < derniere(),
                            mx,
                            my);
            if (avant || apres)
                infobulle =
                        List.of(Component.literal("Page " + (page + 1) + "/" + (derniere() + 1)));
        }
        if (PanneauAssistant.bouton(
                g, font, d.prendre(), REMPLIR, Theme.Genre.NORMAL, true, mx, my))
            infobulle = List.of(Component.literal("Remplir la grille pour 1 fabrication"));
        if (PanneauAssistant.bouton(
                g, font, d.tout(), tout(p.aFaire()), Theme.Genre.PRINCIPAL, true, mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Remplir pour "
                                            + p.aFaire()
                                            + " fabrications avec les ingrédients disponibles"));
        if (infobulle != null) g.renderComponentTooltip(font, infobulle, mx, my);
    }

    private static boolean surPanneau(Screen screen, double mx, double my) {
        return screen == ecranActuel
                && disposition != null
                && !propositions.isEmpty()
                && disposition.cadre().contient(mx, my);
    }

    /** Clic sur une icône : cette fabrication devient celle des boutons Remplir et Tout. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        double mx = event.getMouseX(), my = event.getMouseY();
        if (!surPanneau(event.getScreen(), mx, my)) return;
        RelachementSouris.consommer(event); // jamais de clic qui traverse vers l'établi ou EMI
        if (event.getButton() != 0) return;
        Disposition d = disposition;
        if (d.prendre().contient(mx, my)) {
            PanneauAssistant.son();
            agir(false);
        } else if (d.tout().contient(mx, my)) {
            PanneauAssistant.son();
            agir(true);
        } else if (d.pages() && d.precedent().contient(mx, my)) tourner(-1);
        else if (d.pages() && d.suivant().contient(mx, my)) tourner(1);
        else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int n = page * d.parPage() + rang;
                if (n < propositions.size() && d.icone(rang).contient(mx, my)) {
                    choisi = propositions.get(n).produit().getItem();
                    return;
                }
            }
    }

    private static void tourner(int sens) {
        page = Math.max(0, Math.min(derniere(), page + sens));
    }

    // Pas d'annulation du relâchement selon sa position : RelachementSouris retient celui des clics
    // pris par le panneau, et un glisser commencé dans l'établi doit s'y terminer.

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (disposition.pages()) tourner(event.getScrollDelta() > 0 ? -1 : 1);
    }
}
