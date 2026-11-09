package fr.assistantia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Scan incrémental du volume d'un gisement, colonne de chunk par colonne, sans objet par position.
 *
 * <p>Chaque position du volume est lue une fois : un minerai de la composition est ajouté (ou son
 * type actualisé), un minerai connu qui a disparu devient « miné ». Une colonne non chargée reste
 * en attente et sera parcourue dès que son chunk se charge. Les sections dont la palette ne
 * contient aucun minerai de la composition (et aucun minerai connu) sont sautées d'un coup.
 *
 * <p>Propriété : un minerai déjà mémorisé par un autre gisement ne lui est jamais pris ; dans un
 * chevauchement de boîtes, il revient au gisement où il est le plus au centre. Dans la marge d'un
 * volume inconnu, seuls les minerais reliés au filon par des trous de {@link VolumeFilon#TROU}
 * blocs au plus sont retenus (remplissage de proche en proche).
 */
final class ScanFilon {
    /** Accès au monde ; la mise en œuvre Minecraft garde le chunk et la section courants. */
    interface Monde {
        int minY();

        /** Hauteur maximale incluse. */
        int maxY();

        boolean charge(int cx, int cz);

        /** Vrai si la section ne peut contenir aucun minerai de la composition du gisement. */
        default boolean sansMinerai(int cx, int sy, int cz, FilonMemoire.Filon filon) {
            return false;
        }

        /** Indice du type de bloc dans la mémoire, -1 s'il n'est d'aucun type connu. */
        int type(int x, int y, int z);
    }

    /** Décalages de Chebyshev 1 à TROU + 1 : deux minerais séparés de TROU blocs restent reliés. */
    static final int[] VOISINAGE = voisinage(VolumeFilon.TROU + 1);

    private static int[] voisinage(int r) {
        int[] o = new int[((2 * r + 1) * (2 * r + 1) * (2 * r + 1) - 1) * 3];
        int n = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    o[n++] = dx;
                    o[n++] = dy;
                    o[n++] = dz;
                }
        return o;
    }

    final FilonMemoire memoire;
    final FilonMemoire.Filon filon;
    final VolumeFilon volume;
    private final List<FilonMemoire.Filon> voisins = new ArrayList<>();
    private final TableLong candidats = new TableLong();
    private long[] file = new long[64];
    private int debutFile, finFile, depart, finDepart;
    private boolean aRelier, lienEnCours;

    // Curseur de la colonne en cours.
    private long colonne;
    private boolean enCours;
    private int ax, bx, ay, by, az, bz, x, y, z, sectionY = Integer.MIN_VALUE;
    private boolean sectionConnue, attente;
    private long attenteVerifiee;
    private int lus, nouveaux, disparus;

    /**
     * Nouveau scan complet (bouton Scanner) : toutes les colonnes du volume sont à parcourir. Les
     * minerais connus sont revérifiés au passage.
     *
     * @param autres gisements de la mémoire, pour la propriété des minerais partagés
     */
    static ScanFilon lancer(
            FilonMemoire memoire,
            FilonMemoire.Filon filon,
            VolumeFilon volume,
            Iterable<FilonMemoire.Filon> autres,
            int mondeMinY,
            int mondeMaxY) {
        filon.volume = volume;
        filon.colonnesRestantes = volume.colonnes();
        filon.scanComplet = false;
        filon.scanFait = 0;
        long total = 0;
        for (long c : filon.colonnesRestantes)
            total += volume.positionsColonne(c, mondeMinY, mondeMaxY);
        filon.scanTotal = Math.max(1, total);
        filon.revision++;
        memoire.toucher();
        return reprendre(memoire, filon, autres);
    }

    /** Reprend les colonnes restantes d'un scan (après une reconnexion par exemple). */
    static ScanFilon reprendre(
            FilonMemoire memoire, FilonMemoire.Filon filon, Iterable<FilonMemoire.Filon> autres) {
        if (filon.volume == null) return null;
        var scan = new ScanFilon(memoire, filon, filon.volume);
        for (var autre : autres)
            if (autre != filon
                    && autre.dimension().equals(filon.dimension())
                    && volumeDe(autre).croise(filon.volume)) scan.voisins.add(autre);
        return scan;
    }

    private ScanFilon(FilonMemoire memoire, FilonMemoire.Filon filon, VolumeFilon volume) {
        this.memoire = memoire;
        this.filon = filon;
        this.volume = volume;
    }

    /** Boîte connue d'un gisement, ou le cube historique de 16 blocs autour de son centre. */
    static VolumeFilon volumeDe(FilonMemoire.Filon f) {
        return f.volume != null
                ? f.volume
                : VolumeFilon.inconnu(f.ancre.x(), f.ancre.y(), f.ancre.z(), 16, 0);
    }

    /** Plus rien à lire ni à relier. */
    boolean termine() {
        return filon.colonnesRestantes.length == 0 && !enCours && !aRelier && !lienEnCours;
    }

    int lus() {
        return lus;
    }

    int nouveaux() {
        return nouveaux;
    }

    int disparus() {
        return disparus;
    }

    /** Colonnes restantes (non chargées ou pas encore lues). */
    int restantes() {
        return filon.colonnesRestantes.length;
    }

    /**
     * Avance jusqu'à {@code limite} (horloge en nanosecondes) ou faute de colonne chargée.
     *
     * @return vrai si au moins une position a été lue ou un minerai relié
     */
    boolean avancer(Monde monde, LongSupplier horloge, long limite, long maintenant) {
        boolean travail = false;
        while (true) {
            if (!enCours && !choisirColonne(monde)) {
                attente = filon.colonnesRestantes.length > 0;
                break;
            }
            attente = false;
            if (!monde.charge(VolumeFilon.colonneX(colonne), VolumeFilon.colonneZ(colonne))) {
                enCours = false; // déchargée en route : la colonne reste à lire
                continue;
            }
            travail = true;
            if (!balayer(monde, horloge, limite)) return true; // budget épuisé dans la colonne
            finirColonne(maintenant);
            if (horloge.getAsLong() >= limite) return true;
        }
        completer();
        if (aRelier || lienEnCours) travail |= relier(horloge, limite);
        return travail;
    }

    /** Prochaine colonne chargée parmi les restantes, sinon faux (toutes en attente ou finies). */
    private boolean choisirColonne(Monde monde) {
        int i = 0;
        while (i < filon.colonnesRestantes.length) {
            long c = filon.colonnesRestantes[i];
            int cx = VolumeFilon.colonneX(c), cz = VolumeFilon.colonneZ(c);
            if (!monde.charge(cx, cz)) {
                i++;
                continue;
            }
            ax = Math.max(volume.bordMinX(), cx * 16);
            bx = Math.min(volume.bordMaxX(), cx * 16 + 15);
            az = Math.max(volume.bordMinZ(), cz * 16);
            bz = Math.min(volume.bordMaxZ(), cz * 16 + 15);
            ay = Math.max(volume.bordMinY(), monde.minY());
            by = Math.min(volume.bordMaxY(), monde.maxY());
            if (ax > bx || az > bz || ay > by) {
                retirerColonne(c); // hors du monde : rien à lire
                continue;
            }
            colonne = c;
            x = ax;
            y = ay;
            z = az;
            sectionY = Integer.MIN_VALUE;
            enCours = true;
            return true;
        }
        return false;
    }

    /**
     * @return vrai quand la colonne est entièrement lue
     */
    private boolean balayer(Monde monde, LongSupplier horloge, long limite) {
        int cx = VolumeFilon.colonneX(colonne), cz = VolumeFilon.colonneZ(colonne);
        int pas = 0;
        while (y <= by) {
            int sy = y >> 4;
            if (sy != sectionY) {
                sectionY = sy;
                sectionConnue = filon.sectionConnue(FilonMemoire.pos(cx, sy, cz));
                if (!sectionConnue && monde.sansMinerai(cx, sy, cz, filon)) {
                    // Aucun minerai possible ni connu : passer à la section suivante d'un coup.
                    y = (sy + 1) << 4;
                    x = ax;
                    z = az;
                    continue;
                }
            }
            int t = monde.type(x, y, z);
            lus++;
            if (t >= 0 && filon.dansComposition(t)) minerai(x, y, z, t);
            else if (sectionConnue) {
                // Un minerai connu devenu autre chose est miné ; un bloc inchangé reste, même si la
                // composition ne contient plus son type (définition GregTech illisible ou
                // modifiée).
                long p = FilonMemoire.pos(x, y, z);
                int i = filon.index.lire(p);
                if (i != TableLong.ABSENT && filon.typeBloc(i) != t && memoire.miner(filon, p))
                    disparus++;
            }
            if (++x > bx) {
                x = ax;
                if (++z > bz) {
                    z = az;
                    y++;
                }
            }
            if ((++pas & 127) == 0 && horloge.getAsLong() >= limite) return y > by;
        }
        return true;
    }

    private void minerai(int x, int y, int z, int t) {
        long p = FilonMemoire.pos(x, y, z);
        if (filon.present(p)) {
            memoire.ajouter(filon, p, t); // type éventuellement changé
            return;
        }
        for (var v : voisins) if (v.present(p)) return; // jamais repris à un autre gisement
        if (!volume.contient(x, y, z)) {
            candidats.ecrire(p, t); // marge d'un volume inconnu : retenu seulement s'il est relié
            aRelier = true;
            return;
        }
        if (!aMoi(x, y, z, t)) return;
        if (memoire.ajouter(filon, p, t)) {
            nouveaux++;
            if (volume.marge() > 0) aRelier = true;
        }
    }

    /** Dans un chevauchement, le minerai revient au gisement où il est le plus au centre. */
    private boolean aMoi(int x, int y, int z, int t) {
        double moi = volume.profondeur(x, y, z);
        for (var v : voisins) {
            if (!v.dansComposition(t)) continue;
            VolumeFilon autre = volumeDe(v);
            if (!autre.contient(x, y, z)) continue;
            double lui = autre.profondeur(x, y, z);
            if (lui < moi) return false;
            if (lui == moi) {
                double a = carre(filon.ancre, x, y, z), b = carre(v.ancre, x, y, z);
                if (b < a || b == a && v.id.compareTo(filon.id) < 0) return false;
            }
        }
        return true;
    }

    private static double carre(FilonMemoire.Position p, int x, int y, int z) {
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void finirColonne(long maintenant) {
        filon.scanFait += (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
        filon.verification = maintenant;
        filon.revision++;
        memoire.toucher();
        retirerColonne(colonne);
        enCours = false;
    }

    private void retirerColonne(long c) {
        long[] restantes = filon.colonnesRestantes;
        for (int i = 0; i < restantes.length; i++)
            if (restantes[i] == c) {
                long[] suite = new long[restantes.length - 1];
                System.arraycopy(restantes, 0, suite, 0, i);
                System.arraycopy(restantes, i + 1, suite, i, restantes.length - i - 1);
                filon.colonnesRestantes = suite;
                completer();
                return;
            }
    }

    /**
     * Dès la dernière colonne lue, le volume est entièrement parcouru : marqué ici et non après la
     * boucle, que le budget du tick peut interrompre juste avant.
     */
    private void completer() {
        if (filon.colonnesRestantes.length > 0 || filon.scanComplet) return;
        filon.scanComplet = true;
        filon.scanFait = filon.scanTotal;
        filon.revision++;
        memoire.toucher();
    }

    // ---------- Remplissage de proche en proche dans la marge ----------

    /**
     * Retient les candidats de la marge reliés au filon (Chebyshev ≤ TROU + 1), par lots dans le
     * budget. Les candidats non reliés attendent les colonnes encore à lire, puis sont abandonnés.
     */
    private boolean relier(LongSupplier horloge, long limite) {
        if (!lienEnCours) {
            lienEnCours = true;
            aRelier = false;
            depart = 0;
            finDepart = filon.nombre();
            debutFile = finFile = 0;
        }
        int pas = 0;
        while (true) {
            if (debutFile == finFile) {
                debutFile = finFile = 0;
                while (depart < Math.min(finDepart, filon.nombre()) && finFile == 0) {
                    long d = filon.position(depart++);
                    if (presDesFaces(d)) pousser(d);
                }
                if (finFile == 0) break;
            }
            long p = file[debutFile++];
            int px = FilonMemoire.x(p), py = FilonMemoire.y(p), pz = FilonMemoire.z(p);
            for (int i = 0; i < VOISINAGE.length; i += 3) {
                long q =
                        FilonMemoire.pos(
                                px + VOISINAGE[i], py + VOISINAGE[i + 1], pz + VOISINAGE[i + 2]);
                int t = candidats.lire(q);
                if (t == TableLong.ABSENT) continue;
                candidats.retirer(q);
                boolean pris = false;
                for (var v : voisins) pris |= v.present(q);
                if (pris) continue;
                if (memoire.ajouter(filon, q, t)) nouveaux++;
                pousser(q);
            }
            if ((++pas & 7) == 0 && horloge.getAsLong() >= limite) return true;
        }
        lienEnCours = false;
        if (filon.colonnesRestantes.length == 0) candidats.vider(); // non reliés : abandonnés
        return pas > 0;
    }

    private boolean presDesFaces(long p) {
        int x = FilonMemoire.x(p), y = FilonMemoire.y(p), z = FilonMemoire.z(p);
        int e = VolumeFilon.TROU + 1;
        return x - volume.minX() < e
                || volume.maxX() - x < e
                || y - volume.minY() < e
                || volume.maxY() - y < e
                || z - volume.minZ() < e
                || volume.maxZ() - z < e
                || !volume.contient(x, y, z);
    }

    private void pousser(long p) {
        if (finFile == file.length) {
            if (debutFile > 0) {
                System.arraycopy(file, debutFile, file, 0, finFile - debutFile);
                finFile -= debutFile;
                debutFile = 0;
            } else file = Arrays.copyOf(file, file.length * 2);
        }
        file[finFile++] = p;
    }

    /** Une colonne restante s'est chargée (vérifié au plus toutes les 500 ms). */
    boolean colonnePrete(Monde monde, long maintenant) {
        if (enCours || aRelier || lienEnCours) return true;
        if (maintenant - attenteVerifiee < 500) return !attente;
        attenteVerifiee = maintenant;
        for (long c : filon.colonnesRestantes)
            if (monde.charge(VolumeFilon.colonneX(c), VolumeFilon.colonneZ(c))) {
                attente = false;
                return true;
            }
        attente = filon.colonnesRestantes.length > 0;
        return false;
    }

    /** Toutes les colonnes restantes attendent le chargement de leur chunk. */
    boolean attente() {
        return attente && !enCours;
    }
}
