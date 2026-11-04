package fr.assistantia;

public final class EtatPauseEmiTest {
    public static void main(String[] args) {
        var etat = new EtatPauseEmi();
        assert !etat.actualiser("projet|1", false, 100);
        assert etat.demander("projet|1", 200) : "arrêt immédiat au clic";
        assert !etat.demander("projet|1", 201) : "double clic";
        assert etat.enPause("projet|1", false, 300);
        assert !etat.actualiser("projet|1", false, 300) : "vue encore antérieure à la pause";
        assert !etat.actualiser("projet|1", true, 500)
                : "l'accusé ne coupe pas un autre craft choisi entre temps";
        assert !etat.actualiser("projet|1", true, 600) : "pas d'arrêt répété à chaque tick";
        assert !etat.actualiser("projet|1", false, 800) : "reprendre ne redémarre rien";
        assert !etat.enPause("projet|1", false, 800);
        assert etat.actualiser("projet|1", true, 900) : "pause depuis Discussion";
        assert etat.actualiser("autre|2", true, 1000) : "projet déjà en pause";
        assert !etat.actualiser("autre|2", true, 1100);
        etat = new EtatPauseEmi();
        assert etat.actualiser("projet|1", true, 10) : "pause conservée après redémarrage";
        etat = new EtatPauseEmi();
        assert etat.demander("projet|1", 10);
        assert !etat.actualiser("projet|1", false, 5011);
        assert !etat.enPause("projet|1", false, 5011)
                : "ne pas verrouiller si la pause est refusée";
        assert etat.actualiser("projet|1", true, 6000) : "confirmation tardive";
        System.out.println(
                "Pause : clic, retard, doublons, discussion, projets et reprise explicite OK");
    }
}
