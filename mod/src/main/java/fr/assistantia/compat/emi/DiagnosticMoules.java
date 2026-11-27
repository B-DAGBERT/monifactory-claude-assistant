package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreCollecte;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

/** Explication locale des deux calculs de l'assistant sur le même arbre natif. */
final class DiagnosticMoules {
    private static final int MAX_MOULES = 12;

    private record Moule(
            PlanMachines.Noeud<EmiStack, EmiRecipe> noeud, Set<String> ids, EmiRecipe usage) {}

    private DiagnosticMoules() {}

    static List<Map.Entry<String, String>> lire(
            InstantaneArbreEmi vue,
            StockFabricationEmi stock,
            List<PlanRetrait.Stock<EmiStack>> coffres,
            Map<String, Long> releves,
            boolean memoirePrete,
            List<ItemStack> sacs,
            boolean sacsInclus) {
        List<Moule> moules = new ArrayList<>();
        chercher(vue.collecte(), vue.machines(), null, moules);
        if (moules.isEmpty())
            return List.of(Map.entry("Moules EMI", "Aucun moule dans cet arbre."));
        var base = stock.couverture();
        var suivi = observer(vue, base, moules);
        var recette = suivi;
        var externes = MoulesDisponibles.pourPlan(vue);
        if (!coffres.isEmpty() || !externes.isEmpty()) {
            base.addAll(coffres);
            base.addAll(externes);
            recette = observer(vue, base, moules);
        }
        var coffresMoules = new ArrayList<>(coffres);
        coffresMoules.addAll(MoulesDisponibles.coffres(vue));
        var reseau = MoulesDisponibles.reseau();
        var donnees = new ArrayList<Map.Entry<String, String>>();
        donnees.add(
                Map.entry(
                        "Moules EMI",
                        "Le plan compte les moules à récupérer. Envoyer vérifie les entrées et les poches."));
        var demandes = recette;
        moules.sort(
                java.util.Comparator.comparingInt(
                        m ->
                                suivi.containsKey(m.noeud()) && suivi.get(m.noeud()).manque() > 0
                                        ? 0
                                        : demandes.containsKey(m.noeud()) ? 1 : 2));
        var lignesVues = new java.util.LinkedHashSet<List<Map.Entry<String, String>>>();
        for (var moule : moules) {
            var lignes =
                    expliquer(
                            moule,
                            stock,
                            coffresMoules,
                            reseau,
                            releves,
                            memoirePrete,
                            sacs,
                            sacsInclus,
                            suivi.get(moule.noeud()),
                            recette.get(moule.noeud()));
            if (!lignesVues.add(lignes)) continue;
            if (lignesVues.size() > MAX_MOULES) {
                donnees.add(
                        Map.entry(
                                "Autres demandes",
                                "Affichage limité à " + MAX_MOULES + " demandes."));
                break;
            }
            donnees.addAll(lignes);
        }
        return List.copyOf(donnees);
    }

    private static IdentityHashMap<PlanMachines.Noeud<EmiStack, EmiRecipe>, PlanMachines.EtatBesoin>
            observer(
                    InstantaneArbreEmi vue,
                    List<PlanRetrait.Stock<EmiStack>> stock,
                    List<Moule> moules) {
        var noeuds =
                new IdentityHashMap<
                        PlanMachines.Noeud<EmiStack, EmiRecipe>, PlanMachines.EtatBesoin>();
        for (var moule : moules) noeuds.put(moule.noeud(), null);
        PlanMachines.observer(
                vue.machines(),
                vue.lots(),
                stock,
                (noeud, etat) -> {
                    if (noeuds.containsKey(noeud)) noeuds.put(noeud, etat);
                });
        noeuds.values().removeIf(java.util.Objects::isNull);
        return noeuds;
    }

    private static void chercher(
            ArbreCollecte.Noeud collecte,
            PlanMachines.Noeud<EmiStack, EmiRecipe> noeud,
            EmiRecipe usage,
            List<Moule> moules) {
        var ids =
                collecte.variantes().stream()
                        .map(fr.assistantia.RessourceCollecte::idObjet)
                        .collect(java.util.stream.Collectors.toSet());
        if (ids.stream().anyMatch(MoulesDisponibles::estMoule))
            moules.add(new Moule(noeud, ids, usage));
        for (int i = 0; i < collecte.enfants().size(); i++)
            chercher(collecte.enfants().get(i), noeud.enfants().get(i), noeud.recette(), moules);
    }

    private static List<Map.Entry<String, String>> expliquer(
            Moule moule,
            StockFabricationEmi stock,
            List<PlanRetrait.Stock<EmiStack>> coffres,
            List<PlanRetrait.Stock<EmiStack>> reseau,
            Map<String, Long> releves,
            boolean memoirePrete,
            List<ItemStack> sacs,
            boolean sacsInclus,
            PlanMachines.EtatBesoin suivi,
            PlanMachines.EtatBesoin recette) {
        var accepte = moule.noeud().accepte();
        var variantes = String.join(" / ", moule.ids().stream().sorted().toList());
        String requis =
                moule.usage() != null && moule.usage().getId() != null
                        ? " · requis par " + moule.usage().getId()
                        : "";
        long personnel = compter(moule, stock.personnel());
        long sac =
                sacsInclus
                        ? sacs.stream()
                                .filter(item -> !item.isEmpty() && accepte.test(EmiStack.of(item)))
                                .mapToLong(ItemStack::getCount)
                                .sum()
                        : 0;
        long tampons = compter(moule, stock.tampon());
        long machines = compter(moule, stock.outils());
        long virtuels = compter(moule, stock.credits()) + compter(moule, stock.attendus());
        long compatibles = personnel + tampons + machines + virtuels;
        long proches = compter(moule, coffres);
        long enMe = compter(moule, reseau);
        long observes = moule.ids().stream().mapToLong(id -> releves.getOrDefault(id, 0L)).sum();
        String memoire =
                memoirePrete
                        ? observes
                                + " observé(s) par ID · "
                                + proches
                                + " disponible(s) pour le calcul de Recette"
                        : "relevé indisponible";
        List<Map.Entry<String, String>> lignes = new ArrayList<>();
        lignes.add(Map.entry("Moule recherché", variantes + requis));
        lignes.add(
                Map.entry(
                        "Stocks compatibles",
                        "Poches / curseur : "
                                + Math.max(0, personnel - sac)
                                + " · Backpack : "
                                + (sacsInclus ? Long.toString(sac) : "désactivé")
                                + " · Tampons relevés : "
                                + tampons
                                + " · Machines reconnues : "
                                + machines
                                + " · Crédits et lots attendus : "
                                + virtuels));
        lignes.add(Map.entry("Coffres à portée", memoire));
        lignes.add(Map.entry("Réseau ME", enMe + " dans le dernier relevé du terminal"));
        var autres = new java.util.TreeMap<String, Long>();
        for (var source : List.of(stock.personnel(), stock.tampon()))
            for (var pile : source) {
                String id = pile.objet().getId().toString();
                if (MoulesDisponibles.estMoule(id) && !accepte.test(pile.objet()))
                    autres.merge(id, (long) pile.quantite(), Long::sum);
            }
        if (!autres.isEmpty())
            lignes.add(
                    Map.entry(
                            "Moules non compatibles",
                            String.join(
                                    " · ",
                                    autres.entrySet().stream()
                                            .limit(4)
                                            .map(e -> e.getKey() + " ×" + e.getValue())
                                            .toList())));
        String motifSuivi = decision(moule, suivi, compatibles);
        if (suivi != null && suivi.manque() > 0 && (proches > 0 || enMe > 0))
            motifSuivi =
                    enMe > 0
                            ? "Moule à récupérer dans le réseau ME."
                            : "Moule à récupérer dans un coffre.";
        lignes.add(Map.entry("Envoyer", motifSuivi));
        lignes.add(
                Map.entry(
                        "Recette",
                        decision(moule, recette, compatibles + Math.max(proches, enMe))));
        return List.copyOf(lignes);
    }

    private static long compter(Moule moule, List<PlanRetrait.Stock<EmiStack>> stock) {
        return stock.stream()
                .filter(s -> moule.noeud().accepte().test(s.objet()))
                .mapToLong(PlanRetrait.Stock::quantite)
                .sum();
    }

    private static String decision(Moule moule, PlanMachines.EtatBesoin etat, long compatibles) {
        if (etat == null) return "Branche déjà couverte en amont.";
        String bilan = "Compté : " + etat.stock() + " sur " + etat.demande() + " · ";
        if (etat.manque() == 0) {
            return bilan
                    + (etat.stock() == etat.demande()
                            ? "besoin couvert."
                            : "une fabrication déjà prévue couvre le reste.");
        }
        if (etat.fabrications() == 0)
            return bilan + "manque " + etat.manque() + " · aucune fabrication choisie.";
        var id = moule.noeud().recette().getId();
        return bilan
                + "manque "
                + etat.manque()
                + " · fabrication prévue ×"
                + etat.fabrications()
                + (id == null ? "" : " (" + id + ")")
                + (compatibles > etat.stock() ? " · stock partagé avec un autre besoin." : "");
    }
}
