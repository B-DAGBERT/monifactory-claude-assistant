package fr.assistantia.compat.emi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Définition prudente de « facile à fabriquer » : une recette d'établi dont tous les ingrédients
 * sont déjà possédés (inventaire, sac, coffres connus), ou au plus un sous-craft d'établi par
 * ingrédient manquant, lui-même entièrement couvert par le stock. Calcul pur et borné.
 */
public final class FabricationFacile {
    static final int MAX_RECETTES = 6, MAX_SOUS_RECETTES = 4, MAX_VARIANTES = 4;

    private FabricationFacile() {}

    /** Un ingrédient : objets acceptés (identifiants), quantité, outil non consommé. */
    public record Besoin(List<String> variantes, long quantite, boolean outil) {
        public Besoin {
            variantes = List.copyOf(variantes);
        }
    }

    public record Recette(List<Besoin> entrees, long production) {
        public Recette {
            entrees = List.copyOf(entrees);
        }
    }

    /** Recettes d'établi produisant un objet. */
    public interface Recettes {
        List<Recette> pour(String objet);
    }

    /** {@code sousCrafts} : fabrications d'établi intermédiaires nécessaires (0 = tu as tout). */
    public record Resultat(boolean facile, int sousCrafts, String manque) {}

    public static Resultat evaluer(
            String objet, Recettes recettes, Map<String, Long> stock, boolean sousCraft) {
        String manque = "";
        var liste = recettes.pour(objet);
        for (int i = 0; i < liste.size() && i < MAX_RECETTES; i++) {
            var essai = essayer(liste.get(i), recettes, new HashMap<>(stock), sousCraft);
            if (essai.facile()) return essai;
            if (manque.isEmpty()) manque = essai.manque();
        }
        return new Resultat(false, 0, manque);
    }

    private static Resultat essayer(
            Recette recette, Recettes recettes, Map<String, Long> stock, boolean sousCraft) {
        if (recette.production() <= 0 || recette.entrees().isEmpty())
            return new Resultat(false, 0, "");
        int sous = 0;
        for (var besoin : recette.entrees()) {
            long reste = prendre(besoin, stock);
            if (reste <= 0) continue;
            if (!sousCraft) return new Resultat(false, 0, premier(besoin));
            int faits = sousCraft(besoin, reste, recettes, stock);
            if (faits <= 0) return new Resultat(false, 0, premier(besoin));
            sous += faits;
        }
        return new Resultat(true, sous, "");
    }

    /** Prélève le besoin dans le stock ; un outil possédé n'est pas consommé. Retourne le reste. */
    private static long prendre(Besoin besoin, Map<String, Long> stock) {
        if (besoin.outil()) {
            for (String v : besoin.variantes()) if (stock.getOrDefault(v, 0L) > 0) return 0;
            return 1;
        }
        long reste = Math.max(0, besoin.quantite());
        for (String v : besoin.variantes()) {
            if (reste <= 0) break;
            long dispo = stock.getOrDefault(v, 0L);
            long pris = Math.min(dispo, reste);
            if (pris > 0) {
                stock.put(v, dispo - pris);
                reste -= pris;
            }
        }
        return reste;
    }

    /** Un seul niveau : la sous-recette doit être entièrement couverte par le stock restant. */
    private static int sousCraft(
            Besoin besoin, long reste, Recettes recettes, Map<String, Long> stock) {
        for (int v = 0; v < besoin.variantes().size() && v < MAX_VARIANTES; v++) {
            var sousRecettes = recettes.pour(besoin.variantes().get(v));
            for (int i = 0; i < sousRecettes.size() && i < MAX_SOUS_RECETTES; i++) {
                var sous = sousRecettes.get(i);
                if (sous.production() <= 0) continue;
                long fois = (reste + sous.production() - 1) / sous.production();
                var copie = new HashMap<>(stock);
                boolean ok = true;
                for (var entree : sous.entrees()) {
                    var multiplie =
                            new Besoin(
                                    entree.variantes(),
                                    entree.outil() ? 1 : entree.quantite() * fois,
                                    entree.outil());
                    if (prendre(multiplie, copie) > 0) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                stock.clear();
                stock.putAll(copie);
                // Le surplus de la sous-recette reste disponible pour les ingrédients suivants.
                long surplus = fois * sous.production() - reste;
                if (surplus > 0 && !besoin.outil())
                    stock.merge(besoin.variantes().get(v), surplus, Long::sum);
                return (int) Math.min(Integer.MAX_VALUE, fois);
            }
        }
        return 0;
    }

    private static String premier(Besoin besoin) {
        return besoin.variantes().isEmpty() ? "" : besoin.variantes().get(0);
    }
}
