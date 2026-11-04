package fr.assistantia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Allocation exacte, indépendante de Minecraft. Les variantes partagent les mêmes réserves. */
public final class PlanRetrait {
    public record Stock<T>(T objet, int quantite, boolean inventaire) {}

    public record Besoin<T>(Predicate<T> accepte, int quantite) {}

    public record Prise(int index, int quantite) {}

    public record Resultat(List<Prise> prises, long manquants) {
        public int total() {
            return prises.stream().mapToInt(Prise::quantite).sum();
        }
    }

    private static final class Arc {
        final int vers;
        long capacite;
        Arc inverse;

        Arc(int vers, long capacite) {
            this.vers = vers;
            this.capacite = capacite;
        }
    }

    private static Arc lien(List<List<Arc>> graphe, int de, int vers, long capacite) {
        Arc a = new Arc(vers, capacite), b = new Arc(de, 0);
        a.inverse = b;
        b.inverse = a;
        graphe.get(de).add(a);
        graphe.get(vers).add(b);
        return a;
    }

    public static <T> Resultat calculer(List<Stock<T>> stocks, List<Besoin<T>> besoins) {
        int n = stocks.size(), m = besoins.size(), fin = n + m + 1;
        List<List<Arc>> graphe = new ArrayList<>();
        for (int i = 0; i <= fin; i++) graphe.add(new ArrayList<>());
        List<Arc> sources = new ArrayList<>();
        for (int i = 0; i < n; i++)
            sources.add(
                    lien(
                            graphe,
                            0,
                            i + 1,
                            stocks.get(i).inventaire()
                                    ? Math.max(0, stocks.get(i).quantite())
                                    : 0));
        long demande = 0;
        for (int j = 0; j < m; j++) {
            long q = Math.max(0, besoins.get(j).quantite());
            lien(graphe, n + 1 + j, fin, q);
            demande += q;
            for (int i = 0; i < n; i++)
                if (besoins.get(j).accepte().test(stocks.get(i).objet()))
                    lien(graphe, i + 1, n + 1 + j, q);
        }
        // Réserver d'abord l'inventaire, puis compléter depuis le coffre.
        // Les chemins résiduels réaffectent les variantes sans compter deux fois un objet.
        long fourni = augmenter(graphe, fin);
        for (int i = 0; i < n; i++)
            if (!stocks.get(i).inventaire())
                sources.get(i).capacite = Math.max(0, stocks.get(i).quantite());
        fourni += augmenter(graphe, fin);
        List<Prise> prises = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (!stocks.get(i).inventaire() && sources.get(i).inverse.capacite > 0)
                prises.add(new Prise(i, (int) sources.get(i).inverse.capacite));
        return new Resultat(List.copyOf(prises), demande - fourni);
    }

    private static long augmenter(List<List<Arc>> graphe, int fin) {
        long total = 0;
        while (true) {
            Arc[] parent = new Arc[graphe.size()];
            boolean[] visite = new boolean[graphe.size()];
            visite[0] = true;
            ArrayDeque<Integer> file = new ArrayDeque<>();
            file.add(0);
            while (!file.isEmpty() && !visite[fin]) {
                int u = file.remove();
                for (Arc a : graphe.get(u))
                    if (!visite[a.vers] && a.capacite > 0) {
                        visite[a.vers] = true;
                        parent[a.vers] = a;
                        file.add(a.vers);
                    }
            }
            if (!visite[fin]) return total;
            long q = Long.MAX_VALUE;
            for (int v = fin; v != 0; v = parent[v].inverse.vers)
                q = Math.min(q, parent[v].capacite);
            for (int v = fin; v != 0; v = parent[v].inverse.vers) {
                parent[v].capacite -= q;
                parent[v].inverse.capacite += q;
            }
            total += q;
        }
    }
}
