package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Lecture seule des coffres connus : la mémoire du script client ({@code stockage.json}, coffres
 * ouverts par le joueur) et les relevés du serveur publiés par l'assistant ({@code
 * coffres_connus.json}, tiroirs et coffres jamais ouverts compris, jamais une copie du client).
 * Pour une même position, le relevé le plus récent gagne ; à date égale, celui du serveur. Donne
 * les totaux par objet et les blocs à interface connus. Les fichiers sont lus hors du thread de
 * rendu, seulement quand l’empreinte d’un fichier change ; les variantes NBT ne sont pas
 * distinguées, comme dans les mémoires elles-mêmes.
 */
public final class CoffresConnus {
    private static final Pattern MONDE = Pattern.compile("m1_[0-9a-f]{64}");
    private static final String MEMOIRE = "stockage.json", FUSION = "coffres_connus.json";
    private static final int VERSION = 2, MAX_OBJETS = 32768;
    private static final long INTERVALLE_MS = 3000;
    private static final Set<String> TYPES_COFFRES_DOUBLES =
            Set.of(
                    "minecraft:chest",
                    "minecraft:trapped_chest",
                    "sophisticatedstorage:chest",
                    "sophisticatedstorage:copper_chest",
                    "sophisticatedstorage:iron_chest",
                    "sophisticatedstorage:gold_chest",
                    "sophisticatedstorage:diamond_chest",
                    "sophisticatedstorage:netherite_chest");
    private static final int[][] VOISINS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** Identité, taille et date d'un fichier. */
    public record Empreinte(Object identite, FileTime date, long taille) {}

    private static final Empreinte ABSENT = new Empreinte(null, null, -1);

    /**
     * Totaux mémorisés de tous les coffres connus, toutes dimensions ({@code objets}), et chaque
     * coffre placé ({@code conteneurs}) ; {@code pret} faux tant que la première lecture n'a pas
     * abouti.
     */
    public record Etat(
            Path dossier,
            Empreinte memoire,
            Empreinte fusion,
            Map<String, Long> objets,
            Set<String> machines,
            List<Conteneur> conteneurs,
            long revision,
            boolean pret,
            Map<String, Map<Long, List<Conteneur>>> secteurs) {}

    /** Un coffre retenu, position connue, avec son contenu. */
    public record Conteneur(
            String dimension, long x, long y, long z, String bloc, Map<String, Long> objets) {}

    /** Rayon, en blocs à l'horizontale, des coffres que le plan compte comme à portée. */
    public static final int RAYON_PLAN = 256;

    private static final int BITS_SECTEUR = 6;

    private static Etat vide(Path dossier) {
        return new Etat(dossier, ABSENT, ABSENT, Map.of(), Set.of(), List.of(), 0, false, Map.of());
    }

    private record CleCandidats(long revision, long tampon, String dimension, int x, int z) {}

    private record Candidat(Conteneur conteneur, boolean machine) {}

    private static CleCandidats cleCandidats;
    private static List<Candidat> candidats = List.of();
    private static final BitSet aPortee = new BitSet();
    private static int dernierX, dernierZ;
    private static long revisionProches;
    private static Map<String, Long> proches = Map.of(), enMachines = Map.of();
    private static final ExecutorService LECTEUR =
            Executors.newSingleThreadExecutor(
                    tache -> {
                        var fil = new Thread(tache, "assistantia-coffres-connus");
                        fil.setDaemon(true);
                        fil.setPriority(Thread.MIN_PRIORITY);
                        return fil;
                    });
    private static final AtomicReference<Etat> ETAT = new AtomicReference<>(vide(null));
    private static final AtomicLong REVISIONS = new AtomicLong();
    private static Future<?> lecture;
    private static long prochaine;
    private static Object connexion;
    private static boolean erreurSignalee;

    private CoffresConnus() {}

    public static Etat etat() {
        return ETAT.get();
    }

    /** Révision des totaux à portée, coffres et machines à outils. */
    public static long revisionProches() {
        actualiserProches();
        return revisionProches;
    }

    /**
     * Totaux des coffres que le plan peut compter comme à récupérer, dans la dimension et à portée
     * du joueur. Les coffres connus à distance restent dans la mémoire générale.
     */
    public static Map<String, Long> proches() {
        actualiserProches();
        return proches;
    }

    /**
     * Ce qui est installé dans les machines à outils à portée (inscribers d'AE2 : presses), relevé
     * par le serveur.
     */
    public static Map<String, Long> enMachines() {
        actualiserProches();
        return enMachines;
    }

    private static void actualiserProches() {
        var etat = ETAT.get();
        var mc = Minecraft.getInstance();
        if (!etat.pret() || mc.player == null || mc.level == null) {
            if (cleCandidats != null) {
                cleCandidats = null;
                candidats = List.of();
                aPortee.clear();
                retenirProches(Map.of(), Map.of());
            }
            return;
        }
        var pos = mc.player.blockPosition();
        int x = pos.getX(), z = pos.getZ();
        String dimension = mc.level.dimension().location().toString();
        var cle =
                new CleCandidats(
                        etat.revision(),
                        CoffreTampon.revision(),
                        dimension,
                        x >> BITS_SECTEUR,
                        z >> BITS_SECTEUR);
        boolean nouveauxCandidats = !cle.equals(cleCandidats);
        if (!nouveauxCandidats && dernierX == x && dernierZ == z) return;
        dernierX = x;
        dernierZ = z;
        if (nouveauxCandidats) {
            cleCandidats = cle;
            candidats = candidats(etat.secteurs().get(dimension), cle.x(), cle.z());
            aPortee.clear();
        }
        boolean nouvellesPositions = nouveauxCandidats;
        for (int i = 0; i < candidats.size(); i++) {
            boolean dansRayon = dansRayon(candidats.get(i).conteneur(), x, z, RAYON_PLAN);
            if (dansRayon == aPortee.get(i)) continue;
            aPortee.set(i, dansRayon);
            nouvellesPositions = true;
        }
        if (!nouvellesPositions) return;
        Map<String, Long> coffres = new HashMap<>(), machines = new HashMap<>();
        for (int i = aPortee.nextSetBit(0); i >= 0; i = aPortee.nextSetBit(i + 1)) {
            var candidat = candidats.get(i);
            compter(candidat.machine() ? machines : coffres, candidat.conteneur());
        }
        retenirProches(coffres, machines);
    }

    private static List<Candidat> candidats(Map<Long, List<Conteneur>> secteurs, int x, int z) {
        if (secteurs == null) return List.of();
        List<Candidat> resultats = new ArrayList<>();
        int marge = (RAYON_PLAN + (1 << BITS_SECTEUR) - 1) >> BITS_SECTEUR;
        // Garder tous les secteurs pouvant intersecter le rayon pour un joueur dans ce secteur.
        for (int sx = x - marge; sx <= x + marge; sx++)
            for (int sz = z - marge; sz <= z + marge; sz++) {
                var secteur = secteurs.get(cleSecteur(sx, sz));
                if (secteur == null) continue;
                for (var c : secteur) {
                    boolean machine = TraitsReperes.machineOutils(c.bloc());
                    if (machine
                            || !CoffreTampon.exclu(c.dimension(), c.x(), c.y(), c.z())
                                    && !CoffreTampon.selectionne(
                                            c.dimension(), c.x(), c.y(), c.z()))
                        resultats.add(new Candidat(c, machine));
                }
            }
        return resultats;
    }

    private static void retenirProches(Map<String, Long> coffres, Map<String, Long> machines) {
        boolean coffresModifies = !proches.equals(coffres),
                machinesModifiees = !enMachines.equals(machines);
        if (coffresModifies) proches = Map.copyOf(coffres);
        if (machinesModifiees) enMachines = Map.copyOf(machines);
        if (coffresModifies || machinesModifiees) revisionProches++;
    }

    private static long cleSecteur(int x, int z) {
        return (long) x << 32 | Integer.toUnsignedLong(z);
    }

    private static Map<String, Map<Long, List<Conteneur>>> indexer(List<Conteneur> conteneurs) {
        Map<String, Map<Long, List<Conteneur>>> dimensions = new HashMap<>();
        for (var c : conteneurs) {
            if (c.bloc().startsWith("lootr:") || c.x() != (int) c.x() || c.z() != (int) c.z())
                continue;
            long cle = cleSecteur((int) c.x() >> BITS_SECTEUR, (int) c.z() >> BITS_SECTEUR);
            dimensions
                    .computeIfAbsent(c.dimension(), d -> new HashMap<>())
                    .computeIfAbsent(cle, k -> new ArrayList<>())
                    .add(c);
        }
        dimensions.replaceAll(
                (dimension, secteurs) -> {
                    secteurs.replaceAll(
                            (cle, conteneursDuSecteur) -> List.copyOf(conteneursDuSecteur));
                    return Map.copyOf(secteurs);
                });
        return Map.copyOf(dimensions);
    }

    private static boolean dansRayon(Conteneur c, long x, long z, int rayon) {
        double dx = (double) c.x() - x, dz = (double) c.z() - z;
        return dx * dx + dz * dz <= (double) rayon * rayon;
    }

    private static void compter(Map<String, Long> totaux, Conteneur c) {
        c.objets().forEach((k, n) -> totaux.merge(k, n, (a, b) -> Math.min(1_000_000_000L, a + b)));
    }

    /** Calcul pur de {@link #proches()} (coffres). */
    static Map<String, Long> proches(
            List<Conteneur> conteneurs, String dimension, long x, long z, int rayon) {
        return proches(conteneurs, dimension, x, z, rayon, false);
    }

    /**
     * Calcul pur : coffres à portée, ou, avec {@code machines}, contenu des machines à outils à
     * portée ({@link TraitsReperes#machineOutils}), qui ne comptent jamais comme des coffres.
     */
    static Map<String, Long> proches(
            List<Conteneur> conteneurs,
            String dimension,
            long x,
            long z,
            int rayon,
            boolean machines) {
        Map<String, Long> totaux = new HashMap<>();
        for (var c : conteneurs) {
            if (!c.dimension().equals(dimension) || c.bloc().startsWith("lootr:")) continue;
            if (TraitsReperes.machineOutils(c.bloc()) != machines) continue;
            if (dansRayon(c, x, z, rayon)) compter(totaux, c);
        }
        return Map.copyOf(totaux);
    }

    /** Appelé à chaque tick client : programme au plus une lecture toutes les trois secondes. */
    public static void tick() {
        var mc = Minecraft.getInstance();
        boolean enJeu = mc.level != null && mc.player != null;
        var lien = enJeu ? mc.getConnection() : null;
        Path dossier = enJeu ? dossier() : null;
        if (connexion != lien || !Objects.equals(dossier, ETAT.get().dossier())) {
            connexion = lien;
            // Un état vide neuf sert de génération. Un lecteur de l'ancienne connexion ne peut
            // pas publier, même si les deux générations n'ont encore chargé aucun relevé.
            ETAT.set(vide(dossier));
            prochaine = 0;
        }
        if (dossier == null) return;
        long maintenant = System.currentTimeMillis();
        if (maintenant < prochaine || lecture != null && !lecture.isDone()) return;
        prochaine = maintenant + INTERVALLE_MS;
        var precedent = ETAT.get();
        try {
            lecture = LECTEUR.submit(() -> lire(dossier, precedent));
        } catch (RuntimeException refus) {
            lecture = null;
        }
    }

    /** Dossier des deux fichiers, celui du monde quand l'isolation est active. */
    private static Path dossier() {
        Path dossier = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
        if (!IdentiteMonde.isolation()) return dossier;
        String monde = IdentiteMonde.monde();
        if (!MONDE.matcher(monde).matches()) return null;
        return dossier.resolve("mondes").resolve(monde);
    }

    static void lire(Path dossier, Etat precedent) {
        if (ETAT.get() != precedent) return;
        try {
            Path memoire = dossier.resolve(MEMOIRE), fusion = dossier.resolve(FUSION);
            var empreinteMemoire = empreinte(memoire);
            var empreinteFusion = empreinte(fusion);
            if (precedent.pret()
                    && dossier.equals(precedent.dossier())
                    && empreinteMemoire.equals(precedent.memoire())
                    && empreinteFusion.equals(precedent.fusion())) return;
            // Un fichier absent vaut une mémoire vide (assistant arrêté : la mémoire seule, comme
            // avant) ; un fichier illisible fait garder tout le dernier relevé valable.
            var lu =
                    analyser(
                            ABSENT.equals(empreinteMemoire) ? null : json(memoire),
                            ABSENT.equals(empreinteFusion) ? null : json(fusion));
            if (!empreinteMemoire.equals(empreinte(memoire))
                    || !empreinteFusion.equals(empreinte(fusion))) return;
            // Contenu inchangé (dates d'observation rafraîchies seulement) : même révision, pour
            // ne pas recalculer le plan de l'onglet Recette à chaque relevé du serveur.
            boolean memesConteneurs = lu.conteneurs().equals(precedent.conteneurs());
            long revision =
                    precedent.pret()
                                    && lu.objets().equals(precedent.objets())
                                    && lu.machines().equals(precedent.machines())
                                    && memesConteneurs
                            ? precedent.revision()
                            : REVISIONS.incrementAndGet();
            publier(
                    new Etat(
                            dossier,
                            empreinteMemoire,
                            empreinteFusion,
                            lu.objets(),
                            lu.machines(),
                            lu.conteneurs(),
                            revision,
                            true,
                            memesConteneurs ? precedent.secteurs() : indexer(lu.conteneurs())),
                    precedent);
        } catch (IOException | RuntimeException illisible) {
            // Fichier en cours d'écriture ou disparu entre-temps : garder le dernier relevé
            // valable.
            if (!erreurSignalee) {
                LogUtils.getLogger().debug("Assistant : mémoire des coffres illisible", illisible);
                erreurSignalee = true;
            }
        }
    }

    private static Empreinte empreinte(Path fichier) throws IOException {
        try {
            var attributs =
                    Files.readAttributes(
                            fichier, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributs.isRegularFile()
                    ? new Empreinte(
                            attributs.fileKey(), attributs.lastModifiedTime(), attributs.size())
                    : ABSENT;
        } catch (NoSuchFileException absent) {
            return ABSENT;
        }
    }

    private static JsonObject json(Path fichier) throws IOException {
        return JsonParser.parseString(Files.readString(fichier, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void publier(Etat suivant, Etat precedent) {
        // Une connexion changée pendant la lecture a remis l'état à zéro : ne pas le recouvrir.
        ETAT.compareAndSet(precedent, suivant);
    }

    record Lecture(Map<String, Long> objets, Set<String> machines, List<Conteneur> conteneurs) {}

    /** Un conteneur observé, avec sa position quand elle est complète ({@code cle} sinon nulle). */
    private record Releve(
            JsonObject json,
            String cle,
            String bloc,
            String dimension,
            long x,
            long y,
            long z,
            long t,
            boolean grand,
            List<Long> jumeau,
            boolean aliasStock,
            boolean fusion) {}

    /** Analyse pure de la seule mémoire du client. */
    static Lecture analyser(JsonObject memoire) {
        return analyser(memoire, null);
    }

    /**
     * Analyse pure des deux fichiers (l'un ou l'autre peut manquer) : un relevé par position, le
     * plus récent, puis les deux moitiés d'un grand coffre comptées une seule fois.
     */
    static Lecture analyser(JsonObject memoire, JsonObject fusion) {
        Map<String, Releve> parPosition = new LinkedHashMap<>();
        List<Releve> releves = new ArrayList<>();
        ajouter(memoire, false, parPosition, releves);
        ajouter(fusion, true, parPosition, releves);
        releves.addAll(sansJumeaux(new ArrayList<>(parPosition.values())));
        Map<String, Long> objets = new HashMap<>();
        List<Conteneur> conteneurs = new ArrayList<>();
        for (var r : releves) {
            Map<String, Long> contenu = new HashMap<>();
            for (JsonElement o : tableau(r.json(), "objets")) {
                if (!o.isJsonObject() || objets.size() >= MAX_OBJETS) continue;
                String item = chaine(o.getAsJsonObject(), "item");
                long n = entier(o.getAsJsonObject(), "n");
                if (!item.isEmpty() && n > 0) {
                    objets.merge(item, n, (x, y) -> Math.min(1_000_000_000L, x + y));
                    contenu.merge(item, n, (x, y) -> Math.min(1_000_000_000L, x + y));
                }
            }
            if (r.cle() != null && !contenu.isEmpty())
                conteneurs.add(
                        new Conteneur(
                                r.dimension(), r.x(), r.y(), r.z(), r.bloc(), Map.copyOf(contenu)));
        }
        Set<String> machines = new HashSet<>();
        for (var json : new JsonObject[] {memoire, fusion}) {
            if (json == null) continue;
            for (JsonElement e : tableau(json, "machines"))
                if (e.isJsonObject() && entier(e.getAsJsonObject(), "v") == VERSION) {
                    String bloc = chaine(e.getAsJsonObject(), "bloc");
                    if (!bloc.isEmpty()) machines.add(bloc);
                }
        }
        return new Lecture(Map.copyOf(objets), Set.copyOf(machines), List.copyOf(conteneurs));
    }

    /**
     * Garde, pour chaque position, le relevé prioritaire ; une position incomplète reste à part.
     */
    private static void ajouter(
            JsonObject json,
            boolean fusion,
            Map<String, Releve> parPosition,
            List<Releve> sansPosition) {
        if (json == null) return;
        for (JsonElement e : tableau(json, "conteneurs")) {
            if (!e.isJsonObject() || entier(e.getAsJsonObject(), "v") != VERSION) continue;
            var r = releve(e.getAsJsonObject(), fusion);
            if (r.cle() == null) {
                sansPosition.add(r); // comptée telle quelle, sans fusion ni jumeau
                continue;
            }
            var ancien = parPosition.get(r.cle());
            if (ancien == null || prioritaire(r, ancien)) parPosition.put(r.cle(), r);
        }
    }

    /** Plus récent, ou de même date mais venant de la fusion de l'assistant. */
    private static boolean prioritaire(Releve r, Releve autre) {
        return r.t() > autre.t() || r.t() == autre.t() && r.fusion() && !autre.fusion();
    }

    private static Releve releve(JsonObject json, boolean fusion) {
        String dimension = chaine(json, "dimension"), bloc = chaine(json, "bloc");
        boolean complete =
                !dimension.isEmpty() && nombre(json, "x") && nombre(json, "y") && nombre(json, "z");
        long x = entier(json, "x"), y = entier(json, "y"), z = entier(json, "z");
        return new Releve(
                json,
                complete ? dimension + "|" + x + "|" + y + "|" + z : null,
                bloc,
                dimension,
                x,
                y,
                z,
                entier(json, "t"),
                chaine(json, "nom").startsWith("Large"),
                complete && TYPES_COFFRES_DOUBLES.contains(bloc) ? jumeau(json, x, z) : null,
                json.has("alias_stock")
                        && json.get("alias_stock").isJsonPrimitive()
                        && json.get("alias_stock").getAsJsonPrimitive().isBoolean()
                        && json.get("alias_stock").getAsBoolean(),
                fusion);
    }

    /**
     * Les nouveaux relevés contiennent les coordonnées de l'autre moitié, ou une liste vide pour un
     * coffre simple. Les anciens relevés ne s'apparient que lorsqu'une seule autre moitié "Large…"
     * est plausible de chaque côté.
     */
    private static List<Releve> sansJumeaux(List<Releve> releves) {
        releves.sort(
                Comparator.comparing(Releve::aliasStock)
                        .thenComparing(Comparator.comparingLong(Releve::t).reversed())
                        .thenComparing(r -> !r.fusion())); // tri stable : ordre des fichiers sinon
        Map<String, Releve> places = new HashMap<>();
        for (var r : releves)
            if (TYPES_COFFRES_DOUBLES.contains(r.bloc())) places.put(cleBloc(r, 0, 0), r);
        Map<String, Releve> candidats = new HashMap<>();
        Map<String, Integer> demandes = new HashMap<>();
        for (var r : releves) {
            if (r.jumeau() == null || r.jumeau().isEmpty()) continue;
            var voisin =
                    places.get(cleBloc(r, r.jumeau().get(0) - r.x(), r.jumeau().get(1) - r.z()));
            if (voisin != null
                    && (voisin.jumeau() == null || voisin.jumeau().equals(List.of(r.x(), r.z())))) {
                candidats.put(r.cle(), voisin);
                demandes.merge(voisin.cle(), 1, Integer::sum);
            }
        }
        Map<String, String> paires = new HashMap<>();
        for (var r : releves) {
            var voisin = candidats.get(r.cle());
            if (voisin != null && demandes.get(voisin.cle()) == 1) apparier(paires, r, voisin);
        }
        for (var r : releves) {
            var voisin = ancienJumeau(r, places);
            if (voisin != null && ancienJumeau(voisin, places) == r) apparier(paires, r, voisin);
        }
        var masques = new HashSet<String>();
        List<Releve> resultat = new ArrayList<>();
        for (var r : releves) {
            if (masques.contains(r.cle())) continue;
            resultat.add(r);
            String autre = paires.get(r.cle());
            if (autre != null) masques.add(autre);
        }
        return resultat;
    }

    private static void apparier(Map<String, String> paires, Releve a, Releve b) {
        if (paires.containsKey(a.cle()) || paires.containsKey(b.cle())) return;
        paires.put(a.cle(), b.cle());
        paires.put(b.cle(), a.cle());
    }

    private static Releve ancienJumeau(Releve r, Map<String, Releve> places) {
        if (r.jumeau() != null || !r.grand() || !TYPES_COFFRES_DOUBLES.contains(r.bloc()))
            return null;
        Releve candidat = null;
        for (int[] d : VOISINS) {
            var voisin = places.get(cleBloc(r, d[0], d[1]));
            if (voisin == null || voisin.jumeau() != null || !voisin.grand()) continue;
            if (candidat != null) return null;
            candidat = voisin;
        }
        return candidat;
    }

    private static List<Long> jumeau(JsonObject objet, long x, long z) {
        var valeur = objet.get("jumeau");
        if (valeur == null || !valeur.isJsonArray()) return null;
        var paire = valeur.getAsJsonArray();
        if (paire.isEmpty()) return List.of();
        if (paire.size() != 2 || !coordonnee(paire.get(0)) || !coordonnee(paire.get(1)))
            return null;
        long vx = paire.get(0).getAsLong(), vz = paire.get(1).getAsLong();
        return Math.abs(vx - x) + Math.abs(vz - z) == 1 ? List.of(vx, vz) : null;
    }

    private static boolean coordonnee(JsonElement valeur) {
        if (!valeur.isJsonPrimitive() || !valeur.getAsJsonPrimitive().isNumber()) return false;
        double n = valeur.getAsDouble();
        return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE && n == Math.rint(n);
    }

    private static String cleBloc(Releve r, long dx, long dz) {
        return r.bloc()
                + "|"
                + r.dimension()
                + "|"
                + (r.x() + dx)
                + "|"
                + r.y()
                + "|"
                + (r.z() + dz);
    }

    private static Iterable<JsonElement> tableau(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonArray() ? valeur.getAsJsonArray() : List.of();
    }

    private static String chaine(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonPrimitive() ? valeur.getAsString() : "";
    }

    private static boolean nombre(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null
                && valeur.isJsonPrimitive()
                && valeur.getAsJsonPrimitive().isNumber()
                && Double.isFinite(valeur.getAsDouble());
    }

    private static long entier(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        try {
            return valeur != null && valeur.isJsonPrimitive() ? (long) valeur.getAsDouble() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
