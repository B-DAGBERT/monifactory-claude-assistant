package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.InventaireJoueur;
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

    public static EmiPlayerInventory completer(EmiPlayerInventory natif) {
        var mc = Minecraft.getInstance();
        if (mc.player == null
                || !InventaireJoueur.disponible()
                || InventaireJoueur.sacs().isEmpty()) return natif;
        // Reconstruction depuis la vue commune : un backpack ouvert ne doit pas être ajouté
        // une deuxième fois aux cases que son handler EMI a déjà comptées.
        var grilles = new ArrayList<ItemStack>();
        Set<CraftingContainer> vues = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var slot : mc.player.containerMenu.slots)
            if (slot.container instanceof CraftingContainer grille && vues.add(grille))
                for (int i = 0; i < grille.getContainerSize(); i++) grilles.add(grille.getItem(i));
        var resultat = composer(InventaireJoueur.piles(), grilles);
        // Conserver les sources fluides qu'un handler natif sait déjà exposer à l'arbre.
        for (var pile : natif.inventory.values())
            if (pile.getItemStack().isEmpty()) {
                var copie = pile.copy();
                resultat.inventory.merge(
                        copie, copie, (a, c) -> a.copy().setAmount(a.getAmount() + c.getAmount()));
            }
        return resultat;
    }

    public static EmiPlayerInventory composer(List<ItemStack> personnels, List<ItemStack> grilles) {
        var resultat = new EmiPlayerInventory(List.of());
        // Le constructeur peut ajouter le curseur : il figure déjà dans la vue personnelle.
        resultat.inventory.clear();
        for (var source : List.of(personnels, grilles))
            for (var item : source) {
                if (item.isEmpty()) continue;
                var pile =
                        EmiStack.of(item.copy())
                                .comparison(
                                        c ->
                                                c == Comparison.DEFAULT_COMPARISON
                                                        ? Comparison.compareNbt()
                                                        : c);
                resultat.inventory.merge(
                        pile, pile, (a, b) -> a.copy().setAmount(a.getAmount() + b.getAmount()));
            }
        return resultat;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 10 != 0) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            precedent = "";
            connexion = null;
            ecran = null;
            return;
        }
        if (!InventaireJoueur.disponible() || CoffresArbreEmi.arbreActif() == null) return;
        String cle =
                InventaireJoueur.piles().stream()
                        .map(s -> EmiStack.of(s).getId() + "|" + s.getTag() + "|" + s.getCount())
                        .sorted()
                        .toList()
                        .toString();
        if (cle.equals(precedent) && ecran == mc.screen && connexion == mc.getConnection()) return;
        try {
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
            ecran = mc.screen;
            connexion = mc.getConnection();
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn(
                                "Assistant : inventaire du backpack dans l'arbre EMI indisponible",
                                e);
                erreurSignalee = true;
            }
        }
    }
}
