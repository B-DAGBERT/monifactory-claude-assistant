package fr.assistantia;

import java.util.*;
import java.util.function.Predicate;

/**
 * Modèle local sans dépendance Minecraft : gisements GregTech et minerais repérés.
 *
 * <p>Chaque gisement garde ses minerais encore présents dans des tableaux primitifs (position
 * compactée façon {@code BlockPos.asLong}, type de bloc) avec un index position → case. Les blocs
 * minés ne sont plus mémorisés un par un : ils deviennent des compteurs par type, ce qui garde le
 * total trouvé (restants + minés) sans grossir la mémoire ni la sauvegarde.
 */
public final class FilonMemoire {
    /** Minerais présents mémorisés au plus, tous gisements confondus. */
    public static final int MAX_BLOCS = 400_000;

    /** Proposition « Épuisé » : il reste au plus 10 % des minerais trouvés. */
    public static final int POURCENT_PRESQUE_EPUISE = 10, MINIMUM_PRESQUE_EPUISE = 16;

    public static final int RAYON_TP = 8;

    /** Position lisible, réservée aux interfaces et à la téléportation (jamais par bloc scanné). */
    public record Position(String dimension, int x, int y, int z) {
        public long longue() {
            return pos(x, y, z);
        }

        public double distance2(Position p) {
            if (!dimension.equals(p.dimension)) return Double.POSITIVE_INFINITY;
            return Math.pow((double) x - p.x, 2)
                    + Math.pow((double) y - p.y, 2)
                    + Math.pow((double) z - p.z, 2);
        }
    }

    // ---------- Positions compactées (même codage que BlockPos.asLong) ----------

    public static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long p) {
        return (int) (p >> 38);
    }

    public static int y(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int z(long p) {
        return (int) (p << 26 >> 38);
    }

    /** Section de 16³ blocs contenant la position. */
    static long section(int x, int y, int z) {
        return pos(x >> 4, y >> 4, z >> 4);
    }

    static double distance2(long p, int x, int y, int z) {
        double dx = x(p) - x, dy = y(p) - y, dz = z(p) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------- Types de blocs ----------

    private final List<String> types = new ArrayList<>();
    private final Map<String, Integer> indexTypes = new HashMap<>();

    /** Indice du type, créé s'il est nouveau. */
    public int type(String id) {
        Integer t = indexTypes.get(id);
        if (t != null) return t;
        types.add(id);
        indexTypes.put(id, types.size() - 1);
        return types.size() - 1;
    }

    /** Indice d'un type déjà connu, -1 sinon. */
    public int typeConnu(String id) {
        Integer t = indexTypes.get(id);
        return t == null ? -1 : t;
    }

    public String typeId(int t) {
        return t >= 0 && t < types.size() ? types.get(t) : "";
    }

    public int typesConnus() {
        return types.size();
    }

    // ---------- Gisements ----------

    public static final class Filon {
        public final String id;
        public final Position ancre;
        final FilonMemoire memoire;
        public String definition = "", nom = "";
        public Set<String> composition = Set.of();
        private long[] typesComposition = new long[1];
        Object definitionNative; // disponible uniquement après lecture du journal de la connexion
        public boolean epuise; // état du journal GregTech
        private boolean epuiseManuel;
        private Set<String> typesChoisis; // null : aucun choix validé, tous cochés par défaut

        /** Copies triées et immuables, partagées par les instantanés de sauvegarde. */
        String[] compositionTriee = new String[0], choixTries;

        // Minerais présents.
        long[] positions = new long[16];
        int[] typesBlocs = new int[16];
        int presents;
        final TableLong index = new TableLong();
        final TableLong sections = new TableLong();
        // Minerais minés : compteurs par type.
        int[] minesTypes = new int[4], minesNombres = new int[4];
        int minesTypesNombre, mines;

        /** Dernière vérification d'au moins une partie du gisement (ms), 0 si inconnue. */
        long verification;

        /** Change à chaque modification : invalide les caches de l'interface et du rendu. */
        long revision;

        /** Boîte du générateur, persistée pour reprendre un scan sans le journal GregTech. */
        VolumeFilon volume;

        /** Colonnes de chunks encore à parcourir pour finir le dernier scan demandé. */
        long[] colonnesRestantes = new long[0];

        /** Positions du volume parcourues et à parcourir pour le dernier scan demandé. */
        long scanFait, scanTotal;

        /** Le volume entier a été parcouru depuis le dernier « Scanner ». */
        boolean scanComplet;

        Filon(FilonMemoire memoire, String id, Position ancre) {
            this.memoire = memoire;
            this.id = id;
            this.ancre = ancre;
        }

        public boolean epuiseManuel() {
            return epuiseManuel;
        }

        public boolean estEpuise() {
            return epuise || epuiseManuel;
        }

        public boolean marquerEpuise(boolean valeur) {
            if (epuiseManuel == valeur) return false;
            epuiseManuel = valeur;
            revision++;
            return true;
        }

        public boolean gregtech() {
            return !definition.isEmpty();
        }

        public boolean visible() {
            return !estEpuise() && (gregtech() || presents > 0);
        }

        public String dimension() {
            return ancre.dimension();
        }

        // ----- Compteurs -----

        public int restants() {
            return presents;
        }

        public int mines() {
            return mines;
        }

        /** Minerais trouvés depuis le premier relevé : restants + minés. */
        public int trouves() {
            return presents + mines;
        }

        /** Part restante en pour cent (100 si rien n'a encore été trouvé). */
        public int pourcentRestant() {
            int total = trouves();
            return total == 0 ? 100 : (int) Math.round(presents * 100.0 / total);
        }

        public boolean scanEnAttente() {
            return colonnesRestantes.length > 0;
        }

        public boolean scanComplet() {
            return scanComplet;
        }

        /** Avancement du dernier scan demandé (0 à 100), 100 sans scan en cours. */
        public int avancementScan() {
            if (scanTotal <= 0) return colonnesRestantes.length > 0 ? 0 : 100;
            return (int) Math.min(100, scanFait * 100 / scanTotal);
        }

        /**
         * Peu de minerai restant par rapport au total trouvé : proposer « Épuisé » (jamais de
         * marquage automatique). Pas de proposition pendant un scan inachevé : le reste du volume
         * est inconnu.
         */
        public boolean presqueEpuise() {
            // Seul un scan complet du volume connaît le total : une zone vue en partie ne suffit
            // pas.
            return scanComplet && !scanEnAttente() && peuRestant();
        }

        /**
         * Peu de minerai dans une zone vue en partie (ancien scan de 16 blocs, scan arrêté) :
         * proposer de scanner pour confirmer, jamais « Épuisé ».
         */
        public boolean presqueEpuiseAConfirmer() {
            return !scanComplet && !scanEnAttente() && peuRestant();
        }

        private boolean peuRestant() {
            int total = trouves();
            return !estEpuise()
                    && total >= MINIMUM_PRESQUE_EPUISE
                    && presents * 100L <= (long) total * POURCENT_PRESQUE_EPUISE;
        }

        public String quantite() {
            if (epuiseManuel) return "Épuisé (marquage personnel)";
            if (!gregtech()) return presents + " repérés";
            if (epuise) return "Épuisé (GregTech)";
            if (trouves() == 0)
                return scanEnAttente()
                        ? "Scan en attente · rien trouvé encore"
                        : "Pas encore scanné";
            return presents
                    + " restants sur "
                    + trouves()
                    + " trouvés ("
                    + pourcentRestant()
                    + " %)"
                    + (scanEnAttente()
                            ? " · scan partiel"
                            : scanComplet ? "" : " · zone partielle");
        }

        // ----- Composition et choix d'affichage -----

        public boolean dansComposition(int type) {
            return type >= 0
                    && (type >> 6) < typesComposition.length
                    && (typesComposition[type >> 6] & (1L << (type & 63))) != 0;
        }

        void definirComposition(Set<String> ids) {
            composition = Set.copyOf(ids);
            compositionTriee = composition.toArray(String[]::new);
            Arrays.sort(compositionTriee);
            long[] bits = new long[Math.max(1, (memoire.types.size() + ids.size() + 63) / 64 + 1)];
            for (String id : ids) {
                int t = memoire.type(id);
                if ((t >> 6) >= bits.length) bits = Arrays.copyOf(bits, (t >> 6) + 1);
                bits[t >> 6] |= 1L << (t & 63);
            }
            typesComposition = bits;
        }

        public boolean typeAffiche(String type) {
            return typesChoisis == null || typesChoisis.contains(type);
        }

        boolean typeAffiche(int type) {
            return typesChoisis == null || typesChoisis.contains(memoire.typeId(type));
        }

        public Set<String> typesChoisis() {
            return typesChoisis == null ? null : Set.copyOf(typesChoisis);
        }

        public void choisirTypes(Collection<String> types) {
            typesChoisis = Set.copyOf(types);
            choixTries = typesChoisis.toArray(String[]::new);
            Arrays.sort(choixTries);
            revision++;
        }

        /** Composition GT avec les minerais présents (0 pour les types jamais vus). */
        public Map<String, Integer> types() {
            Map<String, Integer> resultat = new TreeMap<>();
            for (String type : composition) resultat.put(type, 0);
            resultat.putAll(typesReperes());
            return resultat;
        }

        /** Uniquement les minerais observés encore présents, par type de bloc. */
        public Map<String, Integer> typesReperes() {
            int[] compte = new int[memoire.types.size()];
            for (int i = 0; i < presents; i++) compte[typesBlocs[i]]++;
            Map<String, Integer> resultat = new TreeMap<>();
            for (int t = 0; t < compte.length; t++)
                if (compte[t] > 0) resultat.put(memoire.typeId(t), compte[t]);
            return resultat;
        }

        /** Minerais minés par type de bloc. */
        public Map<String, Integer> typesMines() {
            Map<String, Integer> resultat = new TreeMap<>();
            for (int i = 0; i < minesTypesNombre; i++)
                if (minesNombres[i] > 0)
                    resultat.merge(memoire.typeId(minesTypes[i]), minesNombres[i], Integer::sum);
            return resultat;
        }

        // ----- Fraîcheur -----

        public long verificationAncienne() {
            return verification;
        }

        public String ageReleve(long maintenant) {
            if (verification <= 0) return "date inconnue";
            long minutes = Math.max(0, maintenant - verification) / 60000;
            if (minutes == 0) return "moins de 1 min";
            if (minutes < 60) return minutes + " min";
            if (minutes < 1440) return (minutes / 60) + " h";
            return (minutes / 1440) + " j";
        }

        public boolean releveAncien(long maintenant) {
            return verification <= 0 || maintenant - verification >= 600000;
        }

        // ----- Accès aux minerais présents -----

        public int nombre() {
            return presents;
        }

        public long position(int i) {
            return positions[i];
        }

        public int typeBloc(int i) {
            return typesBlocs[i];
        }

        public boolean present(long p) {
            return index.contient(p);
        }

        /** Minerai présent le plus proche, ou l'ancre. */
        public Position destination(Position depuis) {
            if (presents == 0 || !depuis.dimension().equals(ancre.dimension())) return ancre;
            int meilleur = 0;
            double d = Double.MAX_VALUE;
            for (int i = 0; i < presents; i++) {
                double e = distance2(positions[i], depuis.x(), depuis.y(), depuis.z());
                if (e < d) {
                    d = e;
                    meilleur = i;
                }
            }
            long p = positions[meilleur];
            return new Position(ancre.dimension(), x(p), y(p), z(p));
        }

        /** Le joueur est à trois blocs d'un minerai restant. */
        public boolean arrive(Position joueur) {
            if (!joueur.dimension().equals(ancre.dimension())) return false;
            for (int i = 0; i < presents; i++)
                if (distance2(positions[i], joueur.x(), joueur.y(), joueur.z()) <= 9) return true;
            return false;
        }

        /**
         * Minerais présents dans la section : un scan ne saute jamais une section qui en contient.
         */
        public boolean sectionConnue(long section) {
            return sections.contient(section);
        }

        /**
         * Indices des minerais à surligner : types choisis, à moins de {@code rayon} blocs, les
         * plus proches d'abord, au plus {@code maximum}.
         */
        public int[] contours(int px, int py, int pz, int rayon, int maximum) {
            int limite = Math.max(1, Math.min(4096, maximum));
            double r2 = (double) rayon * rayon;
            long[] cles = new long[presents];
            int n = 0;
            boolean tous = typesChoisis == null;
            boolean[] affiches = tous ? null : new boolean[memoire.types.size()];
            if (!tous)
                for (String t : typesChoisis) {
                    int i = memoire.typeConnu(t);
                    if (i >= 0) affiches[i] = true;
                }
            for (int i = 0; i < presents; i++) {
                if (!tous && !affiches[typesBlocs[i]]) continue;
                double d = distance2(positions[i], px, py, pz);
                if (d > r2) continue;
                cles[n++] = (Math.min(Integer.MAX_VALUE, (long) d) << 32) | i;
            }
            if (n > limite) selectionner(cles, n, limite);
            int m = Math.min(n, limite);
            Arrays.sort(cles, 0, m);
            int[] resultat = new int[m];
            for (int i = 0; i < m; i++) resultat[i] = (int) cles[i];
            return resultat;
        }

        /** Place les {@code k} plus petites clés en tête (sélection rapide, O(n) en moyenne). */
        private static void selectionner(long[] a, int n, int k) {
            int gauche = 0, droite = n - 1;
            var hasard = new Random(n * 31L + k);
            while (gauche < droite) {
                long pivot = a[gauche + hasard.nextInt(droite - gauche + 1)];
                int i = gauche, j = droite;
                while (i <= j) {
                    while (a[i] < pivot) i++;
                    while (a[j] > pivot) j--;
                    if (i <= j) {
                        long t = a[i];
                        a[i] = a[j];
                        a[j] = t;
                        i++;
                        j--;
                    }
                }
                if (k - 1 <= j) droite = j;
                else if (k - 1 >= i) gauche = i;
                else return;
            }
        }

        // ----- Modifications (via la mémoire, qui tient le plafond) -----

        private void ajouterPresent(long p, int type) {
            if (presents == positions.length) {
                int n = Math.max(16, presents * 2);
                positions = Arrays.copyOf(positions, n);
                typesBlocs = Arrays.copyOf(typesBlocs, n);
            }
            positions[presents] = p;
            typesBlocs[presents] = type;
            index.ecrire(p, presents);
            sections.ajouter(section(x(p), y(p), z(p)), 1);
            presents++;
            revision++;
        }

        private int retirerPresent(long p) {
            int i = index.retirer(p);
            if (i == TableLong.ABSENT) return -1;
            int type = typesBlocs[i];
            int dernier = presents - 1;
            if (i != dernier) {
                positions[i] = positions[dernier];
                typesBlocs[i] = typesBlocs[dernier];
                index.ecrire(positions[i], i);
            }
            presents--;
            sections.ajouter(section(x(p), y(p), z(p)), -1);
            revision++;
            return type;
        }

        void compterMine(int type, int nombre) {
            if (nombre <= 0) return;
            mines += nombre;
            for (int i = 0; i < minesTypesNombre; i++)
                if (minesTypes[i] == type) {
                    minesNombres[i] += nombre;
                    return;
                }
            if (minesTypesNombre == minesTypes.length) {
                minesTypes = Arrays.copyOf(minesTypes, minesTypesNombre * 2);
                minesNombres = Arrays.copyOf(minesNombres, minesTypesNombre * 2);
            }
            minesTypes[minesTypesNombre] = type;
            minesNombres[minesTypesNombre++] = nombre;
        }
    }

    private final Map<String, Filon> filons = new LinkedHashMap<>();
    private int presents;
    private long revision;
    private int refus;

    public Collection<Filon> filons() {
        return Collections.unmodifiableCollection(filons.values());
    }

    public Filon filon(String id) {
        return id == null ? null : filons.get(id);
    }

    public List<Filon> filonsDans(String dimension) {
        if (dimension == null || dimension.isEmpty()) return List.of();
        return filons.values().stream().filter(f -> f.ancre.dimension().equals(dimension)).toList();
    }

    /** Minerais présents mémorisés, tous gisements confondus. */
    public int presents() {
        return presents;
    }

    public boolean plein() {
        return presents >= MAX_BLOCS;
    }

    /** Minerais trouvés mais non mémorisés faute de place. */
    public int refus() {
        return refus;
    }

    public long revision() {
        return revision;
    }

    /** Ajoute ou actualise un gisement du journal GregTech. */
    public boolean gisement(
            String id,
            Position centre,
            String definition,
            String nom,
            Set<String> composition,
            boolean epuise) {
        Filon f = filons.get(id);
        // Une définition illisible donne une composition vide : garder la composition connue.
        if (f != null && composition.isEmpty() && !f.composition.isEmpty())
            composition = f.composition;
        if (f == null) {
            f = new Filon(this, id, centre);
            filons.put(id, f);
        } else if (f.definition.equals(definition)
                && f.nom.equals(nom)
                && f.composition.equals(composition)
                && f.epuise == epuise) return false;
        f.definition = definition;
        f.nom = nom;
        f.definirComposition(composition);
        f.epuise = epuise;
        f.revision++;
        revision++;
        return true;
    }

    /** Gisement dont la boîte contient la position et qui mémorise déjà ce minerai. */
    public Filon proprietaire(String dimension, long p) {
        for (Filon f : filons.values())
            if (f.presents > 0 && f.ancre.dimension().equals(dimension) && f.index.contient(p))
                return f;
        return null;
    }

    /** Nouveau minerai présent ; refusé quand la mémoire est pleine. */
    public boolean ajouter(Filon f, long p, int type) {
        if (f.index.contient(p)) {
            int i = f.index.lire(p);
            if (f.typesBlocs[i] == type) return false;
            f.typesBlocs[i] = type;
            f.revision++;
            revision++;
            return true;
        }
        if (plein()) {
            refus++;
            return false;
        }
        f.ajouterPresent(p, type);
        presents++;
        revision++;
        return true;
    }

    /** Un minerai connu a disparu : il devient un compteur « miné » de son gisement. */
    public boolean miner(Filon f, long p) {
        int type = f.retirerPresent(p);
        if (type < 0) return false;
        f.compterMine(type, 1);
        presents--;
        revision++;
        return true;
    }

    /** Relevé restauré : présent ou miné, sans dépasser le plafond. */
    public void restaurer(Filon f, long p, int type, boolean mine) {
        if (mine) {
            f.compterMine(type, 1);
        } else if (!f.index.contient(p) && !plein()) {
            f.ajouterPresent(p, type);
            presents++;
        }
        revision++;
    }

    /** Oublie les minerais présents d'un gisement (les compteurs minés restent). */
    public void oublierPresents(Filon f) {
        presents -= f.presents;
        f.presents = 0;
        f.index.vider();
        f.sections.vider();
        f.revision++;
        revision++;
    }

    public void toucher() {
        revision++;
    }

    // ---------- Téléportation ----------

    /**
     * Première position sûre strictement au-dessus du centre, sans déplacement horizontal. maxY est
     * exclusif ; les pieds et la tête doivent rester dans les limites du monde.
     */
    public static Position destinationAuDessus(
            Position cible, int minY, int maxY, Predicate<Position> libre) {
        for (int y = Math.max(cible.y + 1, minY + 1); y < maxY - 1; y++) {
            Position p = new Position(cible.dimension, cible.x, y, cible.z);
            if (libre.test(p)) return p;
        }
        return null;
    }

    /** Point sûr le plus proche dans les colonnes voisines, toujours au-dessus du filon. */
    public static Position destinationProche(
            Position cible, int minY, int maxY, Predicate<Position> libre) {
        Position meilleure = destinationAuDessus(cible, minY, maxY, libre);
        double distance = meilleure == null ? Double.POSITIVE_INFINITY : meilleure.distance2(cible);
        for (int dy = Math.max(1, minY + 1 - cible.y); cible.y + dy < maxY - 1; dy++) {
            if ((double) dy * dy >= distance) break;
            for (int dx = -RAYON_TP; dx <= RAYON_TP; dx++) {
                for (int dz = -RAYON_TP; dz <= RAYON_TP; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (d >= distance) continue;
                    Position p =
                            new Position(cible.dimension, cible.x + dx, cible.y + dy, cible.z + dz);
                    if (libre.test(p)) {
                        meilleure = p;
                        distance = d;
                    }
                }
            }
        }
        return meilleure;
    }

    /**
     * Repli explicite demandé par le joueur : coordonnées non vérifiées, sans modifier les blocs.
     */
    public static Position destinationDirecte(Position cible, int minY, int maxY) {
        if (maxY - minY < 3) return null;
        return new Position(
                cible.dimension,
                cible.x,
                Math.max(minY + 1, Math.min(maxY - 2, cible.y + 1)),
                cible.z);
    }
}
