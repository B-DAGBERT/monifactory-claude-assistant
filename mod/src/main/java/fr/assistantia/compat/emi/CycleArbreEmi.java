package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.AideCoffre;
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

/** Durée de vie du craft EMI, indépendante de la discussion et du bouton Scanner coffres. */
public final class CycleArbreEmi {
    private static Object precedent, connexion;
    private static String signature = "", identite = "", dimension = "";
    private static SuiviFabrication<ItemStack> suivi;
    private static boolean erreurSignalee;

    private record Cibles(
            String signature, String identite, List<PlanRetrait.Besoin<ItemStack>> besoins) {}

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
            OrigineFabrication.vider();
            precedent = connexion = null;
            suivi = null;
            signature = "";
            return;
        }
        Object tree = CoffresArbreEmi.arbreActif();
        String dim = mc.level.dimension().location().toString();
        if (tree != precedent || connexion != mc.getConnection() || !dim.equals(dimension)) {
            if (precedent != null
                    && (tree == null || connexion != mc.getConnection() || !dim.equals(dimension)))
                nettoyer();
            boolean conserver =
                    tree != null && connexion == mc.getConnection() && dim.equals(dimension);
            dimension = dim;
            OrigineFabrication.vider();
            precedent = tree;
            connexion = mc.getConnection();
            if (!conserver) {
                suivi = null;
                signature = identite = "";
            }
        }
        if (tree == null) {
            AideCoffre.vider();
            return;
        }
        try {
            Cibles cibles = cibles(tree);
            if (suivi == null || !signature.equals(cibles.signature())) {
                signature = cibles.signature();
                if (suivi != null && identite.equals(cibles.identite()))
                    suivi.quantites(cibles.besoins());
                else
                    suivi =
                            new SuiviFabrication<>(
                                    cibles.besoins(),
                                    true,
                                    (a, b) ->
                                            a.getItem() == b.getItem()
                                                    && cibles.besoins().stream()
                                                            .allMatch(
                                                                    c ->
                                                                            c.accepte().test(a)
                                                                                    == c.accepte()
                                                                                            .test(
                                                                                                    b)));
                identite = cibles.identite();
            }
            if (RetraitCoffre.occupe()
                    || EnvoiMachine.occupe()
                    || !fr.assistantia.InventaireJoueur.synchronise())
                return; // Attendre la fin des clics et leur confirmation.
            List<PlanRetrait.Stock<ItemStack>> stock = new ArrayList<>();
            for (ItemStack item : fr.assistantia.InventaireJoueur.piles()) ajouter(stock, item);
            OrigineFabrication.actualiser(stock);
            MachinesEmi.recevoir(OrigineFabrication.receptions());
            boolean fini = suivi.actualiser(stock, OrigineFabrication.receptions());
            if (fini && CoffresArbreEmi.arbreActif() == tree) {
                MachinesEmi.termine();
                nettoyer();
                retirerArbre();
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

    /** Fin de craft : l'arbre terminé quitte EMI, sans sélection à réactiver depuis un cache. */
    private static void retirerArbre() {
        try {
            Class<?> bom = Class.forName("dev.emi.emi.bom.BoM");
            var mc = Minecraft.getInstance();
            if (mc.screen != null
                    && mc.screen.getClass().getName().equals("dev.emi.emi.screen.BoMScreen"))
                mc.screen.onClose(); // Ne pas laisser l'écran EMI lire un arbre devenu nul.
            bom.getField("tree").set(null, null);
            bom.getField("craftingMode").setBoolean(null, false);
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("Assistant : arrêt de l’arbre EMI impossible", e);
        }
    }

    private static void ajouter(List<PlanRetrait.Stock<ItemStack>> stock, ItemStack item) {
        if (!item.isEmpty()) stock.add(new PlanRetrait.Stock<>(item.copy(), item.getCount(), true));
    }

    private static Cibles cibles(Object tree) throws ReflectiveOperationException {
        Object root = tree.getClass().getField("goal").get(tree);
        long batches = Math.max(1, tree.getClass().getField("batches").getLong(tree));
        var ingredient = (EmiIngredient) root.getClass().getField("ingredient").get(root);
        var variantes =
                ingredient.getEmiStacks().stream()
                        .filter(s -> !s.getItemStack().isEmpty())
                        .map(EmiStack::copy)
                        .toList();
        // Une cible fluide ou inconnue ne peut pas être déclarée obtenue par l'inventaire.
        if (variantes.isEmpty()) return new Cibles("non-objet", "non-objet", List.of());
        int quantite = multiplier(root.getClass().getField("amount").getLong(root), batches);
        if (quantite <= 0) return new Cibles("invalide", "invalide", List.of());
        StringBuilder key = new StringBuilder(), identite = new StringBuilder();
        key.append(quantite).append(':');
        for (var variante : variantes) {
            key.append(variante.getId()).append(variante.getNbt()).append(';');
            identite.append(variante.getId()).append(variante.getNbt()).append(';');
        }
        key.append('|');
        List<PlanRetrait.Besoin<ItemStack>> besoins =
                List.of(
                        new PlanRetrait.Besoin<>(
                                item ->
                                        variantes.stream()
                                                .anyMatch(v -> EmiStack.of(item).isEqual(v)),
                                quantite));
        return new Cibles(identite + "|" + key, identite.toString(), besoins);
    }

    private static int multiplier(long a, long b) {
        return (int)
                Math.min(
                        1_000_000_000L,
                        Math.min(1_000_000_000L, Math.max(0, a))
                                * Math.min(1_000_000_000L, Math.max(0, b)));
    }
}
