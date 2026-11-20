package fr.assistantia.compat.emi;

import com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.MaterialNode;
import fr.assistantia.ArbreEmi;
import fr.assistantia.DispositionCoffre.Zone;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines.Operation;
import fr.assistantia.Pont;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

/** Exerce le cycle du panneau et le transfert sur des menus simulés. */
public final class PanneauMachineClicTest {
    private static final String CONTEXTE = "Contexte modifié · attends le nouvel affichage.";
    private static final String RECETTE = "Recette modifiée · attends le nouvel affichage.";
    private static final Minecraft mc = Minecraft.getInstance();

    private final EmiRecipe a = recette("A", "tin"), b = recette("B", "copper");
    private final InstantaneArbreEmiTest.Arbre arbre = new InstantaneArbreEmiTest.Arbre();
    private final ChestMenu menu = new ChestMenu();
    private final AbstractContainerScreen<ChestMenu> ecran = new AbstractContainerScreen<>(menu);
    private final Slot entree = new Slot(new Object(), 0);
    private final Slot etain = new Slot(mc.player.getInventory(), 1);
    private final Slot cuivre = new Slot(mc.player.getInventory(), 2);

    private PanneauMachineClicTest() throws ReflectiveOperationException {
        var dernierClic = EnvoiMachine.class.getDeclaredField("dernierClic");
        dernierClic.setAccessible(true);
        dernierClic.setLong(null, 0);
        ArbreEmi.tree = arbre;
        Pont.contexte = "projet";
        mc.screen = ecran;
        mc.player.containerMenu = menu;
        mc.gameMode.clicks = 0;
        etain.item = new ItemStack("tin", 64);
        cuivre.item = new ItemStack("copper", 64);
        menu.slots.addAll(List.of(entree, etain, cuivre));
        PanneauAssistant.son = () -> {};
        PanneauMachineEmi.image = null;
        DeclarerFabricationEcran.ouverte = EmiApi.ouverte = null;
        InventaireJoueur.synchro = true;
        GregTechMachines.configuration = GregTechMachines.machineCircuit = 7;
        GregTechMachines.ui =
                new GregTechMachines.Interface(new Object(), List.of(entree), List.of(), null);
        ReglageCircuitMachine.envoyee = null;
        MachinesEmi.init(new ScreenEvent.Init.Post(ecran));
    }

    private static EmiStack pile(String nom, int n) {
        return EmiStack.of(new ItemStack(nom, n));
    }

    private static EmiRecipe recette(String id, String ingredient) {
        var recette =
                new EmiRecipe(List.of(pile(ingredient, 2)), List.of()) {
                    @Override
                    public String getId() {
                        return "test:" + id;
                    }
                };
        recette.outputs = List.of(pile(id, 1));
        return recette;
    }

    private void preparer(List<Operation<EmiRecipe>> operations) {
        MachinesEmi.scenario = operations;
        MachinesEmi.preparer();
    }

    private void preparer(EmiRecipe recette, int quantite) {
        preparer(List.of(new Operation<>(recette, quantite)));
    }

    private PanneauMachineEmi.Vue rendre() {
        PanneauMachineEmi.image = null;
        MachinesEmi.rendu(new ScreenEvent.Render.Post(ecran));
        return PanneauMachineEmi.image;
    }

    private PanneauMachineEmi.Vue afficherA() {
        preparer(a, 3);
        var vue = rendre();
        assert vue.recettes().get(vue.choix()).recette() == a;
        assert vue.recettes().get(vue.choix()).fois() == 3 && vue.plan().fois() == 3;
        assert vue.page() == 0 && vue.actionActive() && !vue.circuit();
        return vue;
    }

    private boolean clic(Zone zone, int bouton) {
        return clic(ecran, zone, bouton);
    }

    private boolean clic(Screen screen, Zone zone, int bouton) {
        var event =
                new ScreenEvent.MouseButtonPressed.Pre(screen, zone.x() + 1, zone.y() + 1, bouton);
        MachinesEmi.clic(event);
        return event.cancelled;
    }

    private void aucunEnvoi() {
        assert !EnvoiMachine.occupe() && mc.gameMode.clicks == 0;
        assert entree.item.isEmpty() && cuivre.item.getCount() == 64;
    }

    private void verifierEnvoiA(int fois) {
        assert EnvoiMachine.occupe();
        assert EnvoiMachine.diagnostic().recette().equals(a.getId());
        for (int i = 0; i < 120 && EnvoiMachine.occupe(); i++)
            EnvoiMachine.tick(new TickEvent.ClientTickEvent());
        assert !EnvoiMachine.occupe();
        assert entree.item.id.equals("tin") && entree.item.getCount() == 2 * fois;
        assert etain.item.getCount() == 64 - 2 * fois && cuivre.item.getCount() == 64;
    }

    private void ancienneImage() {
        var image = afficherA();
        preparer(b, 2);
        assert PanneauMachineEmi.image == image;
        assert clic(image.disposition().prendre(), 0);
        assert MachinesEmi.problemeOrdre().equals(RECETTE);
        assert clic(image.disposition().icone(0), 1);
        assert EmiApi.ouverte == null;
        aucunEnvoi();
        var suivante = rendre();
        assert suivante.recettes().get(suivante.choix()).recette() == b;
        assert suivante.plan().fois() == 2;
        System.out.println("A ×3 → B ×2 → clic A : " + RECETTE + " 0 transfert, EMI fermé");
    }

    private void aToujoursValide(boolean quantiteChangee) {
        var d = afficherA().disposition();
        int fois = quantiteChangee ? 5 : 3;
        preparer(List.of(new Operation<>(b, 2), new Operation<>(a, fois)));
        assert clic(d.prendre(), 0);
        assert clic(d.prendre(), 0); // Transfert déjà en cours.
        verifierEnvoiA(fois);
        int clicsNatifs = mc.gameMode.clicks;
        assert clic(d.prendre(), 0); // Transfert terminé, limite de répétition native.
        assert !EnvoiMachine.occupe() && mc.gameMode.clicks == clicsNatifs;
        var vue = rendre();
        assert vue.recettes().get(vue.choix()).recette() == a;
        assert vue.recettes().get(vue.choix()).fois() == fois;
        System.out.println("A derrière B : envoi A ×" + fois + " exactement une fois");
    }

    private void operationDisparue() {
        var d = afficherA().disposition();
        preparer(List.of());
        assert !clic(d.prendre(), 0); // Pas de disposition préparée.
        assert rendre() == null;
        preparer(a, 3);
        assert !clic(d.prendre(), 0); // Toujours aucune image interactive.
        aucunEnvoi();
    }

    private void recetteDeMemeIdentifiant() {
        var d = afficherA().disposition();
        preparer(recette("A", "tin"), 3);
        assert clic(d.prendre(), 0);
        assert MachinesEmi.problemeOrdre().equals(RECETTE);
        aucunEnvoi();
    }

    private void contextePerime(String transition) {
        var d = afficherA().disposition();
        switch (transition) {
            case "quantité" -> arbre.batches = 2;
            case "arbre" -> ArbreEmi.tree = new InstantaneArbreEmiTest.Arbre();
            case "structure" -> InstantaneArbreEmi.arbreModifie();
            case "navigation" -> Pont.contexte = "autre projet";
            default -> throw new AssertionError(transition);
        }
        assert clic(d.prendre(), 0);
        assert MachinesEmi.problemeOrdre().equals(CONTEXTE);
        aucunEnvoi();
        System.out.println(transition + " : " + CONTEXTE);
    }

    private void nouvelInstantaneSansRevisionStructurelle() throws ReflectiveOperationException {
        var d = afficherA().disposition();
        var precedent = InstantaneArbreEmi.lire(arbre);
        long revision = InstantaneArbreEmi.revisionStructureProduction();
        arbre.goal = new MaterialNode(pile("autre-produit", 1));
        InstantaneArbreEmi.invalider();
        preparer(a, 4);
        assert InstantaneArbreEmi.lire(arbre) != precedent;
        assert InstantaneArbreEmi.revisionStructureProduction() == revision;
        assert clic(d.prendre(), 0);
        verifierEnvoiA(4);
        System.out.println("Nouvel instantané, même révision structurelle : envoi A ×4");
    }

    private void autreMenu(boolean fermeture) {
        var d = afficherA().disposition();
        if (fermeture) {
            mc.screen = null;
            mc.player.containerMenu = new ChestMenu();
        } else {
            var autre = new AbstractContainerScreen<>(new ChestMenu());
            mc.screen = autre;
            mc.player.containerMenu = autre.getMenu();
            MachinesEmi.init(new ScreenEvent.Init.Post(autre));
            MachinesEmi.preparer();
            assert !clic(autre, d.prendre(), 0);
        }
        assert !clic(d.prendre(), 0);
        aucunEnvoi();
    }

    private void retourAuMemeEcran() {
        var d = afficherA().disposition();
        MachinesEmi.init(new ScreenEvent.Init.Post(ecran));
        assert !clic(d.prendre(), 0);
        preparer(a, 3);
        assert clic(d.prendre(), 0);
        verifierEnvoiA(3);
    }

    private void navigation() {
        var ops = new ArrayList<Operation<EmiRecipe>>();
        for (int i = 0; i < 30; i++) ops.add(new Operation<>(recette("ligne-" + i, "tin"), 1));
        preparer(ops);
        var premiere = rendre();
        var d = premiere.disposition();
        assert d.pages();
        assert clic(d.suivant(), 0) && clic(d.suivant(), 0);
        // La seconde pression avant rendu part encore de la page affichée.
        assert clic(d.icone(1), 1);
        assert EmiApi.ouverte == ops.get(1).recette();
        assert rendre().page() == 1;
        preparer(ops);
        assert rendre().page() == 0; // Le recalcul ramène la sélection explicite dans sa page.
        assert clic(d.suivant(), 0);
        var deuxieme = rendre();
        assert deuxieme.page() == 1;
        assert clic(deuxieme.disposition().icone(0), 0);
        var choisie = rendre();
        assert choisie.recettes().get(choisie.choix()).recette() == ops.get(d.parPage()).recette();
        assert EmiApi.ouverte == ops.get(1).recette();
        MachinesEmi.molette(
                new ScreenEvent.MouseScrolled.Pre(ecran, d.cadre().x(), d.cadre().y(), 1));
        assert rendre().page() == 0;
        aucunEnvoi();
    }

    private void entreesPerimees(boolean pendantLancement) {
        var d = afficherA().disposition();
        if (pendantLancement) PanneauAssistant.son = () -> etain.item = ItemStack.EMPTY;
        else etain.item = ItemStack.EMPTY;
        assert clic(d.prendre(), 0);
        aucunEnvoi();
        var vue = rendre();
        if (pendantLancement) {
            assert EnvoiMachine.diagnostic().motif().equals("preconditions_transfert");
            assert EnvoiMachine.diagnostic().recette().equals(a.getId());
            assert !vue.statut().isEmpty();
            System.out.println("Ressource retirée au lancement : " + vue.statut());
        } else {
            assert !vue.actionActive() && !vue.plan().possible();
            System.out.println("Ressource retirée avant clic : " + vue.plan().probleme());
        }
    }

    private void circuit(int affiche, int courant) {
        var circuit = pile("gtceu:programmed_circuit", 1);
        circuit.chance = 0;
        var recette = new GTEmiRecipe(List.of(pile("tin", 2), circuit), List.of());
        recette.outputs = List.of(pile("produit", 1));
        GregTechMachines.machineCircuit = affiche;
        preparer(recette, 3);
        var image = rendre();
        assert image.actionActive() && image.circuit() == (affiche != 7);
        GregTechMachines.machineCircuit = courant;
        assert clic(image.disposition().prendre(), 0);
        if (affiche != 7 && courant != 7) {
            assert ReglageCircuitMachine.envoyee.valeur() == 7;
            assert clic(image.disposition().prendre(), 0);
            System.out.println("Circuit : réglage demandé sur 7, aucun transfert");
        } else {
            assert ReglageCircuitMachine.envoyee == null;
            var suivante = rendre();
            assert suivante.circuit() == (courant != 7);
            System.out.println("Circuit " + affiche + " → " + courant + " : aucun transfert");
        }
        aucunEnvoi();
    }

    private void actionDesactivee() {
        InventaireJoueur.synchro = false;
        preparer(a, 3);
        var vue = rendre();
        assert !vue.actionActive() && !vue.synchronise();
        InventaireJoueur.synchro = true;
        assert clic(vue.disposition().prendre(), 0);
        aucunEnvoi();
        var suivante = rendre();
        assert suivante.actionActive();
        InventaireJoueur.synchro = false;
        assert clic(suivante.disposition().prendre(), 0);
        aucunEnvoi();
    }

    private void declaration(boolean disparue) {
        var d = afficherA().disposition();
        if (disparue) preparer(b, 2);
        assert clic(d.tout(), 0);
        assert DeclarerFabricationEcran.ouverte == (disparue ? null : a);
        aucunEnvoi();
    }

    private static Object champ(String nom) throws ReflectiveOperationException {
        var champ = MachinesEmi.class.getDeclaredField(nom);
        champ.setAccessible(true);
        return champ.get(null);
    }

    /** Déconnexion : écran, plan et commande de circuit oubliés, puis premier usage intact. */
    private void deconnexion() throws ReflectiveOperationException {
        var circuit = pile("gtceu:programmed_circuit", 1);
        circuit.chance = 0;
        var recette = new GTEmiRecipe(List.of(pile("tin", 2), circuit), List.of());
        recette.outputs = List.of(pile("produit", 1));
        GregTechMachines.machineCircuit = 3;
        preparer(recette, 3);
        var image = rendre();
        assert image.circuit() && image.actionActive();
        for (String nom : List.of("ecran", "plan", "commandeCircuit")) assert champ(nom) != null;
        MachinesEmi.oublierMonde();
        for (String nom : List.of("ecran", "plan", "commandeCircuit"))
            assert champ(nom) == null : nom;
        assert rendre() == null && !clic(image.disposition().prendre(), 0);
        assert ReglageCircuitMachine.envoyee == null;
        aucunEnvoi();
        GregTechMachines.machineCircuit = 7;
        MachinesEmi.init(new ScreenEvent.Init.Post(ecran));
        afficherA();
        System.out.println("PASS deconnexion");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        if (args.length > 0 && args[0].equals("deconnexion")) {
            new PanneauMachineClicTest().deconnexion();
            return;
        }
        new PanneauMachineClicTest().ancienneImage();
        new PanneauMachineClicTest().aToujoursValide(false);
        new PanneauMachineClicTest().aToujoursValide(true);
        new PanneauMachineClicTest().operationDisparue();
        new PanneauMachineClicTest().recetteDeMemeIdentifiant();
        for (var transition : List.of("quantité", "arbre", "structure", "navigation"))
            new PanneauMachineClicTest().contextePerime(transition);
        new PanneauMachineClicTest().nouvelInstantaneSansRevisionStructurelle();
        new PanneauMachineClicTest().autreMenu(true);
        new PanneauMachineClicTest().autreMenu(false);
        new PanneauMachineClicTest().retourAuMemeEcran();
        new PanneauMachineClicTest().navigation();
        new PanneauMachineClicTest().entreesPerimees(false);
        new PanneauMachineClicTest().entreesPerimees(true);
        new PanneauMachineClicTest().circuit(3, 3);
        new PanneauMachineClicTest().circuit(3, 7);
        new PanneauMachineClicTest().circuit(7, 3);
        new PanneauMachineClicTest().actionDesactivee();
        new PanneauMachineClicTest().declaration(false);
        new PanneauMachineClicTest().declaration(true);
        System.out.println("PanneauMachineClic OK");
    }
}
