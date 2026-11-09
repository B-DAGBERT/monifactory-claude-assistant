package fr.assistantia;

/**
 * Géométrie de l'onglet Filons, indépendante du rendu et commune aux lignes et à leurs zones de
 * clic : deux rangées d'outils, la liste, la fiche du filon choisi, ses actions et le statut.
 */
public record DispositionFilons(
        int listeY,
        int listeBas,
        int listeLargeur,
        int detailX,
        int detailY,
        int detailLargeur,
        int detailBas,
        int actionsY,
        int statutY,
        boolean colonnes,
        int outils2Y) {
    /** Mêmes valeurs que Theme (BOUTON, ECART, LIGNE) : classe testable sans Minecraft. */
    static final int BOUTON = 16, ECART = 4, TEXTE = 11;

    /** Hauteur d'une ligne de la liste (icône et deux lignes de texte). */
    public static final int LIGNE = 26;

    /** Lignes de texte de la fiche en petite fenêtre : titre, compte, conseil. */
    public static final int FICHE = 3 * TEXTE;

    /** Ligne d'un minerai dans la fiche (icône de 16). */
    public static final int MINERAI = 18;

    public static DispositionFilons calculer(int x, int y, int largeur, int bas) {
        boolean colonnes = largeur >= 460;
        int outils2 = y + BOUTON + ECART;
        int debut = outils2 + BOUTON + ECART;
        int statut = bas - 9;
        int actions = statut - ECART - BOUTON;
        if (colonnes) {
            int largeurListe = (largeur - 10) * 54 / 100;
            return new DispositionFilons(
                    debut,
                    statut - ECART,
                    largeurListe,
                    x + largeurListe + 10,
                    debut,
                    largeur - largeurListe - 10,
                    actions - ECART,
                    actions,
                    statut,
                    true,
                    outils2);
        }
        // Petite fenêtre : la fiche sous la liste, avec la liste des minerais s'il reste de la
        // place.
        int place = actions - ECART - debut;
        int fiche = FICHE;
        if (place - fiche - ECART < LIGNE) fiche = 2 * TEXTE; // garder une ligne
        if (place >= 2 * LIGNE + FICHE + 4 * MINERAI + 2 * ECART)
            fiche = FICHE + Math.min(6, (place - 2 * LIGNE - FICHE) / MINERAI - 1) * MINERAI;
        int detailY = actions - ECART - fiche;
        return new DispositionFilons(
                debut,
                detailY - ECART,
                largeur,
                x,
                detailY,
                largeur,
                actions - ECART,
                actions,
                statut,
                false,
                outils2);
    }

    /** Lignes entièrement visibles dans la liste. */
    public int lignes() {
        return Math.max(0, (listeBas - listeY) / LIGNE);
    }

    /** Lignes de minerais que la fiche peut montrer sous son en-tête. */
    public int minerais() {
        return Math.max(0, (detailBas - detailY - FICHE) / MINERAI);
    }
}
