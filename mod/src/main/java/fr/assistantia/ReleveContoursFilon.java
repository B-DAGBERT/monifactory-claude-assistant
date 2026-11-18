package fr.assistantia;

import java.util.function.IntPredicate;
import java.util.function.LongFunction;

/** Confirmations tournantes des contours, liées à l'instance chargée de chaque chunk. */
final class ReleveContoursFilon {
    private final long[] positions;
    private final int[] types, colonnesPositions, confirmations;
    private final TableLong indices;
    private final long[] colonnes;
    private final Object[] chargements;
    private final int[] revisions;
    private final int nombreColonnes;
    private ReleveContoursFilon precedent;
    private long dernierVise = TableLong.VIDE;
    private int rotation;

    ReleveContoursFilon(long[] positions, int[] types, ReleveContoursFilon precedent) {
        this.positions = positions;
        this.types = types;
        this.precedent = precedent;
        if (precedent != null) {
            dernierVise = precedent.dernierVise;
            rotation = precedent.rotation;
        }
        indices = new TableLong(positions.length);
        colonnes = new long[positions.length];
        colonnesPositions = new int[positions.length];
        confirmations = new int[positions.length];
        var parColonne = new TableLong();
        int nombre = 0;
        for (int i = 0; i < positions.length; i++) {
            long p = positions[i];
            indices.ecrire(p, i);
            long colonne = VolumeFilon.colonne(FilonMemoire.x(p) >> 4, FilonMemoire.z(p) >> 4);
            int index = parColonne.lire(colonne);
            if (index == TableLong.ABSENT) {
                index = nombre++;
                parColonne.ecrire(colonne, index);
                colonnes[index] = colonne;
            }
            colonnesPositions[i] = index;
        }
        nombreColonnes = nombre;
        chargements = new Object[nombre];
        revisions = new int[nombre];
    }

    boolean confirme(int i) {
        int colonne = colonnesPositions[i];
        return chargements[colonne] != null && confirmations[i] == revisions[colonne];
    }

    /** Chaque tick valide les plus proches, le bloc visé et une tranche du reste des contours. */
    void actualiser(
            LongFunction<?> chunk, IntPredicate verifier, long vise, int proches, int autres) {
        for (int j = 0; j < nombreColonnes; j++) {
            Object charge = chunk.apply(colonnes[j]);
            if (charge != chargements[j]) {
                chargements[j] = charge;
                revisions[j]++;
            }
        }
        reprendreConfirmations();
        int premier = Math.min(proches, positions.length);
        for (int i = 0; i < premier; i++) confirmer(i, verifier);
        int viseAvant =
                dernierVise == TableLong.VIDE ? TableLong.ABSENT : indices.lire(dernierVise);
        int viseMaintenant = vise == TableLong.VIDE ? TableLong.ABSENT : indices.lire(vise);
        if (viseAvant >= premier) confirmer(viseAvant, verifier);
        if (viseMaintenant >= premier && viseMaintenant != viseAvant)
            confirmer(viseMaintenant, verifier);
        dernierVise = vise;
        int distants = positions.length - premier;
        if (distants == 0) return;
        for (int n = 0; n < Math.min(autres, distants); n++) {
            int i = premier + (rotation + n) % distants;
            if (i != viseAvant && i != viseMaintenant) confirmer(i, verifier);
        }
        rotation = (rotation + autres) % distants;
    }

    private void reprendreConfirmations() {
        if (precedent == null) return;
        for (int i = 0; i < positions.length; i++) {
            int j = precedent.indices.lire(positions[i]);
            int colonne = colonnesPositions[i];
            if (j != TableLong.ABSENT
                    && types[i] == precedent.types[j]
                    && precedent.confirme(j)
                    && chargements[colonne]
                            == precedent.chargements[precedent.colonnesPositions[j]])
                confirmations[i] = revisions[colonne];
        }
        precedent = null;
    }

    private void confirmer(int i, IntPredicate verifier) {
        int colonne = colonnesPositions[i];
        if (chargements[colonne] != null)
            confirmations[i] = verifier.test(i) ? revisions[colonne] : -1;
    }
}
