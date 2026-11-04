package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Instantané indépendant d'EMI : toutes les branches choisies, y compris les intermédiaires. */
public final class ArbreCollecte {
    public record Noeud(
            Set<String> variantes,
            long quantite,
            long production,
            boolean reutilisable,
            List<Noeud> enfants) {
        public Noeud {
            variantes = Set.copyOf(variantes);
            enfants = List.copyOf(enfants);
        }
    }

    private static final long MAX = 1_000_000;

    public static List<MemoireCollecte.Ingredient> ingredients(Noeud racine, long lots) {
        List<MemoireCollecte.Ingredient> result = new ArrayList<>();
        Map<Set<String>, Integer> outils = new LinkedHashMap<>();
        visiter(racine, multiplier(Math.max(1, lots), racine.quantite()), result, outils, 0);
        outils.forEach((ids, n) -> result.add(new MemoireCollecte.Ingredient(ids, n)));
        return List.copyOf(result);
    }

    private static void visiter(
            Noeud node,
            long quantite,
            List<MemoireCollecte.Ingredient> result,
            Map<Set<String>, Integer> outils,
            int depth) {
        if (depth > 128 || result.size() > 4096)
            throw new IllegalArgumentException("Arbre trop grand");
        if (!node.variantes().isEmpty() && quantite > 0) {
            if (node.reutilisable())
                outils.merge(node.variantes(), (int) Math.min(MAX, quantite), Math::max);
            else
                result.add(
                        new MemoireCollecte.Ingredient(
                                node.variantes(), (int) Math.min(MAX, quantite)));
        }
        long divisor = Math.max(1, node.production());
        long lots = quantite / divisor + (quantite % divisor == 0 ? 0 : 1);
        for (Noeud enfant : node.enfants())
            visiter(
                    enfant,
                    multiplier(enfant.reutilisable() ? 1 : lots, enfant.quantite()),
                    result,
                    outils,
                    depth + 1);
    }

    private static long multiplier(long a, long b) {
        return Math.min(MAX, Math.min(MAX, Math.max(0, a)) * Math.min(MAX, Math.max(0, b)));
    }
}
