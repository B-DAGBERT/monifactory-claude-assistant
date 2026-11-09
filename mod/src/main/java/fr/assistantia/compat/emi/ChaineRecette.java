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
 * chemin devient une feuille, comme dans {@code MaterialNode.recalculate}. Un ingrédient que
 * l'arbre ne fabrique pas et qui n'en est pas une matière de base (nouvelle matière d'une
 * alternative) est fabriqué par la recette que propose {@link Source#producteur} : ses étapes et
 * ses matières comptent. Les quantités sont rapportées à une unité du produit ; les fluides restent
 * en mB. Calcul pur, borné et mémorisé pour une même révision de l'arbre.
 */
public final class ChaineRecette<K, R> {
    /** Ingrédients développés au plus par évaluation, au-delà la chaîne est déclarée incomplète. */
    public static final int MAX_NOEUDS = 256;

    /** Nouvelles matières fabriquées au plus par évaluation, au-delà la chaîne est incomplète. */
    public static final int MAX_ESTIMEES = 48;

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

        /**
         * Recette qui fabriquerait un ingrédient sans choix dans l'arbre, ou null s'il reste une
         * matière de base (matière de base de l'arbre, matière brute, fluide…). Doit être stable
         * pendant toute la vie du calcul : le résultat est mémorisé.
         */
        default R producteur(K ingredient) {
            return null;
        }
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

    /**
     * Résultat par unité de produit. {@code circulaire} : le produit réapparaît parmi ses propres
     * ingrédients (par exemple casser une batterie pour récupérer la coque qui sert à la faire).
     */
    public record Chaine(
            Map<String, Double> base,
            Set<String> outils,
            Set<Object> postes,
            int etapes,
            double eu,
            double ticks,
            boolean complete,
            boolean circulaire) {
        public Chaine {
            base = Map.copyOf(base);
            outils = Set.copyOf(outils);
            postes = Set.copyOf(postes);
        }

        public Chaine(
                Map<String, Double> base,
                Set<String> outils,
                Set<Object> postes,
                int etapes,
                double eu,
                double ticks,
                boolean complete) {
            this(base, outils, postes, etapes, eu, ticks, complete, false);
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
        boolean tronque, circulaire;

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
            circulaire |= sous.circulaire;
        }
    }

    private record Lecture<R, K>(R recette, K produit) {}

    private static final Object SANS_PRODUCTEUR = new Object();

    private final Source<K, R> source;
    private final Map<K, Cumul> memoire = new HashMap<>();
    private final Map<Lecture<R, K>, Recette<K>> lectures = new HashMap<>();
    private final Map<K, Object> producteurs = new HashMap<>();
    private final List<R> chemin = new ArrayList<>();
    private final Map<R, Integer> positions = new HashMap<>();
    private K racine;
    private String cleRacine;
    private int noeuds, estimees;
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
        noeuds = estimees = 0;
        racine = produit;
        try {
            cleRacine = source.cle(produit);
        } catch (RuntimeException illisible) {
            cleRacine = null;
        }
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
                    !cumul.tronque,
                    cumul.circulaire);
        } finally {
            racine = null;
            cleRacine = null;
            chemin.clear();
            positions.clear();
        }
    }

    /** Le produit évalué, sous la même forme ou sous une autre (tag dont il est la variante). */
    private boolean estRacine(K ingredient) {
        if (racine == null) return false;
        if (racine.equals(ingredient)) return true;
        try {
            return cleRacine != null && cleRacine.equals(source.cle(ingredient));
        } catch (RuntimeException illisible) {
            return false;
        }
    }

    /** Recette d'une nouvelle matière, demandée une seule fois par matière. */
    @SuppressWarnings("unchecked")
    private R producteur(K ingredient) {
        Object connu = producteurs.get(ingredient);
        if (connu == null) {
            R lu;
            try {
                lu = source.producteur(ingredient);
            } catch (RuntimeException illisible) {
                lu = null;
            }
            connu = lu == null ? SANS_PRODUCTEUR : lu;
            if (producteurs.size() < MAX_MEMOIRE) producteurs.put(ingredient, connu);
        }
        return connu == SANS_PRODUCTEUR ? null : (R) connu;
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
        if (estRacine(ingredient)) {
            // Après application, le produit reprend la recette évaluée : déjà sur le chemin. Une
            // chaîne qui consomme son propre produit pour le refaire est circulaire.
            var cycle = feuille(ingredient);
            cycle.coupure = 0;
            cycle.circulaire = true;
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
        // Une matière que l'arbre ne fabrique pas encore se fabrique aussi : ses étapes comptent.
        if (choix == null
                && (choix = producteur(ingredient)) != null
                && ++estimees > MAX_ESTIMEES) {
            var limite = feuille(ingredient);
            limite.tronque = true;
            return limite;
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
