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
import java.util.HashMap;
import java.util.HashSet;
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
 * Lecture seule de la mémoire des coffres du script client ({@code stockage.json}) : totaux par
 * objet de tous les coffres connus du monde et blocs à interface déjà ouverts. Le fichier est lu
 * hors du thread de rendu, seulement quand sa date ou sa taille change ; les variantes NBT ne sont
 * pas distinguées, comme dans la mémoire elle-même.
 */
public final class CoffresConnus {
    private static final Pattern MONDE = Pattern.compile("m1_[0-9a-f]{64}");
    private static final int VERSION = 2, MAX_OBJETS = 32768;
    private static final long INTERVALLE_MS = 3000;

    /** Totaux mémorisés ; {@code pret} faux tant que la première lecture n'a pas abouti. */
    public record Etat(
            Path fichier,
            long date,
            long taille,
            Map<String, Long> objets,
            Set<String> machines,
            long revision,
            boolean pret) {}

    private static final Etat VIDE = new Etat(null, -1, -1, Map.of(), Set.of(), 0, false);
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
        Path fichier = fichier();
        if (fichier == null) return;
        var precedent = ETAT.get();
        try {
            lecture = LECTEUR.submit(() -> lire(fichier, precedent));
        } catch (RuntimeException refus) {
            lecture = null;
        }
    }

    private static Path fichier() {
        Path dossier = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
        if (!IdentiteMonde.isolation()) return dossier.resolve("stockage.json");
        String monde = IdentiteMonde.monde();
        if (!MONDE.matcher(monde).matches()) return null;
        return dossier.resolve("mondes").resolve(monde).resolve("stockage.json");
    }

    private static void lire(Path fichier, Etat precedent) {
        try {
            if (!Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS)) {
                // Aucune mémoire encore écrite : un seul état vide, sans nouvelle révision à chaque
                // essai.
                if (precedent.pret()
                        && precedent.date() == -1
                        && fichier.equals(precedent.fichier())) return;
                publier(
                        new Etat(
                                fichier,
                                -1,
                                -1,
                                Map.of(),
                                Set.of(),
                                REVISIONS.incrementAndGet(),
                                true),
                        precedent);
                return;
            }
            long date = Files.getLastModifiedTime(fichier, LinkOption.NOFOLLOW_LINKS).toMillis();
            long taille = Files.size(fichier);
            if (precedent.pret()
                    && fichier.equals(precedent.fichier())
                    && date == precedent.date()
                    && taille == precedent.taille()) return;
            var json =
                    JsonParser.parseString(Files.readString(fichier, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var lu = analyser(json);
            publier(
                    new Etat(
                            fichier,
                            date,
                            taille,
                            lu.objets(),
                            lu.machines(),
                            REVISIONS.incrementAndGet(),
                            true),
                    precedent);
        } catch (IOException | RuntimeException illisible) {
            // Fichier en cours d'écriture ou absent : garder le dernier relevé valable.
            if (!erreurSignalee) {
                LogUtils.getLogger().debug("Assistant : mémoire des coffres illisible", illisible);
                erreurSignalee = true;
            }
        }
    }

    private static void publier(Etat suivant, Etat precedent) {
        // Une connexion changée pendant la lecture a remis l'état à zéro : ne pas le recouvrir.
        ETAT.compareAndSet(precedent, suivant);
    }

    record Lecture(Map<String, Long> objets, Set<String> machines) {}

    /** Analyse pure du fichier : grands coffres dédoublonnés comme le fait l'assistant. */
    static Lecture analyser(JsonObject json) {
        List<JsonObject> conteneurs = new ArrayList<>();
        for (JsonElement e : tableau(json, "conteneurs"))
            if (e.isJsonObject() && entier(e.getAsJsonObject(), "v") == VERSION)
                conteneurs.add(e.getAsJsonObject());
        Set<JsonObject> ignores = new HashSet<>();
        for (int i = 0; i < conteneurs.size(); i++)
            for (int j = i + 1; j < conteneurs.size(); j++) {
                var a = conteneurs.get(i);
                var b = conteneurs.get(j);
                if (jumeaux(a, b)) ignores.add(entier(a, "t") >= entier(b, "t") ? b : a);
            }
        Map<String, Long> objets = new HashMap<>();
        for (var c : conteneurs) {
            if (ignores.contains(c)) continue;
            for (JsonElement o : tableau(c, "objets")) {
                if (!o.isJsonObject() || objets.size() >= MAX_OBJETS) continue;
                String item = chaine(o.getAsJsonObject(), "item");
                long n = entier(o.getAsJsonObject(), "n");
                if (!item.isEmpty() && n > 0)
                    objets.merge(item, n, (x, y) -> Math.min(1_000_000_000L, x + y));
            }
        }
        Set<String> machines = new HashSet<>();
        for (JsonElement e : tableau(json, "machines"))
            if (e.isJsonObject() && entier(e.getAsJsonObject(), "v") == VERSION) {
                String bloc = chaine(e.getAsJsonObject(), "bloc");
                if (!bloc.isEmpty()) machines.add(bloc);
            }
        return new Lecture(Map.copyOf(objets), Set.copyOf(machines));
    }

    private static boolean jumeaux(JsonObject a, JsonObject b) {
        return chaine(a, "nom").startsWith("Large")
                && chaine(a, "bloc").equals(chaine(b, "bloc"))
                && chaine(a, "dimension").equals(chaine(b, "dimension"))
                && entier(a, "y") == entier(b, "y")
                && Math.abs(entier(a, "x") - entier(b, "x"))
                                + Math.abs(entier(a, "z") - entier(b, "z"))
                        == 1;
    }

    private static Iterable<JsonElement> tableau(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonArray() ? valeur.getAsJsonArray() : List.of();
    }

    private static String chaine(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonPrimitive() ? valeur.getAsString() : "";
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
