package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorites;
import fr.assistantia.CoffreTampon;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.SacsFabrication;
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

    static void entreesEngagees() {
        var canne = EmiStack.of(new ItemStack(LINGOT, 1));
        var entree = new PlanRetrait.Stock<>(canne, 1, true);
        var reserve = new PlanRetrait.Stock<>(canne, 2, false);
        var stock =
                new StockFabricationEmi(
                        List.of(), List.of(), List.of(reserve), List.of(), List.of(), List.of());
        var operation =
                new PlanMachines.Noeud<EmiStack, String>(
                        s -> false, 1, 1, false, "bain", ARBRE.enfants());
        var autre =
                new PlanMachines.Noeud<EmiStack, String>(
                        s -> false, 1, 1, false, "presse", ARBRE.enfants());
        var deux =
                new PlanMachines.Noeud<EmiStack, String>(
                        s -> false, 1, 1, false, "assemblage", List.of(autre, operation));
        var bain = java.util.Map.of("bain", List.of(entree));
        assert stock.retraitsReseau(operation, 1, bain, java.util.Set.of()).isEmpty()
                : "AS-008 : la canne en entrée laisse la réserve en cellule";
        assert stock.retraitsReseau(operation, 2, bain, java.util.Set.of())
                .equals(List.of(new PlanRetrait.Prise(0, 1)));
        assert stock.retraitsReseau(deux, 1, bain, java.util.Set.of())
                .equals(List.of(new PlanRetrait.Prise(0, 1)));
        assert stock.retraitsReseau(autre, 1, bain, java.util.Set.of())
                .equals(List.of(new PlanRetrait.Prise(0, 1)));
        assert stock.retraitsReseau(deux, 1, java.util.Map.of(), java.util.Set.of("bain"))
                        .equals(List.of(new PlanRetrait.Prise(0, 1)))
                : "La presse reste accessible si le bain est illisible";
        var deuxBains =
                new PlanMachines.Noeud<EmiStack, String>(
                        s -> false, 1, 1, false, "assemblage", List.of(operation, operation));
        assert stock.retraitsReseau(deuxBains, 1, bain, java.util.Set.of())
                .equals(List.of(new PlanRetrait.Prise(0, 1)));
        assert stock.retraitsReseau(operation, 1).equals(List.of(new PlanRetrait.Prise(0, 1)))
                : "Le bain vidé rouvre le besoin";
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        if (args.length == 1 && args[0].equals("entrees-engagees")) {
            entreesEngagees();
            return;
        }
        var client = Minecraft.getInstance();
        client.player = new Player();
        client.level = new Object();
        InventaireJoueur.sacs = List.of();
        var arbreNatif = arbreNatif();
        CoffresArbreEmi.arbre = arbreNatif;
        var stockTerminal = TerminalMe.class.getDeclaredField("stock");
        stockTerminal.setAccessible(true);
        if (args.length == 1 && args[0].equals("variantes-nbt")) {
            variantesNbt(stockTerminal);
            return;
        }
        if (args.length == 1 && args[0].equals("basculer-sac")) {
            verifierBasculesSac(stockTerminal);
            return;
        }

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

    private static ItemStack variante(int tag) {
        var pile = new ItemStack("test:composant", 1);
        var nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putInt("variante", tag);
        pile.setTag(nbt);
        return pile;
    }

    private static void variantesNbt(java.lang.reflect.Field terminal)
            throws ReflectiveOperationException {
        var a = new PlisFabricationEmiTest.Node("test:composant", 1);
        var b = new PlisFabricationEmiTest.Node("test:composant", 1);
        var premiere = ComparaisonEmi.pourInventaire(EmiStack.of(variante(1)));
        var seconde = ComparaisonEmi.pourInventaire(EmiStack.of(variante(2)));
        a.ingredient = premiere;
        b.ingredient = seconde;
        var strict =
                new PlisFabricationEmiTest.Tree(
                        new PlisFabricationEmiTest.Node("test:produit", 1, a, b));
        var zero = List.<PlanRetrait.Prise>of();
        var un = List.of(new PlanRetrait.Prise(0, 1));
        var deux = List.of(new PlanRetrait.Prise(0, 1), new PlanRetrait.Prise(1, 1));
        verifierVariantes(strict, terminal, List.of(), List.of(1, 1), zero);
        verifierVariantes(strict, terminal, List.of(1, 1), List.of(0, 1), un);
        verifierVariantes(strict, terminal, List.of(1, 2), List.of(0, 0), deux);
        verifierVariantes(strict, terminal, List.of(2, 1), List.of(0, 0), deux);
        var choix = new PlisFabricationEmiTest.Node("test:composant", 1);
        choix.ingredient = () -> List.of(premiere, seconde);
        var souple =
                new PlisFabricationEmiTest.Tree(
                        new PlisFabricationEmiTest.Node("test:produit", 1, choix));
        verifierVariantes(souple, terminal, List.of(1), List.of(0), un);
        verifierVariantes(souple, terminal, List.of(2), List.of(0), un);
        terminal.set(null, List.of());
        for (var tags : List.of(List.of(1, 1), List.of(1, 2), List.of(2, 1))) {
            var poches = tags.stream().map(ParcoursStockTest::variante).toList();
            InventaireJoueur.piles = poches;
            var entreesHandler = new ArrayList<>(poches.stream().map(EmiStack::of).toList());
            entreesHandler.add(EmiStack.of(new ItemStack("test:case_handler", 3)));
            var natif = new EmiPlayerInventory(entreesHandler);
            assert natif.inventory.size() == 2 : "le handler natif réunit les deux composants";
            var affiche = InventaireArbreEmi.completer(natif);
            var inventaire = InventaireArbreEmi.physiques(affiche);
            var instantane = InstantaneArbreEmi.lire(strict);
            var absents = new ArrayList<Integer>();
            PlanMachines.observer(
                    instantane.machines(),
                    instantane.lots(),
                    inventaire,
                    (branche, etat) -> {
                        if (branche != instantane.machines()) absents.add(etat.manque());
                    });
            var attendus = tags.equals(List.of(1, 1)) ? List.of(0, 1) : List.of(0, 0);
            assert absents.equals(attendus)
                    : "poches " + tags + " manques " + absents + " attendus " + attendus;
            assert quantite(affiche, "test:case_handler") == 3;
            assert natif.inventory.size() == 2 && quantite(natif, "test:composant") == 2;
            if (!tags.get(0).equals(tags.get(1))) {
                entreesHandler.add(EmiStack.of(variante(tags.get(0))));
                var avecSurplus =
                        InventaireArbreEmi.completer(new EmiPlayerInventory(entreesHandler));
                assert avecSurplus.inventory.get(premiere).getAmount() == 1;
                assert avecSurplus.inventory.get(seconde).getAmount() == 1;
                assert quantite(avecSurplus, "test:case_handler") == 3;
                var restreint = new EmiPlayerInventory(List.of(EmiStack.of(variante(1))));
                assert InventaireArbreEmi.completer(restreint) == restreint;
            }
        }
    }

    private static void verifierVariantes(
            PlisFabricationEmiTest.Tree arbre,
            java.lang.reflect.Field terminal,
            List<Integer> tags,
            List<Integer> manques,
            List<PlanRetrait.Prise> prises)
            throws ReflectiveOperationException {
        CoffresArbreEmi.arbre = arbre;
        // AE2 regroupe deux exemplaires de la même variante dans une seule entrée.
        var quantites = new java.util.LinkedHashMap<Integer, Integer>();
        tags.forEach(t -> quantites.merge(t, 1, Integer::sum));
        var entrees =
                quantites.entrySet().stream()
                        .map(
                                e ->
                                        new PlanRetrait.Stock<>(
                                                variante(e.getKey()), e.getValue(), false))
                        .toList();
        terminal.set(null, entrees);
        var instantane = InstantaneArbreEmi.lire(arbre);
        var natif = EmiPlayerInventory.of(Minecraft.getInstance().player);
        var vue = InventaireArbreEmi.physiques(InventaireArbreEmi.completer(natif));
        var absents = new ArrayList<Integer>();
        PlanMachines.observer(
                instantane.machines(),
                instantane.lots(),
                vue,
                (branche, etat) -> {
                    if (branche != instantane.machines()) absents.add(etat.manque());
                });
        assert absents.equals(manques) : "EMI " + tags + " : manques par branche " + absents;
        var poches = InventaireArbreEmi.physiques(InventaireArbreEmi.personnel());
        assert poches.isEmpty() && natif.inventory.isEmpty();
        var reseau = StockFabricationEmi.courant(poches, entrees);
        var retrait = reseau.retraitsReseau(instantane.machines(), instantane.lots());
        assert retrait.equals(prises) : "ME " + tags + " : indices et quantités " + retrait;
    }

    private static void verifierBasculesSac(java.lang.reflect.Field terminal)
            throws IllegalAccessException {
        var mc = Minecraft.getInstance();
        InventaireJoueur.base = piles(3);
        InventaireJoueur.sacs = piles(5);
        InventaireJoueur.piles = piles(8);
        mc.player.items.addAll(InventaireJoueur.base);
        var reseau = List.of(new PlanRetrait.Stock<>(new ItemStack(PLAQUE, 1), 5, false));
        terminal.set(null, reseau);
        // Le handler d'un sac ouvert voit déjà ses cases. Le remplisseur conserve cette vue.
        var fluideSac = new net.minecraft.world.level.material.Fluid("test:contenu_sac");
        var natif =
                new EmiPlayerInventory(
                        List.of(
                                EmiStack.of(new ItemStack(PLAQUE, 8)),
                                EmiStack.of(fluideSac, null, 1_000)));

        for (boolean actif : new boolean[] {true, false, true}) {
            SacsFabrication.actif = actif;
            InventaireJoueur.sync =
                    actif; // un sac peut encore être en cours de lecture lorsque OFF.
            InventaireJoueur.revision++;
            var vueEmi = InventaireArbreEmi.completer(natif);
            assert quantite(vueEmi, PLAQUE) == (actif ? 13 : 8) : "vue synthétique EMI";
            assert quantite(vueEmi, "test:contenu_sac") == (actif ? 1_000 : 0)
                    : "un handler sans provenance ne réintroduit pas le fluide d'un sac désactivé";
            for (int i = 0; i < 10; i++)
                InventaireArbreEmi.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            assert quantite(EmiFavorites.last, PLAQUE) == (actif ? 13 : 8)
                    : "les favoris reflètent le réglage sans déplacement des objets";
            var personnel = InventaireArbreEmi.physiques(InventaireArbreEmi.personnel());
            var ae2 = StockFabricationEmi.courant(personnel, reseau).retraitsReseau(ARBRE, 1);
            assert ae2.stream().mapToInt(PlanRetrait.Prise::quantite).sum() == (actif ? 0 : 5)
                    : "le terminal retire seulement le complément des sources autorisées";
            var operations = StockFabricationEmi.pourMachines(personnel).operations(ARBRE, 1);
            assert operations.stream().mapToInt(PlanMachines.Operation::fois).sum()
                            == (actif ? 0 : 5)
                    : "les opérations machine suivent le stock de calcul";
            assert quantite(natif, PLAQUE) == 8 && InventaireJoueur.piles.get(0).getCount() == 8
                    : "le changement ne mute ni les cases visibles ni l'inventaire physique";
        }
        terminal.set(null, List.of());
        SacsFabrication.actif = false;
        assert quantite(InventaireArbreEmi.completer(natif), PLAQUE) == 3
                : "un handler natif de sac ouvert ne réintroduit pas ses cases";
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
