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
    private static Field boutonArbre, barreRecherche, comparaisonEmi;
    private static boolean charge;
    private static java.lang.reflect.Method desactive;
    private static boolean erreurSignalee;
    private static long dernierClic;

    private static void charger() throws ReflectiveOperationException {
        if (charge) return;
        Class<?> manager = Class.forName("dev.emi.emi.screen.EmiScreenManager");
        boutonArbre = manager.getField("tree");
        barreRecherche = manager.getField("search");
        desactive = manager.getMethod("isDisabled");
        comparaisonEmi = EmiStack.class.getDeclaredField("comparison");
        comparaisonEmi.setAccessible(true);
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
            int width = Math.min(102, Math.max(20, room));
            button.setX(x);
            button.setY(y);
            button.setWidth(width);
            boolean selected = RechercheEmi.selectionnee(ID);
            button.active = selected || ArbreEmi.consulte() != null;
            button.setMessage(
                    Component.literal(
                            width < 80
                                    ? selected ? "■" : "▣"
                                    : selected ? "Arrêter scan" : "Scanner coffres"));
            button.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    selected
                                            ? "Désactiver la recherche de l’arbre dans les coffres"
                                            : button.active
                                                    ? "Chercher tous les objets de l’arbre EMI dans les coffres"
                                                    : "Choisis d’abord un objectif dans Recipe Tree")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            button.visible = false;
            signaler(e);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
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
            ArbreCollecte.Noeud snapshot = lire(root, chemin, count, 0, null);
            if (ArbreEmi.actif() == null) RechercheEmi.arreter();
            if (!ArbreEmi.activer()) return;
            RechercheEmi.basculer(ID, new MemoireCollecte(snapshot, lots));
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

    /** Le même arbre que la recherche, avec les comparaisons d'objets exactes d'EMI. */
    public static fr.assistantia.RetraitCoffre.Plan planTransfert(
            AbstractContainerScreen<?> screen) {
        try {
            charger();
            Object tree = ArbreEmi.actif();
            if (tree == null) return new fr.assistantia.RetraitCoffre.Plan(List.of(), 0);
            Object root = tree.getClass().getField("goal").get(tree);
            long lots = tree.getClass().getField("batches").getLong(tree);
            List<EmiStack> variantes = new ArrayList<>();
            var snapshot =
                    lire(
                            root,
                            Collections.newSetFromMap(new IdentityHashMap<>()),
                            new int[] {0},
                            0,
                            variantes);
            return fr.assistantia.RetraitCoffre.planArbre(
                    screen.getMenu(),
                    snapshot,
                    lots,
                    ids -> {
                        var choix =
                                ids.stream()
                                        .map(id -> variantes.get(Integer.parseInt(id)))
                                        .toList();
                        return stack -> choix.stream().anyMatch(v -> EmiStack.of(stack).isEqual(v));
                    });
        } catch (ReflectiveOperationException | RuntimeException e) {
            signaler(e);
            return new fr.assistantia.RetraitCoffre.Plan(List.of(), 0);
        }
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
                if (variantes == null) ids.add(id.toString());
                else {
                    // Tokens propres aux variantes EMI : conserve aussi leurs règles NBT.
                    int index = 0;
                    while (index < variantes.size()
                            && !(net.minecraft.world.item.ItemStack.isSameItemSameTags(
                                            variantes.get(index).getItemStack(), item)
                                    && comparaisonEmi.get(variantes.get(index))
                                            == comparaisonEmi.get(stack))) index++;
                    if (index == variantes.size()) variantes.add(stack);
                    ids.add(Integer.toString(index));
                }
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
