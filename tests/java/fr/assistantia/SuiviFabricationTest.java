package fr.assistantia;

import java.util.List;

public final class SuiviFabricationTest {
    private static PlanRetrait.Stock<String> stack(String id, int n) {
        return new PlanRetrait.Stock<>(id, n, true);
    }

    private static PlanRetrait.Besoin<String> besoin(String id, int n) {
        return new PlanRetrait.Besoin<>(id::equals, n);
    }

    public static void main(String[] args) {
        var deuxMachines = List.of(besoin("machine", 2));
        var suivi = new SuiviFabrication<>(deuxMachines);
        assert !suivi.actualiser(List.of(stack("motor", 64), stack("circuit", 64)))
                : "un intermédiaire n'est pas le résultat";
        assert !suivi.actualiser(List.of(stack("machine", 1)))
                : "respecter toute la quantité de l'arbre";
        assert suivi.actualiser(List.of(stack("machine", 2)))
                : "Maj-clic : la pile est reçue en une fois";
        assert suivi.actualiser(List.of()) : "la fin ne s'annule pas après rangement";
        assert !new SuiviFabrication<>(deuxMachines).actualiser(List.of())
                : "un nouvel arbre repart à zéro";
        suivi = new SuiviFabrication<>(List.of(besoin("torch", 128)));
        assert !suivi.actualiser(List.of(stack("torch", 64), stack("torch", 63)));
        assert suivi.actualiser(List.of(stack("torch", 64), stack("torch", 64)))
                : "cumuler les piles finales";
        suivi = new SuiviFabrication<>(List.of(besoin("machine", 1), besoin("wire", 16)));
        assert !suivi.actualiser(List.of(stack("machine", 1)));
        assert !suivi.actualiser(List.of(stack("wire", 15)));
        assert suivi.actualiser(List.of(stack("wire", 16)))
                : "plusieurs cibles obtenues successivement";
        suivi = new SuiviFabrication<>(List.of(besoin("circuit#1", 1)));
        assert !suivi.actualiser(List.of(stack("circuit#2", 1)))
                : "comparaison exacte de la variante cible";
        assert suivi.actualiser(List.of(stack("circuit#1", 1)));
        suivi =
                new SuiviFabrication<>(
                        List.of(
                                new PlanRetrait.Besoin<String>(
                                        id -> id.equals("oak") || id.equals("birch"), 1),
                                besoin("oak", 1)));
        assert !suivi.actualiser(List.of(stack("oak", 1))) : "un objet ne valide pas deux cibles";
        assert suivi.actualiser(List.of(stack("oak", 1), stack("birch", 1)));
        assert !new SuiviFabrication<String>(List.of()).actualiser(List.of())
                : "arbre absent ou inconnu : aucune fin inventée";
        System.out.println(
                "Fin de craft : quantités, piles, intermédiaires, variantes, cibles multiples et nouveau suivi OK");
    }
}
