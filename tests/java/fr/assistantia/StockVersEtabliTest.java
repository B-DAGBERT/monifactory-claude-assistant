package fr.assistantia;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;
import fr.assistantia.compat.emi.CoffresArbreEmi;
import fr.assistantia.compat.emi.MachinesEmi;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;

/** Parcours client avec cases et gestionnaire natif de l'établi simulés. */
public final class StockVersEtabliTest {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final String ETAIN = "tin",
            CAOUTCHOUC = "rubber",
            OUTIL = "wrench",
            CABLE = "cable";
    private static final Recette CABLES = new Recette(ETAIN, CABLE);
    private final ChestMenu coffre = new ChestMenu();
    private final AbstractContainerMenu etabli = new AbstractContainerMenu();
    private final Slot caseEtain = new Slot(etabli, 0),
            deuxiemeEtain = new Slot(etabli, 1),
            caseCaoutchouc = new Slot(etabli, 2),
            caseOutil = new Slot(etabli, 3),
            resultat = new ResultSlot(etabli, 4);
    private final List<Slot> poches;
    private final AbstractContainerScreen<ChestMenu> ecranCoffre =
            new AbstractContainerScreen<>(coffre);
    private final AbstractContainerScreen<AbstractContainerMenu> ecranEtabli =
            new AbstractContainerScreen<>(etabli);
    static Remplisseur remplisseur;

    public record Recette(String metal, String produit) implements EmiRecipe {
        @Override
        public EmiRecipeCategory getCategory() {
            return new EmiRecipeCategory("minecraft:crafting");
        }

        @Override
        public List<EmiStack> getOutputs() {
            return List.of(new EmiStack(new ItemStack(produit, 2)));
        }

        @Override
        public List<EmiStack> getInputs() {
            return List.of(
                    new EmiStack(new ItemStack(metal, 1)),
                    new EmiStack(new ItemStack(metal, 1)),
                    new EmiStack(new ItemStack(CAOUTCHOUC, 1)),
                    new EmiStack(new ItemStack(OUTIL, 1)));
        }
    }

    /** Fixture de transfert dans la grille, puis consommation lors de la prise du résultat. */
    final class Remplisseur implements EmiRecipeHandler {
        int appels, remplis;
        boolean refuse;
        Recette chargee;

        @Override
        public Object getInventory(AbstractContainerScreen<?> ecran) {
            return ecran.getMenu();
        }

        @Override
        public boolean supportsRecipe(EmiRecipe recette) {
            return recette instanceof Recette;
        }

        @Override
        public boolean canCraft(EmiRecipe recette, EmiCraftContext contexte) {
            return contexte.inventory() == etabli
                    && contexte.type() == EmiCraftContext.Type.FILL_BUTTON;
        }

        boolean remplir(Recette recette, AbstractContainerScreen<?> ecran, int lots) {
            appels++;
            if (refuse || ecran != ecranEtabli) return false;
            verifier(
                    quantite(poches, recette.metal()) >= lots * 2
                            && quantite(poches, CAOUTCHOUC) >= lots
                            && quantite(poches, OUTIL) + caseOutil.item.getCount() == 1,
                    "répartition des cases de l'inventaire suffisante");
            verifier(remplis == 0, "la fixture attend une grille vide");
            retirer(poches, recette.metal(), lots * 2);
            retirer(poches, CAOUTCHOUC, lots);
            if (caseOutil.item.isEmpty()) {
                retirer(poches, OUTIL, 1);
                caseOutil.item = new ItemStack(OUTIL, 1);
            }
            caseEtain.item = new ItemStack(recette.metal(), lots);
            deuxiemeEtain.item = new ItemStack(recette.metal(), lots);
            caseCaoutchouc.item = new ItemStack(CAOUTCHOUC, lots);
            resultat.item = new ItemStack(recette.produit(), 2);
            remplis = lots;
            chargee = recette;
            return true;
        }

        int recuperer(int lots) {
            verifier(lots <= remplis && lots > 0, "résultat disponible dans la grille");
            caseEtain.item.count -= lots;
            deuxiemeEtain.item.count -= lots;
            caseCaoutchouc.item.count -= lots;
            remplis -= lots;
            if (remplis == 0) resultat.item = ItemStack.EMPTY;
            ranger(poches, chargee.produit(), lots * 2);
            return lots * 2;
        }
    }

    private StockVersEtabliTest() {
        for (int i = 0; i < 3; i++) coffre.slots.add(new Slot(coffre, i));
        poches =
                java.util.stream.IntStream.range(0, 15)
                        .mapToObj(i -> new Slot(MC.player.getInventory(), i + coffre.slots.size()))
                        .toList();
        coffre.slots.addAll(poches);
        etabli.slots.addAll(List.of(caseEtain, deuxiemeEtain, caseCaoutchouc, caseOutil, resultat));
        etabli.slots.addAll(poches);
        remplisseur = new Remplisseur();
        CoffresArbreEmi.tree = new Object();
    }

    private void choisir(Recette recette, int lots) {
        EmiRecipeFiller.handlers.put(recette, remplisseur);
        MachinesEmi.rows = List.of(new MachinesEmi.Ligne(-1, recette, lots));
    }

    private static void ouvrir(AbstractContainerScreen<?> ecran) {
        MC.screen = ecran;
        MC.player.containerMenu = ecran.getMenu();
    }

    private void ouvrirEtabli() {
        ouvrir(ecranEtabli);
        AideEcran.ouvrir(ecranEtabli);
    }

    private RetraitCoffre.Plan plan(int etain, int caoutchouc) {
        var arbre =
                new ArbreCollecte.Noeud(
                        Set.of(),
                        1,
                        1,
                        false,
                        List.of(
                                CollecteCoffreFixture.besoin(ETAIN, etain),
                                CollecteCoffreFixture.besoin(CAOUTCHOUC, caoutchouc)));
        return CollecteCoffreFixture.plan(coffre, arbre, 1);
    }

    private static PlanRetrait.Besoin<ItemStack> besoin(String id, int nombre) {
        return new PlanRetrait.Besoin<>(objet -> id.equals(objet.id), nombre);
    }

    private boolean lancer(RetraitCoffre.Plan retrait) throws ReflectiveOperationException {
        // Retirer l'attente du bouton entre deux actions distinctes, jamais après un double clic.
        var dernier = RetraitCoffre.class.getDeclaredField("dernierClic");
        dernier.setAccessible(true);
        dernier.setLong(null, 0);
        ouvrir(ecranCoffre);
        return RetraitCoffre.lancer(ecranCoffre, retrait);
    }

    private static void attendreRetrait() {
        for (int tick = 0; tick < 1_000 && RetraitCoffre.occupe(); tick++)
            RetraitCoffre.tick(new TickEvent.ClientTickEvent());
        verifier(!RetraitCoffre.occupe(), "fin de l'action de retrait");
    }

    private static int quantite(List<Slot> slots, String id) {
        return slots.stream()
                .filter(s -> id.equals(s.item.id))
                .mapToInt(s -> s.item.getCount())
                .sum();
    }

    private static void retirer(List<Slot> slots, String id, int nombre) {
        for (Slot slot : slots) {
            if (!id.equals(slot.item.id)) continue;
            int aPrendre = Math.min(nombre, slot.item.getCount());
            slot.item.count -= aPrendre;
            nombre -= aPrendre;
        }
        verifier(nombre == 0, "quantité retirée de la réserve");
    }

    private static void ranger(List<Slot> slots, String id, int nombre) {
        for (Slot slot : slots) {
            if (!slot.item.isEmpty() && !id.equals(slot.item.id)) continue;
            int present = slot.item.getCount();
            int ajout = Math.min(nombre, 64 - present);
            slot.item = new ItemStack(id, present + ajout);
            nombre -= ajout;
            if (nombre == 0) return;
        }
        throw new AssertionError("place insuffisante dans la fixture");
    }

    private void chargerCoffre(int etain, int caoutchouc) {
        coffre.slots.get(0).item = new ItemStack(ETAIN, etain);
        coffre.slots.get(1).item = new ItemStack(CAOUTCHOUC, caoutchouc);
    }

    private void collecte() throws ReflectiveOperationException {
        choisir(CABLES, 6);
        chargerCoffre(16, 8);
        ranger(poches, ETAIN, 4);
        ranger(poches, OUTIL, 1);
        ouvrirCoffre();
        var choix = plan(12, 6);
        verifier(choix.total() == 14, "8 étains et 6 caoutchoucs présents au coffre");
        var etain = choix.pour(new ItemStack(ETAIN, 1));
        verifier(etain.total() == 8 && lancer(etain), "Prendre déduit les quatre étains portés");
        verifier(
                !RetraitCoffre.lancer(ecranCoffre, etain),
                "un double clic n'envoie pas un second lot");
        attendreRetrait();
        verifier(
                quantite(poches, ETAIN) == 12 && quantite(poches, CAOUTCHOUC) == 0,
                "retrait ciblé");
        ouvrirEtabli();
        verifier(AideEcran.propositions.isEmpty(), "le caoutchouc manque toujours à l'établi");
        ouvrirCoffre();
        var solde = plan(12, 6);
        verifier(solde.total() == 6 && lancer(solde), "Tout récupère le solde");
        attendreRetrait();
        ouvrirEtabli();
        AideEcran.cliquer(true);
        verifier(remplisseur.remplis == 6, "six lots remplis");
        verifier(
                quantite(poches, CABLE) == 0 && resultat.item.getCount() == 2,
                "grille remplie, aucun produit acquis");
        verifier(
                remplisseur.recuperer(2) == 4 && quantite(poches, CABLE) == 4,
                "première prise de résultat");
        verifier(remplisseur.recuperer(4) == 8 && quantite(poches, CABLE) == 12, "prise du solde");
        verifier(
                coffre.slots.get(0).item.getCount() == 8
                        && coffre.slots.get(1).item.getCount() == 2
                        && caseEtain.item.isEmpty()
                        && deuxiemeEtain.item.isEmpty()
                        && caseCaoutchouc.item.isEmpty()
                        && caseOutil.item.getCount() == 1,
                "ingrédients consommés une fois et outil préservé");
    }

    private void ouvrirCoffre() {
        ouvrir(ecranCoffre);
    }

    private void quantites(int demande) {
        choisir(CABLES, demande);
        ranger(poches, ETAIN, 130);
        ranger(poches, CAOUTCHOUC, 65);
        ranger(poches, OUTIL, 1);
        ouvrirEtabli();
        AideEcran.cliquer(true);
        int prevu = Math.min(64, demande);
        verifier(remplisseur.remplis == prevu, "limite de 64 façonnages par remplissage");
        verifier(
                quantite(poches, ETAIN) == 130 - 2 * prevu,
                "seuls les objets remplis ont quitté les poches");
        verifier(quantite(poches, CABLE) == 0, "le remplissage ne prend pas la sortie");
        if (prevu > 0)
            verifier(remplisseur.recuperer(prevu) == 2 * prevu, "deux produits par fabrication");
    }

    private void interruption(String phase) throws ReflectiveOperationException {
        choisir(CABLES, 4);
        chargerCoffre(12, 4);
        ranger(poches, OUTIL, 1);
        ouvrirCoffre();
        verifier(lancer(plan(8, 4)), "retrait démarré");
        if (phase.equals("echap")) {
            int avant = MC.gameMode.clicks;
            MC.screen = null;
            attendreRetrait();
            verifier(
                    MC.gameMode.clicks == avant
                            && quantite(poches, ETAIN) == 0
                            && coffre.getCarried().isEmpty(),
                    "fermer le coffre avant le premier clic arrête le retrait");
            ouvrirEtabli();
            verifier(AideEcran.propositions.isEmpty(), "pas de craft après un retrait interrompu");
            return;
        }
        if (phase.equals("retrait-partiel")) {
            RetraitCoffre.tick(new TickEvent.ClientTickEvent());
            verifier(quantite(poches, ETAIN) == 8, "premier ingrédient effectivement pris");
            int avant = MC.gameMode.clicks;
            ouvrirEtabli();
            attendreRetrait();
            verifier(
                    MC.gameMode.clicks == avant
                            && quantite(poches, CAOUTCHOUC) == 0
                            && coffre.getCarried().isEmpty()
                            && AideEcran.propositions.isEmpty(),
                    "le changement de menu arrête le solde et préserve le premier retrait");
            ouvrirCoffre();
            var solde = plan(8, 4);
            verifier(
                    solde.total() == 4 && lancer(solde),
                    "reprise explicite du seul ingrédient manquant");
            attendreRetrait();
            ouvrirEtabli();
            AideEcran.cliquer(true);
            verifier(remplisseur.recuperer(4) == 8, "la reprise permet de produire le lot attendu");
            return;
        }
        attendreRetrait();
        ouvrirEtabli();
        verifier(AideEcran.propositions.size() == 1, "la recette initiale est visible");
        switch (phase) {
            case "recette" -> choisir(new Recette(ETAIN, "alternate"), 3);
            case "stock" -> {
                retirer(poches, ETAIN, 8);
                // Une sortie virtuelle ne doit pas tenir lieu de matière à consommer.
                resultat.item = new ItemStack(ETAIN, 8);
            }
            case "grille" -> remplisseur.refuse = true;
            default -> throw new IllegalArgumentException(phase);
        }
        AideEcran.cliquer(false);
        verifier(
                remplisseur.appels == (phase.equals("grille") ? 1 : 0),
                "aucun clic sur une recette périmée");
        verifier(
                remplisseur.remplis == 0
                        && caseEtain.item.isEmpty()
                        && quantite(poches, CABLE) == 0
                        && !AideEcran.message.isBlank(),
                "le refus préserve les piles et explique l'absence de déplacement");
    }

    private static int credits(SuiviFabrication<ItemStack> suivi) {
        return suivi.obtenus().stream().mapToInt(PlanRetrait.Stock::quantite).sum();
    }

    private boolean suivre(SuiviFabrication<ItemStack> suivi, int preuve) {
        var portes =
                poches.stream()
                        .filter(s -> !s.item.isEmpty())
                        .map(s -> new PlanRetrait.Stock<>(s.item.copy(), s.item.getCount(), true))
                        .toList();
        var preuveCraft =
                preuve == 0
                        ? List.<PlanRetrait.Stock<ItemStack>>of()
                        : List.of(new PlanRetrait.Stock<>(new ItemStack(CABLE, 1), preuve, true));
        return suivi.actualiser(portes, preuveCraft);
    }

    private void credit() throws ReflectiveOperationException {
        choisir(CABLES, 2);
        ranger(poches, ETAIN, 4);
        ranger(poches, CAOUTCHOUC, 2);
        ranger(poches, OUTIL, 1);
        var suivi =
                new SuiviFabrication<>(
                        List.of(besoin(CABLE, 4)), true, ItemStack::isSameItemSameTags);
        verifier(!suivre(suivi, 0), "aucun résultat avant fabrication");
        ouvrirEtabli();
        AideEcran.cliquer(false);
        verifier(
                remplisseur.remplis == 1 && !suivre(suivi, 0),
                "Remplir demande un lot qui reste à prendre");
        verifier(
                !suivre(suivi, remplisseur.recuperer(1)) && credits(suivi) == 2,
                "une fabrication partielle");
        retirer(poches, CABLE, 2);
        coffre.slots.get(0).item = new ItemStack(CABLE, 2);
        verifier(
                !suivre(suivi, 0) && suivi.credits().get(0).quantite() == 2,
                "rangement sans perdre le progrès");
        ouvrirCoffre();
        verifier(
                lancer(
                        CollecteCoffreFixture.plan(
                                coffre, CollecteCoffreFixture.besoin(CABLE, 2), 1)),
                "reprise du même produit");
        attendreRetrait();
        verifier(!suivre(suivi, 0) && credits(suivi) == 2, "la reprise ne double pas le crédit");
        choisir(CABLES, 1);
        ouvrirEtabli();
        AideEcran.cliquer(false);
        verifier(
                suivre(suivi, remplisseur.recuperer(1)) && credits(suivi) == 4,
                "la seconde fabrication est acquise");
    }

    private static void verifier(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        var parcours = new StockVersEtabliTest();
        switch (args[0]) {
            case "collecte" -> parcours.collecte();
            case "quantites" -> parcours.quantites(Integer.parseInt(args[1]));
            case "interruption" -> parcours.interruption(args[1]);
            case "credit" -> parcours.credit();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
