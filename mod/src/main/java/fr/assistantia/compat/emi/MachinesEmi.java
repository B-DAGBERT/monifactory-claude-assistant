package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.Lignes;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Étapes machine du véritable arbre EMI : affichage rouge et dépôt explicite. */
public final class MachinesEmi {
    private static Object arbre, connexion;
    private static String dimension = "", signature = "";
    private static int ticks;
    private static List<PlanMachines.Operation<EmiRecipe>> operations = List.of(),
            locales = List.of();
    private static List<BlockPos> machines = List.of();
    private static AbstractContainerScreen<?> ecran;
    private static Disposition disposition;
    private static int choix, page;
    private static EnvoiMachine.Plan plan;
    private static long actualise;
    private static final List<Production> productions = new ArrayList<>();
    private static boolean erreurSignalee;

    private static final class Production {
        final EmiStack item;
        int restant, observe;

        Production(EmiStack item, int restant, int observe) {
            this.item = item;
            this.restant = restant;
            this.observe = observe;
        }
    }

    private static Object actif() {
        return CoffresArbreEmi.arbreActif();
    }

    private static List<PlanRetrait.Stock<EmiStack>> inventaire() {
        List<PlanRetrait.Stock<EmiStack>> stock = new ArrayList<>();
        var mc = Minecraft.getInstance();
        if (mc.player == null) return stock;
        for (ItemStack s : mc.player.getInventory().items) {
            if (s.isEmpty()) continue;
            stock.add(new PlanRetrait.Stock<>(EmiStack.of(s), s.getCount(), true));
            ItemStack un = s.copy();
            un.setCount(1);
            var fluid = FluidUtil.getFluidContained(un).orElse(null);
            if (fluid != null && !fluid.isEmpty())
                stock.add(
                        new PlanRetrait.Stock<>(
                                EmiStack.of(fluid.getFluid(), fluid.getTag(), fluid.getAmount()),
                                PlanMachines.produit(fluid.getAmount(), s.getCount()),
                                true));
        }
        return stock;
    }

    private static int compte(List<PlanRetrait.Stock<EmiStack>> stock, EmiStack objet) {
        return stock.stream()
                .filter(s -> s.objet().isEqual(objet))
                .mapToInt(PlanRetrait.Stock::quantite)
                .sum();
    }

    private static void envoyee(EmiRecipe recipe, int n) {
        var stock = inventaire();
        for (var output : recipe.getOutputs()) {
            if (output.isEmpty() || output.getChance() < 1) continue;
            var existant =
                    productions.stream()
                            .filter(p -> p.item.isEqual(output))
                            .findFirst()
                            .orElse(null);
            int quantite = PlanMachines.produit(output.getAmount(), n);
            if (existant == null)
                productions.add(new Production(output.copy(), quantite, compte(stock, output)));
            else existant.restant += quantite;
        }
        actualise = 0;
    }

    private static PlanMachines.Noeud<EmiStack, EmiRecipe> lire(
            Object node, Set<Object> chemin, StringBuilder key, int depth, int[] count)
            throws ReflectiveOperationException {
        if (node == null || depth > 128 || ++count[0] > 4096 || !chemin.add(node))
            throw new IllegalArgumentException("Arbre invalide");
        var type = node.getClass();
        var ingredient = (EmiIngredient) type.getField("ingredient").get(node);
        var variantes =
                ingredient.getEmiStacks().stream()
                        .filter(
                                s -> {
                                    var item = s.getItemStack();
                                    return !item.hasTag()
                                            || !item.getTag().contains("assistantia_objectif");
                                })
                        .map(EmiStack::copy)
                        .toList();
        var recipe = (EmiRecipe) type.getField("recipe").get(node);
        long amount = type.getField("amount").getLong(node),
                divisor = type.getField("divisor").getLong(node);
        boolean reusable =
                type.getField("catalyst").getBoolean(node)
                        || type.getField("consumeChance").getFloat(node) == 0;
        key.append(recipe == null ? "-" : recipe.getId())
                .append(':')
                .append(amount)
                .append('/')
                .append(divisor)
                .append(ingredient)
                .append(';');
        List<PlanMachines.Noeud<EmiStack, EmiRecipe>> enfants = new ArrayList<>();
        Object raw = type.getField("children").get(node);
        if (raw instanceof List<?> nodes)
            for (Object child : nodes) enfants.add(lire(child, chemin, key, depth + 1, count));
        chemin.remove(node);
        return new PlanMachines.Noeud<>(
                s -> variantes.stream().anyMatch(v -> s.isEqual(v)),
                amount,
                divisor,
                reusable,
                recipe,
                enfants);
    }

    private static void actualiserArbre() {
        var mc = Minecraft.getInstance();
        Object current = actif();
        String dim = mc.level == null ? "" : mc.level.dimension().location().toString();
        if (current != arbre || connexion != mc.getConnection() || !dim.equals(dimension)) {
            EnvoiMachine.arreter();
            productions.clear();
            operations = locales = List.of();
            machines = List.of();
            disposition = null;
            signature = "";
            arbre = current;
            connexion = mc.getConnection();
            dimension = dim;
            choix = page = 0;
        }
        if (arbre == null || mc.player == null || mc.level == null || EnvoiMachine.occupe()) return;
        try {
            var key = new StringBuilder();
            var root =
                    lire(
                            arbre.getClass().getField("goal").get(arbre),
                            Collections.newSetFromMap(new IdentityHashMap<>()),
                            key,
                            0,
                            new int[] {0});
            long batches = arbre.getClass().getField("batches").getLong(arbre);
            key.append(batches);
            if (!signature.equals(key.toString())) {
                productions.clear();
                signature = key.toString();
            }
            var stock = inventaire();
            // Les sorties déjà préparées ne réapparaissent pas après rangement ou consommation.
            // Le stock réel et le crédit se recouvrent : ne jamais les additionner deux fois.
            for (var p : productions) {
                int credit = Math.max(0, p.observe + p.restant - compte(stock, p.item));
                if (credit > 0) stock.add(new PlanRetrait.Stock<>(p.item, credit, true));
            }
            operations =
                    PlanMachines.calculer(root, batches, stock).stream()
                            .filter(
                                    o ->
                                            o.recette()
                                                    .getClass()
                                                    .getName()
                                                    .equals(
                                                            "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe"))
                            .toList();
        } catch (ReflectiveOperationException | RuntimeException e) {
            operations = List.of();
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : étapes machines EMI indisponibles", e);
                erreurSignalee = true;
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (actif() != arbre || ++ticks % 10 == 0) actualiserArbre();
        if (ticks % 20 != 0) return;
        var mc = Minecraft.getInstance();
        if (arbre == null || mc.level == null || mc.player == null || operations.isEmpty()) {
            machines = List.of();
            return;
        }
        List<BlockPos> trouvées = new ArrayList<>();
        BlockPos joueur = mc.player.blockPosition();
        for (int x = (joueur.getX() >> 4) - 3; x <= (joueur.getX() >> 4) + 3; x++)
            for (int z = (joueur.getZ() >> 4) - 3; z <= (joueur.getZ() >> 4) + 3; z++) {
                var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (be.getBlockPos().distSqr(joueur) > 48 * 48) continue;
                    Object machine = GregTechMachines.machine(be);
                    if (machine != null
                            && operations.stream()
                                    .anyMatch(
                                            o -> GregTechMachines.compatible(machine, o.recette())))
                        trouvées.add(be.getBlockPos().immutable());
                }
            }
        machines = List.copyOf(trouvées);
    }

    @SubscribeEvent
    public static void monde(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || actif() == null
                || machines.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var buffers = mc.renderBuffers().bufferSource();
        var lignes = buffers.getBuffer(Lignes.TRAVERSANTES);
        var camera = event.getCamera().getPosition();
        for (var pos : machines)
            if (mc.level.hasChunkAt(pos) && !mc.level.getBlockState(pos).isAir())
                LevelRenderer.renderLineBox(
                        event.getPoseStack(),
                        lignes,
                        new AABB(pos).inflate(.006).move(-camera.x, -camera.y, -camera.z),
                        1f,
                        .12f,
                        .12f,
                        .95f);
        buffers.endBatch(Lignes.TRAVERSANTES);
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        ecran = null;
        disposition = null;
        locales = List.of();
        plan = null;
        actualise = 0;
        choix = page = 0;
        if (event.getScreen() instanceof AbstractContainerScreen<?> s
                && GregTechMachines.estMachine(s.getMenu())) {
            ecran = s;
            EnvoiMachine.effacerStatut();
        }
    }

    private static boolean affiche(Screen s) {
        return s == ecran && ecran != null && actif() != null;
    }

    private static PlanMachines.Operation<EmiRecipe> selection() {
        return locales.isEmpty() ? null : locales.get(Math.min(choix, locales.size() - 1));
    }

    private static void actualiserInterface() {
        if (ecran == null || EnvoiMachine.occupe()) return;
        actualiserArbre();
        try {
            var ui = GregTechMachines.ouvrir(ecran.getMenu());
            if (ui == null) {
                disposition = null;
                return;
            }
            var precedent = selection();
            locales =
                    operations.stream()
                            .filter(o -> GregTechMachines.compatible(ui.machine(), o.recette()))
                            .toList();
            choix = Math.min(choix, Math.max(0, locales.size() - 1));
            if (precedent != null)
                for (int i = 0; i < locales.size(); i++)
                    if (locales.get(i).recette() == precedent.recette()) choix = i;
            var selected = selection();
            if (selected == null) {
                disposition = null;
                plan = null;
                return;
            }
            plan = EnvoiMachine.preparer(ecran, selected.recette(), selected.fois());
            disposition =
                    DispositionCoffre.calculer(
                            ecran.width,
                            ecran.height,
                            PanneauAssistant.occupee(ecran),
                            locales.size());
            page = Math.min(page, (locales.size() - 1) / disposition.parPage());
        } catch (ReflectiveOperationException e) {
            disposition = null;
        }
        actualise = System.currentTimeMillis();
    }

    public static Zone zoneEmi(Screen screen) {
        return affiche(screen) && disposition != null ? disposition.cadre() : null;
    }

    @SubscribeEvent
    public static void avant(ScreenEvent.Render.Pre event) {
        if (affiche(event.getScreen()) && System.currentTimeMillis() - actualise > 500)
            actualiserInterface();
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (!affiche(event.getScreen()) || disposition == null || selection() == null) return;
        var d = disposition;
        var c = d.cadre();
        var g = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        var selected = selection();
        // Même habillage que le panneau des coffres et le HUD.
        PanneauAssistant.fond(g, c);
        String titre =
                EnvoiMachine.occupe()
                        ? "Envoi…"
                        : selected.recette().getCategory().getName().getString()
                                + " · "
                                + (plan == null ? 0 : plan.fois())
                                + " / "
                                + selected.fois()
                                + " fabrications";
        String quantites = (plan == null ? 0 : plan.fois()) + " / " + selected.fois();
        String nom =
                EnvoiMachine.occupe()
                        ? "Envoi…"
                        : selected.recette().getCategory().getName().getString();
        PanneauAssistant.titre(g, font, d.titre(), nom, quantites);
        List<Component> tooltip = null;
        for (int rang = 0; rang < d.parPage(); rang++) {
            int i = page * d.parPage() + rang;
            if (i >= locales.size()) break;
            var op = locales.get(i);
            var z = d.icone(rang);
            PanneauAssistant.caseIcone(g, z, i == choix);
            if (!op.recette().getOutputs().isEmpty())
                op.recette()
                        .getOutputs()
                        .get(0)
                        .render(g, z.x() + 1, z.y() + 1, 0, EmiIngredient.RENDER_ICON);
            PanneauAssistant.nombre(g, font, z, op.fois());
            if (z.contient(mx, my)) {
                tooltip = new ArrayList<>();
                tooltip.add(
                        Component.literal(
                                op.recette().getOutputs().isEmpty()
                                        ? "Recette"
                                        : op.recette().getOutputs().get(0).getName().getString()));
                tooltip.add(
                        Component.literal(op.fois() + " fabrications restantes · Clic : choisir")
                                .withStyle(s -> s.withColor(PanneauAssistant.GRIS)));
            }
        }
        boolean libre = !EnvoiMachine.occupe();
        PanneauAssistant.bouton(
                g,
                font,
                d.prendre(),
                "Envoyer",
                libre && plan != null && plan.possible(),
                true,
                mx,
                my);
        PanneauAssistant.bouton(g, font, d.tout(), "Voir", libre, false, mx, my);
        if (d.pages()) {
            PanneauAssistant.bouton(g, font, d.precedent(), "‹", libre && page > 0, false, mx, my);
            PanneauAssistant.bouton(
                    g,
                    font,
                    d.suivant(),
                    "›",
                    libre && (page + 1) * d.parPage() < locales.size(),
                    false,
                    mx,
                    my);
        }
        if (d.prendre().contient(mx, my) || d.titre().contient(mx, my)) {
            tooltip = new ArrayList<>();
            tooltip.add(Component.literal(titre));
            if (plan != null) {
                if (!plan.probleme().isEmpty()) tooltip.add(Component.literal(plan.probleme()));
                for (String s : plan.details()) tooltip.add(Component.literal(s));
            }
            if (!EnvoiMachine.statut().isEmpty())
                tooltip.add(Component.literal(EnvoiMachine.statut()));
        }
        if (d.tout().contient(mx, my))
            tooltip = List.of(Component.literal("Voir la recette complète dans EMI"));
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, mx, my);
    }

    private static boolean surPanneau(Screen screen, double x, double y) {
        return affiche(screen) && disposition != null && disposition.cadre().contient(x, y);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (event.getButton() != 0 || EnvoiMachine.occupe()) return;
        var d = disposition;
        double x = event.getMouseX(), y = event.getMouseY();
        if (d.prendre().contient(x, y)) {
            actualiserInterface();
            var op = selection();
            var envoi = plan;
            if (op != null && envoi != null && envoi.possible()) {
                PanneauAssistant.son();
                EnvoiMachine.lancer(ecran, envoi, () -> envoyee(op.recette(), envoi.fois()));
            }
        } else if (d.tout().contient(x, y)) {
            var op = selection();
            if (op != null) {
                PanneauAssistant.son();
                EmiApi.displayRecipe(op.recette());
            }
        } else if (d.pages() && d.precedent().contient(x, y)) page = Math.max(0, page - 1);
        else if (d.pages() && d.suivant().contient(x, y))
            page = Math.min((locales.size() - 1) / d.parPage(), page + 1);
        else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int i = page * d.parPage() + rang;
                if (i < locales.size() && d.icone(rang).contient(x, y)) {
                    choix = i;
                    actualise = 0;
                    break;
                }
            }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void relache(ScreenEvent.MouseButtonReleased.Pre event) {
        if (surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY()))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (!EnvoiMachine.occupe())
            page =
                    Math.max(
                            0,
                            Math.min(
                                    (locales.size() - 1) / disposition.parPage(),
                                    page + (event.getScrollDelta() > 0 ? -1 : 1)));
    }
}
