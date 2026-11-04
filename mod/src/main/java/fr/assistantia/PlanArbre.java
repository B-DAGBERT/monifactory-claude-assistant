package fr.assistantia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Réserve les composants avant de descendre vers leurs matières premières. */
public final class PlanArbre {
    public record Resultat(
            List<PlanRetrait.Prise> prises, List<MemoireCollecte.Ingredient> manquants) {}

    private record Demande(ArbreCollecte.Noeud noeud, int quantite) {}

    public static <T> Resultat calculer(
            ArbreCollecte.Noeud racine,
            long lots,
            List<PlanRetrait.Stock<T>> stocks,
            Function<Set<String>, Predicate<T>> accepte) {
        List<PlanRetrait.Stock<T>> reserves = new ArrayList<>(stocks);
        int[] retraits = new int[stocks.size()];
        List<MemoireCollecte.Ingredient> manquants = new ArrayList<>();
        Map<Set<String>, Integer> outils = new LinkedHashMap<>();
        List<Demande> niveau =
                List.of(new Demande(racine, multiplier(racine.quantite(), Math.max(1, lots))));
        int profondeur = 0, visites = 0;
        while (!niveau.isEmpty()) {
            if (++profondeur > 129 || (visites += niveau.size()) > 4096)
                throw new IllegalArgumentException("Arbre trop grand");
            List<PlanRetrait.Besoin<T>> besoins = new ArrayList<>();
            List<Integer> demandes = new ArrayList<>();
            for (Demande demande : niveau) {
                var node = demande.noeud();
                int quantite = demande.quantite();
                if (node.reutilisable() && !node.variantes().isEmpty()) {
                    int deja = outils.getOrDefault(node.variantes(), 0);
                    outils.merge(node.variantes(), quantite, Math::max);
                    quantite = Math.max(0, quantite - deja);
                }
                demandes.add(quantite);
                besoins.add(
                        new PlanRetrait.Besoin<>(
                                accepte.apply(node.variantes()),
                                node.variantes().isEmpty() ? 0 : quantite));
            }
            var allocation = PlanRetrait.calculer(reserves, besoins);
            for (int i = 0; i < reserves.size(); i++) {
                var stock = reserves.get(i);
                int utilise = allocation.utilises().get(i);
                if (!stock.inventaire()) retraits[i] += utilise;
                reserves.set(
                        i,
                        new PlanRetrait.Stock<>(
                                stock.objet(), stock.quantite() - utilise, stock.inventaire()));
            }
            List<Demande> suite = new ArrayList<>();
            for (int i = 0; i < niveau.size(); i++) {
                var node = niveau.get(i).noeud();
                int manque = demandes.get(i) - allocation.fournis().get(i);
                if (manque <= 0) continue; // Le composant couvre toute sa branche.
                if (!node.variantes().isEmpty())
                    manquants.add(new MemoireCollecte.Ingredient(node.variantes(), manque));
                long production = Math.max(1, node.production());
                long fabrications = manque / production + (manque % production == 0 ? 0 : 1);
                for (var enfant : node.enfants())
                    suite.add(
                            new Demande(
                                    enfant,
                                    multiplier(
                                            enfant.quantite(),
                                            enfant.reutilisable() ? 1 : fabrications)));
            }
            niveau = suite;
        }
        List<PlanRetrait.Prise> prises = new ArrayList<>();
        for (int i = 0; i < retraits.length; i++)
            if (retraits[i] > 0) prises.add(new PlanRetrait.Prise(i, retraits[i]));
        return new Resultat(List.copyOf(prises), List.copyOf(manquants));
    }

    private static int multiplier(long a, long b) {
        return (int)
                Math.min(
                        1_000_000L,
                        Math.min(1_000_000L, Math.max(0, a))
                                * Math.min(1_000_000L, Math.max(0, b)));
    }
}
