package fr.assistantia;

import java.util.*;
import java.util.function.Predicate;

/** Modèle local sans dépendance Minecraft : groupes approximatifs, stables après minage. */
public final class FilonMemoire {
    public static final int MAX_BLOCS = 20000, RAYON_GROUPE = 24;
    public record Position(String dimension, int x, int y, int z) {
        public String cle() { return dimension + "|" + x + "|" + y + "|" + z; }
        public double distance2(Position p) {
            if (!dimension.equals(p.dimension)) return Double.POSITIVE_INFINITY;
            return Math.pow((double) x - p.x, 2) + Math.pow((double) y - p.y, 2) + Math.pow((double) z - p.z, 2);
        }
    }
    public static final class Bloc {
        public final Position position;
        public final String filon;
        public String id;
        public boolean retire;
        public long vu;
        Bloc(Position p, String f, String id, boolean retire, long vu) {
            this.position = p; this.filon = f; this.id = id; this.retire = retire; this.vu = vu;
        }
    }
    public static final class Filon {
        public final String id;
        public final Position ancre;
        public final List<Bloc> blocs = new ArrayList<>();
        Filon(String id, Position p) { this.id = id; this.ancre = p; }
        public int restants() { return (int) blocs.stream().filter(b -> !b.retire).count(); }
        public long derniereVue() { return blocs.stream().mapToLong(b -> b.vu).max().orElse(0); }
        // Le bloc le moins récemment contrôlé borne la fraîcheur du compteur restant.
        public long verificationAncienne() {
            return blocs.stream().filter(b -> !b.retire).mapToLong(b -> b.vu).min()
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
            for (Bloc b : blocs) types.merge(b.id, b.retire ? 0 : 1, Integer::sum);
            return types;
        }
        public Position destination(Position depuis) {
            return blocs.stream().filter(b -> !b.retire)
                    .min(Comparator.comparingDouble(b -> b.position.distance2(depuis)))
                    .map(b -> b.position).orElse(ancre);
        }
    }
    private final Map<String, Bloc> blocs = new LinkedHashMap<>();
    private final Map<String, Filon> filons = new LinkedHashMap<>();
    public Collection<Bloc> blocs() { return Collections.unmodifiableCollection(blocs.values()); }
    public Collection<Filon> filons() { return Collections.unmodifiableCollection(filons.values()); }
    public Filon filon(String id) { return filons.get(id); }
    public boolean plein() { return blocs.size() >= MAX_BLOCS; }

    /** Ne jamais appeler sur une position non chargée : l'air d'un chunk absent n'est pas du minage. */
    public boolean observer(Position p, String id, boolean minerai, long maintenant) {
        Bloc b = blocs.get(p.cle());
        if (b != null) {
            boolean change = b.retire == minerai || (minerai && !b.id.equals(id));
            if (!change && maintenant - b.vu < 60000) return false;
            b.retire = !minerai;
            if (minerai) b.id = id;
            b.vu = maintenant;
            return true;
        }
        if (!minerai || plein()) return false;
        Filon f = filons.values().stream().filter(v -> v.ancre.distance2(p) <= RAYON_GROUPE * RAYON_GROUPE)
                .min(Comparator.comparingDouble(v -> v.ancre.distance2(p))).orElse(null);
        if (f == null) { f = new Filon(p.cle(), p); filons.put(f.id, f); }
        b = new Bloc(p, f.id, id, false, maintenant);
        blocs.put(p.cle(), b); f.blocs.add(b);
        return true;
    }

    /** Import explicite : pas de doublon et jamais de retour à une observation plus ancienne. */
    public boolean importer(Position p, String id, boolean retire, long vu) {
        Bloc connu = blocs.get(p.cle());
        if (connu != null) {
            if (connu.vu >= vu) return false;
            connu.id = id; connu.retire = retire; connu.vu = vu;
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
        blocs.put(p.cle(), b); f.blocs.add(b);
    }

    /** Cube parcouru de façon incrémentale, pas de scan complet par image. */
    public static final class Scan {
        public static final int RAYON = 16, COTE = 2 * RAYON + 1, TOTAL = COTE * COTE * COTE;
        private static final int[] ORDRE = ordre();
        private static int[] ordre() {
            int[] codes = new int[TOTAL]; int n = 0;
            for (int r = 0; r <= RAYON; r++)
                for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++)
                    if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) == r)
                        codes[n++] = x + RAYON + (z + RAYON) * COTE + (y + RAYON) * COTE * COTE;
            return codes;
        }
        private Position centre;
        private int index;
        private boolean actif = true, ponctuel, termine;
        public boolean actif() { return actif; }
        public boolean ponctuel() { return ponctuel; }
        public boolean termine() { return termine; }
        public void lancerPonctuel(Position joueur) {
            centre = joueur; index = 0; actif = ponctuel = true; termine = false;
        }
        public boolean activer(boolean valeur) {
            if (actif == valeur) return false;
            actif = valeur; ponctuel = termine = false;
            if (index == TOTAL) index = 0;
            return true;
        }
        public Position suivant(Position joueur) {
            if (!actif) return null; // aucune position visitée, curseur conservé
            if (ponctuel && !centre.dimension.equals(joueur.dimension)) { activer(false); return null; }
            if (centre == null || (!ponctuel && centre.distance2(joueur) > 64)) { centre = joueur; index = 0; }
            int i = ORDRE[index++];
            Position resultat = new Position(centre.dimension, centre.x + i % COTE - RAYON,
                    centre.y + i / (COTE * COTE) - RAYON, centre.z + i / COTE % COTE - RAYON);
            if (index == TOTAL) {
                if (ponctuel) { actif = ponctuel = false; termine = true; }
                else { index = 0; centre = joueur; }
            }
            return resultat;
        }
        public int pourcentage() { return index * 100 / TOTAL; }
    }

    /** Ordre stable du plus proche au plus loin ; au plus 17³ positions sont vérifiées. */
    private static final List<int[]> EMPLACEMENTS;
    static {
        List<int[]> positions = new ArrayList<>();
        for (int x = -8; x <= 8; x++) for (int y = -8; y <= 8; y++) for (int z = -8; z <= 8; z++)
            positions.add(new int[]{x, y, z});
        positions.sort(Comparator.comparingInt(p -> p[0]*p[0] + p[1]*p[1] + p[2]*p[2]));
        EMPLACEMENTS = List.copyOf(positions);
    }
    public static Position destinationSure(Position cible, Predicate<Position> libre) {
        for (int[] d : EMPLACEMENTS) {
            Position p = new Position(cible.dimension, cible.x + d[0], cible.y + d[1], cible.z + d[2]);
            if (libre.test(p)) return p;
        }
        return null;
    }
}
