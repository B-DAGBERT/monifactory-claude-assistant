package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreCollecte;
import fr.assistantia.ArbreEmi;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.RechercheEmi;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Un seul interrupteur près de Recipe Tree. Aucune mutation de l'arbre ni des favoris EMI. */
public final class CoffresArbreEmi {
    private static final String ID = "assistantia:coffres_arbre";
    private static final Map<Screen, Button> boutons = new WeakHashMap<>();
    private static final Map<Screen, Button> suivis = new WeakHashMap<>();
    private static Field boutonArbre, barreRecherche;
    private static boolean charge;
    private static java.lang.reflect.Method desactive;
    private static boolean erreurSignalee;
    private static long dernierClic;
    private static int ticks;
    private static Instantane dernierArbre;

    private record Instantane(
            ArbreCollecte.Noeud noeud,
            long lots,
            List<EmiStack> variantes,
            String cles,
            String cible) {
        boolean meme(Instantane autre) {
            return autre != null
                    && lots == autre.lots
                    && noeud.equals(autre.noeud)
                    && cles.equals(autre.cles)
                    && cible.equals(autre.cible);
        }
    }

    private static Instantane instantane(Object tree) throws ReflectiveOperationException {
        List<EmiStack> variantes = new ArrayList<>();
        var node =
                lire(
                        tree.getClass().getField("goal").get(tree),
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        new int[] {0},
                        0,
                        variantes);
        long lots = tree.getClass().getField("batches").getLong(tree);
        String cles =
                variantes.stream()
                        .map(v -> v.getId() + "|" + v.getNbt())
                        .sorted()
                        .toList()
                        .toString();
        var root = tree.getClass().getField("goal").get(tree);
        var produit = (EmiIngredient) root.getClass().getField("ingredient").get(root);
        String cible =
                produit.getEmiStacks().stream()
                        .map(v -> v.getId() + "|" + v.getNbt())
                        .sorted()
                        .toList()
                        .toString();
        return new Instantane(node, lots, List.copyOf(variantes), cles, cible);
    }

    private static void appliquer(Instantane vue) {
        RechercheEmi.recalculer(
                new MemoireCollecte(vue.noeud(), vue.lots()),
                item ->
                        vue.variantes().stream()
                                .anyMatch(v -> ComparaisonEmi.meme(EmiStack.of(item), v)),
                dernierArbre != null && vue.cible().equals(dernierArbre.cible()));
        dernierArbre = vue;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!RechercheEmi.selectionnee(ID)) {
            dernierArbre = null;
            return;
        }
        if (++ticks % 10 != 0 || EnvoiMachine.occupe() || fr.assistantia.RetraitCoffre.occupe())
            return;
        try {
            Object tree = ArbreEmi.actif();
            if (tree == null) return;
            var vue = instantane(tree);
            if (!vue.meme(dernierArbre)) appliquer(vue);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RechercheEmi.arreter(); // Ne pas proposer les ingrédients de l'ancien arbre.
            signaler(e);
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(
                        Component.literal("Arbre illisible · scan arrêté. Rouvre Recipe Tree."),
                        true);
        }
    }

    private static void charger() throws ReflectiveOperationException {
        if (charge) return;
        Class<?> manager = Class.forName("dev.emi.emi.screen.EmiScreenManager");
        boutonArbre = manager.getField("tree");
        barreRecherche = manager.getField("search");
        desactive = manager.getMethod("isDisabled");
        charge = true;
    }

    public static Object arbreActif() {
        try {
            charger();
            return ArbreEmi.actif();
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            return null;
        }
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!(screen instanceof AbstractContainerScreen<?>)
                && !name.equals("dev.emi.emi.screen.RecipeScreen")
                && !name.equals("dev.emi.emi.screen.BoMScreen")) return;
        Button button =
                Button.builder(Component.literal("Scanner coffres"), b -> basculer())
                        .bounds(46, screen.height - 22, 102, 20)
                        .build();
        button.visible = false;
        boutons.put(screen, button);
        event.addListener(button);
        Button suivi =
                Button.builder(
                                Component.literal("Suivi"),
                                b -> Minecraft.getInstance().setScreen(new SuiviEmiEcran(screen)))
                        .bounds(150, screen.height - 22, 48, 20)
                        .build();
        suivi.visible = false;
        suivi.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "États des fabrications, recalcul et annulation des lots")));
        suivis.put(screen, suivi);
        event.addListener(suivi);
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Pre event) {
        Button button = boutons.get(event.getScreen());
        if (button == null) return;
        try {
            charger();
            AbstractWidget tree = (AbstractWidget) boutonArbre.get(null);
            AbstractWidget search = (AbstractWidget) barreRecherche.get(null);
            button.visible = tree != null && tree.visible && !(boolean) desactive.invoke(null);
            Button suivi = suivis.get(event.getScreen());
            if (suivi != null) suivi.visible = button.visible;
            if (!button.visible) return;
            int x = tree.getX() + tree.getWidth() + 2;
            int y = tree.getY();
            int room = event.getScreen().width - x - 2;
            if (search != null
                    && search.visible
                    && search.getX() > x
                    && search.getY() < y + 20
                    && search.getY() + search.getHeight() > y)
                room = Math.min(room, search.getX() - x - 2);
            int width = Math.min(102, Math.max(20, room - 52));
            if (suivi != null) {
                suivi.setX(room >= 74 ? x + width + 2 : x);
                suivi.setY(room >= 74 ? y : y - 22);
                suivi.setWidth(48);
                suivi.active = ArbreEmi.actif() != null || MachinesEmi.dernierTermine();
            }
            button.setX(x);
            button.setY(y);
            button.setWidth(width);
            boolean selected = RechercheEmi.selectionnee(ID);
            boolean lectureSac = selected && !fr.assistantia.InventaireJoueur.synchronise();
            button.active = selected || ArbreEmi.consulte() != null;
            button.setMessage(
                    Component.literal(
                            lectureSac
                                    ? (width < 80 ? "…" : "Lecture du sac…")
                                    : width < 80
                                            ? selected ? "■" : "▣"
                                            : selected ? "Arrêter scan" : "Scanner coffres"));
            button.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    lectureSac
                                            ? "Synchronisation du backpack · cliquer pour arrêter le scan"
                                            : selected
                                                    ? "Désactiver la recherche de l’arbre dans les coffres"
                                                    : button.active
                                                            ? "Chercher tous les objets de l’arbre EMI dans les coffres"
                                                            : "Choisis d’abord un objectif dans Recipe Tree")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            button.visible = false;
            if (suivis.get(event.getScreen()) != null)
                suivis.get(event.getScreen()).visible = false;
            signaler(e);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        Button suivi = suivis.get(event.getScreen());
        if (suivi != null
                && suivi.visible
                && suivi.isMouseOver(event.getMouseX(), event.getMouseY())) {
            if (suivi.active)
                suivi.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
            event.setCanceled(true);
            return;
        }
        Button button = boutons.get(event.getScreen());
        if (button != null
                && button.visible
                && button.isMouseOver(event.getMouseX(), event.getMouseY())) {
            if (button.active)
                button.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
            event.setCanceled(
                    true); // EMI ne doit pas aussi prendre le clic pour un objet situé derrière.
        }
    }

    public static fr.assistantia.DispositionCoffre.Zone zoneSuivi(Screen screen) {
        var b = suivis.get(screen);
        return b != null && b.visible
                ? new fr.assistantia.DispositionCoffre.Zone(
                        b.getX(), b.getY(), b.getWidth(), b.getHeight())
                : null;
    }

    public static boolean recalculer() {
        if (!RechercheEmi.actif()) return true;
        try {
            Object tree = ArbreEmi.actif();
            if (tree == null) return false;
            appliquer(instantane(tree));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            return false;
        }
    }

    private static void basculer() {
        long now = System.nanoTime();
        if (now - dernierClic < 650_000_000L) return;
        dernierClic = now;
        if (RechercheEmi.selectionnee(ID)) {
            RechercheEmi.arreter();
            return;
        }
        try {
            charger();
            Object tree = ArbreEmi.consulte();
            if (tree == null) return;
            Object root = tree.getClass().getField("goal").get(tree);
            long lots = tree.getClass().getField("batches").getLong(tree);
            Set<Object> chemin = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] count = {0};
            List<EmiStack> variantes = new ArrayList<>();
            ArbreCollecte.Noeud snapshot = lire(root, chemin, count, 0, variantes);
            dernierArbre = instantane(tree);
            if (ArbreEmi.actif() == null) RechercheEmi.arreter();
            if (!ArbreEmi.activer()) return;
            RechercheEmi.basculer(
                    ID,
                    new MemoireCollecte(snapshot, lots),
                    item ->
                            variantes.stream()
                                    .anyMatch(v -> ComparaisonEmi.meme(EmiStack.of(item), v)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(
                        Component.literal(
                                "Arbre EMI indisponible. Rouvre Recipe Tree puis réessaie."),
                        true);
        }
    }

    /** Les quantités réservées par la recherche active, sans reconstruire l'arbre. */
    public static fr.assistantia.RetraitCoffre.Plan planTransfert(
            AbstractContainerScreen<?> screen) {
        return RechercheEmi.retrait(screen.getMenu());
    }

    private static ArbreCollecte.Noeud lire(
            Object node, Set<Object> chemin, int[] count, int depth, List<EmiStack> variantes)
            throws ReflectiveOperationException {
        if (node == null || depth > 128 || ++count[0] > 4096 || !chemin.add(node))
            throw new IllegalArgumentException("Arbre EMI invalide ou trop grand");
        Class<?> type = node.getClass();
        EmiIngredient ingredient = (EmiIngredient) type.getField("ingredient").get(node);
        Set<String> ids = new LinkedHashSet<>();
        boolean outil = true;
        for (EmiStack stack : ingredient.getEmiStacks()) {
            var item = stack.getItemStack();
            if (item.isEmpty()) continue;
            // Le livre de l'objectif est une racine virtuelle, jamais un objet à rechercher.
            if (item.hasTag() && item.getTag().contains("assistantia_objectif")) continue;
            var id = ForgeRegistries.ITEMS.getKey(item.getItem());
            if (id != null) {
                ids.add(id.toString());
                variantes.add(stack.copy());
            }
            outil &=
                    item.isDamageableItem()
                            || !stack.getRemainder().isEmpty()
                                    && stack.getRemainder().getItemStack().is(item.getItem());
        }
        boolean catalyst =
                type.getField("catalyst").getBoolean(node)
                        || outil && !ids.isEmpty()
                        || type.getField("consumeChance").getFloat(node) == 0;
        List<ArbreCollecte.Noeud> children = new ArrayList<>();
        Object raw = type.getField("children").get(node);
        if (raw instanceof List<?> nodes)
            for (Object child : nodes)
                children.add(lire(child, chemin, count, depth + 1, variantes));
        chemin.remove(node);
        return new ArbreCollecte.Noeud(
                ids,
                type.getField("amount").getLong(node),
                type.getField("divisor").getLong(node),
                catalyst,
                children);
    }

    private static void signaler(Exception e) {
        if (!erreurSignalee) {
            LogUtils.getLogger().warn("Assistant : recherche de l'arbre EMI indisponible", e);
            erreurSignalee = true;
        }
    }
}
