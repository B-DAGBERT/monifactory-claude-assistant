package fr.assistantia.compat.emi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nœuds de l'arbre dont on cherche une meilleure recette : un par ingrédient (la clé de résolution
 * d'EMI, partagée par tous ses nœuds), la racine comprise. Les outils, les choix de variante d'un
 * tag, la racine synthétique d'un objectif et les ingrédients à plusieurs variantes sont exclus.
 * Calcul pur.
 */
public final class SelectionAlternatives {
    private SelectionAlternatives() {}

    /** Ce que l'analyse sait d'un nœud lu dans l'arbre, dans l'ordre de lecture. */
    public record Noeud<K>(
            K cle,
            int profondeur,
            boolean resolu,
            boolean reutilisable,
            boolean transparente,
            boolean assistant,
            int variantes,
            double besoin) {}

    /** Un nœud retenu : index de sa première lecture, besoin cumulé et racine de l'arbre. */
    public record Choisi(int index, double besoin, boolean racine) {}

    public static <K> List<Choisi> choisir(List<Noeud<K>> noeuds, int max) {
        Map<K, double[]> besoins = new LinkedHashMap<>();
        Map<K, Integer> premiers = new LinkedHashMap<>();
        for (int i = 0; i < noeuds.size(); i++) {
            var n = noeuds.get(i);
            if (!n.resolu() || n.reutilisable() || n.transparente() || n.assistant()) continue;
            if (n.variantes() != 1) continue;
            var connu = besoins.get(n.cle());
            if (connu != null) {
                connu[0] += n.besoin();
                continue;
            }
            if (premiers.size() >= max) break;
            besoins.put(n.cle(), new double[] {n.besoin()});
            premiers.put(n.cle(), i);
        }
        List<Choisi> choisis = new ArrayList<>();
        for (var e : premiers.entrySet()) {
            int index = e.getValue();
            choisis.add(
                    new Choisi(
                            index,
                            besoins.get(e.getKey())[0],
                            noeuds.get(index).profondeur() == 0));
        }
        return List.copyOf(choisis);
    }
}
