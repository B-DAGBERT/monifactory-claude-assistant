package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.LotFabrication;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.ReceptionManuelle;
import fr.assistantia.ReceptionSortie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Réservations et réceptions des lots, indépendamment du panneau ouvert. */
final class SuiviLotsEmi {
    private final List<LotMachine> lots = new ArrayList<>();
    private final List<LotMachine> lecture = Collections.unmodifiableList(lots);
    private final Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire;
    private final Runnable changement;
    private final Supplier<Object> provenance;
    private final java.util.Map<Integer, Object> origines = new java.util.HashMap<>();
    private List<PlanRetrait.Stock<EmiStack>> inventaireAssimile;
    private final ReceptionManuelle<EmiStack> receptionManuelle =
            new ReceptionManuelle<>(ComparaisonEmi::meme);
    private long revision;
    private int prochainLot;
    private AbstractContainerMenu menuObserve;
    private GregTechMachines.Interface machineObservee;
    private List<Slot> sortiesObservees = List.of();
    private List<GregTechMachines.Cuve> cuvesObservees = List.of();
    private ReceptionSortie<EmiStack> receptionFluides =
            new ReceptionSortie<>(ComparaisonEmi::meme);

    SuiviLotsEmi(Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire, Runnable changement) {
        this(inventaire, changement, () -> null);
    }

    SuiviLotsEmi(
            Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire,
            Runnable changement,
            Supplier<Object> provenance) {
        this.inventaire = inventaire;
        this.changement = changement;
        this.provenance = provenance;
    }

    Object origine(int id) {
        return origines.get(id);
    }

    List<PlanRetrait.Stock<EmiStack>> inventaireAssimile() {
        return inventaireAssimile;
    }

    boolean receptionEnAttente() {
        return receptionManuelle.enAttente() || receptionFluides.enAttente();
    }

    private void memoriserInventaire(List<PlanRetrait.Stock<EmiStack>> stock) {
        if (inventaireAssimile != null && stock.size() == inventaireAssimile.size()) {
            boolean identique = true;
            for (int i = 0; i < stock.size() && identique; i++) {
                var a = stock.get(i);
                var b = inventaireAssimile.get(i);
                identique =
                        a.quantite() == b.quantite()
                                && a.objet().getItemStack().isEmpty()
                                        == b.objet().getItemStack().isEmpty()
                                && a.objet().getId().equals(b.objet().getId())
                                && java.util.Objects.equals(a.objet().getNbt(), b.objet().getNbt());
            }
            if (identique) return;
        }
        inventaireAssimile =
                stock.stream()
                        .map(s -> new PlanRetrait.Stock<>(s.objet().copy(), s.quantite(), true))
                        .toList();
    }

    List<LotMachine> actifs() {
        return lecture;
    }

    long revision() {
        return revision;
    }

    private void modifie() {
        revision++;
        changement.run();
    }

    void oublierInterface() {
        menuObserve = null;
        machineObservee = null;
        sortiesObservees = List.of();
        cuvesObservees = List.of();
    }

    void vider() {
        lots.clear();
        origines.clear();
        inventaireAssimile = null;
        oublierInterface();
        receptionManuelle.vider();
        modifie();
    }

    boolean annuler(int id) {
        for (var lot : lots)
            if (lot.id() == id && lot.suivi().annulable()) {
                lot.suivi().annuler();
                modifie();
                return true;
            }
        return false;
    }

    boolean completer(int id, EmiRecipe recette, int supplement) {
        for (int i = 0; i < lots.size(); i++) {
            var lot = lots.get(i);
            if (lot.id() != id || lot.recette() != recette) continue;
            var sorties = sortiesLot(recette, supplement, !lot.manuel());
            if (sorties.isEmpty() || !lot.suivi().ajouter(sorties)) return false;
            if (!java.util.Objects.equals(origines.get(id), provenance.get())) origines.remove(id);
            lots.set(
                    i,
                    new LotMachine(
                            id,
                            recette,
                            PlanMachines.produit(1, (long) lot.fois() + supplement),
                            lot.emplacement(),
                            lot.suivi()));
            modifie();
            return true;
        }
        return false;
    }

    private static List<PlanRetrait.Stock<EmiStack>> sortiesLot(
            EmiRecipe recipe, int n, boolean automatique) {
        List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
        for (var output : recipe.getOutputs())
            if (!output.isEmpty()
                    && output.getChance() >= 1
                    && (automatique || !output.getItemStack().isEmpty()))
                sorties.add(
                        new PlanRetrait.Stock<>(
                                output.copy(), PlanMachines.produit(output.getAmount(), n), true));
        return List.copyOf(sorties);
    }

    int ajouter(EmiRecipe recipe, int n, BlockPos position) {
        recevoir(List.of());
        var personnels = inventaire.get();
        receptionManuelle.initialiser(personnels);
        memoriserInventaire(personnels);
        var sorties = sortiesLot(recipe, n, position != null);
        if (sorties.isEmpty()) return 0;
        int id = ++prochainLot;
        var origine = provenance.get();
        if (origine != null) origines.put(id, origine);
        lots.add(
                new LotMachine(
                        id,
                        recipe,
                        n,
                        java.util.Optional.ofNullable(position),
                        new LotFabrication<>(sorties, ComparaisonEmi::meme)));
        modifie();
        return id;
    }

    void recevoir(List<PlanRetrait.Stock<ItemStack>> objets) {
        if (lots.isEmpty()) return;
        List<PlanRetrait.Stock<EmiStack>> gains =
                objets.stream()
                        .map(
                                s ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(s.objet()), s.quantite(), true))
                        .toList();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        List<PlanRetrait.Stock<EmiStack>> restants = gains;
        List<PlanRetrait.Stock<EmiStack>> attendusAutomatiques = new ArrayList<>();
        try {
            var ui = GregTechMachines.ouvrir(player.containerMenu);
            if (ui != null) {
                for (var lot : lots)
                    if (lot.ici(ui.position())) attendusAutomatiques.addAll(lot.suivi().restants());
                restants =
                        recevoir(
                                gains,
                                ui.position(),
                                OrigineFabrication.receptionsMachineProuvees(
                                        objets, player.containerMenu));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Les lots envoyés automatiquement exigent une preuve de leur machine.
        }
        var attribues = receptionManuelle.soustraire(gains, restants);
        var personnels = inventaire.get();
        var nouveaux =
                receptionManuelle.actualiser(personnels, attribues, attendusAutomatiques, restants);
        memoriserInventaire(personnels);
        if (nouveaux.isEmpty()) return;
        var distribution =
                fr.assistantia.ReceptionLots.repartir(
                        nouveaux,
                        lots.stream()
                                .map(
                                        l ->
                                                new fr.assistantia.ReceptionLots.Source<>(
                                                        l.suivi(),
                                                        (java.util.function.Predicate<EmiStack>)
                                                                s ->
                                                                        l.manuel()
                                                                                || !s.getItemStack()
                                                                                        .isEmpty()))
                                .toList(),
                        false);
        if (distribution.change()) {
            modifie();
        }
    }

    private List<PlanRetrait.Stock<EmiStack>> recevoir(
            List<PlanRetrait.Stock<EmiStack>> gains, BlockPos position) {
        return recevoir(gains, position, true);
    }

    private List<PlanRetrait.Stock<EmiStack>> recevoir(
            List<PlanRetrait.Stock<EmiStack>> gains, BlockPos position, boolean origineProuvee) {
        if (gains.isEmpty()) return gains;
        for (var lot : lots)
            if (lot.ici(position)) {
                int avant = lot.suivi().totalRecu();
                gains = lot.suivi().recevoir(gains, origineProuvee);
                if (avant != lot.suivi().totalRecu()) {
                    modifie();
                }
            }
        return gains;
    }

    void actualiser() {
        var player = Minecraft.getInstance().player;
        if (player == null || lots.isEmpty()) {
            oublierInterface();
            return;
        }
        var dansCoffres =
                new java.util.HashMap<>(fr.assistantia.RechercheEmi.resultatsDisponibles());
        for (var lot : lots) {
            boolean disponible = false;
            for (var attendu : lot.suivi().restants()) {
                String id = attendu.objet().getId().toString();
                int n = Math.min(attendu.quantite(), dansCoffres.getOrDefault(id, 0));
                disponible |= n > 0;
                dansCoffres.put(id, Math.max(0, dansCoffres.getOrDefault(id, 0) - n));
            }
            lot.suivi().disponible(disponible);
        }
        try {
            if (menuObserve != player.containerMenu) {
                menuObserve = player.containerMenu;
                machineObservee = GregTechMachines.ouvrir(menuObserve);
                sortiesObservees =
                        machineObservee == null ? List.of() : GregTechMachines.sorties(menuObserve);
                cuvesObservees =
                        machineObservee == null
                                ? List.of()
                                : GregTechMachines.cuvesSortie(menuObserve);
                receptionFluides = new fr.assistantia.ReceptionSortie<>(ComparaisonEmi::meme);
            }
            if (machineObservee == null || !fr.assistantia.InventaireJoueur.synchronise()) return;
            List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
            for (var slot : sortiesObservees)
                if (!slot.getItem().isEmpty())
                    sorties.add(
                            new PlanRetrait.Stock<>(
                                    EmiStack.of(slot.getItem()), slot.getItem().getCount(), true));
            List<PlanRetrait.Stock<EmiStack>> fluides = new ArrayList<>();
            for (var cuve : cuvesObservees) {
                var f = cuve.contenu();
                if (!f.isEmpty())
                    fluides.add(
                            new PlanRetrait.Stock<>(
                                    EmiStack.of(f.getFluid(), f.getTag(), f.getAmount()),
                                    f.getAmount(),
                                    true));
            }
            sorties.addAll(fluides);
            var personnels = new ArrayList<PlanRetrait.Stock<EmiStack>>();
            if (!cuvesObservees.isEmpty()) {
                personnels.addAll(
                        inventaire.get().stream()
                                .filter(
                                        o ->
                                                o.objet().getKey()
                                                        instanceof
                                                        net.minecraft.world.level.material.Fluid)
                                .toList());
            }
            recevoir(receptionFluides.actualiser(personnels, fluides), machineObservee.position());
            for (var lot : lots)
                if (lot.ici(machineObservee.position()))
                    lot.suivi()
                            .disponible(
                                    lot.suivi().etat() == LotFabrication.Etat.A_RECUPERER
                                            || sorties.stream()
                                                    .anyMatch(
                                                            s ->
                                                                    lot.suivi().restants().stream()
                                                                            .anyMatch(
                                                                                    r ->
                                                                                            ComparaisonEmi
                                                                                                    .meme(
                                                                                                            s
                                                                                                                    .objet(),
                                                                                                            r
                                                                                                                    .objet()))));
        } catch (ReflectiveOperationException | RuntimeException e) {
            machineObservee = null;
        }
    }
}
