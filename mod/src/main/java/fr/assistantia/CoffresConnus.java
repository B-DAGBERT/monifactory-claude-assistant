package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * rendu, seulement quand la date ou la taille de l'un d'eux change ; les variantes NBT ne sont pas
 * distinguées, comme dans les mémoires elles-mêmes.
 */
public final class CoffresConnus {
    private static final Pattern MONDE = Pattern.compile("m1_[0-9a-f]{64}");
    private static final String MEMOIRE = "stockage.json", FUSION = "coffres_connus.json";
    private static final int VERSION = 2, MAX_OBJETS = 32768;
    private static final long INTERVALLE_MS = 3000;
    private static final int[][] VOISINS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** Date et taille d'un fichier ; {@link #ABSENT} quand il n'existe pas encore. */
    public record Empreinte(long date, long taille) {}

    private static final Empreinte ABSENT = new Empreinte(-1, -1);

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
            boolean pret) {}

    /** Un coffre retenu, position connue, avec son contenu. */
    public record Conteneur(
            String dimension, long x, long z, String bloc, Map<String, Long> objets) {}

    /** Rayon, en blocs à l'horizontale, des coffres que le plan compte comme à portée. */
    public static final int RAYON_PLAN = 256;

    private static final Etat VIDE =
            new Etat(null, ABSENT, ABSENT, Map.of(), Set.of(), List.of(), 0, false);

    private record CleProches(long revision, String zone) {}

    private static CleProches cleProches;
    private static Map<String, Long> proches = Map.of();
    private static final ExecutorService LECTEUR =
            Executors.newSingleThreadExecutor(
                    tache -> {
                        var fil = new Thread(tache, "assistantia-coffres-connus");
                        fil.setDaemon(true);
                        fil.setPriority(Thread.MIN_PRIORITY);
                        return fil;
                    });
    private static final AtomicReference<Etat> ETAT = new AtomicReference<>(VIDE);
    private static final AtomicLong REVISIONS = new AtomicLong();
    private static Future<?> lecture;
    private static long prochaine;
    private static Object connexion;
    private static boolean erreurSignalee;

    private CoffresConnus() {}

    public static Etat etat() {
        return ETAT.get();
    }

    /**
     * Zone du joueur pour {@link #proches()} : dimension et case de 64 blocs. Un changement de zone
     * suffit à refaire un plan qui en dépend.
     */
    public static String zone() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "";
        var pos = mc.player.blockPosition();
        return mc.level.dimension().location() + "|" + (pos.getX() >> 6) + "|" + (pos.getZ() >> 6);
    }

    /**
     * Totaux des coffres que le plan peut compter comme à récupérer : même dimension que le joueur,
     * à moins de {@link #RAYON_PLAN} blocs à l'horizontale, jamais un coffre à butin (Lootr). Les
     * coffres vus en exploration ou dans une autre dimension restent connus (« Introuvable »), mais
     * ne remplacent jamais une fabrication. Calculé au plus une fois par zone et par relevé.
     */
    public static Map<String, Long> proches() {
        var etat = ETAT.get();
        var mc = Minecraft.getInstance();
        if (!etat.pret() || mc.player == null || mc.level == null) return Map.of();
        var cle = new CleProches(etat.revision(), zone());
        if (cle.equals(cleProches)) return proches;
        var pos = mc.player.blockPosition();
        proches =
                proches(
                        etat.conteneurs(),
                        mc.level.dimension().location().toString(),
                        pos.getX(),
                        pos.getZ(),
                        RAYON_PLAN);
        cleProches = cle;
        return proches;
    }

    /** Calcul pur de {@link #proches()}. */
    static Map<String, Long> proches(
            List<Conteneur> conteneurs, String dimension, long x, long z, int rayon) {
        Map<String, Long> totaux = new HashMap<>();
        long r2 = (long) rayon * rayon;
        for (var c : conteneurs) {
            if (!c.dimension().equals(dimension) || c.bloc().startsWith("lootr:")) continue;
            long dx = c.x() - x, dz = c.z() - z;
            if (dx * dx + dz * dz > r2) continue;
            c.objets()
                    .forEach(
                            (k, n) ->
                                    totaux.merge(k, n, (a, b) -> Math.min(1_000_000_000L, a + b)));
        }
        return Map.copyOf(totaux);
    }

    /** Appelé à chaque tick client : programme au plus une lecture toutes les trois secondes. */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (connexion != null || ETAT.get().pret()) ETAT.set(VIDE);
            connexion = null;
            return;
        }
        if (connexion != mc.getConnection()) {
            connexion = mc.getConnection();
            ETAT.set(VIDE);
            prochaine = 0;
        }
        long maintenant = System.currentTimeMillis();
        if (maintenant < prochaine || lecture != null && !lecture.isDone()) return;
        prochaine = maintenant + INTERVALLE_MS;
        Path dossier = dossier();
        if (dossier == null) return;
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
            // Contenu inchangé (dates d'observation rafraîchies seulement) : même révision, pour
            // ne pas recalculer le plan de l'onglet Recette à chaque relevé du serveur.
            long revision =
                    precedent.pret()
                                    && lu.objets().equals(precedent.objets())
                                    && lu.machines().equals(precedent.machines())
                                    && lu.conteneurs().equals(precedent.conteneurs())
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
                            true),
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
        if (!Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS)) return ABSENT;
        return new Empreinte(
                Files.getLastModifiedTime(fichier, LinkOption.NOFOLLOW_LINKS).toMillis(),
                Files.size(fichier));
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
                        new Conteneur(r.dimension(), r.x(), r.z(), r.bloc(), Map.copyOf(contenu)));
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
                fusion);
    }

    /**
     * Un grand coffre occupe deux blocs voisins, mémorisés selon le côté ouvert : nom « Large… »
     * côté client, nom du bloc (ou aucun) côté serveur. Du plus récent au plus ancien, un relevé
     * voisin d'un relevé gardé du même bloc et à la même hauteur est écarté quand l'un des deux
     * porte un nom en « Large… », quel qu'il soit.
     */
    private static List<Releve> sansJumeaux(List<Releve> releves) {
        releves.sort(
                Comparator.comparingLong(Releve::t)
                        .reversed()
                        .thenComparing(r -> !r.fusion())); // tri stable : ordre des fichiers sinon
        Map<String, Releve> gardes = new HashMap<>();
        List<Releve> resultat = new ArrayList<>();
        for (var r : releves) {
            boolean jumeau = false;
            for (int[] d : VOISINS) {
                var voisin = gardes.get(cleBloc(r, d[0], d[1]));
                if (voisin != null && (r.grand() || voisin.grand())) {
                    jumeau = true;
                    break;
                }
            }
            if (jumeau) continue;
            gardes.put(cleBloc(r, 0, 0), r);
            resultat.add(r);
        }
        return resultat;
    }

    private static String cleBloc(Releve r, int dx, int dz) {
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
