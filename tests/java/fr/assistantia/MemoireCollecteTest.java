package fr.assistantia;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemoireCollecteTest {
    private static MemoireCollecte.Ingredient besoin(int n, String... ids) {
        return new MemoireCollecte.Ingredient(Set.of(ids), n);
    }

    public static void main(String[] args) {
        var liste = List.of(besoin(4, "iron"), besoin(2, "stick"));
        var suivi = new MemoireCollecte(liste);
        assert suivi.actualiser(Map.of()).equals(Set.of("iron", "stick"));
        assert suivi.actualiser(Map.of("iron", 3)).equals(Set.of("iron", "stick"));
        assert suivi.actualiser(Map.of("iron", 4)).equals(Set.of("stick"));
        assert suivi.actualiser(Map.of()).equals(Set.of("stick"))
                : "ne pas rallumer après rangement";
        assert suivi.actualiser(Map.of("stick", 2)).isEmpty();
        assert suivi.actualiser(Map.of()).isEmpty() : "ne pas rallumer après craft ou perte";
        assert new MemoireCollecte(liste).actualiser(Map.of()).equals(Set.of("iron", "stick"))
                : "nouvelle recherche indépendante";
        assert new MemoireCollecte(liste).actualiser(Map.of("iron", 4, "stick", 2)).isEmpty()
                : "objets déjà présents au départ";
        suivi = new MemoireCollecte(List.of(besoin(2, "oak", "birch"), besoin(2, "birch", "oak")));
        assert suivi.actualiser(Map.of("oak", 3)).equals(Set.of("oak", "birch"))
                : "addition des cases répétées";
        assert suivi.actualiser(Map.of("oak", 3, "birch", 1)).isEmpty()
                : "équivalents de la recette";
        assert suivi.actualiser(Map.of()).isEmpty();
        suivi = new MemoireCollecte(List.of(besoin(1, "oak", "birch"), besoin(1, "oak")));
        assert !suivi.actualiser(Map.of("oak", 1)).isEmpty()
                : "ne pas compter le même objet deux fois";
        assert suivi.actualiser(Map.of("oak", 1, "birch", 1)).isEmpty();
        suivi = new MemoireCollecte(List.of(besoin(1, "hammer"), besoin(3, "iron")));
        assert suivi.actualiser(Map.of("hammer", 1, "stone", 64)).equals(Set.of("iron"));
        assert suivi.actualiser(Map.of("iron", 3)).isEmpty() : "outil validé reste acquis";
        preuvesApresBascule();
        System.out.println(
                "Collecte : seuils, validation définitive, variantes, doublons et réinitialisation OK");
    }

    private static void preuvesApresBascule() {
        var plaque = new ArbreCollecte.Noeud(Set.of("plaque"), 1, 1, false, List.of());
        var moule = new ArbreCollecte.Noeud(Set.of("moule"), 1, 1, true, List.of());
        var arbre = new ArbreCollecte.Noeud(Set.of(), 1, 1, false, List.of(plaque, moule));
        var suivi = new MemoireCollecte(arbre, 1);
        assert suivi.actualiser(Map.of("moule", 1), Map.of("plaque", 1)).isEmpty();
        var reprise = new MemoireCollecte(arbre, 1);
        reprise.reprendre(suivi);
        reprise.reprendreStockCourant();
        assert reprise.actualiser(Map.of()).equals(Set.of("moule"))
                : "le moule observé disparaît, la fabrication prouvée reste acquise";

        var boite = new ArbreCollecte.Noeud(Set.of("boite"), 1, 1, false, List.of(plaque));
        var assemblage = new ArbreCollecte.Noeud(Set.of(), 1, 1, false, List.of(boite, plaque));
        var fabrication = new MemoireCollecte(assemblage, 1);
        fabrication.actualiser(Map.of(), Map.of("plaque", 1));
        fabrication.actualiser(Map.of(), Map.of("boite", 1));
        fabrication.reprendreStockCourant();
        assert fabrication.actualiser(Map.of()).equals(Set.of("plaque"))
                : "la preuve de la plaque consommée ne peut plus couvrir la seconde branche";
    }
}
