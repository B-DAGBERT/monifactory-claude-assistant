package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanRetrait;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Stock de calcul de l'arbre uniquement. Les remplisseurs et transferts gardent leurs vraies cases.
 */
public final class InventaireArbreEmi {
    private static String precedent = "";
    private static Object ecran, connexion;
    private static int ticks;
    private static boolean erreurSignalee;
    private static Object arbrePrecedent;
    private static final PlisFabricationEmi PLIS = new PlisFabricationEmi();
    private static final Map<EmiPlayerInventory, EmiPlayerInventory> ORIGINES = new WeakHashMap<>();

    public static void invalider() {
        precedent = "";
        ticks = 9;
    }

    public static EmiPlayerInventory completer(EmiPlayerInventory natif) {
        // Le mixin des favoris peut repasser sur une vue déjà enrichie : repartir de sa source.
        natif = ORIGINES.getOrDefault(natif, natif);
        var mc = Minecraft.getInstance();
        if (mc.player == null || !InventaireJoueur.disponible()) return natif;
        var resultat = composer(InventaireJoueur.piles(), grilles());
        var reservations = stocksVirtuels(resultat);
        if (InventaireJoueur.sacs().isEmpty() && reservations.isEmpty()) return natif;
        // Conserver les sources fluides qu'un handler natif sait déjà exposer à l'arbre.
        for (var pile : natif.inventory.values())
            if (pile.getItemStack().isEmpty()) {
                var copie = pile.copy();
                resultat.inventory.merge(
                        copie, copie, (a, c) -> a.copy().setAmount(a.getAmount() + c.getAmount()));
            }
        for (var reserve : reservations) {
            var pile = ComparaisonEmi.pourInventaire(reserve.objet()).setAmount(reserve.quantite());
            resultat.inventory.merge(
                    pile, pile, (a, b) -> a.copy().setAmount(a.getAmount() + b.getAmount()));
        }
        ORIGINES.put(resultat, natif);
        return resultat;
    }

    private static List<ItemStack> grilles() {
        var grilles = new ArrayList<ItemStack>();
        Set<CraftingContainer> vues = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var slot : Minecraft.getInstance().player.containerMenu.slots)
            if (slot.container instanceof CraftingContainer grille && vues.add(grille))
                for (int i = 0; i < grille.getContainerSize(); i++) grilles.add(grille.getItem(i));
        return grilles;
    }

    private static List<PlanRetrait.Stock<EmiStack>> stocksVirtuels(EmiPlayerInventory personnels) {
        var physiques =
                personnels.inventory.values().stream()
                        .map(
                                s ->
                                        new PlanRetrait.Stock<>(
                                                s.copy(),
                                                (int) Math.min(1_000_000L, s.getAmount()),
                                                true))
                        .toList();
        var finaux =
                CycleArbreEmi.credits().stream()
                        .map(
                                c ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(c.objet()), c.quantite(), true))
                        .toList();
        var resultat = new ArrayList<>(MachinesEmi.reservationsArbre());
        resultat.addAll(finaux);
        resultat.addAll(MachinesEmi.creditsAcquis(physiques, finaux));
        return List.copyOf(resultat);
    }

    public static EmiPlayerInventory composer(List<ItemStack> personnels, List<ItemStack> grilles) {
        var resultat = new EmiPlayerInventory(List.of());
        // Le constructeur peut ajouter le curseur : il figure déjà dans la vue personnelle.
        resultat.inventory.clear();
        for (var source : List.of(personnels, grilles))
            for (var item : source) {
                if (item.isEmpty()) continue;
                var pile = ComparaisonEmi.pourInventaire(EmiStack.of(item.copy()));
                resultat.inventory.merge(
                        pile, pile, (a, b) -> a.copy().setAmount(a.getAmount() + b.getAmount()));
            }
        return resultat;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 10 != 0) return;
        var mc = Minecraft.getInstance();
        Object arbre = CoffresArbreEmi.arbreActif();
        try {
            if (mc.player == null || mc.level == null || arbre == null) {
                PLIS.actualiser(null, List.of(), List.of());
                precedent = "";
                connexion = arbrePrecedent = null;
                ecran = null;
                return;
            }
            if (!InventaireJoueur.disponible()) return;
            var personnels = composer(InventaireJoueur.piles(), grilles());
            var reservations = stocksVirtuels(personnels);
            var physiques =
                    personnels.inventory.values().stream()
                            .map(
                                    s ->
                                            new PlanRetrait.Stock<>(
                                                    s.copy(),
                                                    (int) Math.min(1_000_000L, s.getAmount()),
                                                    true))
                            .toList();
            boolean plisChanges = PLIS.actualiser(arbre, physiques, reservations);
            String cle =
                    physiques.stream()
                                    .map(
                                            s ->
                                                    s.objet().getId()
                                                            + "|"
                                                            + s.objet().getNbt()
                                                            + "|"
                                                            + s.quantite())
                                    .sorted()
                                    .toList()
                                    .toString()
                            + reservations.stream()
                                    .map(
                                            s ->
                                                    s.objet().getId()
                                                            + "|"
                                                            + s.objet().getNbt()
                                                            + "|"
                                                            + s.quantite())
                                    .sorted()
                                    .toList();
            if (!plisChanges
                    && cle.equals(precedent)
                    && arbrePrecedent == arbre
                    && ecran == mc.screen
                    && connexion == mc.getConnection()) return;
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
            precedent = cle;
            arbrePrecedent = arbre;
            ecran = mc.screen;
            connexion = mc.getConnection();
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
