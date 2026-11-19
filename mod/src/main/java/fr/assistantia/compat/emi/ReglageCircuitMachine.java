package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Action du configurateur GregTech, confirmée par la case synchronisée du menu. */
public final class ReglageCircuitMachine {
    private static final String PANNEAU = "com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel";
    private static final String CONFIGURATEUR =
            "com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator";
    private static final long DELAI = 4_000_000_000L;

    record Commande(Object bouton, Method action, int operation, int index, int valeur) {
        void transmettre() throws ReflectiveOperationException {
            // La molette native émet la même action, mais prédit aussi la valeur côté client.
            action.invoke(
                    bouton, operation, (Consumer<FriendlyByteBuf>) buf -> buf.writeVarInt(valeur));
        }
    }

    private record Demande(
            AbstractContainerScreen<?> ecran,
            Connection connexion,
            Object monde,
            Commande commande,
            long echeance) {
        boolean courante() {
            var mc = Minecraft.getInstance();
            return mc.screen == ecran
                    && mc.level == monde
                    && mc.player != null
                    && mc.player.containerMenu == ecran.getMenu()
                    && mc.getConnection() != null
                    && mc.getConnection().getConnection() == connexion;
        }
    }

    private static Demande demande;
    private static boolean confirmee;

    private ReglageCircuitMachine() {}

    static Commande preparer(
            AbstractContainerMenu menu, GregTechMachines.Interface ui, EmiRecipe recette) {
        if (ui == null || !menu.getCarried().isEmpty()) return null;
        try {
            if (!GregTechMachines.compatible(ui.machine(), recette)
                    || GregTechMachines.occupe(ui.machine())
                    || Boolean.FALSE.equals(
                            GregTechMachines.appeler(ui.machine(), "isCircuitSlotEnabled")))
                return null;
            int valeur = EnvoiMachine.circuitRequis(recette);
            if (valeur < 0 || valeur > 32 || GregTechMachines.circuitConfigure(ui) == valeur)
                return null;
            var circuit = GregTechMachines.appeler(ui.machine(), "getCircuitInventory");
            var stockage = GregTechMachines.champ(circuit.getClass(), "storage").get(circuit);
            int index = indexSynchronise(menu, stockage);
            if (index < 0) return null;
            Object interfaceNative = GregTechMachines.appeler(menu, "getModularUI");
            for (var widget :
                    (List<?>)
                            GregTechMachines.appeler(
                                    interfaceNative, "getFlatVisibleWidgetCollection")) {
                if (!widget.getClass().getName().equals(PANNEAU)) continue;
                for (var tab : (List<?>) GregTechMachines.appeler(widget, "getTabs")) {
                    var config = GregTechMachines.champ(tab.getClass(), "configurator").get(tab);
                    if (!config.getClass().getName().equals(CONFIGURATEUR)
                            || GregTechMachines.champ(config.getClass(), "circuitSlot").get(config)
                                    != stockage) continue;
                    var bouton = GregTechMachines.champ(tab.getClass(), "button").get(tab);
                    if (!bouton.getClass().getName().equals(PANNEAU + "$Tab$1")) continue;
                    Class<?> base = Class.forName("com.lowdragmc.lowdraglib.gui.widget.Widget");
                    var action =
                            base.getDeclaredMethod("writeClientAction", int.class, Consumer.class);
                    action.setAccessible(true);
                    int operation =
                            GregTechMachines.champ(config.getClass(), "SET_TO_N").getInt(null);
                    return new Commande(bouton, action, operation, index, valeur);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Garder le réglage manuel si la version du widget n'est pas reconnue.
        }
        return null;
    }

    private static int indexSynchronise(AbstractContainerMenu menu, Object stockage)
            throws ReflectiveOperationException {
        for (var slot : menu.slots) {
            if (slot.getContainerSlot() != 0) continue;
            try {
                if (GregTechMachines.appeler(slot, "getItemHandler") == stockage) return slot.index;
            } catch (NoSuchMethodException ignored) {
                // GTCEu expose la méthode sur sa propre Slot, pas sur les cases vanilla.
            }
        }
        return -1;
    }

    static boolean enCours() {
        return demande != null;
    }

    static void lancer(AbstractContainerScreen<?> ecran, Commande commande)
            throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (enCours() || mc.getConnection() == null || mc.level == null) return;
        var suivante =
                new Demande(
                        ecran,
                        mc.getConnection().getConnection(),
                        mc.level,
                        commande,
                        System.nanoTime() + DELAI);
        if (!suivante.courante()) return;
        commande.transmettre();
        demande = suivante;
        confirmee = false;
    }

    /** Retourne la fin de l'attente afin de recalculer le panneau au tick suivant. */
    static String actualiser() {
        if (demande == null) return null;
        boolean courante = demande.courante();
        if (courante && !confirmee && System.nanoTime() - demande.echeance() < 0) return null;
        demande = null;
        return courante && !confirmee ? "Réglage du circuit non confirmé. Réessaie." : "";
    }

    public static void confirmer(Connection connexion, int menu, int index, ItemStack pile) {
        var reception = demande;
        if (reception == null
                || connexion != reception.connexion()
                || menu != reception.ecran().getMenu().containerId
                || index != reception.commande().index()
                || System.nanoTime() - reception.echeance() >= 0
                || !reception.courante()) return;
        try {
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(pile.getItem());
            confirmee =
                    !pile.isEmpty()
                            && id != null
                            && id.toString().equals("gtceu:programmed_circuit")
                            && GregTechMachines.circuit(pile) == reception.commande().valeur();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            confirmee = false;
        }
    }

    public static void confirmer(Connection connexion, int menu, List<ItemStack> piles) {
        var reception = demande;
        if (reception != null && reception.commande().index() < piles.size()) {
            int index = reception.commande().index();
            confirmer(connexion, menu, index, piles.get(index));
        }
    }
}
