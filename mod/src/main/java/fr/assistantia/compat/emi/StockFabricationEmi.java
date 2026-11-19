package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.CoffreTampon;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.ReceptionLots;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Stock de calcul uniquement. Chaque menu conserve ses sources et sa validation de transfert. */
record StockFabricationEmi(
        List<PlanRetrait.Stock<EmiStack>> personnel,
        List<PlanRetrait.Stock<EmiStack>> tampon,
        List<PlanRetrait.Stock<EmiStack>> reseau,
        List<PlanRetrait.Stock<EmiStack>> credits,
        List<PlanRetrait.Stock<EmiStack>> attendus,
        List<PlanRetrait.Stock<EmiStack>> outils) {

    private record Variante(Object objet, CompoundTag nbt, Tag capacites) {
        Variante(ItemStack pile) {
            this(pile.getItem(), pile.getTag(), pile.save(new CompoundTag()).get("ForgeCaps"));
        }
    }

    static StockFabricationEmi courant(
            List<PlanRetrait.Stock<EmiStack>> personnel,
            List<PlanRetrait.Stock<ItemStack>> reseau) {
        return courant(personnel, reseau, false);
    }

    static StockFabricationEmi pourMachines(List<PlanRetrait.Stock<EmiStack>> personnel) {
        return courant(personnel, List.of(), true);
    }

    private static StockFabricationEmi courant(
            List<PlanRetrait.Stock<EmiStack>> personnel,
            List<PlanRetrait.Stock<ItemStack>> reseau,
            boolean pourMachines) {
        var pilesTampon = CoffreTampon.piles();
        // Priorité aux acquisitions et aux lots avant les ingrédients à aller chercher.
        var tampons = tampons(pilesTampon, !pourMachines);
        var stockMe = horsTampon(reseau, pilesTampon);
        var externes = joindre(tampons, stockMe);
        var physiques = joindre(personnel, externes);
        var finaux =
                CycleArbreEmi.credits().stream()
                        .map(
                                c ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(c.objet()), c.quantite(), true))
                        .toList();
        var creditsFinaux =
                externes.isEmpty()
                        ? finaux
                        : ReceptionLots.credits(finaux, externes, List.of(), ComparaisonEmi::meme);
        var credits = joindre(creditsFinaux, MachinesEmi.creditsAcquis(physiques, creditsFinaux));
        // Ces quantités écartent un craft en double, sans alimenter les transferts natifs.
        var attendus = MachinesEmi.reservationsArbre();
        var dejaCouverts = joindre(physiques, credits);
        if (!pourMachines) dejaCouverts.addAll(attendus);
        var outils = MachinesEmi.completerOutils(dejaCouverts);
        return new StockFabricationEmi(
                List.copyOf(personnel), tampons, stockMe, List.copyOf(credits), attendus, outils);
    }

    List<PlanRetrait.Stock<EmiStack>> physiques() {
        return joindre(personnel, tampon, reseau);
    }

    List<PlanRetrait.Stock<EmiStack>> virtuels() {
        return joindre(credits, attendus, outils);
    }

    List<PlanRetrait.Stock<EmiStack>> couverture() {
        return joindre(personnel, tampon, credits, attendus, outils, reseau);
    }

    <R> List<PlanMachines.Operation<R>> operations(
            PlanMachines.Noeud<EmiStack, R> arbre, long lots) {
        return PlanMachines.calculer(arbre, lots, couverture());
    }

    <R> List<PlanMachines.Operation<R>> operationsSansSortiesAttendues(
            PlanMachines.Noeud<EmiStack, R> arbre, long lots) {
        return PlanMachines.calculer(
                arbre, lots, joindre(personnel, tampon, credits, outils, reseau));
    }

    <R> List<PlanRetrait.Prise> retraitsReseau(PlanMachines.Noeud<EmiStack, R> arbre, long lots) {
        if (reseau.stream().noneMatch(s -> s.quantite() > 0)) return List.of();
        var couverture = couverture();
        int debutReseau = couverture.size() - reseau.size();
        return PlanMachines.retraits(arbre, lots, couverture).stream()
                .filter(p -> p.index() >= debutReseau)
                .map(p -> new PlanRetrait.Prise(p.index() - debutReseau, p.quantite()))
                .toList();
    }

    @SafeVarargs
    private static List<PlanRetrait.Stock<EmiStack>> joindre(
            List<PlanRetrait.Stock<EmiStack>>... sources) {
        var resultat = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (var source : sources) resultat.addAll(source);
        return resultat;
    }

    static List<PlanRetrait.Stock<EmiStack>> tampons(List<ItemStack> tampon, boolean sansRetrait) {
        return tampon.stream()
                .map(p -> new PlanRetrait.Stock<>(EmiStack.of(p), p.getCount(), sansRetrait))
                .toList();
    }

    /** Le terminal agrège ses bus de stockage. Conserver seulement l'excédent ME par variante. */
    private static List<PlanRetrait.Stock<EmiStack>> horsTampon(
            List<PlanRetrait.Stock<ItemStack>> stockMe, List<ItemStack> tampon) {
        if (stockMe.isEmpty()) return List.of();
        var doublons = new HashMap<Variante, Long>();
        for (var pile : tampon)
            doublons.merge(new Variante(pile), (long) pile.getCount(), Long::sum);
        var resultat = new ArrayList<PlanRetrait.Stock<EmiStack>>(stockMe.size());
        for (var stock : stockMe) {
            int quantite = stock.quantite();
            if (!doublons.isEmpty()) {
                var variante = new Variante(stock.objet());
                long commun = Math.min(quantite, doublons.getOrDefault(variante, 0L));
                if (commun > 0) {
                    doublons.merge(variante, -commun, Long::sum);
                    quantite -= (int) commun;
                }
            }
            // Conserver les indices des entrées AE2, même quand une entrée est couverte.
            resultat.add(
                    new PlanRetrait.Stock<>(
                            ComparaisonEmi.pourInventaire(EmiStack.of(stock.objet())),
                            quantite,
                            false));
        }
        return List.copyOf(resultat);
    }
}
