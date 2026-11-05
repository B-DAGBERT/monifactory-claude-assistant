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
    private static final List<LotMachine> lots = new ArrayList<>();
    private static List<PlanMachines.Operation<EmiRecipe>> toutes = List.of();
    private static String calculPrecedent = "";
    private static long lotsVersion;
    private static int prochainLot;
    private static final fr.assistantia.ReceptionManuelle<EmiStack> receptionManuelle =
            new fr.assistantia.ReceptionManuelle<>(ComparaisonEmi::meme);
    private static boolean dernierTermine;
    private static final java.util.Map<Object, java.util.Map<EmiRecipe, Boolean>> compatibilites =
            new java.util.WeakHashMap<>();
    private static boolean erreurSignalee, erreurInterfaceSignalee;
    private static net.minecraft.world.inventory.AbstractContainerMenu menuObserve;
    private static GregTechMachines.Interface machineObservee;
    private static List<net.minecraft.world.inventory.Slot> sortiesObservees = List.of();
    private static List<GregTechMachines.Cuve> cuvesObservees = List.of();
    private static fr.assistantia.ReceptionSortie<EmiStack> receptionFluides =
            new fr.assistantia.ReceptionSortie<>(ComparaisonEmi::meme);

    public record Ligne(
            int lot, EmiRecipe recette, int fois, String etat, boolean annulable, String detail) {}

    public static void termine() {
        dernierTermine = true;
    }

    public static boolean dernierTermine() {
        return dernierTermine;
    }

    public static void recevoir(List<PlanRetrait.Stock<ItemStack>> objets) {
        if (lots.isEmpty()) return;
        List<PlanRetrait.Stock<EmiStack>> gains =
                objets.stream()
                        .map(
                                s ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(s.objet()), s.quantite(), true))
                        .toList();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        List<PlanRetrait.Stock<EmiStack>> restants = gains;
        List<PlanRetrait.Stock<EmiStack>> attendusAutomatiques = new ArrayList<>();
        try {
            var ui = GregTechMachines.ouvrir(player.containerMenu);
            if (ui != null) {
                for (var lot : lots)
                    if (lot.ici(ui.position())) attendusAutomatiques.addAll(lot.suivi().restants());
                restants = recevoir(gains, ui.position());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Les lots envoyés automatiquement exigent une preuve de leur machine.
        }
        var attribues = receptionManuelle.soustraire(gains, restants);
        var nouveaux =
                receptionManuelle.actualiser(
                        inventaire(), attribues, attendusAutomatiques, restants);
        if (nouveaux.isEmpty()) return;
        var distribution =
                fr.assistantia.ReceptionLots.repartir(
                        nouveaux,
                        lots.stream()
                                .map(
                                        l ->
                                                new fr.assistantia.ReceptionLots.Source<>(
                                                        l.suivi(),
                                                        (java.util.function.Predicate<EmiStack>)
                                                                s ->
                                                                        l.manuel()
                                                                                || !s.getItemStack()
                                                                                        .isEmpty()))
                                .toList());
        if (distribution.change()) {
            lotsVersion++;
            InventaireArbreEmi.invalider();
            actualise = 0;
        }
    }

    private static List<PlanRetrait.Stock<EmiStack>> recevoir(
            List<PlanRetrait.Stock<EmiStack>> gains, BlockPos position) {
        if (gains.isEmpty()) return gains;
        for (var lot : lots)
            if (lot.ici(position)) {
                int avant = lot.suivi().totalRecu();
                gains = lot.suivi().recevoir(gains);
                if (avant != lot.suivi().totalRecu()) {
                    lotsVersion++;
                    InventaireArbreEmi.invalider();
                    actualise = 0;
                }
            }
        return gains;
    }

    private static void actualiserLots() {
        var player = Minecraft.getInstance().player;
        if (player == null || lots.isEmpty()) {
            menuObserve = null;
            return;
        }
        var dansCoffres =
                new java.util.HashMap<>(fr.assistantia.RechercheEmi.resultatsDisponibles());
        for (var lot : lots) {
            boolean disponible = false;
            for (var attendu : lot.suivi().restants()) {
                String id = attendu.objet().getId().toString();
                int n = Math.min(attendu.quantite(), dansCoffres.getOrDefault(id, 0));
                disponible |= n > 0;
                dansCoffres.put(id, Math.max(0, dansCoffres.getOrDefault(id, 0) - n));
            }
            lot.suivi().disponible(disponible);
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
                if (lot.ici(machineObservee.position()))
                    lot.suivi()
                            .disponible(
                                    lot.suivi().etat() == LotFabrication.Etat.A_RECUPERER
                                            || sorties.stream()
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
        InventaireArbreEmi.invalider();
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
                                    : circuitARegler(o.recette())
                                            ? "Circuit à régler"
                                            : pret
                                                    ? (!ingredientsDisponibles(
                                                                    o.recette(), disponibles)
                                                            ? "À sortir du sac"
                                                            : estGT(o.recette())
                                                                    ? "À envoyer"
                                                                    : "À fabriquer")
                                                    : "À collecter",
                            false,
                            circuitARegler(o.recette())
                                    ? plan.probleme()
                                    : o.recette().getCategory().getName().getString()));
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
                                        + lot.lieu()));
        return List.copyOf(lignes);
    }

    public static int declarables(EmiRecipe recette) {
        if (actif() == null
                || EnvoiMachine.occupe()
                || !fr.assistantia.InventaireJoueur.synchronise()
                || recette.getOutputs().stream()
                        .noneMatch(s -> !s.getItemStack().isEmpty() && s.getChance() >= 1))
            return 0;
        return toutes.stream()
                .filter(o -> o.recette() == recette)
                .mapToInt(PlanMachines.Operation::fois)
                .sum();
    }

    public static boolean completerLot(int id, EmiRecipe recette, int supplement) {
        actualiserArbre();
        if (supplement <= 0 || supplement > declarables(recette)) return false;
        for (int i = 0; i < lots.size(); i++) {
            var lot = lots.get(i);
            if (lot.id() != id || lot.recette() != recette) continue;
            var sorties = sortiesLot(recette, supplement, !lot.manuel());
            if (sorties.isEmpty() || !lot.suivi().ajouter(sorties)) return false;
            lots.set(
                    i,
                    new LotMachine(
                            id,
                            recette,
                            PlanMachines.produit(1, (long) lot.fois() + supplement),
                            lot.emplacement(),
                            lot.suivi()));
            lotsVersion++;
            InventaireArbreEmi.invalider();
            calculPrecedent = "";
            actualiserArbre();
            return true;
        }
        return false;
    }

    public static Object arbreDeclaration() {
        return actif();
    }

    public static boolean declarer(EmiRecipe recette, int fois) {
        actualiserArbre();
        int max = declarables(recette);
        if (fois < 1 || fois > max) return false;
        // Terminer l'observation des anciens lots avant d'établir le point de départ du nouveau.
        recevoir(List.of());
        // Point de départ physique : les objets déjà possédés ne valident pas ce nouveau lot.
        receptionManuelle.initialiser(inventaire());
        ajouterLot(recette, fois, null);
        calculPrecedent = "";
        actualiserArbre();
        return true;
    }

    /** Stock de calcul temporaire pour EMI ; jamais ajouté à l'inventaire réel ni aux preuves. */
    public static List<PlanRetrait.Stock<EmiStack>> reservationsArbre() {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || actif() != arbre
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString())) return List.of();
        return lots.stream()
                .flatMap(l -> l.suivi().restants().stream())
                .map(s -> new PlanRetrait.Stock<>(s.objet().copy(), s.quantite(), true))
                .toList();
    }

    /** Réservations temporaires, distinctes de l'avancement réel et des acquisitions du scan. */
    public static java.util.Map<String, Integer> reservationsObjets() {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString()))
            return java.util.Map.of();
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (var lot : lots)
            for (var s : lot.suivi().restants()) {
                var item = s.objet().getItemStack();
                if (!item.isEmpty())
                    result.merge(
                            s.objet().getId().toString(),
                            s.quantite(),
                            (a, b) -> (int) Math.min(1_000_000L, (long) a + b));
            }
        return java.util.Map.copyOf(result);
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
            boolean catalyst = index >= r.getInputs().size() || i.getChance() == 0;
            if (i.isEmpty() || ComparaisonEmi.circuitVirtuel(r, i, catalyst)) continue;
            int besoin = PlanMachines.produit(i.getAmount(), 1);
            if (catalyst) {
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

    private static void envoyee(
            EmiRecipe recipe, int n, int demande, BlockPos position, Screen parent) {
        recevoir(List.of());
        receptionManuelle.initialiser(inventaire());
        int id = ajouterLot(recipe, n, position);
        actualiserArbre();
        if (demande > n
                && recipe.getOutputs().stream()
                        .anyMatch(
                                o ->
                                        !o.getItemStack().isEmpty()
                                                && PlanMachines.produit(o.getAmount(), demande)
                                                        > 64))
            Minecraft.getInstance()
                    .setScreen(new DeclarerFabricationEcran(parent, recipe, n, demande, id));
    }

    private static List<PlanRetrait.Stock<EmiStack>> sortiesLot(
            EmiRecipe recipe, int n, boolean automatique) {
        List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
        for (var output : recipe.getOutputs())
            if (!output.isEmpty()
                    && output.getChance() >= 1
                    && (automatique || !output.getItemStack().isEmpty()))
                sorties.add(
                        new PlanRetrait.Stock<>(
                                output.copy(), PlanMachines.produit(output.getAmount(), n), true));
        return List.copyOf(sorties);
    }

    private static int ajouterLot(EmiRecipe recipe, int n, BlockPos position) {
        var sorties = sortiesLot(recipe, n, position != null);
        if (sorties.isEmpty()) return 0;
        int id = ++prochainLot;
        lots.add(
                new LotMachine(
                        id,
                        recipe,
                        n,
                        java.util.Optional.ofNullable(position),
                        new LotFabrication<>(sorties, ComparaisonEmi::meme)));
        lotsVersion++;
        InventaireArbreEmi.invalider();
        actualise = 0;
        return id;
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
        return lire(node, chemin, key, depth, count, null);
    }

    private static PlanMachines.Noeud<EmiStack, EmiRecipe> lire(
            Object node,
            Set<Object> chemin,
            StringBuilder key,
            int depth,
            int[] count,
            EmiRecipe parent)
            throws ReflectiveOperationException {
        if (node == null || depth > 128 || ++count[0] > 4096 || !chemin.add(node))
            throw new IllegalArgumentException("Arbre invalide");
        var type = node.getClass();
        var ingredient = (EmiIngredient) type.getField("ingredient").get(node);
        boolean reusable =
                type.getField("catalyst").getBoolean(node)
                        || type.getField("consumeChance").getFloat(node) == 0;
        if (ComparaisonEmi.circuitVirtuel(parent, ingredient, reusable)) {
            // Supprimer toute la branche de configuration, pas les vrais outils de la recette.
            chemin.remove(node);
            return null;
        }
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
            for (Object child : nodes) {
                var enfant = lire(child, chemin, key, depth + 1, count, recipe);
                if (enfant != null) enfants.add(enfant);
            }
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
            if ((current == null && !fr.assistantia.PauseObjectifEmi.enPause())
                    || connexion != mc.getConnection()
                    || !dim.equals(dimension)) {
                lots.clear();
                receptionManuelle.vider();
                lotsVersion++;
                InventaireArbreEmi.invalider();
            }
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
                // Un changement de recette ou de quantité ne retire rien des machines.
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
                if (lot.ici(ui.position())
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
                            locales.size(),
                            46,
                            104);
            page = Math.min(page, (locales.size() - 1) / disposition.parPage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Un panneau indisponible ne doit jamais interrompre le rendu du jeu entier.
            disposition = null;
            plan = null;
            locales = List.of();
            if (!erreurInterfaceSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : panneau machine indisponible", e);
                erreurInterfaceSignalee = true;
            }
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
                                                : "Lot en cours · récupère le résultat à sa sortie")
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
        PanneauAssistant.bouton(
                g,
                font,
                d.tout(),
                "En cours de craft",
                libre && selected.fois() > 0 && declarables(selected.recette()) > 0,
                false,
                mx,
                my);
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
                                    selected.fois() <= 0
                                            ? "Déjà déclaré. Le suivi global d'EMI permet d'annuler cette réservation."
                                            : EnvoiMachine.occupe()
                                                    ? "Attends la fin de l'envoi."
                                                    : !fr.assistantia.InventaireJoueur.synchronise()
                                                            ? "Lecture du sac en cours…"
                                                            : declarables(selected.recette()) == 0
                                                                    ? "Déclaration manuelle disponible pour les recettes avec un résultat objet garanti."
                                                                    : "Déclarer une quantité déjà lancée. Les ingrédients ne seront plus demandés ; récupère ensuite le résultat."));
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

    private static boolean circuitARegler(EmiRecipe recette) {
        var selection = selection();
        return selection != null
                && selection.recette() == recette
                && plan != null
                && plan.probleme().startsWith("Circuit machine");
    }

    private static String etatSelection() {
        var op = selection();
        if (op == null) return "À collecter";
        if (op.fois() > 0) {
            if (circuitARegler(op.recette())) return "Circuit à régler";
            return plan != null && plan.possible() ? "À envoyer" : "À collecter";
        }
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
                                () ->
                                        envoyee(
                                                op.recette(),
                                                envoi.fois(),
                                                op.fois(),
                                                ui.position(),
                                                ecran));
                } catch (ReflectiveOperationException ex) {
                    actualise = 0;
                }
            }
        } else if (d.tout().contient(x, y)) {
            var op = selection();
            if (op != null && op.fois() > 0 && declarables(op.recette()) > 0) {
                PanneauAssistant.son();
                Minecraft.getInstance()
                        .setScreen(new DeclarerFabricationEcran(ecran, op.recette()));
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
