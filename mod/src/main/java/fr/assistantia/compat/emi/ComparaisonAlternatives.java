package fr.assistantia.compat.emi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Règles de comparaison de deux sous-chaînes d'un même produit et texte du gain en français. Une
 * alternative n'est retenue que si elle est vraiment meilleure : jamais plus de matière, et moins
 * de matière ou moins d'étapes.
 */
public final class ComparaisonAlternatives {
    static final double EPS = 1e-6;

    private ComparaisonAlternatives() {}

    /** Classement : matières de base, puis étapes, énergie et durée par unité produite. */
    public static final Comparator<ChaineRecette.Chaine> ORDRE =
            Comparator.comparingDouble(ChaineRecette.Chaine::total)
                    .thenComparingInt(ChaineRecette.Chaine::etapes)
                    .thenComparingDouble(ChaineRecette.Chaine::eu)
                    .thenComparingDouble(ChaineRecette.Chaine::ticks);

    /** Chaque matière de {@code a} est en quantité au plus égale dans {@code b}. */
    public static boolean auPlus(Map<String, Double> a, Map<String, Double> b) {
        for (var e : a.entrySet())
            if (e.getValue() > b.getOrDefault(e.getKey(), 0.0) + EPS) return false;
        return true;
    }

    /** {@code a} demande au plus autant que {@code b} partout et strictement moins quelque part. */
    public static boolean domine(Map<String, Double> a, Map<String, Double> b) {
        if (!auPlus(a, b)) return false;
        for (var e : b.entrySet())
            if (a.getOrDefault(e.getKey(), 0.0) < e.getValue() - EPS) return true;
        return false;
    }

    /**
     * Strictement meilleure : moins de matière sans ajouter plus d'une étape, même matière (ou
     * moins) avec moins d'étapes, ou au moins deux fois moins d'étapes sans plus de matière au
     * total. Une chaîne incomplète ou vide n'est jamais comparable.
     */
    public static boolean meilleure(ChaineRecette.Chaine alt, ChaineRecette.Chaine cur) {
        if (alt == null || cur == null || !alt.complete() || !cur.complete()) return false;
        if (alt.base().isEmpty()) return false;
        if (domine(alt.base(), cur.base()) && alt.etapes() <= cur.etapes() + 1) return true;
        if (auPlus(alt.base(), cur.base()) && alt.etapes() < cur.etapes()) return true;
        return cur.etapes() >= 4
                && alt.etapes() * 2 <= cur.etapes()
                && alt.total() <= cur.total() + EPS;
    }

    /**
     * Les outils (moules, lentilles, formes d'extrudeuse) que l'alternative ajoute à la recette
     * actuelle doivent déjà être possédés. Sans relevé complet des possessions, une alternative à
     * outil nouveau n'est jamais retenue.
     */
    public static boolean outilsPossedes(
            ChaineRecette.Chaine alt,
            ChaineRecette.Chaine cur,
            java.util.function.Predicate<String> possede,
            boolean complet) {
        for (String outil : alt.outils()) {
            if (cur.outils().contains(outil)) continue;
            if (!complet || !possede.test(outil)) return false;
        }
        return true;
    }

    /** Économie de matière pour toute la quantité nécessaire, puis étapes économisées. */
    public static double economie(
            ChaineRecette.Chaine alt, ChaineRecette.Chaine cur, double quantite) {
        return Math.max(0, cur.total() - alt.total()) * Math.max(1, quantite);
    }

    /**
     * « 1 Iron Ingot au lieu de 2 par Iron Plate · 2 étapes au lieu de 12 ». Les noms viennent de
     * {@code nom} (clé de matière vers nom affiché).
     */
    public static String gain(
            ChaineRecette.Chaine alt,
            ChaineRecette.Chaine cur,
            Function<String, String> nom,
            String produit) {
        List<String> parties = new ArrayList<>();
        String cle = domine(alt.base(), cur.base()) ? principale(alt.base(), cur.base()) : null;
        if (cle != null) {
            double a = alt.base().getOrDefault(cle, 0.0), c = cur.base().getOrDefault(cle, 0.0);
            String unite = cle.startsWith(ChaineRecette.FLUIDE) ? " mB " : " ";
            int autres = changees(alt.base(), cur.base()) - 1;
            parties.add(
                    nombre(a)
                            + unite
                            + nom.apply(cle)
                            + " au lieu de "
                            + nombre(c)
                            + " par "
                            + produit
                            + (autres > 0
                                    ? " (+" + autres + " autre" + (autres > 1 ? "s" : "") + ")"
                                    : ""));
        } else if (!auPlus(alt.base(), cur.base())) {
            // Matières différentes : nommer la première matière nouvelle, sans fausse économie.
            String nouvelle =
                    alt.base().keySet().stream()
                            .filter(k -> alt.base().get(k) > cur.base().getOrDefault(k, 0.0) + EPS)
                            .sorted()
                            .findFirst()
                            .orElse(null);
            if (nouvelle != null) parties.add("avec " + nom.apply(nouvelle));
        }
        if (alt.etapes() < cur.etapes())
            parties.add(etapes(alt.etapes()) + " au lieu de " + cur.etapes());
        if (parties.isEmpty()) {
            if (alt.ticks() + EPS < cur.ticks()) parties.add("plus rapide");
            else if (alt.eu() + EPS < cur.eu()) parties.add("moins d’énergie");
            else parties.add("équivalente");
        }
        return String.join(" · ", parties);
    }

    /** La matière dont la quantité change le plus, ou null si rien ne change. */
    static String principale(Map<String, Double> alt, Map<String, Double> cur) {
        String meilleure = null;
        double ecart = EPS;
        Set<String> cles = new HashSet<>(alt.keySet());
        cles.addAll(cur.keySet());
        for (String cle : cles.stream().sorted().toList()) {
            double a = alt.getOrDefault(cle, 0.0), c = cur.getOrDefault(cle, 0.0);
            if (cle.startsWith(ChaineRecette.FLUIDE)) {
                a /= 1000;
                c /= 1000;
            }
            if (c - a > ecart) {
                ecart = c - a;
                meilleure = cle;
            }
        }
        if (meilleure != null) return meilleure;
        for (String cle : cles.stream().sorted().toList()) {
            double a = alt.getOrDefault(cle, 0.0), c = cur.getOrDefault(cle, 0.0);
            if (Math.abs(a - c) > EPS) return cle;
        }
        return null;
    }

    private static int changees(Map<String, Double> alt, Map<String, Double> cur) {
        Set<String> cles = new HashSet<>(alt.keySet());
        cles.addAll(cur.keySet());
        int n = 0;
        for (String cle : cles)
            if (Math.abs(alt.getOrDefault(cle, 0.0) - cur.getOrDefault(cle, 0.0)) > EPS) n++;
        return n;
    }

    public static String etapes(int n) {
        return n + (n > 1 ? " étapes" : " étape");
    }

    /** 2, 1,5, 0,33 : entier quand c'est possible, sinon deux décimales au plus. */
    public static String nombre(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        String texte = String.format(Locale.FRANCE, "%.2f", v);
        while (texte.endsWith("0")) texte = texte.substring(0, texte.length() - 1);
        return texte.endsWith(",") ? texte.substring(0, texte.length() - 1) : texte;
    }
}
