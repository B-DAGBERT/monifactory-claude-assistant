package fr.assistantia;

/** Géométrie indépendante du rendu, commune aux lignes et à leurs zones de clic. */
public record DispositionFilons(int listeY, int listeBas, int listeLargeur,
                                int detailX, int detailY, int detailLargeur,
                                int actionsY, int statutY, boolean colonnes) {
    public static final int LIGNE = 32;
    public static DispositionFilons calculer(int x, int y, int largeur, int bas, boolean outils) {
        boolean colonnes = largeur >= 460;
        int debut = y + (outils ? 66 : 44);
        int actions = bas - 36;
        int largeurListe = colonnes ? (largeur - 10) * 54 / 100 : largeur;
        int finListe = colonnes ? bas - 18 : actions - (bas - debut >= 230 ? 88 : 28);
        int detailX = colonnes ? x + largeurListe + 10 : x;
        int detailY = colonnes ? debut : finListe + 4;
        return new DispositionFilons(debut, finListe, largeurListe, detailX, detailY,
                colonnes ? largeur - largeurListe - 10 : largeur, actions, bas - 11, colonnes);
    }
    public int lignes() { return Math.max(0, (listeBas-listeY)/LIGNE); }
}
