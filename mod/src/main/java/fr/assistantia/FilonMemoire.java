package fr.assistantia;

import java.util.*;
import java.util.function.Predicate;

/** Modèle local sans dépendance Minecraft : groupes approximatifs, stables après minage. */
public final class FilonMemoire {
    public static final int MAX_BLOCS = 100000, RAYON_GROUPE = 24, LOT_ROTATION = 8192;

    public record Position(String dimension, int x, int y, int z) {
        public String cle() {
            return dimension + "|" + x + "|" + y + "|" + z;
        }

        public double distance2(Position p) {
            if (!dimension.equals(p.dimension)) return Double.POSITIVE_INFINITY;
            return Math.pow((double) x - p.x, 2)
                    + Math.pow((double) y - p.y, 2)
                    + Math.pow((double) z - p.z, 2);
        }
    }

    public static final class Bloc {
        public final Position position;
        public final String filon;
        public String id;
        public boolean retire;
        public long vu;

        Bloc(Position p, String f, String id, boolean retire, long vu) {
            this.position = p;
            this.filon = f;
            this.id = id;
            this.retire = retire;
            this.vu = vu;
        }
    }

    public static final class Filon {
        public final String id;
        public final Position ancre;
        public final List<Bloc> blocs = new ArrayList<>();
        public String definition = "", nom = "";
        public Set<String> composition = Set.of();
        public boolean epuise; // état du journal GregTech
        private boolean epuiseManuel;

        public boolean epuiseManuel() {
            return epuiseManuel;
        }

        public boolean estEpuise() {
            return epuise || epuiseManuel;
        }

        public boolean marquerEpuise(boolean valeur) {
            if (epuiseManuel == valeur) return false;
            epuiseManuel = valeur;
            return true;
        }

        public boolean gregtech() {
            return !definition.isEmpty();
        }

        public boolean visible() {
            return !estEpuise() && (gregtech() || restants() > 0);
        }

        public String quantite() {
            if (epuiseManuel) return "Épuisé (marquage personnel)";
            if (!gregtech()) return restants() + " repérés";
            if (epuise) return "Épuisé (GregTech)";
            return restants() > 0
                    ? restants() + " blocs repérés · total inconnu"
                    : "Aucun bloc repéré · total inconnu";
        }

        private Set<String> typesChoisis; // null : aucun choix validé, tous cochés par défaut

        public boolean typeAffiche(String type) {
            return typesChoisis == null || typesChoisis.contains(type);
        }

        public Set<String> typesChoisis() {
            return typesChoisis == null ? null : Set.copyOf(typesChoisis);
        }

        public void choisirTypes(Collection<String> types) {
            typesChoisis = Set.copyOf(types);
        }

        public List<Bloc> contours(Position joueur) {
            return blocs.stream()
                    .filter(
                            b ->
                                    !b.retire
                                            && typeAffiche(b.id)
                                            && b.position.distance2(joueur) <= 64 * 64)
                    .sorted(Comparator.comparingDouble(b -> b.position.distance2(joueur)))
                    .limit(768)
                    .toList();
        }

        Filon(String id, Position p) {
            this.id = id;
            this.ancre = p;
        }

        public int restants() {
            return (int) blocs.stream().filter(b -> !b.retire).count();
        }

        public long derniereVue() {
            return blocs.stream().mapToLong(b -> b.vu).max().orElse(0);
        }

        // Le bloc le moins récemment contrôlé borne la fraîcheur du compteur restant.
        public long verificationAncienne() {
            return blocs.stream()
                    .filter(b -> !b.retire)
                    .mapToLong(b -> b.vu)
                    .min()
                    .orElseGet(() -> blocs.stream().mapToLong(b -> b.vu).min().orElse(0));
        }

        public String ageReleve(long maintenant) {
            long date = verificationAncienne();
            if (date <= 0) return "date inconnue";
            long minutes = Math.max(0, maintenant - date) / 60000;
            if (minutes == 0) return "moins de 1 min";
            if (minutes < 60) return minutes + " min";
            if (minutes < 1440) return (minutes / 60) + " h";
            return (minutes / 1440) + " j";
        }

        public boolean releveAncien(long maintenant) {
            return verificationAncienne() <= 0 || maintenant - verificationAncienne() >= 600000;
        }

        public boolean arrive(Position joueur) {
            return blocs.stream().anyMatch(b -> !b.retire && b.position.distance2(joueur) <= 9);
        }

        public Map<String, Integer> types() {
            Map<String, Integer> types = new TreeMap<>();
            for (String type : composition) types.put(type, 0);
            for (Bloc b : blocs) types.merge(b.id, b.retire ? 0 : 1, Integer::sum);
            return types;
        }

        /**
         * Uniquement les blocs observés encore présents ; la composition GT sert au repérage, pas
         * aux compteurs.
         */
        public Map<String, Integer> typesReperes() {
            Map<String, Integer> presents = new TreeMap<>();
            for (Bloc b : blocs) if (!b.retire) presents.merge(b.id, 1, Integer::sum);
            return presents;
        }

        public Position destination(Position depuis) {
            return blocs.stream()
                    .filter(b -> !b.retire)
                    .min(Comparator.comparingDouble(b -> b.position.distance2(depuis)))
                    .map(b -> b.position)
                    .orElse(ancre);
        }
    }

    private final boolean gregtechSeulement;

    public FilonMemoire() {
        this(false);
    }

    public FilonMemoire(boolean gregtechSeulement) {
        this.gregtechSeulement = gregtechSeulement;
    }

    public boolean gisement(
            String id,
            Position centre,
            String definition,
            String nom,
            Set<String> composition,
            boolean epuise) {
        Filon f = filons.get(id);
        if (f == null) {
            f = new Filon(id, centre);
            filons.put(id, f);
        } else if (f.definition.equals(definition)
                && f.nom.equals(nom)
                && f.composition.equals(composition)
                && f.epuise == epuise) return false;
        f.definition = definition;
        f.nom = nom;
        f.composition = Set.copyOf(composition);
        f.epuise = epuise;
        return true;
    }

    public int viderReleves() {
        int nombre = blocs.size();
        blocs.clear();
        for (var f : filons.values()) f.blocs.clear();
        filons.values().removeIf(f -> !f.gregtech());
        return nombre;
    }

    public void restaurerReleve(Position p, String id, boolean retire, long vu, String filon) {
        var f = filons.get(filon);
        if (f == null
                || !f.ancre.dimension.equals(p.dimension)
                || plein()
                || blocs.containsKey(p.cle())) return;
        var b = new Bloc(p, filon, id, retire, vu);
        blocs.put(p.cle(), b);
        f.blocs.add(b);
    }

    // Les accès d'observation placent les blocs revus en fin de liste (LRU).
    private final Map<String, Bloc> blocs = new LinkedHashMap<>(16, .75f, true);
    private Position zoneScan;
    private long remplaces;

    public void protegerScan(Position centre) {
        zoneScan = centre;
    }

    public long remplaces() {
        return remplaces;
    }

    private static boolean dansZone(Position p, Position centre) {
        return p.dimension.equals(centre.dimension)
                && Math.abs(p.x - centre.x) <= Scan.RAYON
                && Math.abs(p.y - centre.y) <= Scan.RAYON
                && Math.abs(p.z - centre.z) <= Scan.RAYON;
    }

    /** Rotation par lots, seulement à saturation : minés d'abord, puis anciens hors zone. */
    private int rotation() {
        if (zoneScan == null || !plein()) return 0;
        Set<Bloc> retires = new HashSet<>();
        for (int passe = 0; passe < 2 && retires.size() < LOT_ROTATION; passe++) {
            var it = blocs.values().iterator();
            while (it.hasNext() && retires.size() < LOT_ROTATION) {
                Bloc b = it.next();
                if (passe == 0 ? b.retire : !dansZone(b.position, zoneScan)) {
                    retires.add(b);
                    it.remove();
                }
            }
        }
        // Un seul passage par groupe, pas de retrait quadratique dans les listes.
        if (!retires.isEmpty()) for (Filon f : filons.values()) f.blocs.removeIf(retires::contains);
        remplaces += retires.size();
        return retires.size();
    }

    private final Map<String, Filon> filons = new LinkedHashMap<>();

    public Collection<Bloc> blocs() {
        return Collections.unmodifiableCollection(blocs.values());
    }

    public Collection<Filon> filons() {
        return Collections.unmodifiableCollection(filons.values());
    }

    public Filon filon(String id) {
        return filons.get(id);
    }

    public List<Filon> filonsDans(String dimension) {
        if (dimension == null || dimension.isEmpty()) return List.of();
        return filons.values().stream().filter(f -> f.ancre.dimension().equals(dimension)).toList();
    }

    public boolean plein() {
        return blocs.size() >= MAX_BLOCS;
    }

    /**
     * Ne jamais appeler sur une position non chargée : l'air d'un chunk absent n'est pas du minage.
     */
    public boolean observer(Position p, String id, boolean minerai, long maintenant) {
        Bloc b = blocs.get(p.cle());
        if (b != null) {
            if (gregtechSeulement && minerai && !filons.get(b.filon).composition.contains(id))
                minerai = false;
            boolean change = b.retire == minerai || (minerai && !b.id.equals(id));
            if (!change && maintenant - b.vu < 60000) return false;
            b.retire = !minerai;
            if (minerai) b.id = id;
            b.vu = maintenant;
            return true;
        }
        if (!minerai) return false;
        // Relevé de proximité, pas une preuve d'appartenance géologique au gisement.
        Filon f =
                filons.values().stream()
                        .filter(
                                v ->
                                        gregtechSeulement
                                                ? v.gregtech()
                                                        && v.composition.contains(id)
                                                        && v.ancre.distance2(p) <= 64 * 64
                                                : v.ancre.distance2(p)
                                                        <= RAYON_GROUPE * RAYON_GROUPE)
                        .min(
                                Comparator.<Filon>comparingDouble(v -> v.ancre.distance2(p))
                                        .thenComparing(v -> v.id))
                        .orElse(null);
        if (f == null && gregtechSeulement) return false;
        if (plein() && rotation() == 0) return false;
        if (f == null) {
            f = new Filon(p.cle(), p);
            filons.put(f.id, f);
        }
        b = new Bloc(p, f.id, id, false, maintenant);
        blocs.put(p.cle(), b);
        f.blocs.add(b);
        return true;
    }

    /** Import explicite : pas de doublon et jamais de retour à une observation plus ancienne. */
    public boolean importer(Position p, String id, boolean retire, long vu) {
        Bloc connu = blocs.get(p.cle());
        if (connu != null) {
            if (connu.vu >= vu) return false;
            connu.id = id;
            connu.retire = retire;
            connu.vu = vu;
            return true;
        }
        if (!observer(p, id, true, vu)) return false;
        blocs.get(p.cle()).retire = retire;
        return true;
    }

    public void restaurer(Position p, String id, boolean retire, long vu, Position ancre) {
        if (plein() || blocs.containsKey(p.cle()) || !p.dimension.equals(ancre.dimension)) return;
        Filon f = filons.computeIfAbsent(ancre.cle(), k -> new Filon(k, ancre));
        Bloc b = new Bloc(p, f.id, id, retire, vu);
        blocs.put(p.cle(), b);
        f.blocs.add(b);
    }

    /** Cube parcouru de façon incrémentale, pas de scan complet par image. */
    public static final class Scan {
        public static final int RAYON = 16, COTE = 2 * RAYON + 1, TOTAL = COTE * COTE * COTE;
        private static final int[] ORDRE = ordre();

        private static int[] ordre() {
            int[] codes = new int[TOTAL];
            int n = 0;
            for (int r = 0; r <= RAYON; r++)
                for (int x = -r; x <= r; x++)
                    for (int y = -r; y <= r; y++)
                        for (int z = -r; z <= r; z++)
                            if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) == r)
                                codes[n++] =
                                        x + RAYON + (z + RAYON) * COTE + (y + RAYON) * COTE * COTE;
            return codes;
        }

        private Position centre;
        private int index;
        private boolean actif = true, ponctuel, termine;

        public boolean actif() {
            return actif;
        }

        public boolean ponctuel() {
            return ponctuel;
        }

        public boolean termine() {
            return termine;
        }

        public void reprendrePonctuel() {
            if (ponctuel) index = 0;
        }

        public void lancerPonctuel(Position joueur) {
            centre = joueur;
            index = 0;
            actif = ponctuel = true;
            termine = false;
        }

        public boolean activer(boolean valeur) {
            if (actif == valeur) return false;
            actif = valeur;
            ponctuel = termine = false;
            if (index == TOTAL) index = 0;
            return true;
        }

        public Position centre() {
            return centre;
        }

        public Position suivant(Position joueur) {
            if (!actif) return null; // aucune position visitée, curseur conservé
            if (ponctuel && !centre.dimension.equals(joueur.dimension)) {
                activer(false);
                return null;
            }
            if (centre == null || (!ponctuel && centre.distance2(joueur) > 64)) {
                centre = joueur;
                index = 0;
            }
            int i = ORDRE[index++];
            Position resultat =
                    new Position(
                            centre.dimension,
                            centre.x + i % COTE - RAYON,
                            centre.y + i / (COTE * COTE) - RAYON,
                            centre.z + i / COTE % COTE - RAYON);
            if (index == TOTAL) {
                if (ponctuel) {
                    actif = ponctuel = false;
                    termine = true;
                } else {
                    index = 0;
                    centre = joueur;
                }
            }
            return resultat;
        }

        public int pourcentage() {
            return index * 100 / TOTAL;
        }
    }

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

    public static final int RAYON_TP = 8;

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
