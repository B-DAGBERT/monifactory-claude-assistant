package fr.assistantia;

import java.util.List;

public final class SequenceEnvoiTest {
    private static final class Action implements SequenceEnvoi.Action {
        int envois;
        boolean confirme;

        public void envoyer() {
            envois++;
        }

        public boolean confirmee() {
            return confirme;
        }
    }

    public static void main(String[] args) {
        var a = new Action();
        var b = new Action();
        var s = new SequenceEnvoi(List.of(a, b));
        for (int i = 0; i < 40; i++) s.tick();
        assert a.envois == 1 && b.envois == 0 : "Ne jamais réessayer pendant la latence";
        a.confirme = true;
        for (int i = 0; i < 12; i++) s.tick();
        assert b.envois == 1 && !s.terminee();
        b.confirme = true;
        for (int i = 0; i < 12; i++) s.tick();
        assert s.reussie();
        // Une confirmation momentanée suivie d'une correction ne valide pas l'action.
        a = new Action();
        b = new Action();
        s = new SequenceEnvoi(List.of(a, b));
        s.tick();
        a.confirme = true;
        for (int i = 0; i < 6; i++) s.tick();
        a.confirme = false;
        for (int i = 0; i < 30; i++) s.tick();
        assert b.envois == 0 && a.envois == 1;
        a.confirme = true;
        for (int i = 0; i < 8; i++) s.tick();
        assert b.envois == 1 && a.envois == 1;
        s.annuler("Déconnexion");
        b.confirme = true;
        for (int i = 0; i < 100; i++) s.tick();
        assert !s.reussie() && b.envois == 1
                : "un paquet tardif ne reprend pas une séquence annulée";
        var refuse = new Action();
        s = new SequenceEnvoi(List.of(refuse));
        for (int i = 0; i < 100; i++) s.tick();
        assert s.terminee() && !s.reussie() && refuse.envois == 1;
        a = new Action();
        b = new Action();
        s = new SequenceEnvoi(List.of(a, b));
        s.tick();
        s.annuler("Interface fermée");
        for (int i = 0; i < 100; i++) s.tick();
        assert b.envois == 0 && !s.reussie();
    }
}
