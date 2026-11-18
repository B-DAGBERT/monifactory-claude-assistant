package fr.assistantia;

import java.util.List;

/** Une action n'est envoyée qu'une fois ; la suivante attend sa confirmation. */
public final class SequenceEnvoi {
    public enum Echec {
        AUCUN,
        INTERROMPU,
        NON_CONFIRME,
        ACTION_REFUSEE
    }

    public interface Action {
        void envoyer() throws Exception;

        default void observer() throws Exception {}

        boolean confirmee() throws Exception;

        default void acquittee() {}

        default void terminer() {}
    }

    private final List<Action> actions;
    private int index, attente, stable;
    private boolean envoyee, fin;
    private String erreur = "";
    private Echec echec = Echec.AUCUN;

    public SequenceEnvoi(List<Action> actions) {
        this.actions = List.copyOf(actions);
    }

    public boolean terminee() {
        return fin;
    }

    public boolean reussie() {
        return fin && erreur.isEmpty() && index == actions.size();
    }

    public int progression() {
        return actions.isEmpty() ? 100 : index * 100 / actions.size();
    }

    public String erreur() {
        return erreur;
    }

    public Echec echec() {
        return echec;
    }

    public void annuler(String raison) {
        annuler(raison, Echec.INTERROMPU);
    }

    private void annuler(String raison, Echec cause) {
        if (fin) return;
        fin = true;
        erreur = raison;
        echec = cause;
        if (index < actions.size()) actions.get(index).terminer();
    }

    public void tick() {
        if (fin) return;
        if (index == actions.size()) {
            fin = true;
            return;
        }
        try {
            var action = actions.get(index);
            if (!envoyee) {
                action.envoyer();
                envoyee = true;
                attente = stable = 0;
                return;
            }
            action.observer();
            if (++attente < 5) return;
            stable = action.confirmee() ? stable + 1 : 0;
            if (stable >= 3) {
                action.acquittee();
                action.terminer();
                index++;
                envoyee = false;
            } else if (attente >= 80)
                annuler(
                        "Envoi non confirmé par le serveur. Vérifie la machine avant de réessayer.",
                        Echec.NON_CONFIRME);
        } catch (Exception e) {
            annuler(
                    e.getMessage() == null ? "Envoi interrompu" : e.getMessage(),
                    Echec.ACTION_REFUSEE);
        }
    }
}
