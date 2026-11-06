package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Export V2 volontaire. Les objets Minecraft sont lus sur le thread client seulement. */
public final class CatalogueProduction {
    private static final Path DIRECTORY = FMLPaths.GAMEDIR.get().resolve("kubejs/assistant");
    private static final Path CATALOGUE = DIRECTORY.resolve("catalogue_production.json");
    private static final Path STATUS = DIRECTORY.resolve("catalogue_production_etat.json");
    private static final Path PROOF_REQUEST =
            DIRECTORY.resolve("production_v2_preuve_demande.json");
    private static final Path PROOF_REPLY = DIRECTORY.resolve("production_v2_preuve_reponse.json");
    private static final List<String> PROOF_CONTEXT =
            List.of(
                    "monde_actif",
                    "session_monde",
                    "dimension_monde",
                    "source",
                    "generation",
                    "sha256");
    private static final List<String> CATALOGUE_KEYS = List.of("source", "generation", "sha256");
    private static final Object LOCK = new Object();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final ScheduledExecutorService WRITER =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "assistant-catalogue-production");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static JsonObject state = empty("Aucun catalogue demandé.");
    private static Object capture, connection;
    private static String context = "";
    private static long request, generation;
    private static boolean writing;
    private static String lastProof = "";
    private static BasicFileAttributes lastProofFile;

    private CatalogueProduction() {}

    /** EMI appelle ce point au début de chaque nouvelle génération de registres. */
    public static void rechargement() {
        synchronized (LOCK) {
            if (capture != null)
                echec("Les recettes ou la sélection EMI ont changé. Relance le calcul.");
        }
    }

    public static JsonObject etat() {
        synchronized (LOCK) {
            if (capture != null && Minecraft.getInstance().isSameThread() && !valide())
                echec("L’arbre EMI ou le monde a changé. Relance le calcul.");
            return state.deepCopy();
        }
    }

    static boolean apercuCourant(JsonObject message) {
        if (!message.has("catalogue_production")) return true;
        var metadata = message.get("catalogue_production");
        if (!metadata.isJsonObject()) return false;
        synchronized (LOCK) {
            if (capture == null || !"pret".equals(chaine(state, "etat")) || !valide()) return false;
            for (String key : CATALOGUE_KEYS)
                if (!state.get(key).equals(metadata.getAsJsonObject().get(key))) return false;
            return true;
        }
    }

    public static boolean demander() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread())
            throw new IllegalStateException("Demande catalogue hors thread client");
        synchronized (LOCK) {
            if (capture != null && contexteCourant().equals(context) && valide()) return true;
            request = EPOCH.incrementAndGet();
            capture = null;
            writing = false;
            context = contexteCourant();
            connection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
            if (context.isEmpty()) {
                echec("Monde ou session non attesté. Entre dans ta partie.");
                return false;
            }
            try {
                capture =
                        Class.forName("fr.assistantia.compat.emi.CatalogueEmi")
                                .getMethod("capturer")
                                .invoke(null);
                state = (JsonObject) call("metadata");
                annoter(state);
                state.addProperty("etat", "chargement");
                state.addProperty("message", "Lecture de l'arbre EMI sélectionné…");
                publierEtat(state.deepCopy(), request);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                echec(message(ex));
                return false;
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        synchronized (LOCK) {
            if (capture == null) return;
            if (!valide()) {
                echec("L'arbre EMI ou le monde a changé. Relance le calcul.");
                return;
            }
            if ("pret".equals(chaine(state, "etat"))) {
                repondrePreuve();
                return;
            }
            if (writing) return;
            try {
                if (!(boolean) call("avancer")) {
                    JsonObject progress = (JsonObject) call("metadata");
                    state.add("recettes_lues", progress.get("recettes_lues"));
                    return;
                }
                generation = Math.max(System.currentTimeMillis(), generation + 1);
                JsonObject payload =
                        (JsonObject)
                                capture.getClass()
                                        .getMethod("catalogue", long.class)
                                        .invoke(capture, generation);
                JsonObject ready = (JsonObject) call("selection");
                annoter(ready);
                ready.addProperty("generation", generation);
                ready.addProperty("source", "emi/" + context + "/" + request);
                ready.addProperty("etat", "pret");
                ready.addProperty("message", "Recettes de l'arbre EMI prêtes.");
                writing = true;
                long capturedRequest = request;
                WRITER.execute(() -> publier(payload, ready, capturedRequest));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                echec(message(ex));
            }
        }
    }

    /** Called on the client tick only after validating the live EMI tree and connection. */
    private static void repondrePreuve() {
        try {
            if (Files.isSymbolicLink(DIRECTORY)) return;
            var attrs =
                    Files.readAttributes(
                            PROOF_REQUEST, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() <= 0 || attrs.size() > 4096) return;
            if (lastProofFile != null
                    && lastProofFile.size() == attrs.size()
                    && lastProofFile.lastModifiedTime().equals(attrs.lastModifiedTime())
                    && java.util.Objects.equals(lastProofFile.fileKey(), attrs.fileKey())) return;
            byte[] raw;
            try (var input = Files.newInputStream(PROOF_REQUEST, LinkOption.NOFOLLOW_LINKS)) {
                raw = input.readNBytes(4097);
            }
            if (raw.length != attrs.size()) return;
            lastProofFile = attrs;
            var proof =
                    JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            String nonce = chaine(proof, "nonce");
            if (!nonce.matches("[0-9a-f]{32}")
                    || nonce.equals(lastProof)
                    || proof.size() != PROOF_CONTEXT.size() + 2
                    || !new com.google.gson.JsonPrimitive(1).equals(proof.get("v"))) return;
            for (String key : PROOF_CONTEXT) if (!state.get(key).equals(proof.get(key))) return;
            lastProof = nonce;
            long token = request;
            WRITER.execute(() -> ecrirePreuve(proof, token));
        } catch (Exception ignored) {
            // A missing, locked or malformed challenge receives no positive attestation.
        }
    }

    private static void ecrirePreuve(JsonObject proof, long token) {
        if (token != EPOCH.get()) return;
        Path temporary = null;
        try {
            temporary = PublicationCatalogue.preparer(PROOF_REPLY, proof);
            synchronized (LOCK) {
                if (token == EPOCH.get()) PublicationCatalogue.publier(temporary, PROOF_REPLY);
            }
        } catch (Exception ignored) {
            // Python times out instead of accepting an acknowledgement from an older challenge.
        } finally {
            nettoyer(temporary);
        }
    }

    private static boolean valide() {
        var mc = Minecraft.getInstance();
        if (request != EPOCH.get()
                || mc.getConnection() == null
                || connection != mc.getConnection().getConnection()
                || !context.equals(contexteCourant())) return false;
        try {
            return (boolean) call("valide");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static Object call(String method) throws ReflectiveOperationException {
        return capture.getClass().getMethod(method).invoke(capture);
    }

    private static String contexteCourant() {
        var mc = Minecraft.getInstance();
        String world = IdentiteMonde.monde(), session = IdentiteMonde.session();
        if (mc.level == null
                || mc.player == null
                || !world.matches("m1_[0-9a-f]{64}")
                || !session.matches("[0-9a-f]{32}")) return "";
        return world + "/" + session + "/" + mc.level.dimension().location();
    }

    private static void annoter(JsonObject value) {
        var mc = Minecraft.getInstance();
        value.addProperty("v", 1);
        value.addProperty("monde_actif", IdentiteMonde.monde());
        value.addProperty("session_monde", IdentiteMonde.session());
        value.addProperty("dimension_monde", mc.level.dimension().location().toString());
        value.addProperty("t", System.currentTimeMillis());
        value.addProperty("n", Long.toString(request));
    }

    private static void publier(JsonObject payload, JsonObject ready, long token) {
        Path temporary = null, temporaryStatus = null;
        try {
            JsonObject envelope = PublicationCatalogue.enveloppe(chaine(ready, "source"), payload);
            ready.add("sha256", envelope.get("sha256"));
            temporary = PublicationCatalogue.preparer(CATALOGUE, envelope);
            temporaryStatus = PublicationCatalogue.preparer(STATUS, ready);
            synchronized (LOCK) {
                if (token != EPOCH.get()) return;
                PublicationCatalogue.publier(temporary, CATALOGUE);
                PublicationCatalogue.publier(temporaryStatus, STATUS); // Commit marker last.
                state = ready.deepCopy();
            }
        } catch (Exception ex) {
            synchronized (LOCK) {
                if (token == EPOCH.get())
                    echec("Publication du catalogue impossible. Relance le calcul.");
            }
        } finally {
            nettoyer(temporary);
            nettoyer(temporaryStatus);
        }
    }

    private static String chaine(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive()
                ? value.get(key).getAsString()
                : "";
    }

    private static JsonObject empty(String message) {
        JsonObject value = new JsonObject();
        value.addProperty("v", 1);
        value.addProperty("etat", "indisponible");
        value.addProperty("message", message);
        return value;
    }

    private static void echec(String message) {
        capture = null;
        writing = false;
        request = EPOCH.incrementAndGet();
        state = empty(message);
        // No positive receipt remains after an invalidation, including legacy mode.
        state.addProperty("t", System.currentTimeMillis());
        state.addProperty("n", Long.toString(request));
        publierEtat(state.deepCopy(), request);
    }

    private static void publierEtat(JsonObject value, long token) {
        WRITER.execute(() -> ecrireEtat(value, token));
    }

    private static void ecrireEtat(JsonObject value, long token) {
        if (token != EPOCH.get()) return;
        Path temporary = null;
        try {
            temporary = PublicationCatalogue.preparer(STATUS, value);
            synchronized (LOCK) {
                if (token == EPOCH.get()) PublicationCatalogue.publier(temporary, STATUS);
            }
        } catch (Exception ex) {
            if (token == EPOCH.get() && "indisponible".equals(chaine(value, "etat")))
                WRITER.schedule(() -> ecrireEtat(value, token), 1, TimeUnit.SECONDS);
        } finally {
            nettoyer(temporary);
        }
    }

    private static void nettoyer(Path path) {
        if (path != null)
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
    }

    private static String message(Throwable error) {
        if (error instanceof InvocationTargetException invocation && invocation.getCause() != null)
            error = invocation.getCause();
        if (error instanceof IllegalArgumentException && error.getMessage() != null)
            return error.getMessage();
        return "Recettes EMI indisponibles pour cette version ou cette sélection.";
    }

    @SubscribeEvent
    public static void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        synchronized (LOCK) {
            if (capture != null) echec("Déconnexion : catalogue désactivé.");
        }
    }
}
