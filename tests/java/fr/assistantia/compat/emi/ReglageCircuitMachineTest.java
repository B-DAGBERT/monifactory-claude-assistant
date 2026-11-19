package fr.assistantia.compat.emi;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.widget.SlotWidget.WidgetSlotItemHandler;
import dev.emi.emi.api.recipe.EmiRecipe;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ReglageCircuitMachineTest {
    private static final String CIRCUIT = "gtceu:programmed_circuit";
    private static final EmiRecipe RECETTE = new EmiRecipe(7);
    private static final int SLOT = 2;
    private final Minecraft jeu = Minecraft.getInstance();
    private final GregTechMachines.Machine machine = new GregTechMachines.Machine();
    private final GregTechMachines.Interface ui = new GregTechMachines.Interface(machine);
    private final ConfiguratorPanel.Tab autre = new ConfiguratorPanel.Tab(new Object());
    private final ConfiguratorPanel.Tab circuit = new ConfiguratorPanel.Tab(machine.getStockage());
    private final Menu menu = new Menu(new ConfiguratorPanel(autre, circuit));
    private final AbstractContainerScreen<Menu> ecran = new AbstractContainerScreen<>(menu);
    private final Slot slotCircuit = new WidgetSlotItemHandler(machine.getStockage(), SLOT, 0);

    public static final class Menu extends AbstractContainerMenu {
        private final Native widgets;

        Menu(ConfiguratorPanel panneau) {
            widgets = new Native(panneau);
        }

        public Native getModularUI() {
            return widgets;
        }
    }

    public record Native(ConfiguratorPanel panneau) {
        public List<ConfiguratorPanel> getFlatVisibleWidgetCollection() {
            return List.of(panneau);
        }
    }

    private ReglageCircuitMachineTest() {
        jeu.screen = ecran;
        jeu.player.containerMenu = menu;
        menu.slots.add(new Slot(new Object(), 0));
        menu.slots.add(new WidgetSlotItemHandler(machine.getStockage(), 1, 1));
        menu.slots.add(slotCircuit);
    }

    private static ItemStack pile(String id, int valeur) {
        var pile = new ItemStack(new Item(id), 1);
        pile.tag = new CompoundTag(Integer.toString(valeur));
        return pile;
    }

    private ReglageCircuitMachine.Commande preparer() {
        return ReglageCircuitMachine.preparer(menu, ui, RECETTE);
    }

    private static void verifierAttente() {
        assert ReglageCircuitMachine.actualiser() == null && ReglageCircuitMachine.enCours();
    }

    private void verifierPreconditions() {
        var commande = preparer();
        assert commande != null && commande.bouton() == circuit.button;
        assert commande.operation() == 4 && commande.index() == SLOT && commande.valeur() == 7;
        machine.compatible = false;
        assert preparer() == null;
        machine.compatible = true;
        machine.occupe = true;
        assert preparer() == null;
        machine.occupe = false;
        machine.circuitActif = false;
        assert preparer() == null;
        machine.circuitActif = true;
        machine.valeurDuCircuit = 7;
        assert preparer() == null : "le circuit déjà bon n'émet aucune commande";
        machine.valeurDuCircuit = 2;
        menu.carried = pile("minecraft:stick", 0);
        assert preparer() == null;
        menu.carried = ItemStack.EMPTY;
        menu.slots.remove(slotCircuit);
        assert preparer() == null : "il faut une case synchronisée du même handler";
        menu.slots.add(slotCircuit);
        for (int valeur : new int[] {-1, 33})
            assert ReglageCircuitMachine.preparer(menu, ui, new EmiRecipe(valeur)) == null;
        assert autre.button.demandes == 0 && circuit.button.demandes == 0;
    }

    private void verifierAccuses() throws Exception {
        var commande = preparer();
        ReglageCircuitMachine.lancer(ecran, commande);
        ReglageCircuitMachine.lancer(ecran, commande);
        assert autre.button.demandes == 0 && circuit.button.demandes == 1;
        assert circuit.button.operation == 4 && circuit.button.valeur == 7;
        assert machine.valeurDuCircuit == 2 : "aucune prédiction de la valeur dans le client";
        machine.valeurDuCircuit = 7; // Une autre source locale ne suffit pas à lever l'attente.
        verifierAttente();
        var connexion = jeu.getConnection().getConnection();
        var correct = pile(CIRCUIT, 7);
        ReglageCircuitMachine.confirmer(new Connection(), menu.containerId, SLOT, correct);
        verifierAttente();
        ReglageCircuitMachine.confirmer(connexion, menu.containerId + 1, SLOT, correct);
        verifierAttente();
        ReglageCircuitMachine.confirmer(connexion, menu.containerId, 1, correct);
        verifierAttente();
        ReglageCircuitMachine.confirmer(connexion, menu.containerId, SLOT, pile(CIRCUIT, 6));
        verifierAttente();
        ReglageCircuitMachine.confirmer(
                connexion, menu.containerId, SLOT, pile("minecraft:stick", 7));
        verifierAttente();
        ReglageCircuitMachine.confirmer(connexion, menu.containerId, List.of(correct));
        verifierAttente();
        ReglageCircuitMachine.confirmer(connexion, menu.containerId, SLOT, correct);
        assert "".equals(ReglageCircuitMachine.actualiser()) && !ReglageCircuitMachine.enCours();

        machine.valeurDuCircuit = 2;
        ReglageCircuitMachine.lancer(ecran, preparer());
        ReglageCircuitMachine.confirmer(
                connexion, menu.containerId, List.of(ItemStack.EMPTY, ItemStack.EMPTY, correct));
        assert "".equals(ReglageCircuitMachine.actualiser()) && !ReglageCircuitMachine.enCours();
    }

    private void verifierFermeture() throws Exception {
        var commande = preparer();
        ReglageCircuitMachine.lancer(ecran, commande);
        var ancienneConnexion = jeu.getConnection().getConnection();
        jeu.screen = null;
        ReglageCircuitMachine.confirmer(
                ancienneConnexion, menu.containerId, SLOT, pile(CIRCUIT, 7));
        assert "".equals(ReglageCircuitMachine.actualiser()) && !ReglageCircuitMachine.enCours();

        jeu.screen = ecran;
        jeu.listener = new Minecraft.Listener();
        ReglageCircuitMachine.lancer(ecran, commande);
        ReglageCircuitMachine.confirmer(
                ancienneConnexion, menu.containerId, SLOT, pile(CIRCUIT, 7));
        verifierAttente();
        jeu.level = new Object();
        assert "".equals(ReglageCircuitMachine.actualiser()) && !ReglageCircuitMachine.enCours();
    }

    private void verifierEcheance() throws Exception {
        var commande = preparer();
        ReglageCircuitMachine.lancer(ecran, commande);
        var champ = ReglageCircuitMachine.class.getDeclaredField("demande");
        champ.setAccessible(true);
        var demande = champ.get(null);
        var constructeur =
                demande.getClass()
                        .getDeclaredConstructor(
                                AbstractContainerScreen.class,
                                Connection.class,
                                Object.class,
                                ReglageCircuitMachine.Commande.class,
                                long.class);
        constructeur.setAccessible(true);
        var connexion = jeu.getConnection().getConnection();
        champ.set(
                null,
                constructeur.newInstance(
                        ecran, connexion, jeu.level, commande, System.nanoTime() - 1));
        ReglageCircuitMachine.confirmer(connexion, menu.containerId, SLOT, pile(CIRCUIT, 7));
        var resultat = ReglageCircuitMachine.actualiser();
        assert resultat != null
                        && resultat.contains("non confirmé")
                        && !ReglageCircuitMachine.enCours()
                : "un paquet reçu après l'échéance ne valide pas l'ancienne commande";
    }

    public static void main(String[] args) throws Exception {
        var test = new ReglageCircuitMachineTest();
        test.verifierPreconditions();
        test.verifierAccuses();
        test.verifierFermeture();
        test.verifierEcheance();
        System.out.println("ReglageCircuitMachine OK");
    }
}
