package fr.assistantia;

import java.util.List;

/** Une action n'est envoyée qu'une fois ; la suivante attend sa confirmation. */
public final class SequenceEnvoi {
    public interface Action {
        void envoyer() throws Exception;

        boolean confirmee() throws Exception;
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

    public String erreur() {
        return erreur;
    }

    public void annuler(String raison) {
        fin = true;
        erreur = raison;
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
            if (++attente < 5) return;
            stable = action.confirmee() ? stable + 1 : 0;
            if (stable >= 3) {
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
