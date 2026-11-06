package fr.assistantia;

import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final Object LOCK = new Object();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final ExecutorService WRITER =
            Executors.newSingleThreadExecutor(
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
            if (writing || "pret".equals(chaine(state, "etat"))) return;
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
        WRITER.execute(
                () -> {
                    Path temporary = null;
                    try {
                        temporary = PublicationCatalogue.preparer(STATUS, value);
                        synchronized (LOCK) {
                            if (token == EPOCH.get())
                                PublicationCatalogue.publier(temporary, STATUS);
                        }
                    } catch (Exception ignored) {
                        /* In-memory UI still exposes the error; no ready ack is sent. */
                    } finally {
                        nettoyer(temporary);
                    }
                });
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
