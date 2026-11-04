package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.AideCoffre;
import fr.assistantia.EmiObjectif;
import fr.assistantia.PlanRetrait;
import fr.assistantia.RechercheEmi;
import fr.assistantia.RetraitCoffre;
import fr.assistantia.SuiviFabrication;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Durée de vie du craft EMI, indépendante de l'objectif IA et du bouton Scanner coffres. */
public final class CycleArbreEmi {
    private static Object precedent, connexion;
    private static String signature = "";
    private static SuiviFabrication<ItemStack> suivi;
    private static boolean erreurSignalee;

    // objectif : arbre d'un objectif IA, dont les quantités incluent déjà ce que le joueur possède.
    private record Cibles(
            String signature, List<PlanRetrait.Besoin<ItemStack>> besoins, boolean objectif) {}

    public static List<PlanRetrait.Stock<ItemStack>> credits() {
        return suivi == null || CoffresArbreEmi.arbreActif() != precedent
                ? List.of()
                : suivi.credits();
    }

    private static void nettoyer() {
        RechercheEmi.arreter();
        RetraitCoffre.arreter();
        AideCoffre.vider();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (precedent != null) nettoyer();
            precedent = connexion = null;
            suivi = null;
            signature = "";
            return;
        }
        Object tree = CoffresArbreEmi.arbreActif();
        if (tree != precedent || connexion != mc.getConnection()) {
            if (precedent != null) nettoyer();
            precedent = tree;
            connexion = mc.getConnection();
            suivi = null;
            signature = "";
        }
        if (tree == null) {
            AideCoffre.vider();
            return;
        }
        try {
            Cibles cibles = cibles(tree);
            if (suivi == null || !signature.equals(cibles.signature())) {
                if (suivi != null) nettoyer(); // Quantités modifiées : ne pas garder l'ancien scan.
                signature = cibles.signature();
                suivi =
                        new SuiviFabrication<>(
                                cibles.besoins(),
                                !cibles.objectif(),
                                (a, b) ->
                                        a.getItem() == b.getItem()
                                                && cibles.besoins().stream()
                                                        .allMatch(
                                                                c ->
                                                                        c.accepte().test(a)
                                                                                == c.accepte()
                                                                                        .test(b)));
            }
            if (RetraitCoffre.occupe() || EnvoiMachine.occupe())
                return; // Attendre la fin des clics et leur confirmation.
            List<PlanRetrait.Stock<ItemStack>> stock = new ArrayList<>();
            for (ItemStack item : mc.player.getInventory().items) ajouter(stock, item);
            for (ItemStack item : mc.player.getInventory().armor) ajouter(stock, item);
            for (ItemStack item : mc.player.getInventory().offhand) ajouter(stock, item);
            // Un craft normal peut arriver sur le curseur, un Maj-clic dans l'inventaire.
            // Ne jamais lire la case de résultat : ce n'est qu'un aperçu non encore fabriqué.
            ajouter(stock, mc.player.containerMenu.getCarried());
            if (suivi.actualiser(stock) && CoffresArbreEmi.arbreActif() == tree) {
                nettoyer();
                EmiObjectif.terminerArbre(tree);
                precedent = null;
                suivi = null;
                signature = "";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurSignalee) {
                LogUtils.getLogger()
                        .warn("Assistant : suivi du résultat final EMI indisponible", e);
                erreurSignalee = true;
            }
        }
    }

    private static void ajouter(List<PlanRetrait.Stock<ItemStack>> stock, ItemStack item) {
        if (!item.isEmpty()) stock.add(new PlanRetrait.Stock<>(item.copy(), item.getCount(), true));
    }

    private static Cibles cibles(Object tree) throws ReflectiveOperationException {
        Object root = tree.getClass().getField("goal").get(tree);
        long batches = Math.max(1, tree.getClass().getField("batches").getLong(tree));
        var ingredient = (EmiIngredient) root.getClass().getField("ingredient").get(root);
        boolean virtuelle =
                ingredient.getEmiStacks().stream()
                        .anyMatch(
                                stack -> {
                                    var item = stack.getItemStack();
                                    return item.hasTag()
                                            && item.getTag().contains("assistantia_objectif");
                                });
        List<?> nodes =
                virtuelle
                        ? (List<?>) root.getClass().getField("children").get(root)
                        : List.of(root);
        if (virtuelle) {
            long quantite = multiplier(root.getClass().getField("amount").getLong(root), batches);
            long production = Math.max(1, root.getClass().getField("divisor").getLong(root));
            batches = quantite / production + (quantite % production == 0 ? 0 : 1);
        }
        StringBuilder key = new StringBuilder();
        List<PlanRetrait.Besoin<ItemStack>> besoins = new ArrayList<>();
        for (Object node : nodes) {
            var cible = (EmiIngredient) node.getClass().getField("ingredient").get(node);
            var variantes =
                    cible.getEmiStacks().stream()
                            .filter(s -> !s.getItemStack().isEmpty())
                            .map(EmiStack::copy)
                            .toList();
            // Une cible fluide ou inconnue ne peut pas être déclarée obtenue par l'inventaire.
            if (variantes.isEmpty()) return new Cibles("non-objet", List.of(), virtuelle);
            int quantite = multiplier(node.getClass().getField("amount").getLong(node), batches);
            if (quantite <= 0) return new Cibles("invalide", List.of(), virtuelle);
            key.append(quantite).append(':');
            for (var variante : variantes)
                key.append(variante.getId()).append(variante.getNbt()).append(';');
            key.append('|');
            besoins.add(
                    new PlanRetrait.Besoin<>(
                            item -> variantes.stream().anyMatch(v -> EmiStack.of(item).isEqual(v)),
                            quantite));
        }
        return new Cibles(key.toString(), besoins, virtuelle);
    }

    private static int multiplier(long a, long b) {
        return (int)
                Math.min(
                        1_000_000_000L,
                        Math.min(1_000_000_000L, Math.max(0, a))
                                * Math.min(1_000_000_000L, Math.max(0, b)));
    }
}
