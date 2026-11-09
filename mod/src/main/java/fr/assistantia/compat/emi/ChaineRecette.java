package fr.assistantia.compat.emi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coût d'une sous-chaîne de fabrication, sans construire d'arbre EMI. Chaque ingrédient suit le
 * choix que ferait l'arbre (résolution locale, puis défaut EMI) et une recette déjà présente sur le
 * chemin devient une feuille, comme dans {@code MaterialNode.recalculate}. Les quantités sont
 * rapportées à une unité du produit ; les fluides restent en mB. Calcul pur, borné et mémorisé pour
 * une même révision de l'arbre.
 */
public final class ChaineRecette<K, R> {
    /** Ingrédients développés au plus par évaluation, au-delà la chaîne est déclarée incomplète. */
    public static final int MAX_NOEUDS = 256;

    public static final int MAX_PROFONDEUR = 24;
    public static final int MAX_MEMOIRE = 4096;

    /** Préfixe des clés de matière exprimées en mB. */
    public static final String FLUIDE = "fluide:";

    public interface Source<K, R> {
        /** Recette retenue par l'arbre pour cet ingrédient, ou null pour une matière de base. */
        R choix(K ingredient);

        /** Lecture de la recette pour ce produit, ou null si elle est illisible. */
        Recette<K> lire(R recette, K produit);

        /** Clé stable d'une matière ; {@link #FLUIDE} en tête pour une quantité en mB. */
        String cle(K ingredient);
    }

    public record Entree<K>(K ingredient, double quantite, boolean reutilisable) {}

    /**
     * Une fabrication : production du produit par fabrication, poste exigé, énergie et durée par
     * fabrication. Une recette transparente choisit seulement une variante (pas d'étape).
     */
    public record Recette<K>(
            List<Entree<K>> entrees,
            double production,
            Object poste,
            double eu,
            double ticks,
            boolean transparente) {
        public Recette {
            entrees = List.copyOf(entrees);
        }
    }

    /** Résultat par unité de produit. */
    public record Chaine(
            Map<String, Double> base,
            Set<String> outils,
            Set<Object> postes,
            int etapes,
            double eu,
            double ticks,
            boolean complete) {
        public Chaine {
            base = Map.copyOf(base);
            outils = Set.copyOf(outils);
            postes = Set.copyOf(postes);
        }

        /** Matières de base totales : objets, plus les fluides comptés par 1 000 mB. */
        public double total() {
            double total = 0;
            for (var e : base.entrySet())
                total += e.getKey().startsWith(FLUIDE) ? e.getValue() / 1000.0 : e.getValue();
            return total;
        }
    }

    private static final int SANS_COUPURE = Integer.MAX_VALUE;

    private static final class Cumul {
        final Map<String, Double> base = new HashMap<>();
        final Set<String> outils = new HashSet<>();
        final Set<Object> postes = new HashSet<>();
        final Set<Object> recettes = new HashSet<>();
        double eu, ticks;
        int coupure = SANS_COUPURE;
        boolean tronque;

        void ajouter(Cumul sous, double facteur) {
            for (var e : sous.base.entrySet())
                base.merge(e.getKey(), e.getValue() * facteur, Double::sum);
            outils.addAll(sous.outils);
            postes.addAll(sous.postes);
            recettes.addAll(sous.recettes);
            eu += sous.eu * facteur;
            ticks += sous.ticks * facteur;
            coupure = Math.min(coupure, sous.coupure);
            tronque |= sous.tronque;
        }
    }

    private record Lecture<R, K>(R recette, K produit) {}

    private final Source<K, R> source;
    private final Map<K, Cumul> memoire = new HashMap<>();
    private final Map<Lecture<R, K>, Recette<K>> lectures = new HashMap<>();
    private final List<R> chemin = new ArrayList<>();
    private final Map<R, Integer> positions = new HashMap<>();
    private K racine;
    private int noeuds;
    private long expansions;

    public ChaineRecette(Source<K, R> source) {
        this.source = source;
    }

    /**
     * Chaîne d'une unité de {@code produit} fabriquée par {@code recette}, comme si cette recette
     * était choisie pour ce produit : une réapparition du produit plus bas devient une feuille.
     *
     * @return null si la recette est illisible ou ne produit pas ce produit
     */
    public Chaine evaluer(R recette, K produit) {
        chemin.clear();
        positions.clear();
        noeuds = 0;
        racine = produit;
        try {
            var cumul = recette(recette, produit, 0);
            if (cumul == null) return null;
            return new Chaine(
                    cumul.base,
                    cumul.outils,
                    cumul.postes,
                    cumul.recettes.size(),
                    cumul.eu,
                    cumul.ticks,
                    !cumul.tronque);
        } finally {
            racine = null;
            chemin.clear();
            positions.clear();
        }
    }

    public int memorises() {
        return memoire.size();
    }

    /** Ingrédients développés depuis la création : permet de borner le travail par tick. */
    public long expansions() {
        return expansions;
    }

    private Recette<K> lire(R recette, K produit) {
        var cle = new Lecture<>(recette, produit);
        if (lectures.containsKey(cle)) return lectures.get(cle);
        Recette<K> lue;
        try {
            lue = source.lire(recette, produit);
        } catch (RuntimeException illisible) {
            lue = null;
        }
        if (lectures.size() < MAX_MEMOIRE) lectures.put(cle, lue);
        return lue;
    }

    private Cumul recette(R recette, K produit, int profondeur) {
        var lue = lire(recette, produit);
        if (lue == null || !(lue.production() > 0)) return null;
        positions.put(recette, chemin.size());
        chemin.add(recette);
        try {
            var cumul = new Cumul();
            if (!lue.transparente()) {
                cumul.recettes.add(recette);
                if (lue.poste() != null) cumul.postes.add(lue.poste());
                cumul.eu += lue.eu() / lue.production();
                cumul.ticks += lue.ticks() / lue.production();
            }
            for (var entree : lue.entrees()) {
                if (!(entree.quantite() > 0)) continue;
                if (entree.reutilisable()) {
                    // Un outil sert à toutes les fabrications : jamais un coût par unité.
                    cumul.outils.add(source.cle(entree.ingredient()));
                    continue;
                }
                cumul.ajouter(
                        ingredient(entree.ingredient(), profondeur + 1),
                        entree.quantite() / lue.production());
            }
            return cumul;
        } finally {
            chemin.remove(chemin.size() - 1);
            positions.remove(recette);
        }
    }

    private Cumul feuille(K ingredient) {
        var cumul = new Cumul();
        cumul.base.put(source.cle(ingredient), 1.0);
        return cumul;
    }

    private Cumul ingredient(K ingredient, int profondeur) {
        if (racine != null && racine.equals(ingredient)) {
            // Après application, le produit reprend la recette évaluée : déjà sur le chemin.
            var cycle = feuille(ingredient);
            cycle.coupure = 0;
            return cycle;
        }
        var connu = memoire.get(ingredient);
        if (connu != null) return connu;
        expansions++;
        if (++noeuds > MAX_NOEUDS || profondeur > MAX_PROFONDEUR) {
            var limite = feuille(ingredient);
            limite.tronque = true;
            return limite;
        }
        R choix;
        try {
            choix = source.choix(ingredient);
        } catch (RuntimeException illisible) {
            choix = null;
        }
        if (choix == null) {
            var base = feuille(ingredient);
            memoriser(ingredient, base);
            return base;
        }
        Integer position = positions.get(choix);
        if (position != null) {
            var cycle = feuille(ingredient);
            cycle.coupure = position;
            return cycle;
        }
        int ici = chemin.size();
        var cumul = recette(choix, ingredient, profondeur);
        if (cumul == null) {
            var base = feuille(ingredient);
            memoriser(ingredient, base);
            return base;
        }
        // Une coupure sur la recette de cet ingrédient ou plus bas ne dépend plus du chemin.
        if (cumul.coupure >= ici) cumul.coupure = SANS_COUPURE;
        if (cumul.coupure == SANS_COUPURE && !cumul.tronque) memoriser(ingredient, cumul);
        return cumul;
    }

    private void memoriser(K ingredient, Cumul cumul) {
        if (memoire.size() < MAX_MEMOIRE) memoire.put(ingredient, cumul);
    }
}
