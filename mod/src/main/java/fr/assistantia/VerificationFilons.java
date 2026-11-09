package fr.assistantia;

import java.util.List;

/**
 * Compte restant tenu à jour sans rescanner : les minerais connus des chunks chargés sont relus à
 * tour de rôle (quelques centaines par tick), et ceux du filon surligné près du joueur à chaque
 * tick. Un minerai connu devenu autre chose est compté « miné ».
 */
final class VerificationFilons {
    private List<FilonMemoire.Filon> liste = List.of();
    private String dimension = "";
    private long revisionListe = -1;
    private int filon, entree;
    private boolean toutCharge = true;

    /**
     * Relit au plus {@code nombre} minerais connus de la dimension, en reprenant là où le tour
     * précédent s'est arrêté.
     *
     * @return minerais devenus « minés »
     */
    int tour(
            FilonMemoire memoire,
            String dimension,
            ScanFilon.Monde monde,
            int nombre,
            long maintenant) {
        if (!dimension.equals(this.dimension)
                || memoire.revision() != revisionListe && entree == 0) {
            this.dimension = dimension;
            liste = memoire.filonsDans(dimension);
            revisionListe = memoire.revision();
            filon = Math.min(filon, liste.size());
        }
        if (liste.isEmpty()) return 0;
        int mines = 0, vus = 0, vides = 0;
        while (vus < nombre && vides <= liste.size()) {
            if (filon >= liste.size()) filon = 0;
            var f = liste.get(filon);
            if (entree >= f.nombre()) {
                if (f.nombre() > 0 && toutCharge) f.verification = maintenant;
                filon++;
                entree = 0;
                toutCharge = true;
                vides++;
                continue;
            }
            vides = 0;
            vus++;
            int resultat = verifier(memoire, f, entree, monde);
            if (resultat < 0) toutCharge = false;
            if (resultat == 1) mines++;
            else entree++; // un minerai miné est remplacé par le dernier : relire la même case
        }
        return mines;
    }

    /**
     * Relit les minerais du filon surligné aux positions données (les plus proches du joueur).
     *
     * @return minerais devenus « minés »
     */
    static int proches(
            FilonMemoire memoire,
            FilonMemoire.Filon f,
            long[] positions,
            int nombre,
            ScanFilon.Monde monde) {
        int mines = 0;
        for (int i = 0; i < Math.min(nombre, positions.length); i++) {
            long p = positions[i];
            if (!f.present(p)) continue;
            int x = FilonMemoire.x(p), y = FilonMemoire.y(p), z = FilonMemoire.z(p);
            if (!monde.charge(x >> 4, z >> 4)) continue;
            int t = monde.type(x, y, z);
            int attendu = f.typeBloc(f.index.lire(p));
            if (t == attendu) continue;
            if (t >= 0 && f.dansComposition(t)) memoire.ajouter(f, p, t);
            else if (memoire.miner(f, p)) mines++;
        }
        return mines;
    }

    /**
     * @return 1 si miné, 0 si toujours là (ou type actualisé), -1 si le chunk n'est pas chargé
     */
    static int verifier(FilonMemoire memoire, FilonMemoire.Filon f, int i, ScanFilon.Monde monde) {
        long p = f.position(i);
        int x = FilonMemoire.x(p), y = FilonMemoire.y(p), z = FilonMemoire.z(p);
        if (y < monde.minY() || y > monde.maxY() || !monde.charge(x >> 4, z >> 4)) return -1;
        int t = monde.type(x, y, z);
        if (t == f.typeBloc(i)) return 0;
        if (t >= 0 && f.dansComposition(t)) {
            memoire.ajouter(f, p, t);
            return 0;
        }
        return memoire.miner(f, p) ? 1 : 0;
    }

    void oublier() {
        liste = List.of();
        dimension = "";
        revisionListe = -1;
        filon = entree = 0;
        toutCharge = true;
    }
}
