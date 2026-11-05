package fr.assistantia;

import java.util.HashSet;
import java.util.List;

public final class GroupesMachinesTest {
    private static GroupesMachines.Machine<String> machine(
            String type, String position, int priorite) {
        return new GroupesMachines.Machine<>(type, "Bender", position, priorite, position);
    }

    public static void main(String[] args) {
        var ouverts = new HashSet<String>();
        var liste = new GroupesMachines<String>(ouverts);
        var premiere = machine("gtceu:lv_bender", "overworld:10/64/20", 2);
        var seconde = machine("gtceu:lv_bender", "overworld:20/64/20", 0);
        var autreTier = machine("gtceu:mv_bender", "overworld:30/64/20", 2);
        var relevé = List.of(premiere, autreTier, seconde);
        liste.actualiser(relevé);
        assert liste.groupes().size() == 2 : "les tiers ne sont pas fusionnés malgré le même nom";
        assert liste.lignes().size() == 2 : "les doublons sont repliés au départ";
        var groupe = liste.groupes().get(0);
        assert groupe.type().equals("gtceu:lv_bender") && groupe.machines().size() == 2;
        assert groupe.enMarche() == 1 && groupe.alertes() == 1 : "résumé des vrais états";
        assert liste.lignes().get(0).entete();
        assert !liste.lignes().get(1).entete()
                        && liste.lignes().get(1).machine().valeur().equals(autreTier.valeur())
                : "une machine unique conserve sa ligne directe";
        assert liste.basculer(groupe.type(), 1000);
        assert liste.lignes().size() == 4 && liste.ouvert(groupe.type());
        assert liste.lignes().get(1).machine().valeur().equals(seconde.valeur());
        assert liste.lignes().get(2).machine().valeur().equals(premiere.valeur())
                : "déplier conserve les deux positions et leurs relevés distincts";
        assert !liste.basculer(groupe.type(), 1001) && liste.ouvert(groupe.type())
                : "un double clic ne replie pas immédiatement le groupe";
        assert !liste.basculer("inconnu", 1700);
        assert !liste.basculer(autreTier.type(), 1700)
                : "une ligne unique n'a aucune action cachée";
        // Un filtre temporaire peut ne conserver qu'un membre, sans perdre l'état d'ouverture.
        liste.actualiser(List.of(premiere));
        assert liste.lignes().size() == 1 && !liste.lignes().get(0).entete();
        liste.actualiser(relevé);
        assert liste.lignes().size() == 4 && liste.ouvert(groupe.type());
        // La même position dans une autre dimension reste une machine distincte.
        var autreDimension = machine("gtceu:lv_bender", "nether:10/64/20", 1);
        liste.actualiser(List.of(premiere, seconde, autreDimension, autreTier));
        assert liste.groupes().get(0).machines().size() == 3;
        assert liste.groupes().get(0).alertes() == 2;
        assert liste.lignes().size() == 5 : "rafraîchir ne referme pas le groupe";
        assert liste.basculer(groupe.type(), 1800);
        assert liste.lignes().size() == 2;
        assert liste.basculer(groupe.type(), 2500);
        var retour = new GroupesMachines<String>(ouverts);
        retour.actualiser(relevé);
        assert retour.ouvert(groupe.type()) && retour.lignes().size() == 4
                : "le retour au menu garde les groupes ouverts";
        retour.actualiser(List.of());
        assert retour.groupes().isEmpty() && retour.lignes().isEmpty();
    }
}
