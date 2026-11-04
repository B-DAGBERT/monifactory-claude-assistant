package fr.assistantia;

/** Une seule interruption par passage en pause, même avec un accusé de réception retardé. */
public final class EtatPauseEmi {
    private String objectif = "";
    private boolean pause, attente;
    private long envoye;

    public boolean demander(String cle, long maintenant) {
        boolean arreter = !cle.equals(objectif) || !pause;
        objectif = cle;
        pause = attente = true;
        envoye = maintenant;
        return arreter;
    }

    public boolean actualiser(String cle, boolean enPause, long maintenant) {
        boolean change = !cle.equals(objectif);
        if (!change && attente && !enPause && maintenant - envoye < 5000) return false;
        boolean arreter = enPause && (change || !pause);
        objectif = cle;
        pause = enPause;
        attente = false;
        return arreter;
    }

    public boolean enPause(String cle, boolean confirme, long maintenant) {
        return confirme || attente && cle.equals(objectif) && maintenant - envoye < 5000;
    }
}
