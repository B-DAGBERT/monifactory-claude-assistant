package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorites;
import fr.assistantia.CoffreTampon;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;

/** Les mêmes observations sont rejouées par le contrat de RechercheEmi. */
public final class ParcoursStockTest {
    private static final String PLAQUE = "test:plate";
    private static final String LINGOT = "test:ingot";
    private static final PlanMachines.Noeud<EmiStack, String> ARBRE =
            new PlanMachines.Noeud<>(
                    pile -> PLAQUE.equals(pile.getId().toString()),
                    8,
                    1,
                    false,
                    "cintreuse",
                    List.of(
                            new PlanMachines.Noeud<>(
                                    pile -> LINGOT.equals(pile.getId().toString()),
                                    1,
                                    1,
                                    false,
                                    null,
                                    List.of())));

    public static void main(String[] args) throws ReflectiveOperationException {
        var client = Minecraft.getInstance();
        client.player = new Player();
        client.level = new Object();
        InventaireJoueur.sacs = List.of();
        var arbreNatif = arbreNatif();
        CoffresArbreEmi.arbre = arbreNatif;
        var stockTerminal = TerminalMe.class.getDeclaredField("stock");
        stockTerminal.setAccessible(true);

        for (var etape : args) {
            String[] donnees = etape.split(",");
            int portes = Integer.parseInt(donnees[0]);
            int tampon = Integer.parseInt(donnees[1]);
            int reseau = Integer.parseInt(donnees[2]);
            int credits = Integer.parseInt(donnees[3]);
            int attendus = Integer.parseInt(donnees[4]);
            if (Boolean.parseBoolean(donnees[5])) {
                client.connection = new Object();
                arbreNatif = arbreNatif();
                CoffresArbreEmi.arbre = arbreNatif;
            }
            int lingotsMe = Integer.parseInt(donnees[6]);

            InventaireJoueur.piles = piles(portes);
            InventaireJoueur.revision++;
            client.player.items.clear();
            client.player.items.addAll(InventaireJoueur.piles);
            CoffreTampon.piles = piles(tampon);
            CoffreTampon.revision++;
            CycleArbreEmi.acquis =
                    credits == 0
                            ? List.of()
                            : List.of(
                                    new PlanRetrait.Stock<>(
                                            new ItemStack(PLAQUE, 1), credits, true));
            MachinesEmi.reservations =
                    attendus == 0
                            ? List.of()
                            : List.of(new PlanRetrait.Stock<>(plaque(), attendus, true));
            List<PlanRetrait.Stock<ItemStack>> entrees = new ArrayList<>();
            if (reseau > 0)
                entrees.add(new PlanRetrait.Stock<>(new ItemStack(PLAQUE, 1), reseau, false));
            if (lingotsMe > 0)
                entrees.add(new PlanRetrait.Stock<>(new ItemStack(LINGOT, 1), lingotsMe, false));
            stockTerminal.set(null, entrees);

            var natif = EmiPlayerInventory.of(client.player);
            var inventaireEmi = InventaireArbreEmi.completer(natif);
            long arbre = quantite(inventaireEmi, PLAQUE);
            assert quantite(inventaireEmi, LINGOT) == lingotsMe
                    : "le stock EMI distingue les ingrédients";
            assert quantite(natif, PLAQUE) == portes
                    : "le stock natif des transferts n’est pas enrichi";
            for (int i = 0; i < 10; i++)
                InventaireArbreEmi.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            assert quantite(EmiFavorites.last, PLAQUE) == arbre
                    : "la liste EMI doit suivre cette observation";
            if (attendus > 0 && arbre >= ARBRE.quantite())
                assert arbreNatif.goal.state == PlisFabricationEmiTest.FoldState.COLLAPSED
                        : "le lot attendu couvre la branche sans devenir du stock physique";
            var personnel = InventaireArbreEmi.physiques(InventaireArbreEmi.personnel());
            var ae2 = StockFabricationEmi.courant(personnel, entrees);
            int plaquesARetirer = 0, lingotsARetirer = 0;
            for (var prise : ae2.retraitsReseau(ARBRE, 1)) {
                var item = entrees.get(prise.index()).objet().getItem();
                if (PLAQUE.equals(item)) plaquesARetirer += prise.quantite();
                else if (LINGOT.equals(item)) lingotsARetirer += prise.quantite();
                else throw new AssertionError("Entrée ME inconnue : " + item);
            }
            // En ouvrant la machine, le menu du terminal est fermé.
            var machine = StockFabricationEmi.pourMachines(personnel);
            int aFabriquer =
                    machine.operations(ARBRE, 1).stream()
                            .mapToInt(PlanMachines.Operation::fois)
                            .sum();
            int physiques =
                    machine.physiques().stream().mapToInt(PlanRetrait.Stock::quantite).sum();
            System.out.printf(
                    "%d,%d,%d,%d,%d%n",
                    arbre, plaquesARetirer, lingotsARetirer, aFabriquer, physiques);
        }
        verifierPrioriteMachine();
    }

    private static PlisFabricationEmiTest.Tree arbreNatif() {
        return new PlisFabricationEmiTest.Tree(
                new PlisFabricationEmiTest.Node(
                        PLAQUE, 8, new PlisFabricationEmiTest.Node(LINGOT, 1)));
    }

    private static void verifierPrioriteMachine() {
        CoffreTampon.piles = piles(1);
        CycleArbreEmi.acquis = List.of();
        MachinesEmi.reservations =
                List.of(new PlanRetrait.Stock<>(EmiStack.of(new ItemStack(LINGOT, 1)), 1, true));
        var plaqueSeule =
                new PlanMachines.Noeud<EmiStack, String>(
                        pile -> PLAQUE.equals(pile.getId().toString()),
                        1,
                        1,
                        false,
                        "plaque",
                        List.of());
        var interchangeable =
                new PlanMachines.Noeud<EmiStack, String>(
                        pile ->
                                PLAQUE.equals(pile.getId().toString())
                                        || LINGOT.equals(pile.getId().toString()),
                        1,
                        1,
                        false,
                        null,
                        List.of());
        var branche =
                new PlanMachines.Noeud<EmiStack, String>(
                        pile -> false, 1, 1, false, null, List.of(plaqueSeule));
        var racine =
                new PlanMachines.Noeud<EmiStack, String>(
                        pile -> false, 1, 1, false, null, List.of(interchangeable, branche));
        // Préserver la plaque du tampon pour la branche qui ne peut utiliser aucun lingot.
        var couverture = StockFabricationEmi.pourMachines(List.of());
        assert couverture.operations(racine, 1).isEmpty()
                : "la réservation couvre le choix interchangeable";
    }

    private static long quantite(EmiPlayerInventory inventaire, String item) {
        return inventaire.inventory.values().stream()
                .filter(pile -> item.equals(pile.getId().toString()))
                .mapToLong(EmiStack::getAmount)
                .sum();
    }

    private static EmiStack plaque() {
        return EmiStack.of(new ItemStack(PLAQUE, 1));
    }

    private static List<ItemStack> piles(int quantite) {
        return quantite == 0 ? List.of() : List.of(new ItemStack(PLAQUE, quantite));
    }
}
