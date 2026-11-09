package fr.assistantia;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonWriter;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Remplacements atomiques des fichiers d'échange sur un fil d'écriture : le thread client ne dort
 * jamais et n'attend jamais le disque. Pour un même fichier, seul le contenu le plus récent compte
 * et un contenu plus ancien ne passe jamais après lui. Quand Windows refuse le remplacement parce
 * que l'assistant lit encore le fichier, le fil réessaie plus tard, tant que la demande reste
 * valable (45 s, comme côté Python).
 */
public final class Ecritures {
    /** Durée pendant laquelle un remplacement refusé est retenté. */
    static final long VALIDITE_MS = 45_000;

    private static final long DELAI_MAX_MS = 250, DELAI_MAX_DURABLE_MS = 1000;

    /** Remplace la cible par le fichier temporaire (déplacement atomique en production). */
    @FunctionalInterface
    interface Remplacement {
        void remplacer(Path temporaire, Path cible) throws IOException;
    }

    /** Force le fichier temporaire sur le disque avant le remplacement (fsync en production). */
    @FunctionalInterface
    interface Synchronisation {
        void forcer(Path temporaire) throws IOException;
    }

    /** Contenu d'un fichier, produit une seule fois : sur le fil d'écriture s'il est différé. */
    public abstract static class Contenu {
        private byte[] octets;
        private boolean produit, durable;

        /** Les octets à écrire, ou null pour ne rien écrire (relevé devenu invalide). */
        protected abstract byte[] produire() throws IOException;

        /** Sur le fil d'écriture, avant tout fichier temporaire : prépare et contrôle la cible. */
        protected void preparer() throws IOException {}

        /** Appelé sur le fil d'écriture une fois le fichier remplacé. */
        protected void ecrit() {}

        /**
         * Donnée du joueur : forcée sur le disque avant le remplacement, et retentée après toute
         * erreur jusqu'à ce qu'un contenu plus récent la remplace (jamais abandonnée).
         */
        public final Contenu durable() {
            durable = true;
            return this;
        }

        final boolean estDurable() {
            return durable;
        }

        final synchronized byte[] octets() throws IOException {
            if (!produit) {
                octets = produire();
                produit = true;
            }
            return octets;
        }
    }

    private static final class Tache {
        final Path cible;
        Contenu contenu; // dernier contenu demandé, pas encore écrit
        Contenu enCours; // contenu en train d'être écrit (lu par enAttente)
        long echeance; // System.nanoTime() au-delà duquel un refus n'est plus retenté
        int refus;

        Tache(Path cible) {
            this.cible = cible;
        }
    }

    private static final com.google.gson.Gson GSON =
            new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final Ecritures INSTANCE =
            new Ecritures(
                    Ecritures::deplacer,
                    Ecritures::forcer,
                    fil("assistant-ecritures"),
                    VALIDITE_MS);

    static {
        try {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(() -> INSTANCE.vider(2000), "assistant-ecritures-fin"));
        } catch (IllegalStateException | SecurityException ignored) {
            // Arrêt déjà commencé : rien à garantir de plus.
        }
    }

    private final Object verrou = new Object();
    // Une tâche présente est en cours d'écriture ou programmée sur le fil : jamais les deux.
    private final Map<Path, Tache> taches = new HashMap<>();
    private final Set<Path> signales = new HashSet<>();
    private final Map<Path, String> erreurs = new HashMap<>();
    private final Remplacement remplacement;
    private final Synchronisation synchronisation;
    private final ScheduledExecutorService fil;
    private final long validite;

    Ecritures(Remplacement remplacement, ScheduledExecutorService fil, long validiteMs) {
        this(remplacement, Ecritures::forcer, fil, validiteMs);
    }

    Ecritures(
            Remplacement remplacement,
            Synchronisation synchronisation,
            ScheduledExecutorService fil,
            long validiteMs) {
        this.remplacement = remplacement;
        this.synchronisation = synchronisation;
        this.fil = fil;
        this.validite = TimeUnit.MILLISECONDS.toNanos(validiteMs);
    }

    static ScheduledExecutorService fil(String nom) {
        var executeur =
                new ScheduledThreadPoolExecutor(
                        1,
                        tache -> {
                            Thread thread = new Thread(tache, nom);
                            thread.setDaemon(true);
                            return thread;
                        });
        executeur.setRemoveOnCancelPolicy(true);
        return executeur;
    }

    /** Un texte UTF-8. */
    public static Contenu texte(String texte) {
        return new Contenu() {
            protected byte[] produire() {
                return texte.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /**
     * Un document JSON, sérialisé sur le fil d'écriture ; il ne doit plus être modifié. Indenté par
     * tabulations comme les fichiers que KubeJS écrivait lui-même.
     */
    public static Contenu json(JsonElement json, boolean indente) {
        return new Contenu() {
            protected byte[] produire() throws IOException {
                return serialiser(json, indente).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    static String serialiser(JsonElement json, boolean indente) throws IOException {
        if (!indente) return json.toString();
        StringWriter texte = new StringWriter();
        JsonWriter sortie = new JsonWriter(texte);
        sortie.setIndent("\t");
        GSON.toJson(json, sortie);
        sortie.flush();
        return texte.toString();
    }

    /** Écriture entièrement différée (exports périodiques) : retour immédiat. */
    public static void publier(Path cible, Contenu contenu) {
        INSTANCE.differer(cible, contenu);
    }

    /**
     * Un seul essai immédiat ; si le fichier est tenu par son lecteur, le fil d'écriture prend le
     * relais sans bloquer l'appelant. Faux seulement pour une autre erreur d'écriture.
     */
    public static boolean ecrire(Path cible, Contenu contenu) {
        return INSTANCE.essayer(cible, contenu);
    }

    /** Tests et arrêt du jeu : attend que tout soit écrit (au plus le délai donné). */
    public static boolean attendre(long millisecondes) {
        return INSTANCE.vider(millisecondes);
    }

    /**
     * Le contenu le plus récent encore en file ou en cours d'écriture pour ce fichier, sinon null
     * (le fichier sur le disque est alors à jour) : une relecture voit toujours la dernière
     * écriture.
     */
    static Contenu enAttente(Path cible) {
        return INSTANCE.dernier(cible);
    }

    /** Dernière erreur d'écriture de ce fichier, effacée par la réussite suivante ; null sinon. */
    public static String erreur(Path cible) {
        return INSTANCE.derniereErreur(cible);
    }

    Contenu dernier(Path cible) {
        synchronized (verrou) {
            Tache tache = taches.get(cible);
            if (tache == null) return null;
            return tache.contenu != null ? tache.contenu : tache.enCours;
        }
    }

    String derniereErreur(Path cible) {
        synchronized (verrou) {
            return erreurs.get(cible);
        }
    }

    void differer(Path cible, Contenu contenu) {
        synchronized (verrou) {
            Tache tache = taches.get(cible);
            boolean nouvelle = tache == null;
            if (nouvelle) {
                tache = new Tache(cible);
                taches.put(cible, tache);
            }
            tache.contenu = contenu;
            tache.echeance = echeance();
            // Une tâche existante est déjà programmée ou en cours : elle prendra ce contenu.
            if (nouvelle) programmer(tache, 0);
        }
    }

    boolean essayer(Path cible, Contenu contenu) {
        Tache tache;
        synchronized (verrou) {
            tache = taches.get(cible);
            if (tache != null) {
                // Une écriture de ce fichier attend déjà : passer derrière elle, jamais devant.
                tache.contenu = contenu;
                tache.echeance = echeance();
                return true;
            }
            tache = new Tache(cible); // réservée : le fil ne touche pas à ce fichier entre-temps
            tache.enCours = contenu;
            taches.put(cible, tache);
        }
        IOException echec = essai(cible, contenu);
        boolean refuse = echec != null && (retentable(echec) || contenu.estDurable());
        synchronized (verrou) {
            tache.enCours = null;
            if (refuse && tache.contenu == null) {
                tache.contenu = contenu;
                tache.echeance = echeance();
            }
            tache.refus = refuse ? 1 : 0;
            terminer(tache, refuse, lent(echec));
        }
        if (echec != null && !retentable(echec)) {
            signaler(cible, echec);
            return contenu.estDurable(); // une donnée du joueur reste en file jusqu'à réussite
        }
        return true;
    }

    private void traiter(Tache tache) {
        Contenu contenu;
        synchronized (verrou) {
            contenu = tache.contenu;
            tache.contenu = null;
            if (contenu == null) {
                terminer(tache, false, false);
                return;
            }
            tache.enCours = contenu;
        }
        IOException echec = essai(tache.cible, contenu);
        boolean refuse = echec != null && (retentable(echec) || contenu.estDurable());
        boolean abandon = false;
        synchronized (verrou) {
            tache.enCours = null;
            if (refuse) {
                tache.refus++;
                if (tache.contenu == null) { // rien de plus récent : retenter le même contenu
                    // Une donnée du joueur n'est jamais abandonnée ; une demande l'est après sa
                    // validité.
                    if (contenu.estDurable() || System.nanoTime() - tache.echeance < 0)
                        tache.contenu = contenu;
                    else abandon = true;
                }
            } else tache.refus = 0;
            terminer(tache, refuse, lent(echec));
        }
        if (echec != null && !retentable(echec)) signaler(tache.cible, echec);
        else if (abandon)
            signaler(tache.cible, new IOException("fichier toujours tenu par son lecteur"));
    }

    /**
     * Un essai complet ; null s'il a réussi (ou rien à écrire). La confirmation a lieu avant que la
     * tâche ne soit libérée : une écriture terminée est toujours confirmée.
     */
    private IOException essai(Path cible, Contenu contenu) {
        try {
            if (ecrireMaintenant(cible, contenu)) reussite(cible, contenu);
            return null;
        } catch (IOException e) {
            return e;
        } catch (RuntimeException e) {
            return new IOException(e);
        }
    }

    /** Une vraie erreur (disque, dossier refusé) se retente moins souvent qu'un refus d'accès. */
    private static boolean lent(IOException echec) {
        return echec != null && !retentable(echec);
    }

    /** Sous le verrou : reprogramme la tâche s'il reste un contenu, sinon la retire. */
    private void terminer(Tache tache, boolean refuse, boolean lent) {
        if (tache.contenu != null) {
            long plafond = lent ? DELAI_MAX_DURABLE_MS : DELAI_MAX_MS;
            programmer(tache, refuse ? Math.min(plafond, 5L << Math.min(8, tache.refus - 1)) : 0);
        } else {
            taches.remove(tache.cible);
            verrou.notifyAll();
        }
    }

    private void programmer(Tache tache, long delai) {
        try {
            fil.schedule(() -> traiter(tache), delai, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Fil arrêté (fin du jeu) : la tâche est abandonnée.
            taches.remove(tache.cible);
            verrou.notifyAll();
        }
    }

    boolean vider(long millisecondes) {
        long fin = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millisecondes);
        synchronized (verrou) {
            while (!taches.isEmpty()) {
                long reste = fin - System.nanoTime();
                if (reste <= 0) return false;
                try {
                    verrou.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(reste)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Vrai si le fichier a été remplacé ; faux pour un contenu nul (rien à écrire). */
    private boolean ecrireMaintenant(Path cible, Contenu contenu) throws IOException {
        byte[] octets = contenu.octets();
        if (octets == null) return false;
        contenu.preparer();
        Path dossier = cible.toAbsolutePath().getParent();
        Files.createDirectories(dossier);
        Path temporaire =
                Files.createTempFile(dossier, "." + cible.getFileName().toString() + "-", ".tmp");
        try {
            Files.write(temporaire, octets);
            if (contenu.estDurable()) synchronisation.forcer(temporaire);
            remplacement.remplacer(temporaire, cible);
            temporaire = null;
            return true;
        } finally {
            if (temporaire != null) {
                try {
                    Files.deleteIfExists(temporaire);
                } catch (IOException ignored) {
                    // Le fichier temporaire sera remplacé au prochain essai.
                }
            }
        }
    }

    private static void forcer(Path temporaire) throws IOException {
        try (var canal = FileChannel.open(temporaire, StandardOpenOption.WRITE)) {
            canal.force(true);
        }
    }

    private static void deplacer(Path temporaire, Path cible) throws IOException {
        try {
            Files.move(
                    temporaire,
                    cible,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Refus de Windows tant qu'un lecteur garde le fichier ouvert, ou partage verrouillé. */
    static boolean retentable(IOException e) {
        return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
    }

    private long echeance() {
        return System.nanoTime() + validite;
    }

    private void reussite(Path cible, Contenu contenu) {
        synchronized (verrou) {
            signales.remove(cible);
            erreurs.remove(cible);
        }
        try {
            contenu.ecrit();
        } catch (RuntimeException ignored) {
            // Un suivi d'écriture ne doit jamais arrêter le fil.
        }
    }

    private void signaler(Path cible, IOException erreur) {
        synchronized (verrou) {
            erreurs.put(
                    cible,
                    cible.getFileName()
                            + " non écrit ("
                            + erreur.getClass().getSimpleName()
                            + (erreur.getMessage() == null ? "" : " : " + erreur.getMessage())
                            + ")");
            if (!signales.add(cible)) return; // une seule trace par série d'échecs
        }
        LogUtils.getLogger().warn("Assistant IA : " + cible.getFileName() + " non écrit", erreur);
    }
}
