package fr.assistantia;

import java.util.List;

/** Une action n'est envoyée qu'une fois ; la suivante attend sa confirmation. */
public final class SequenceEnvoi {
    public interface Action {
        void envoyer() throws Exception;

        default void observer() throws Exception {}

        boolean confirmee() throws Exception;

        default void terminer() {}
    }

    private final List<Action> actions;
    private int index, attente, stable;
    private boolean envoyee, fin;
    private String erreur = "";

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

    public void annuler(String raison) {
        if (fin) return;
        fin = true;
        erreur = raison;
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
                action.terminer();
                index++;
                envoyee = false;
            } else if (attente >= 80)
                annuler(
                        "Envoi non confirmé par le serveur. Vérifie la machine avant de réessayer.");
        } catch (Exception e) {
            annuler(e.getMessage() == null ? "Envoi interrompu" : e.getMessage());
        }
    }
}
