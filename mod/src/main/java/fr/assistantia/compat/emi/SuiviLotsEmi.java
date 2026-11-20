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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
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
    private final Set<Integer> retiresDuSuivi = new HashSet<>();
    private final Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire;
    private final Supplier<List<PlanRetrait.Stock<EmiStack>>> poches;
    private final BooleanSupplier inventaireDisponible;
    private final Runnable changement;
    private final Supplier<Object> provenance;
    private final java.util.Map<Integer, Object> origines = new java.util.HashMap<>();
    private final java.util.Map<Integer, Object> arbresTermines = new java.util.HashMap<>();
    private List<PlanRetrait.Stock<EmiStack>> inventaireAssimile;
    private List<PlanRetrait.Stock<EmiStack>> sourceAssimilee;
    private OrigineFabrication.Provenances derniereReception;
    private final List<PlanRetrait.Stock<EmiStack>> gainsExclus = new ArrayList<>();
    private final List<CollecteDifferee> collectesDifferees = new ArrayList<>();
    private final List<PlanRetrait.Stock<EmiStack>> craftsDifferes = new ArrayList<>();
    private final ReceptionManuelle<EmiStack> receptionManuelle =
            new ReceptionManuelle<>(ComparaisonEmi::meme);
    private long revision;
    private int prochainLot;
    private AbstractContainerMenu menuObserve;
    private GregTechMachines.Interface machineObservee;
    private List<Slot> sortiesObservees = List.of();
    private List<GregTechMachines.Cuve> cuvesObservees = List.of();
    private ReceptionSortie<EmiStack> receptionFluides =
            new ReceptionSortie<>(SuiviLotsEmi::memeFluide, true);

    SuiviLotsEmi(Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire, Runnable changement) {
        this(inventaire, changement, () -> null);
    }

    SuiviLotsEmi(
            Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire,
            Runnable changement,
            Supplier<Object> provenance) {
        this(inventaire, changement, provenance, () -> true, inventaire);
    }

    SuiviLotsEmi(
            Supplier<List<PlanRetrait.Stock<EmiStack>>> inventaire,
            Runnable changement,
            Supplier<Object> provenance,
            BooleanSupplier inventaireDisponible,
            Supplier<List<PlanRetrait.Stock<EmiStack>>> poches) {
        this.inventaire = inventaire;
        this.changement = changement;
        this.provenance = provenance;
        this.inventaireDisponible = inventaireDisponible;
        this.poches = poches;
    }

    Object origine(int id) {
        return origines.get(id);
    }

    List<PlanRetrait.Stock<EmiStack>> inventaireAssimile() {
        return inventaireAssimile;
    }

    boolean receptionEnAttente() {
        long maintenant = System.nanoTime();
        return !gainsExclus.isEmpty()
                || collectesDifferees.stream().anyMatch(c -> maintenant - c.echeance() < 0)
                || !craftsDifferes.isEmpty()
                || receptionManuelle.enAttente()
                || receptionFluides.enAttente();
    }

    private void memoriserInventaire(List<PlanRetrait.Stock<EmiStack>> stock) {
        // Une liste immuable déjà assimilée ne peut pas avoir changé : List.copyOf la rend telle
        // quelle, alors qu'une liste modifiable est copiée et passe par la comparaison complète.
        var source = List.copyOf(stock);
        if (source == sourceAssimilee) return;
        sourceAssimilee = source;
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

    /** Depuis un coffre ou ME, on sait ce qui est porté, pas de quelle machine ça provient. */
    java.util.Map<Integer, Integer> aConfirmer() {
        var aVerifier = lots.stream().filter(l -> !l.manuel() && l.suivi().annulable()).toList();
        if (aVerifier.isEmpty()) return java.util.Map.of();
        var disponibles = new ArrayList<>(inventaire.get());
        if (disponibles.isEmpty()) return java.util.Map.of();
        var recus =
                lots.stream()
                        .flatMap(
                                l ->
                                        (l.suivi().termineManuellement()
                                                        ? l.suivi().attendus()
                                                        : l.suivi().obtenus())
                                                .stream())
                        .map(SuiviLotsEmi::besoin)
                        .toList();
        PlanRetrait.reserver(disponibles, recus, 0, null);

        List<PlanRetrait.Besoin<EmiStack>> demandes = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (var lot : aVerifier)
            for (var restant : lot.suivi().restants())
                if (!restant.objet().getItemStack().isEmpty()) {
                    demandes.add(besoin(restant));
                    ids.add(lot.id());
                }
        var quantites = PlanRetrait.calculer(disponibles, demandes).fournis();
        var resultat = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < ids.size(); i++)
            if (quantites.get(i) > 0) resultat.merge(ids.get(i), quantites.get(i), Integer::sum);
        return resultat;
    }

    private static PlanRetrait.Besoin<EmiStack> besoin(PlanRetrait.Stock<EmiStack> sortie) {
        return new PlanRetrait.Besoin<>(
                pile -> ComparaisonEmi.meme(pile, sortie.objet()), sortie.quantite());
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
        receptionFluides = new ReceptionSortie<>(SuiviLotsEmi::memeFluide, true);
    }

    void vider() {
        lots.clear();
        origines.clear();
        arbresTermines.clear();
        retiresDuSuivi.clear();
        inventaireAssimile = sourceAssimilee = null;
        derniereReception = null;
        gainsExclus.clear();
        collectesDifferees.clear();
        craftsDifferes.clear();
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

    boolean terminer(int id, Object arbre) {
        if (arbre == null) return false;
        for (var lot : lots)
            if (lot.id() == id && lot.suivi().terminerManuellement()) {
                arbresTermines.put(id, arbre);
                modifie();
                return true;
            }
        return false;
    }

    boolean retirerDuSuivi(int id) {
        return lots.stream()
                        .anyMatch(
                                lot ->
                                        lot.id() == id
                                                && lot.suivi().etat()
                                                        == LotFabrication.Etat.TERMINE)
                && retiresDuSuivi.add(id);
    }

    boolean visibleDansSuivi(LotMachine lot) {
        return switch (lot.suivi().etat()) {
            case ANNULE -> false;
            case TERMINE -> !retiresDuSuivi.contains(lot.id());
            default -> true;
        };
    }

    /** Les sorties confirmées par le joueur comptent uniquement pour le calcul de cet arbre. */
    List<PlanRetrait.Stock<EmiStack>> avancement(Object arbre) {
        return lots.stream()
                .flatMap(
                        lot ->
                                arbre != null && arbresTermines.get(lot.id()) == arbre
                                        ? lot.suivi().attendus().stream()
                                        : lot.suivi().obtenus().stream()
                                                .filter(s -> !s.objet().getItemStack().isEmpty()))
                .toList();
    }

    boolean completer(int id, EmiRecipe recette, int supplement) {
        for (int i = 0; i < lots.size(); i++) {
            var lot = lots.get(i);
            if (lot.id() != id || lot.recette() != recette) continue;
            var sorties = sortiesLot(recette, supplement, !lot.manuel());
            if (sorties.isEmpty() || !lot.suivi().ajouter(sorties)) return false;
            retiresDuSuivi.remove(id);
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
        if (inventaireDisponible.getAsBoolean()) {
            var personnels = inventaire.get();
            receptionManuelle.initialiser(personnels);
            memoriserInventaire(personnels);
        }
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

    private static List<PlanRetrait.Stock<EmiStack>> objets(
            List<PlanRetrait.Stock<ItemStack>> stock) {
        return stock.stream()
                .map(s -> new PlanRetrait.Stock<>(EmiStack.of(s.objet()), s.quantite(), true))
                .toList();
    }

    private record Collecte(ItemStack variante, LotMachine lot) {}

    private record CollecteDifferee(PlanRetrait.Stock<EmiStack> gain, long echeance) {}

    // Après Échap, le dépôt d'un terminal peut devenir visible seulement à sa réouverture.
    private static final long ATTENTE_GAIN_COLLECTE = 120_000_000_000L;

    /** Le bouton apporte une acquisition, sans preuve de la machine qui l'a produite. */
    BiConsumer<ItemStack, Integer> preparerCollecte(List<ItemStack> variantes) {
        List<Collecte> suivies = new ArrayList<>();
        for (var pile : variantes) {
            var objet = EmiStack.of(pile);
            var candidats = lots.stream().filter(lot -> attend(lot, objet)).limit(2).toList();
            if (candidats.size() == 1 && !candidats.get(0).manuel())
                suivies.add(new Collecte(pile.copy(), candidats.get(0)));
        }
        return (pile, quantite) -> {
            if (quantite <= 0) return;
            for (var collecte : suivies)
                if (ItemStack.isSameItemSameTags(collecte.variante(), pile)) {
                    recevoirCollecte(collecte.lot(), EmiStack.of(pile), quantite);
                    return;
                }
        };
    }

    private static boolean attend(LotMachine lot, EmiStack objet) {
        return lot.suivi().restants().stream().anyMatch(r -> ComparaisonEmi.meme(r.objet(), objet));
    }

    private void recevoirCollecte(LotMachine lot, EmiStack objet, int quantite) {
        if (!lots.contains(lot) || lots.stream().anyMatch(l -> l != lot && attend(l, objet)))
            return;
        var gain = new PlanRetrait.Stock<>(objet, quantite, true);
        int avant = lot.suivi().totalRecu();
        lot.suivi().recevoir(List.of(gain), false);
        int recu = lot.suivi().totalRecu() - avant;
        if (recu <= 0) return;
        // Tout le retrait provenait du stockage, y compris un éventuel surplus du lot.
        collectesDifferees.add(
                new CollecteDifferee(gain, System.nanoTime() + ATTENTE_GAIN_COLLECTE));
        modifie();
    }

    /** Le paquet d'un menu fermé peut confirmer le dépôt avant le relevé des poches. */
    private void exclureCollectes(List<PlanRetrait.Stock<EmiStack>> personnels) {
        var avant =
                inventaireAssimile == null
                        ? List.<PlanRetrait.Stock<EmiStack>>of()
                        : inventaireAssimile;
        for (var it = collectesDifferees.listIterator(); it.hasNext(); ) {
            var collecte = it.next();
            var gain = collecte.gain();
            var objet = gain.objet();
            long delta = quantiteExacte(personnels, objet) - quantiteExacte(avant, objet);
            long deja = quantiteExacte(gainsExclus, objet) + quantiteExacte(craftsDifferes, objet);
            int n = (int) Math.min(gain.quantite(), Math.max(0L, delta - deja));
            if (n <= 0) continue;
            gainsExclus.add(new PlanRetrait.Stock<>(objet, n, true));
            if (n == gain.quantite()) it.remove();
            else
                it.set(
                        new CollecteDifferee(
                                new PlanRetrait.Stock<>(objet, gain.quantite() - n, true),
                                collecte.echeance()));
        }
    }

    private static long quantiteExacte(List<PlanRetrait.Stock<EmiStack>> stock, EmiStack objet) {
        var item = objet.getItemStack();
        return stock.stream()
                .filter(s -> objet.getKey().equals(s.objet().getKey()))
                .filter(s -> ItemStack.isSameItemSameTags(item, s.objet().getItemStack()))
                .mapToLong(PlanRetrait.Stock::quantite)
                .sum();
    }

    void recevoir(OrigineFabrication.Provenances provenances) {
        if (provenances == derniereReception) return;
        derniereReception = provenances;
        recevoir(provenances.crafts(), provenances.machine());
    }

    void recevoir(List<PlanRetrait.Stock<ItemStack>> crafts) {
        recevoir(crafts, null);
    }

    private void recevoir(
            List<PlanRetrait.Stock<ItemStack>> crafts,
            OrigineFabrication.ReceptionMachine machine) {
        if (lots.isEmpty()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        long maintenant = System.nanoTime();
        collectesDifferees.removeIf(c -> maintenant - c.echeance() >= 0);
        craftsDifferes.addAll(objets(crafts));
        boolean complet = inventaireDisponible.getAsBoolean();
        if (!complet && !crafts.isEmpty()) compacter(craftsDifferes);
        if (machine != null
                && (player.containerMenu == machine.menu()
                        || player.containerMenu == player.inventoryMenu)) {
            var retraitsMachine = objets(machine.objets());
            recevoirMachine(retraitsMachine, machine.position());
            gainsExclus.addAll(retraitsMachine);
            if (!complet) compacter(gainsExclus);
        }
        if (!complet) return;
        var attendusAutomatiques =
                receptionManuelle.soustraire(
                        objets(OrigineFabrication.retraitsAttendus(player)), craftsDifferes);
        var personnels = inventaire.get();
        exclureCollectes(personnels);
        var nouveaux =
                receptionManuelle.actualiser(
                        personnels, gainsExclus, attendusAutomatiques, craftsDifferes);
        gainsExclus.clear();
        craftsDifferes.clear();
        memoriserInventaire(personnels);
        if (nouveaux.isEmpty()) return;
        var distribution =
                fr.assistantia.ReceptionLots.repartir(
                        nouveaux,
                        lots.stream()
                                .filter(LotMachine::manuel)
                                .map(
                                        l ->
                                                new fr.assistantia.ReceptionLots.Source<>(
                                                        l.suivi(),
                                                        (java.util.function.Predicate<EmiStack>)
                                                                s -> true))
                                .toList(),
                        false);
        if (distribution.change()) {
            modifie();
        }
    }

    private void compacter(List<PlanRetrait.Stock<EmiStack>> stock) {
        if (stock.size() < 2) return;
        var cumule = receptionManuelle.soustraire(stock, List.of());
        stock.clear();
        stock.addAll(cumule);
    }

    private void recevoirMachine(List<PlanRetrait.Stock<EmiStack>> gains, BlockPos position) {
        boolean change = false;
        for (var gain : gains) {
            var candidats =
                    lots.stream()
                            .filter(l -> l.ici(position))
                            .filter(
                                    l ->
                                            l.suivi().restants().stream()
                                                    .anyMatch(
                                                            r ->
                                                                    ComparaisonEmi.meme(
                                                                            r.objet(),
                                                                            gain.objet())))
                            .toList();
            if (candidats.isEmpty()) continue;
            var recette = candidats.get(0).recette();
            // Dans une même machine, deux recettes différentes peuvent produire le même objet.
            if (candidats.stream().anyMatch(lot -> lot.recette() != recette)) continue;
            var reste = List.of(gain);
            for (var lot : candidats) {
                var suivi = lot.suivi();
                int avant = suivi.totalRecu();
                // Ordre de lancement pour une même recette exacte ; preuve locale uniquement.
                reste = suivi.recevoir(reste, false);
                change |= avant != suivi.totalRecu();
                if (reste.isEmpty()) break;
            }
        }
        if (change) modifie();
    }

    private static boolean memeFluide(EmiStack a, EmiStack b) {
        return a.getKey().equals(b.getKey()) && java.util.Objects.equals(a.getNbt(), b.getNbt());
    }

    void clicSortieFluide(Object widget) {
        var player = Minecraft.getInstance().player;
        if (player == null || lots.isEmpty()) return;
        try {
            observerMenu(player.containerMenu);
            for (var cuve : cuvesObservees)
                if (cuve.widget() == widget) {
                    if (!cuve.peutRemplirContenant()) return;
                    var fluide = cuve.contenu();
                    if (!fluide.isEmpty()) {
                        receptionFluides.preparerGeste(fluidesPortes(), fluidesSortie());
                        receptionFluides.autoriser(
                                EmiStack.of(fluide.getFluid(), fluide.getTag(), fluide.getAmount()),
                                fluide.getAmount());
                    }
                    return;
                }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Les interfaces dont les cuves ne sont pas lisibles ne prouvent aucun retrait.
        }
    }

    private void observerMenu(AbstractContainerMenu menu) throws ReflectiveOperationException {
        if (menu == menuObserve) return;
        oublierInterface();
        menuObserve = menu;
        machineObservee = GregTechMachines.ouvrir(menu);
        if (machineObservee == null) return;
        sortiesObservees = GregTechMachines.sorties(menu);
        cuvesObservees = GregTechMachines.cuvesSortie(menu);
    }

    private List<PlanRetrait.Stock<EmiStack>> fluidesPortes() {
        return poches.get().stream()
                .filter(o -> o.objet().getKey() instanceof net.minecraft.world.level.material.Fluid)
                .toList();
    }

    private List<PlanRetrait.Stock<EmiStack>> fluidesSortie() throws ReflectiveOperationException {
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
        return fluides;
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
            boolean ferme =
                    player.containerMenu == player.inventoryMenu
                            && player.containerMenu != menuObserve
                            && receptionFluides.enAttente();
            if (!ferme) observerMenu(player.containerMenu);
            if (machineObservee == null) return;
            var fluides = fluidesSortie();
            if (receptionFluides.enAttente())
                recevoirMachine(
                        receptionFluides.actualiser(fluidesPortes(), fluides),
                        machineObservee.position());
            if (ferme) return;
            List<PlanRetrait.Stock<EmiStack>> sorties = new ArrayList<>();
            for (var slot : sortiesObservees)
                if (slot.isActive() && !slot.getItem().isEmpty())
                    sorties.add(
                            new PlanRetrait.Stock<>(
                                    EmiStack.of(slot.getItem()), slot.getItem().getCount(), true));
            sorties.addAll(fluides);
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
