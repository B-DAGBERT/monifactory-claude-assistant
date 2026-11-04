package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.Lignes;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import fr.assistantia.LotFabrication;
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
    private static final List<Lot> lots = new ArrayList<>();
    private static List<PlanMachines.Operation<EmiRecipe>> toutes = List.of();
    private static String calculPrecedent = "";
    private static long lotsVersion;
    private static int prochainLot;
    private static boolean dernierTermine;
    private static final java.util.Map<Object, java.util.Map<EmiRecipe, Boolean>> compatibilites =
            new java.util.WeakHashMap<>();
    private static boolean erreurSignalee;
    private static net.minecraft.world.inventory.AbstractContainerMenu menuObserve;
    private static GregTechMachines.Interface machineObservee;
    private static List<net.minecraft.world.inventory.Slot> sortiesObservees = List.of();
    private static List<GregTechMachines.Cuve> cuvesObservees = List.of();
    private static fr.assistantia.ReceptionSortie<EmiStack> receptionFluides =
            new fr.assistantia.ReceptionSortie<>(ComparaisonEmi::meme);

    private record Lot(
            int id,
            EmiRecipe recette,
            int fois,
            BlockPos position,
            LotFabrication<EmiStack> suivi) {}

    public record Ligne(
            int lot, EmiRecipe recette, int fois, String etat, boolean annulable, String detail) {}

    public static void termine() {
        dernierTermine = true;
    }

    public static boolean dernierTermine() {
        return dernierTermine;
    }

    public static void recevoir(List<PlanRetrait.Stock<ItemStack>> objets) {
        if (objets.isEmpty() || lots.isEmpty()) return;
        List<PlanRetrait.Stock<EmiStack>> gains =
                objets.stream()
                        .map(
                                s ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(s.objet()), s.quantite(), true))
                        .toList();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        try {
            var ui = GregTechMachines.ouvrir(player.containerMenu);
            if (ui != null) recevoir(gains, ui.position());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Pas de réservation validée sans connaître l'origine du résultat.
        }
    }

    private static void recevoir(List<PlanRetrait.Stock<EmiStack>> gains, BlockPos position) {
        if (gains.isEmpty()) return;
        for (var lot : lots)
            if (lot.position().equals(position)) gains = lot.suivi().recevoir(gains);
        lotsVersion++;
        actualise = 0;
    }

    private static void actualiserLots() {
        var player = Minecraft.getInstance().player;
        if (player == null || lots.isEmpty()) {
            menuObserve = null;
            return;
        }
        try {
            if (menuObserve != player.containerMenu) {
                menuObserve = player.containerMenu;
                machineObservee = GregTechMachines.ouvrir(menuObserve);
                sortiesObservees =
                        machineObservee == null ? List.of() : GregTechMachines.sorties(menuObserve);
                cuvesObservees =
                        machineObservee == null
                                ? List.of()
                                : GregTechMachines.cuvesSortie(menuObserve);
                receptionFluides = new fr.assistantia.ReceptionSortie<>(ComparaisonEmi::meme);
            }
            if (machineObservee == null || !fr.assistantia.InventaireJoueur.synchronise()) return;
            List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
            for (var slot : sortiesObservees)
                if (!slot.getItem().isEmpty())
                    sorties.add(
                            new PlanRetrait.Stock<>(
                                    EmiStack.of(slot.getItem()), slot.getItem().getCount(), true));
            List<PlanRetrait.Stock<EmiStack>> fluides = new ArrayList<>();
            for (var cuve : cuvesObservees) {
                var f = cuve.contenu();
                if (!f.isEmpty())
                    fluides.add(
                            new PlanRetrait.Stock<>(
                                    EmiStack.of(f.getFluid(), f.getTag(), f.getAmount()),
                                    f.getAmount(),
                                    true));
            }
            sorties.addAll(fluides);
            var personnels = new ArrayList<PlanRetrait.Stock<EmiStack>>();
            if (!cuvesObservees.isEmpty()) {
                personnels.addAll(
                        inventaire().stream()
                                .filter(
                                        o ->
                                                o.objet().getKey()
                                                        instanceof
                                                        net.minecraft.world.level.material.Fluid)
                                .toList());
            }
            recevoir(receptionFluides.actualiser(personnels, fluides), machineObservee.position());
            for (var lot : lots)
                if (lot.position().equals(machineObservee.position()))
                    lot.suivi()
                            .disponible(
                                    sorties.stream()
                                            .anyMatch(
                                                    s ->
                                                            lot.suivi().restants().stream()
                                                                    .anyMatch(
                                                                            r ->
                                                                                    ComparaisonEmi
                                                                                            .meme(
                                                                                                    s
                                                                                                            .objet(),
                                                                                                    r
                                                                                                            .objet()))));
        } catch (ReflectiveOperationException | RuntimeException e) {
            machineObservee = null;
        }
    }

    private static void ajouterFluide(List<PlanRetrait.Stock<EmiStack>> stock, ItemStack item) {
        if (item.isEmpty()) return;
        ItemStack un = item.copy();
        un.setCount(1);
        var f = FluidUtil.getFluidContained(un).orElse(null);
        if (f != null && !f.isEmpty())
            stock.add(
                    new PlanRetrait.Stock<>(
                            EmiStack.of(f.getFluid(), f.getTag(), f.getAmount()),
                            PlanMachines.produit(f.getAmount(), item.getCount()),
                            true));
    }

    public static void annulerLot(int id) {
        if (EnvoiMachine.occupe()) return;
        lots.stream()
                .filter(l -> l.id() == id && l.suivi().annulable())
                .forEach(l -> l.suivi().annuler());
        lotsVersion++;
        actualise = 0;
        calculPrecedent = "";
        actualiserArbre();
    }

    public static void recalculer() {
        if (EnvoiMachine.occupe()) return;
        calculPrecedent = "";
        compatibilites.clear();
        actualise = 0;
        actualiserArbre();
    }

    public static List<Ligne> lignes() {
        actualiserArbre();
        if (actif() == null) return List.of();
        List<Ligne> lignes = new ArrayList<>();
        var stock = inventaire();
        var disponibles = inventaire(false);
        for (var o : toutes) {
            if (o.recette().getId() != null
                    && o.recette().getId().getNamespace().equals("assistantia")) continue;
            boolean pret = ingredientsDisponibles(o.recette(), stock);
            lignes.add(
                    new Ligne(
                            -1,
                            o.recette(),
                            o.fois(),
                            !fr.assistantia.InventaireJoueur.synchronise()
                                    ? "Lecture du sac…"
                                    : pret
                                            ? (!ingredientsDisponibles(o.recette(), disponibles)
                                                    ? "À sortir du sac"
                                                    : estGT(o.recette())
                                                            ? "À envoyer"
                                                            : "À fabriquer")
                                            : "À collecter",
                            false,
                            o.recette().getCategory().getName().getString()));
        }
        for (var lot : lots)
            if (lot.suivi().etat() != LotFabrication.Etat.ANNULE)
                lignes.add(
                        new Ligne(
                                lot.id(),
                                lot.recette(),
                                lot.fois(),
                                lot.suivi().etat().texte,
                                lot.suivi().annulable(),
                                lot.suivi().totalRecu()
                                        + "/"
                                        + lot.suivi().total()
                                        + " récupérés · "
                                        + lot.position().toShortString()));
        return List.copyOf(lignes);
    }

    private static boolean estGT(EmiRecipe recette) {
        return recette.getClass()
                .getName()
                .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
    }

    private static boolean ingredientsDisponibles(
            EmiRecipe r, List<PlanRetrait.Stock<EmiStack>> stock) {
        var demandes = new ArrayList<PlanRetrait.Besoin<EmiStack>>();
        var ingredients = new ArrayList<>(r.getInputs());
        ingredients.addAll(r.getCatalysts());
        var outils = new java.util.HashMap<String, Integer>();
        for (int index = 0; index < ingredients.size(); index++) {
            var i = ingredients.get(index);
            if (i.isEmpty() || i.getEmiStacks().stream().anyMatch(ComparaisonEmi::circuit))
                continue;
            int besoin = PlanMachines.produit(i.getAmount(), 1);
            if (index >= r.getInputs().size() || i.getChance() == 0) {
                String key =
                        i.getEmiStacks().stream()
                                .map(v -> v.getId() + "|" + v.getNbt())
                                .sorted()
                                .toList()
                                .toString();
                int deja = outils.getOrDefault(key, 0);
                outils.merge(key, besoin, Math::max);
                besoin = Math.max(0, besoin - deja);
            }
            if (besoin > 0)
                demandes.add(
                        new PlanRetrait.Besoin<>(
                                s ->
                                        i.getEmiStacks().stream()
                                                .anyMatch(v -> ComparaisonEmi.meme(s, v)),
                                besoin));
        }
        return PlanRetrait.calculer(stock, demandes).manquants() == 0;
    }

    private static boolean compatible(Object machine, EmiRecipe recipe) {
        return compatibilites
                .computeIfAbsent(machine, k -> new java.util.HashMap<>())
                .computeIfAbsent(recipe, r -> GregTechMachines.compatible(machine, r));
    }

    private static Object actif() {
        return CoffresArbreEmi.arbreActif();
    }

    private static List<PlanRetrait.Stock<EmiStack>> inventaire() {
        return inventaire(true);
    }

    private static List<PlanRetrait.Stock<EmiStack>> inventaire(boolean avecSacs) {
        List<PlanRetrait.Stock<EmiStack>> stock = new ArrayList<>();
        var mc = Minecraft.getInstance();
        if (mc.player == null) return stock;
        for (ItemStack s :
                avecSacs
                        ? fr.assistantia.InventaireJoueur.piles()
                        : mc.player.getInventory().items) {
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

    private static void envoyee(EmiRecipe recipe, int n, BlockPos position) {
        List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
        for (var output : recipe.getOutputs())
            if (!output.isEmpty() && output.getChance() >= 1)
                sorties.add(
                        new PlanRetrait.Stock<>(
                                output.copy(), PlanMachines.produit(output.getAmount(), n), true));
        if (!sorties.isEmpty())
            lots.add(
                    new Lot(
                            ++prochainLot,
                            recipe,
                            n,
                            position.immutable(),
                            new LotFabrication<>(sorties, ComparaisonEmi::meme)));
        lotsVersion++;
        actualise = 0;
    }

    private static String cleStock(List<PlanRetrait.Stock<EmiStack>> stock) {
        return stock.stream()
                .map(s -> s.objet().getId() + "|" + s.objet().getNbt() + "|" + s.quantite())
                .sorted()
                .toList()
                .toString();
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
                s -> variantes.stream().anyMatch(v -> ComparaisonEmi.meme(s, v)),
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
            lots.clear();
            menuObserve = null;
            toutes = List.of();
            calculPrecedent = "";
            compatibilites.clear();
            if (current != null || connexion != mc.getConnection()) dernierTermine = false;
            operations = locales = List.of();
            machines = List.of();
            disposition = null;
            signature = "";
            arbre = current;
            connexion = mc.getConnection();
            dimension = dim;
            choix = page = 0;
        }
        if (arbre == null
                || mc.player == null
                || mc.level == null
                || EnvoiMachine.occupe()
                || !fr.assistantia.InventaireJoueur.synchronise()) return;
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
                lots.clear();
                lotsVersion++;
                compatibilites.clear();
                signature = key.toString();
            }
            var stock = inventaire();
            for (var credit : CycleArbreEmi.credits())
                stock.add(
                        new PlanRetrait.Stock<>(
                                EmiStack.of(credit.objet()), credit.quantite(), true));
            String cle = signature + "|" + cleStock(stock) + "|" + lotsVersion;
            if (cle.equals(calculPrecedent)) return;
            calculPrecedent = cle;
            // Seuls les résultats encore en attente sont réservés. Les reçus sont déjà dans le
            // stock réel.
            for (var lot : lots) stock.addAll(lot.suivi().restants());
            toutes = PlanMachines.calculer(root, batches, stock);
            operations = toutes.stream().filter(o -> estGT(o.recette())).toList();
        } catch (ReflectiveOperationException | RuntimeException e) {
            operations = toutes = List.of();
            calculPrecedent = "";
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
        actualiserLots();
        if (ticks % 100 == 0) compatibilites.clear();
        if (ticks % 40 != 0) return;
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
                            && operations.stream().anyMatch(o -> compatible(machine, o.recette())))
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
            List<PlanMachines.Operation<EmiRecipe>> visibles =
                    new ArrayList<>(
                            operations.stream()
                                    .filter(
                                            o ->
                                                    GregTechMachines.compatible(
                                                            ui.machine(), o.recette()))
                                    .toList());
            for (var lot : lots)
                if (lot.position().equals(ui.position())
                        && lot.suivi().annulable()
                        && visibles.stream().noneMatch(o -> o.recette() == lot.recette()))
                    visibles.add(new PlanMachines.Operation<>(lot.recette(), 0));
            locales = List.copyOf(visibles);
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
            plan =
                    selected.fois() == 0
                            ? null
                            : EnvoiMachine.preparer(ecran, selected.recette(), selected.fois());
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
                        ? EnvoiMachine.statut()
                        : selected.recette().getCategory().getName().getString()
                                + " · "
                                + (plan == null ? 0 : plan.fois())
                                + " / "
                                + selected.fois()
                                + " fabrications";
        String quantites =
                selected.fois() > 0
                        ? (plan == null ? 0 : plan.fois()) + "/" + selected.fois()
                        : quantiteSelection();
        if (selected.fois() == 0) titre = etatSelection() + " · " + quantites + " récupérés";
        String nom = EnvoiMachine.occupe() ? EnvoiMachine.statut() : etatSelection();
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
            if (op.fois() > 0) PanneauAssistant.nombre(g, font, z, op.fois());
            if (z.contient(mx, my)) {
                tooltip = new ArrayList<>();
                tooltip.add(
                        Component.literal(
                                op.recette().getOutputs().isEmpty()
                                        ? "Recette"
                                        : op.recette().getOutputs().get(0).getName().getString()));
                tooltip.add(
                        Component.literal(
                                        op.fois() > 0
                                                ? op.fois()
                                                        + " fabrications restantes · Clic : choisir"
                                                : "Lot envoyé · Suivi pour voir le détail")
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
        PanneauAssistant.bouton(g, font, d.tout(), "Suivi", libre, false, mx, my);
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
            tooltip =
                    List.of(
                            Component.literal(
                                    "Voir les états, recalculer ou annuler une réservation"));
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, mx, my);
    }

    private static String quantiteSelection() {
        var op = selection();
        int recu = 0, total = 0;
        for (var lot : lots)
            if (op != null && lot.recette() == op.recette() && lot.suivi().annulable()) {
                recu += lot.suivi().totalRecu();
                total += lot.suivi().total();
            }
        return recu + "/" + total;
    }

    private static String etatSelection() {
        var op = selection();
        if (op == null) return "À collecter";
        if (op.fois() > 0) return plan != null && plan.possible() ? "À envoyer" : "À collecter";
        return lots.stream()
                .filter(l -> l.recette() == op.recette() && l.suivi().annulable())
                .map(l -> l.suivi().etat().texte)
                .findFirst()
                .orElse("Terminé");
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
                try {
                    var ui = GregTechMachines.ouvrir(ecran.getMenu());
                    if (ui != null)
                        EnvoiMachine.lancer(
                                ecran,
                                envoi,
                                () -> envoyee(op.recette(), envoi.fois(), ui.position()));
                } catch (ReflectiveOperationException ex) {
                    actualise = 0;
                }
            }
        } else if (d.tout().contient(x, y)) {
            var op = selection();
            if (op != null) {
                PanneauAssistant.son();
                Minecraft.getInstance().setScreen(new SuiviEmiEcran(ecran));
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
