package fr.assistantia.compat.emi;

import com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import fr.assistantia.LotFabrication.Etat;
import fr.assistantia.PlanRetrait;
import fr.assistantia.ReceptionLots;
import fr.assistantia.RechercheEmi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

/** Le fixture produit les sorties et synchronise les menus ; les décisions sont celles du mod. */
public final class CycleMachineTest {
    private static final String ETAIN = "test:tin", PLAQUE = "test:plate", MOULE = "test:mold";
    private static final Fluid EAU = new Fluid("test:water"), ACIDE = new Fluid("test:acid");

    private final Minecraft mc = Minecraft.getInstance();
    private final ChestMenu menu = new ChestMenu();
    private final AbstractContainerScreen<ChestMenu> ecran = new AbstractContainerScreen<>(menu);
    private final Slot entree = new Slot(new Object(), 0);
    private final Slot moule = new Slot(new Object(), 1);
    private final Slot sortie = new Slot(new Object(), 2);
    private final GregTechMachines.Cuve cuveEntree = new GregTechMachines.Cuve();
    private final GregTechMachines.Cuve cuveSortie = new GregTechMachines.Cuve();
    private final List<ItemStack> sac = new ArrayList<>();
    private final SuiviLotsEmi suivi = new SuiviLotsEmi(this::stock, () -> {});
    private int lotId;

    private CycleMachineTest() throws ReflectiveOperationException {
        ArbreEmi.tree = new Object();
        OrigineFabrication.vider();
        mc.screen = ecran;
        mc.player.containerMenu = menu;
        mc.gameMode.clicks = 0;
        mc.gameMode.refuse = false;
        RechercheEmi.disponibles = java.util.Map.of();
        GregTechMachines.machineCircuit = 7;
        GregTechMachines.configuration = 7;
        GregTechMachines.busy = false;
        GregTechMachines.recetteActive = null;
        GregTechMachines.outputSlots = List.of(sortie);
        GregTechMachines.outputTanks = List.of(cuveSortie);
        menu.slots.addAll(List.of(entree, moule, sortie));
        for (int i = 3; i < 12; i++) menu.slots.add(new Slot(mc.player.getInventory(), i));
        GregTechMachines.ui =
                new GregTechMachines.Interface(
                        new Object(), List.of(entree, moule), List.of(cuveEntree), null);
        rendreClicDisponible();
    }

    private static void rendreClicDisponible() throws ReflectiveOperationException {
        var antiDoubleClic = EnvoiMachine.class.getDeclaredField("dernierClic");
        antiDoubleClic.setAccessible(true);
        antiDoubleClic.setLong(null, 0);
    }

    private List<PlanRetrait.Stock<ItemStack>> objets() {
        var piles = new ArrayList<>(sac);
        menu.slots.stream().filter(EnvoiMachine::personnel).map(Slot::getItem).forEach(piles::add);
        piles.add(menu.getCarried());
        return piles.stream()
                .filter(s -> !s.isEmpty())
                .map(s -> new PlanRetrait.Stock<>(s.copy(), s.getCount(), true))
                .toList();
    }

    private List<PlanRetrait.Stock<EmiStack>> stock() {
        List<PlanRetrait.Stock<EmiStack>> physique = new ArrayList<>();
        for (var pile : objets()) {
            physique.add(new PlanRetrait.Stock<>(EmiStack.of(pile.objet()), pile.quantite(), true));
            FluidUtil.getFluidContained(pile.objet())
                    .ifPresent(
                            f -> {
                                int volume = f.getAmount() * pile.quantite();
                                physique.add(
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(f.getFluid(), f.getTag(), volume),
                                                volume,
                                                true));
                            });
        }
        return physique;
    }

    private void synchroniser() {
        OrigineFabrication.actualiser(objets());
        suivi.recevoir(OrigineFabrication.receptions());
        suivi.actualiser();
    }

    private static EmiStack item(String id, int quantite) {
        return EmiStack.of(new ItemStack(id, quantite));
    }

    private static EmiStack fluide(Fluid type, int quantite) {
        return EmiStack.of(type, null, quantite);
    }

    private static ItemStack contenant(String id, int nombre, Fluid type, int volume) {
        var contenant = new ItemStack(id, nombre);
        contenant.fluid = type.id();
        contenant.amount = volume;
        return contenant;
    }

    private static int quantite(List<PlanRetrait.Stock<EmiStack>> stock, EmiStack variante) {
        return stock.stream()
                .filter(s -> ComparaisonEmi.meme(s.objet(), variante))
                .mapToInt(PlanRetrait.Stock::quantite)
                .sum();
    }

    private EmiRecipe recetteMixte() {
        var recette =
                new GTEmiRecipe(
                        List.of(item(ETAIN, 2), fluide(EAU, 144), circuitVirtuel()),
                        List.of(item(MOULE, 1)));
        var bonus = item("test:bonus", 1);
        bonus.chance = 0.25f;
        recette.outputs = List.of(item(PLAQUE, 2), fluide(ACIDE, 144), bonus);
        return recette;
    }

    private static EmiStack circuitVirtuel() {
        var circuit = item("gtceu:programmed_circuit", 1);
        circuit.chance = 0;
        return circuit;
    }

    private void confirmerDestination(String scenario) {
        boolean fluide = scenario.equals("cuve-corrigee");
        var recette =
                new EmiRecipe(List.of(fluide ? fluide(EAU, 1_000) : item(ETAIN, 2)), List.of());
        recette.outputs = List.of(item(PLAQUE, 1));
        var source = menu.slots.get(3);
        source.item =
                fluide ? contenant("test:water_cell", 1, EAU, 1_000) : new ItemStack(ETAIN, 2);
        var plan = EnvoiMachine.preparer(ecran, recette, 1);
        assert plan.possible() : plan.probleme();
        envoyer(plan);
        for (int i = 0; i < 50 && entree.item.isEmpty() && cuveEntree.calls == 0; i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert fluide ? cuveEntree.tank.getFluidAmount() == 1_000 : entree.item.getCount() == 2;
        assert source.item.isEmpty();
        entree.item = ItemStack.EMPTY;
        cuveEntree.tank.setFluid(FluidStack.EMPTY);
        boolean demarre = scenario.equals("consommation-immediate");
        GregTechMachines.busy = demarre || scenario.equals("autre-recette-demarree");
        GregTechMachines.recetteActive = demarre ? recette : new EmiRecipe(List.of(), List.of());
        attendreFinEnvoi();
        if (demarre) assert lot().fois() == 1;
        else {
            assert suivi.actifs().isEmpty() : "aucun lot quand le dépôt est resté sans preuve";
            assert EnvoiMachine.statut().contains("non confirmé") : EnvoiMachine.statut();
        }
    }

    private static EmiRecipe recettePlaques(int ingredients) {
        var recette = new EmiRecipe(List.of(item(ETAIN, ingredients)), List.of());
        recette.outputs = List.of(item(PLAQUE, 1));
        return recette;
    }

    private void attendreDepot(Slot source, int quantite) {
        for (int i = 0; i < 100 && EnvoiMachine.occupe() && !source.getItem().isEmpty(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert source.getItem().isEmpty() && entree.getItem().getCount() == quantite
                : EnvoiMachine.statut();
        assert menu.getCarried().isEmpty();
        assert suivi.actifs().isEmpty() : "un dépôt en attente ne crée pas de lot";
    }

    private void jouerRecetteCourte(EmiRecipe recette) {
        GregTechMachines.recetteActive = recette;
        GregTechMachines.busy = true;
        EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert EnvoiMachine.occupe();
        GregTechMachines.busy = false;
        sortie.item = new ItemStack(PLAQUE, sortie.getItem().getCount() + 1);
    }

    private void verifierEnvoiNonConfirme(int clics) {
        assert suivi.actifs().isEmpty() : "aucun lot tant qu'un dépôt reste sans preuve";
        assert EnvoiMachine.statut().contains("non confirmé") : EnvoiMachine.statut();
        assert mc.gameMode.clicks == clics : "aucun nouveau clic après un dépôt non confirmé";
        assert menu.getCarried().isEmpty();
    }

    private void recetteDeuxTicks(String scenario) {
        var recette = recettePlaques(2);
        var source = menu.slots.get(3);
        boolean sortieRetardee = scenario.startsWith("recette-rapide-sortie-retardee");
        boolean autreRecette = scenario.equals("recette-rapide-sortie-retardee-id-change");
        boolean deuxCycles = sortieRetardee || scenario.equals("recette-rapide-deux-cycles");
        int fois = deuxCycles || scenario.equals("recette-deux-ticks-entree-partielle") ? 2 : 1;
        source.item = new ItemStack(ETAIN, 2 * fois);
        sortie.item = new ItemStack(PLAQUE, 2);
        boolean sortieMasquee = scenario.equals("sortie-preexistante-masquee");
        if (sortieMasquee) sortie.active = false;
        // Le dernier craft connu peut déjà être le même avant l'envoi.
        GregTechMachines.recetteActive = recette;
        synchroniser();
        var plan = EnvoiMachine.preparer(ecran, recette, fois);
        assert plan.fois() == fois : plan.probleme();
        envoyer(plan);
        attendreDepot(source, 2 * fois);
        // Une seule sortie ne justifie pas la disparition des ingrédients de deux cycles.
        entree.item = ItemStack.EMPTY;
        int clics = mc.gameMode.clicks;
        boolean entreObservations = scenario.equals("recette-entre-observations");
        boolean sortieCorrigee = scenario.equals("sortie-corrigee-apres-observation");
        if (sortieRetardee) {
            GregTechMachines.busy = true;
            sortie.item = new ItemStack(PLAQUE, 3);
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
            GregTechMachines.busy = false;
            GregTechMachines.recetteActive = autreRecette ? recettePlaques(2) : null;
            sortie.item = new ItemStack(PLAQUE, 4);
        } else if (entreObservations || sortieCorrigee || deuxCycles) {
            sortie.item = new ItemStack(PLAQUE, deuxCycles ? 4 : 3);
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
            GregTechMachines.recetteActive = null;
            if (sortieCorrigee) sortie.item = new ItemStack(PLAQUE, 2);
        } else if (sortieMasquee) sortie.active = true;
        else if (!scenario.equals("sortie-preexistante-seule"))
            jouerRecetteCourte(
                    scenario.equals("recette-deux-ticks-autre") ? recettePlaques(2) : recette);
        if (scenario.equals("recette-deux-ticks-source-corrigee"))
            source.item = new ItemStack(ETAIN, 2);
        attendreFinEnvoi();
        if (entreObservations
                || deuxCycles && !autreRecette
                || scenario.equals("recette-deux-ticks")) {
            assert lot().fois() == fois;
            synchroniser();
            assert lot().suivi().totalRecu() == 0 : "aucune sortie n'a été récupérée";
            assert quantite(lot().suivi().restants(), item(PLAQUE, 1)) == fois;
            assert sortie.getItem().getCount() == 2 + fois;
            assert mc.gameMode.clicks == clics : "aucun rejeu après acquittement";
        } else {
            verifierEnvoiNonConfirme(clics);
            if (scenario.equals("recette-deux-ticks-source-corrigee"))
                assert source.getItem().getCount() == 2 : "le retour serveur est préservé";
        }
    }

    private void fluideEntreObservations(boolean sortieConfirmee) {
        var recette = new EmiRecipe(List.of(fluide(EAU, 144)), List.of());
        recette.outputs = List.of(fluide(ACIDE, 144));
        var source = menu.slots.get(3);
        source.item = contenant("test:water_cell", 1, EAU, 144);
        cuveSortie.tank.setFluid(new FluidStack(ACIDE, 288));
        GregTechMachines.recetteActive = recette;
        synchroniser();
        envoyer(EnvoiMachine.preparer(ecran, recette, 1));
        for (int i = 0; i < 100 && EnvoiMachine.occupe() && cuveEntree.calls == 0; i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert cuveEntree.calls == 1 && cuveEntree.tank.getFluidAmount() == 144
                : EnvoiMachine.statut();
        assert source.getItem().isEmpty() && menu.getCarried().is("empty_cell");
        int clics = mc.gameMode.clicks;
        cuveEntree.tank.setFluid(FluidStack.EMPTY);
        if (sortieConfirmee) cuveSortie.tank.setFluid(new FluidStack(ACIDE, 432));
        attendreFinEnvoi();
        assert cuveEntree.calls == 1 : "le vidage ne doit pas être rejoué";
        assert quantite(stock(), item("empty_cell", 1)) == 1;
        if (sortieConfirmee) {
            assert lot().fois() == 1 && menu.getCarried().isEmpty();
            assert mc.gameMode.clicks == clics + 1 : "le contenant vide est rangé une seule fois";
            synchroniser();
            assert lot().suivi().totalRecu() == 0 : "la sortie est encore dans la cuve";
            assert quantite(lot().suivi().restants(), fluide(ACIDE, 1)) == 144;
        } else {
            assert suivi.actifs().isEmpty() && EnvoiMachine.statut().contains("non confirmé")
                    : EnvoiMachine.statut();
            assert mc.gameMode.clicks == clics && menu.getCarried().is("empty_cell")
                    : "aucun clic supplémentaire, le contenant vide reste au curseur";
        }
    }

    private void absorptionAvantDepotSuivant(String scenario) {
        var recette = recettePlaques(1);
        var premiere = menu.slots.get(3);
        var suivante = menu.slots.get(4);
        premiere.item = new ItemStack(ETAIN, 1);
        suivante.item = new ItemStack(ETAIN, 1);
        synchroniser();
        var plan = EnvoiMachine.preparer(ecran, recette, 2);
        assert plan.fois() == 2 : plan.probleme();
        envoyer(plan);
        attendreDepot(premiere, 1);
        assert suivante.getItem().getCount() == 1 : "le deuxième dépôt attend le premier";
        entree.item = ItemStack.EMPTY;
        boolean mauvaiseRecette = scenario.equals("absorption-avant-depot-suivant-autre");
        jouerRecetteCourte(mauvaiseRecette ? recettePlaques(1) : recette);
        if (mauvaiseRecette) {
            int clics = mc.gameMode.clicks;
            attendreFinEnvoi();
            verifierEnvoiNonConfirme(clics);
            assert suivante.getItem().getCount() == 1 : "la poche suivante reste intacte";
            return;
        }
        attendreDepot(suivante, 1);
        int clics = mc.gameMode.clicks;
        boolean preuveAncienne = scenario.equals("absorption-ancien-demarrage");
        if (preuveAncienne) entree.item = ItemStack.EMPTY;
        attendreFinEnvoi();
        if (preuveAncienne) {
            verifierEnvoiNonConfirme(clics);
            assert sortie.getItem().getCount() == 1 : "seul le premier dépôt a produit";
            return;
        }
        assert lot().fois() == 2 && lot().suivi().totalRecu() == 0;
        assert quantite(lot().suivi().restants(), item(PLAQUE, 1)) == 2;
        assert quantite(stock(), item(ETAIN, 1)) == 0;
        assert entree.getItem().getCount() == 1 : "le deuxième dépôt est resté dans l'entrée";
        assert mc.gameMode.clicks == 4 : "exactement deux clics par dépôt, sans rejeu";
    }

    private void fournirIngredients() {
        menu.slots.get(3).item = new ItemStack(ETAIN, 12);
        menu.slots.get(4).item = contenant("test:water_cell", 2, EAU, 1000);
        menu.slots.get(5).item = new ItemStack(MOULE, 1);
        synchroniser();
    }

    private void envoyer(EnvoiMachine.Plan plan) {
        EnvoiMachine.lancer(
                ecran,
                plan,
                () -> {
                    assert lotId == 0 : "le callback d'envoi n'est appelé qu'une fois";
                    lotId =
                            suivi.ajouter(
                                    plan.recette(), plan.fois(), GregTechMachines.ui.position());
                });
        assert EnvoiMachine.occupe() : EnvoiMachine.statut();
    }

    private static void attendreFinEnvoi() {
        for (int i = 0; i < 1200 && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert !EnvoiMachine.occupe() : "envoi bloqué";
    }

    private LotMachine lot() {
        assert suivi.actifs().size() == 1 && lotId > 0 : EnvoiMachine.statut();
        return suivi.actifs().get(0);
    }

    private void receptionnerPlaques(int resteSortie, int totalPorte, boolean sortieDabord) {
        var poche = menu.slots.get(8);
        if (sortieDabord) sortie.item = new ItemStack(PLAQUE, resteSortie);
        else poche.item = new ItemStack(PLAQUE, totalPorte);
        synchroniser();
        assert OrigineFabrication.receptions().isEmpty()
                : "une seule trame ne prouve aucune réception depuis la machine";
        if (sortieDabord) poche.item = new ItemStack(PLAQUE, totalPorte);
        else sortie.item = new ItemStack(PLAQUE, resteSortie);
        synchroniser();
    }

    private void receptionnerAcide(int resteCuve, int totalPorte, boolean sortieDabord) {
        var poche = menu.slots.get(9);
        int avant = quantite(lot().suivi().obtenus(), fluide(ACIDE, 1));
        if (sortieDabord) cuveSortie.tank.setFluid(new FluidStack(ACIDE, resteCuve));
        else poche.item = contenant("test:acid_cell", 1, ACIDE, totalPorte);
        synchroniser();
        assert quantite(lot().suivi().obtenus(), fluide(ACIDE, 1)) == avant
                : "une trame seule ne crédite aucun liquide";
        if (sortieDabord) poche.item = contenant("test:acid_cell", 1, ACIDE, totalPorte);
        else cuveSortie.tank.setFluid(new FluidStack(ACIDE, resteCuve));
        synchroniser();
    }

    private void cycleMixte(boolean sortieDabord, boolean annulerReste) {
        fournirIngredients();
        var recette = recetteMixte();
        GregTechMachines.machineCircuit = 2;
        var refuse = EnvoiMachine.preparer(ecran, recette, 3);
        assert !refuse.possible() && refuse.probleme().contains("régler sur 7");
        assert mc.gameMode.clicks == 0 && suivi.actifs().isEmpty();
        GregTechMachines.machineCircuit = 7;
        var plan = EnvoiMachine.preparer(ecran, recette, 3);
        assert plan.possible() && plan.fois() == 3 : plan.probleme();
        assert cuveEntree.tank.isEmpty() && entree.item.isEmpty() : "aperçu sans mutation";
        envoyer(plan);
        EnvoiMachine.lancer(
                ecran,
                plan,
                () -> {
                    throw new AssertionError("double clic : deuxième callback");
                });
        assert suivi.actifs().isEmpty() : "lot créé avant la dernière confirmation";
        attendreFinEnvoi();
        var lot = lot();
        assert lot.fois() == 3 && lot.suivi().etat() == Etat.EN_MACHINE;
        assert entree.item.id.equals(ETAIN) && entree.item.getCount() == 6;
        assert moule.item.id.equals(MOULE) && moule.item.getCount() == 1;
        assert menu.slots.get(3).item.getCount() == 6 && menu.slots.get(4).item.getCount() == 1;
        assert cuveEntree.calls == 1 && cuveEntree.tank.getFluidAmount() == 1000;
        assert quantite(stock(), item("empty_cell", 1)) == 1 && menu.getCarried().isEmpty();
        assert GregTechMachines.machineCircuit == 7 && lot.suivi().attendus().size() == 2
                : "circuit inchangé, sorties probabilistes exclues";
        assert quantite(lot.suivi().restants(), item(PLAQUE, 1)) == 6;
        assert quantite(lot.suivi().restants(), fluide(ACIDE, 1)) == 432;
        assert suivi.avancement(ArbreEmi.tree).isEmpty() && lot.suivi().obtenus().isEmpty();

        // Le serveur de test consomme la recette et expose ses sorties dans le menu natif.
        entree.item = ItemStack.EMPTY;
        cuveEntree.tank.setFluid(new FluidStack(EAU, 568));
        sortie.item = new ItemStack(PLAQUE, 6);
        cuveSortie.tank.setFluid(new FluidStack(ACIDE, 432));
        synchroniser();
        assert lot.suivi().etat() == Etat.A_RECUPERER && lot.suivi().obtenus().isEmpty();
        receptionnerPlaques(4, 2, sortieDabord);
        receptionnerAcide(288, 144, !sortieDabord);
        synchroniser();
        assert quantite(lot.suivi().obtenus(), item(PLAQUE, 1)) == 2;
        assert quantite(lot.suivi().obtenus(), fluide(ACIDE, 1)) == 144;
        assert quantite(lot.suivi().restants(), item(PLAQUE, 1)) == 4;
        assert quantite(lot.suivi().restants(), fluide(ACIDE, 1)) == 288;
        assert lot.suivi().receptionNonAttribuee()
                : "la corrélation ne constitue pas une preuve de transaction Production";
        sac.add(menu.slots.get(8).item);
        menu.slots.get(8).item = ItemStack.EMPTY;
        long apresReception = suivi.revision();
        synchroniser();
        assert suivi.revision() == apresReception && OrigineFabrication.totaux().get(PLAQUE) == 2
                : "des poches vers le sac : aucun nouvel acquis";
        menu.slots.get(8).item = sac.remove(0);
        synchroniser();

        if (annulerReste) {
            assert suivi.annuler(lotId) && !suivi.annuler(lotId);
            assert lot.suivi().etat() == Etat.ANNULE && !suivi.visibleDansSuivi(lot);
        }
        receptionnerPlaques(0, 6, !sortieDabord);
        receptionnerAcide(0, 432, sortieDabord);
        assert lot.suivi().restants().isEmpty() && moule.item.getCount() == 1;
        assert quantite(stock(), item(PLAQUE, 1)) == 6;
        assert quantite(stock(), fluide(ACIDE, 1)) == 432;
        if (annulerReste) {
            assert quantite(lot.suivi().obtenus(), item(PLAQUE, 1)) == 2;
            assert quantite(lot.suivi().obtenus(), fluide(ACIDE, 1)) == 144
                    : "une réception tardive ne réactive pas le lot annulé";
        } else {
            assert lot.suivi().etat() == Etat.TERMINE
                    && quantite(lot.suivi().obtenus(), item(PLAQUE, 1)) == 6;
            assert quantite(lot.suivi().obtenus(), fluide(ACIDE, 1)) == 432;
            assert suivi.retirerDuSuivi(lotId) && !suivi.retirerDuSuivi(lotId);
            assert !suivi.visibleDansSuivi(lot);
            assert ReceptionLots.credits(
                                    suivi.avancement(ArbreEmi.tree),
                                    stock(),
                                    List.of(),
                                    ComparaisonEmi::meme)
                            .isEmpty()
                    : "les six objets physiquement présents ne sont pas crédités une seconde fois";
        }
    }

    private void interrompre(String interruption) {
        fournirIngredients();
        var plan = EnvoiMachine.preparer(ecran, recetteMixte(), 3);
        assert plan.possible() : plan.probleme();
        envoyer(plan);
        for (int i = 0; i < 40 && menu.getCarried().isEmpty() && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert menu.getCarried().amount == 1000 && cuveEntree.calls == 0
                : "la coupure intervient après la prise et avant le vidage";
        switch (interruption) {
            case "interface" -> mc.screen = null;
            case "arbre" -> ArbreEmi.tree = new Object();
            case "connexion" -> mc.player = null;
            case "cuve" -> cuveEntree.tank.capacity = 999;
            case "arret" -> EnvoiMachine.arreter();
            default -> throw new IllegalArgumentException(interruption);
        }
        attendreFinEnvoi();
        assert lotId == 0 && suivi.actifs().isEmpty() && cuveEntree.calls == 0;
        assert menu.getCarried().amount == 1000 && menu.slots.get(4).item.getCount() == 1
                : "le contenant pris et le contenant resté représentent exactement les deux d'origine";
        assert entree.item.isEmpty() && moule.item.isEmpty();
        assert menu.slots.get(3).item.getCount() == 12 && menu.slots.get(5).item.getCount() == 1;
        int clics = mc.gameMode.clicks;
        // Un ancien résultat arrive après l'arrêt : aucune action suivante ni aucun callback.
        menu.carried = ItemStack.EMPTY;
        menu.slots.get(4).item.setCount(2);
        for (int i = 0; i < 100; i++) EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert mc.gameMode.clicks == clics && suivi.actifs().isEmpty();
    }

    private void fermerApresVidage() {
        fournirIngredients();
        var plan = EnvoiMachine.preparer(ecran, recetteMixte(), 3);
        envoyer(plan);
        for (int i = 0; i < 40 && cuveEntree.calls == 0 && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert cuveEntree.calls == 1 && cuveEntree.tank.getFluidAmount() == 1000;
        assert menu.getCarried().id.equals("empty_cell") && lotId == 0;
        int clics = mc.gameMode.clicks;
        mc.screen = null;
        attendreFinEnvoi();
        assert mc.gameMode.clicks == clics && suivi.actifs().isEmpty();
        assert entree.item.isEmpty() && moule.item.isEmpty();
        assert cuveEntree.tank.getFluidAmount() == 1000
                        && quantite(stock(), fluide(EAU, 1)) == 1000
                        && quantite(stock(), item("empty_cell", 1)) == 1
                : "une fermeture ne doit ni annuler un liquide accepté ni en envoyer un autre";

        // Réouverture après le retour du curseur dans une case d'inventaire.
        menu.slots.get(6).item = menu.getCarried();
        menu.carried = ItemStack.EMPTY;
        mc.screen = ecran;
        synchroniser();
        var reprise = EnvoiMachine.preparer(ecran, plan.recette(), 3);
        assert reprise.possible()
                        && reprise.details().stream().anyMatch(s -> s.contains("0 mB à envoyer"))
                : "recalculer le manque sur la cuve actuelle";
    }

    private void changerCircuit(boolean apresVidage, boolean retourBloque) {
        fournirIngredients();
        envoyer(EnvoiMachine.preparer(ecran, recetteMixte(), 3));
        for (int i = 0; i < 40 && EnvoiMachine.occupe(); i++) {
            if (apresVidage ? cuveEntree.calls > 0 : !menu.getCarried().isEmpty()) break;
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        }
        assert !menu.getCarried().isEmpty() && cuveEntree.calls == (apresVidage ? 1 : 0);
        GregTechMachines.machineCircuit = 2;
        var cellules = menu.slots.get(4);
        if (retourBloque) cellules.place = false;
        attendreFinEnvoi();
        assert EnvoiMachine.statut().contains("actuel : 2") : EnvoiMachine.statut();
        assert suivi.actifs().isEmpty() && entree.item.isEmpty() && moule.item.isEmpty();
        assert menu.slots.get(3).item.getCount() == 12 && menu.slots.get(5).item.getCount() == 1;
        assert GregTechMachines.machineCircuit == 2;
        if (apresVidage) {
            assert cuveEntree.calls == 1 && cuveEntree.tank.getFluidAmount() == 1000;
            assert cellules.item.getCount() == 1 && menu.getCarried().isEmpty();
            assert quantite(stock(), item("empty_cell", 1)) == 1
                    : "le contenant vidé avant le changement doit être rendu";
        } else {
            assert cuveEntree.calls == 0 && cuveEntree.tank.isEmpty();
            assert quantite(stock(), fluide(EAU, 1)) == 2000;
            if (retourBloque) {
                assert menu.getCarried().amount == 1000 && cellules.item.getCount() == 1;
                assert EnvoiMachine.statut().contains("contenant tenu par la souris");
            } else {
                assert menu.getCarried().isEmpty() && cellules.item.getCount() == 2
                        : "la cellule pleine doit revenir dans sa pile avant l'arrêt";
            }
        }
    }

    private void changerCircuitEntreObjets() {
        fournirIngredients();
        var recette =
                new GTEmiRecipe(List.of(item(ETAIN, 2), circuitVirtuel()), List.of(item(MOULE, 1)));
        recette.outputs = List.of(item(PLAQUE, 1));
        envoyer(EnvoiMachine.preparer(ecran, recette, 3));
        for (int i = 0; i < 40 && entree.item.isEmpty() && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert entree.item.getCount() == 6 && moule.item.isEmpty();
        GregTechMachines.machineCircuit = 2;
        attendreFinEnvoi();
        assert entree.item.getCount() == 6 && moule.item.isEmpty();
        assert menu.slots.get(3).item.getCount() == 6 && menu.slots.get(5).item.getCount() == 1;
        assert menu.getCarried().isEmpty() && suivi.actifs().isEmpty();
        assert EnvoiMachine.statut().contains("actuel : 2") : EnvoiMachine.statut();
    }

    private void preconditions() {
        fournirIngredients();
        var recette = recetteMixte();
        cuveEntree.tank.setFluid(new FluidStack(ACIDE, 450));
        assert EnvoiMachine.preparer(ecran, recette, 1)
                .probleme()
                .contains("Fluide manquant ou cuve pleine");
        assert cuveEntree.tank.getFluidAmount() == 450 && cuveEntree.calls == 0;
        cuveEntree.tank.setFluid(FluidStack.EMPTY);
        for (int i = 6; i < menu.slots.size(); i++)
            menu.slots.get(i).item = new ItemStack("test:autre", 64);
        assert EnvoiMachine.preparer(ecran, recette, 1)
                .probleme()
                .contains("case pour le contenant vide");
        var pocheLibre = menu.slots.get(6);
        pocheLibre.item = ItemStack.EMPTY;
        entree.place = false;
        moule.place = false;
        assert EnvoiMachine.preparer(ecran, recette, 1).probleme().contains("Pas assez de place");
        assert mc.gameMode.clicks == 0 && suivi.actifs().isEmpty();
        assert cuveEntree.tank.isEmpty() && cuveEntree.calls == 0;
        assert menu.slots.get(3).item.getCount() == 12 && menu.slots.get(4).item.getCount() == 2;
    }

    private void deuxLots() throws ReflectiveOperationException {
        var recette = new EmiRecipe(List.of(item(ETAIN, 1)), List.of());
        recette.outputs = List.of(item(PLAQUE, 1));
        menu.slots.get(3).item = new ItemStack(ETAIN, 8);
        synchroniser();
        envoyer(EnvoiMachine.preparer(ecran, recette, 3));
        attendreFinEnvoi();
        var premier = lot();
        assert premier.fois() == 3 && entree.item.getCount() == 3;
        entree.item = ItemStack.EMPTY;
        sortie.item = new ItemStack(PLAQUE, 3);

        rendreClicDisponible();
        var plan = EnvoiMachine.preparer(ecran, recette, 5);
        assert plan.possible() && plan.fois() == 5 : plan.probleme();
        EnvoiMachine.lancer(
                ecran,
                plan,
                () -> suivi.ajouter(recette, plan.fois(), GregTechMachines.ui.position()));
        attendreFinEnvoi();
        assert suivi.actifs().size() == 2 && entree.item.getCount() == 5;
        var deuxieme = suivi.actifs().get(1);
        assert premier.id() != deuxieme.id();
        entree.item = ItemStack.EMPTY;
        sortie.item = new ItemStack(PLAQUE, 8);
        synchroniser();
        receptionnerPlaques(4, 4, true);
        assert premier.suivi().etat() == Etat.TERMINE && premier.suivi().totalRecu() == 3;
        assert deuxieme.suivi().totalRecu() == 1 && deuxieme.suivi().etat() == Etat.A_RECUPERER;
        assert quantite(deuxieme.suivi().restants(), item(PLAQUE, 1)) == 4;
        assert suivi.retirerDuSuivi(premier.id());
        receptionnerPlaques(0, 8, false);
        assert deuxieme.suivi().etat() == Etat.TERMINE && deuxieme.suivi().totalRecu() == 5;
        assert OrigineFabrication.totaux().get(PLAQUE) == 8;
        assert quantite(suivi.avancement(ArbreEmi.tree), item(PLAQUE, 1)) == 8;
    }

    private void lotLimite(int demande, boolean ajouterSurplus) {
        var recette = new EmiRecipe(List.of(item(ETAIN, 1)), List.of());
        recette.outputs = List.of(item(PLAQUE, 1));
        menu.slots.get(3).item = new ItemStack(ETAIN, 64);
        menu.slots.get(4).item = new ItemStack(ETAIN, 1);
        synchroniser();
        var plan = EnvoiMachine.preparer(ecran, recette, demande);
        assert plan.possible() && plan.fois() == Math.min(64, demande) : plan.probleme();
        envoyer(plan);
        attendreFinEnvoi();
        var lot = lot();
        assert lot.fois() == plan.fois() && lot.suivi().total() == plan.fois();
        assert quantite(stock(), item(ETAIN, 1)) == 65 - plan.fois();
        assert entree.item.getCount() == plan.fois();
        entree.item.shrink(16);
        sortie.item = new ItemStack(PLAQUE, 16);
        synchroniser();
        receptionnerPlaques(0, 16, true);
        assert lot.suivi().totalRecu() == 16;
        if (ajouterSurplus) {
            assert demande == 65 && suivi.completer(lotId, recette, 1);
            var lotAgrandit = lot();
            assert lotAgrandit.id() == lot.id() && lotAgrandit.fois() == 65;
            assert lotAgrandit.suivi().totalRecu() == 16 && lotAgrandit.suivi().total() == 65;
            assert entree.item.getCount() == 48 && quantite(stock(), item(ETAIN, 1)) == 1
                    : "déclarer le surplus ne déplace aucun ingrédient";
        }
        assert quantite(lot().suivi().restants(), item(PLAQUE, 1))
                == (ajouterSurplus ? 65 : Math.min(demande, 64)) - 16;
    }

    public static void main(String[] args) throws Exception {
        var fixture = new CycleMachineTest();
        String scenario = args[0];
        switch (scenario) {
            case "objets-puis-cuves" -> fixture.cycleMixte(true, false);
            case "poches-puis-sortie" -> fixture.cycleMixte(false, false);
            case "annulation-reception-partielle" -> fixture.cycleMixte(true, true);
            case "interface", "arbre", "connexion", "cuve", "arret" -> fixture.interrompre(
                    scenario);
            case "fermeture-apres-vidage" -> fixture.fermerApresVidage();
            case "circuit-avant-vidage" -> fixture.changerCircuit(false, false);
            case "circuit-apres-vidage" -> fixture.changerCircuit(true, false);
            case "circuit-retour-bloque" -> fixture.changerCircuit(false, true);
            case "circuit-entre-objets" -> fixture.changerCircuitEntreObjets();
            case "preconditions" -> fixture.preconditions();
            case "destination-corrigee",
                    "cuve-corrigee",
                    "consommation-immediate",
                    "autre-recette-demarree" -> fixture.confirmerDestination(scenario);
            case "recette-deux-ticks",
                    "recette-entre-observations",
                    "recette-rapide-deux-cycles",
                    "recette-rapide-sortie-retardee",
                    "recette-rapide-sortie-retardee-id-change",
                    "recette-deux-ticks-entree-partielle",
                    "recette-deux-ticks-autre",
                    "recette-deux-ticks-source-corrigee",
                    "sortie-corrigee-apres-observation",
                    "sortie-preexistante-seule",
                    "sortie-preexistante-masquee" -> fixture.recetteDeuxTicks(scenario);
            case "fluide-entre-observations" -> fixture.fluideEntreObservations(true);
            case "fluide-sortie-preexistante" -> fixture.fluideEntreObservations(false);
            case "absorption-avant-depot-suivant",
                    "absorption-avant-depot-suivant-autre",
                    "absorption-ancien-demarrage" -> fixture.absorptionAvantDepotSuivant(scenario);
            case "deux-lots" -> fixture.deuxLots();
            case "63" -> fixture.lotLimite(63, false);
            case "64" -> fixture.lotLimite(64, false);
            case "65" -> fixture.lotLimite(65, false);
            case "65-avec-surplus" -> fixture.lotLimite(65, true);
            default -> throw new IllegalArgumentException(scenario);
        }
        System.out.println("PASS " + scenario);
    }
}
