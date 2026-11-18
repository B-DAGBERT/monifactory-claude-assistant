package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.CoffreTampon;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanRetrait;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Stock de calcul de l'arbre uniquement. Les remplisseurs et transferts gardent leurs vraies cases.
 */
public final class InventaireArbreEmi {
    private record Sources(
            InstantaneArbreEmi arbre,
            Object niveau,
            Object connexion,
            long inventaire,
            long tampon,
            long lots,
            List<PlanRetrait.Stock<ItemStack>> reseau,
            List<PlanRetrait.Stock<ItemStack>> credits,
            List<PlanRetrait.Stock<EmiStack>> outils) {}

    private static Sources precedent;
    private static List<ItemStack> grillePrecedente = List.of();
    private static Object ecran;
    private static int ticks;
    private static boolean erreurSignalee;
    private static final PlisFabricationEmi PLIS = new PlisFabricationEmi();
    private static final Map<EmiPlayerInventory, EmiPlayerInventory> ORIGINES = new WeakHashMap<>();

    public static void invalider() {
        precedent = null;
        ticks = 9;
    }

    public static EmiPlayerInventory completer(EmiPlayerInventory natif) {
        // Le mixin des favoris peut repasser sur une vue déjà enrichie : repartir de sa source.
        natif = ORIGINES.getOrDefault(natif, natif);
        var mc = Minecraft.getInstance();
        if (mc.player == null || !InventaireJoueur.disponible()) return natif;
        var resultat = personnel();
        var stock = StockFabricationEmi.courant(physiques(resultat), TerminalMe.stock());
        var reservations = stock.virtuels();
        if (InventaireJoueur.sacs().isEmpty()
                && stock.reseau().isEmpty()
                && stock.tampon().isEmpty()
                && reservations.isEmpty()
                && resultat.inventory.values().stream()
                        .noneMatch(pile -> pile.getKey() instanceof Fluid)) return natif;
        // Le handler natif peut déjà représenter les mêmes fluides portés, sans indiquer leur
        // origine.
        for (var pile : natif.inventory.values())
            if (pile.getKey() instanceof Fluid) {
                var copie = ComparaisonEmi.pourInventaire(pile);
                resultat.inventory.merge(
                        copie, copie, (a, b) -> a.getAmount() >= b.getAmount() ? a : b);
            }
        ajouter(resultat, stock.tampon());
        ajouter(resultat, stock.reseau());
        ajouter(resultat, reservations);
        ORIGINES.put(resultat, natif);
        return resultat;
    }

    static EmiPlayerInventory personnel() {
        return composer(InventaireJoueur.piles(), grilles());
    }

    private static List<ItemStack> grilles() {
        var grilles = new ArrayList<ItemStack>();
        Set<CraftingContainer> vues = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var slot : Minecraft.getInstance().player.containerMenu.slots)
            if (slot.container instanceof CraftingContainer grille && vues.add(grille))
                for (int i = 0; i < grille.getContainerSize(); i++) grilles.add(grille.getItem(i));
        return grilles;
    }

    static List<PlanRetrait.Stock<EmiStack>> physiques(EmiPlayerInventory personnels) {
        return personnels.inventory.values().stream()
                .map(
                        s ->
                                new PlanRetrait.Stock<>(
                                        s, (int) Math.min(1_000_000L, s.getAmount()), true))
                .toList();
    }

    public static EmiPlayerInventory composer(List<ItemStack> personnels, List<ItemStack> grilles) {
        var resultat = new EmiPlayerInventory(List.of());
        // Le constructeur peut ajouter le curseur : il figure déjà dans la vue personnelle.
        resultat.inventory.clear();
        for (var source : List.of(personnels, grilles))
            for (var item : source) {
                if (item.isEmpty()) continue;
                ajouter(resultat, EmiStack.of(item.copy()));
                // Forge simule la vidange d'une copie d'un seul contenant.
                FluidUtil.getFluidContained(item)
                        .ifPresent(
                                fluide ->
                                        ajouter(
                                                resultat,
                                                EmiStack.of(
                                                        fluide.getFluid(),
                                                        fluide.getTag(),
                                                        (long) fluide.getAmount()
                                                                * item.getCount())));
            }
        return resultat;
    }

    private static void ajouter(EmiPlayerInventory stock, EmiStack pile) {
        var cle = ComparaisonEmi.pourInventaire(pile);
        stock.inventory.merge(
                cle, cle, (a, b) -> a.copy().setAmount(a.getAmount() + b.getAmount()));
    }

    private static void ajouter(
            EmiPlayerInventory inventaire, List<PlanRetrait.Stock<EmiStack>> stocks) {
        for (var stock : stocks)
            if (stock.quantite() > 0)
                ajouter(inventaire, stock.objet().copy().setAmount(stock.quantite()));
    }

    private static boolean memeGrille(List<ItemStack> grille) {
        if (grille.size() != grillePrecedente.size()) return false;
        for (int i = 0; i < grille.size(); i++)
            if (!ItemStack.matches(grille.get(i), grillePrecedente.get(i))) return false;
        return true;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 10 != 0) return;
        var mc = Minecraft.getInstance();
        Object arbre = CoffresArbreEmi.arbreActif();
        try {
            if (mc.player == null || mc.level == null || arbre == null) {
                PLIS.actualiser(null, List.of(), List.of());
                precedent = null;
                grillePrecedente = List.of();
                ecran = null;
                return;
            }
            if (!InventaireJoueur.disponible()) return;
            var instantane = InstantaneArbreEmi.lire(arbre);
            var reseau = TerminalMe.stock();
            var sources =
                    new Sources(
                            instantane,
                            mc.level,
                            mc.getConnection(),
                            InventaireJoueur.revision(),
                            CoffreTampon.revision(),
                            MachinesEmi.revisionLotsProduction(),
                            reseau,
                            CycleArbreEmi.credits(),
                            MachinesEmi.outilsInstalles());
            var grille = grilles();
            boolean modifie = !sources.equals(precedent) || !memeGrille(grille);
            boolean plisChanges;
            if (modifie) {
                var personnels = composer(InventaireJoueur.piles(), grille);
                var stock = StockFabricationEmi.courant(physiques(personnels), reseau);
                ajouter(personnels, stock.tampon());
                ajouter(personnels, stock.reseau());
                plisChanges = PLIS.actualiser(arbre, physiques(personnels), stock.virtuels());
                grillePrecedente = grille.stream().map(ItemStack::copy).toList();
            } else {
                plisChanges = PLIS.surveiller();
            }
            if (!modifie && !plisChanges && ecran == mc.screen) return;
            // EMI n'invalide pas toujours sa liste lorsque seul le contenu du sac a changé.
            Class.forName("dev.emi.emi.runtime.EmiFavorites")
                    .getMethod("updateSynthetic", EmiPlayerInventory.class)
                    .invoke(null, completer(EmiPlayerInventory.of(mc.player)));
            var type = Class.forName("dev.emi.emi.config.SidebarType");
            Class.forName("dev.emi.emi.screen.EmiScreenManager")
                    .getMethod("repopulatePanels", type)
                    .invoke(null, type.getField("CRAFTABLES").get(null));
            if (mc.screen != null
                    && mc.screen.getClass().getName().equals("dev.emi.emi.screen.BoMScreen"))
                mc.screen.getClass().getMethod("recalculateTree").invoke(mc.screen);
            precedent = sources;
            ecran = mc.screen;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn(
                                "Assistant : inventaire et fabrications dans l'arbre EMI indisponibles",
                                e);
                erreurSignalee = true;
            }
        }
    }
}
