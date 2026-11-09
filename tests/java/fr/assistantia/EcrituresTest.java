package fr.assistantia;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The client thread never sleeps; order and requests survive a reader holding the file. */
public final class EcrituresTest {
    static final List<String> ECRITS = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger REFUS = new AtomicInteger();
    static volatile CountDownLatch bloque; // holds the writer thread inside a replacement
    static volatile boolean erreurDisque;

    static void remplacer(Path temporaire, Path cible) throws IOException {
        var verrou = bloque;
        if (verrou != null && Thread.currentThread().getName().startsWith("test-")) {
            try {
                verrou.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        if (erreurDisque) throw new IOException("disk full");
        if (REFUS.get() > 0) {
            REFUS.decrementAndGet();
            // What Windows reports while the assistant still has the target open.
            throw new AccessDeniedException(cible.toString());
        }
        Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
        ECRITS.add(cible.getFileName() + "=" + Files.readString(cible));
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static long depuis(long debut) {
        return (System.nanoTime() - debut) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        Path dossier = Files.createTempDirectory("ecritures");
        Path cible = dossier.resolve("inventaire_joueur.json");
        Path autre = dossier.resolve("configuration_ia.json");
        var e = new Ecritures(EcrituresTest::remplacer, Ecritures.fil("test-ecritures"), 45_000);

        // 1. The reader holds the file: the attempt returns at once (before any retry could have
        // run: six refusals need 315 ms of back-off), then the writer thread delivers it.
        REFUS.set(6);
        long debut = System.nanoTime();
        check(e.essayer(cible, Ecritures.texte("A")), "contended request accepted");
        long ms = depuis(debut);
        check(!Files.exists(cible), "caller returned before delivery: no sleep on its thread");
        check(ms < 250, "no wait on the caller thread: " + ms + " ms");
        check(e.vider(5000), "retries finish");
        check(Files.readString(cible).equals("A"), "request delivered once the reader released it");

        // 2. A newer request during a refusal is written alone; the older one never lands after.
        ECRITS.clear();
        REFUS.set(2);
        check(e.essayer(cible, Ecritures.texte("B")), "B accepted");
        e.differer(cible, Ecritures.texte("C"));
        check(e.vider(5000), "flushed");
        check(Files.readString(cible).equals("C"), "latest content kept");
        check(
                ECRITS.equals(List.of("inventaire_joueur.json=C")),
                "older never after newer: " + ECRITS);

        // 3. Slow disk on the writer thread: callers never wait for it, order stays FIFO.
        ECRITS.clear();
        bloque = new CountDownLatch(1);
        e.differer(cible, Ecritures.texte("D"));
        Thread.sleep(100); // the writer thread is now stuck inside D
        debut = System.nanoTime();
        e.differer(cible, Ecritures.texte("E"));
        check(e.essayer(cible, Ecritures.texte("F")), "same file queued behind the running write");
        ms = depuis(debut);
        check(ms < 1000, "caller not blocked by the busy writer: " + ms + " ms");
        debut = System.nanoTime();
        check(e.essayer(autre, Ecritures.texte("{}")), "independent file written directly");
        check(Files.readString(autre).equals("{}"), "direct write visible at once");
        check(depuis(debut) < 2000, "independent file did not wait for the stuck writer");
        check(Files.readString(cible).equals("C"), "nothing overtook the stuck write of D");
        bloque.countDown();
        bloque = null;
        check(e.vider(5000), "flushed after slow disk");
        check(Files.readString(cible).equals("F"), "last request wins");
        check(
                ECRITS.equals(
                        List.of(
                                "configuration_ia.json={}",
                                "inventaire_joueur.json=D",
                                "inventaire_joueur.json=F")),
                "FIFO per file, E superseded: " + ECRITS);

        // 4. Any other I/O error is reported to the caller and never retried.
        erreurDisque = true;
        check(!e.essayer(autre, Ecritures.texte("x")), "real I/O error reported");
        erreurDisque = false;
        check(e.vider(1000), "no retry left behind");
        check(Files.readString(autre).equals("{}"), "previous file intact");

        // 5. A reader that never releases: the request is dropped after its validity.
        var court = new Ecritures(EcrituresTest::remplacer, Ecritures.fil("test-court"), 300);
        REFUS.set(1_000_000);
        Path demande = dossier.resolve("demande_mod.json");
        court.differer(demande, Ecritures.texte("old"));
        check(!court.vider(100), "still retrying while valid");
        check(court.vider(5000), "abandoned after its validity");
        REFUS.set(0);
        check(!Files.exists(demande), "expired request never written late");

        // 6. A null content writes nothing; ecrit() only after a real replacement.
        int[] confirmations = {0};
        Path monde = dossier.resolve("monde.json");
        e.differer(
                monde,
                new Ecritures.Contenu() {
                    protected byte[] produire() {
                        return null;
                    }

                    protected void ecrit() {
                        confirmations[0]++;
                    }
                });
        check(e.vider(5000), "skip flushed");
        check(!Files.exists(monde) && confirmations[0] == 0, "invalid frame skipped silently");
        e.differer(
                monde,
                new Ecritures.Contenu() {
                    protected byte[] produire() {
                        return "{}".getBytes();
                    }

                    protected void ecrit() {
                        confirmations[0]++;
                    }
                });
        check(e.vider(5000), "frame flushed");
        check(confirmations[0] == 1, "written frame confirmed once");

        // 7. No temporary file is left behind, even after refusals and errors.
        try (var fichiers = Files.list(dossier)) {
            check(fichiers.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp file left");
        }

        // 8. Indented JSON keeps the KubeJS layout (tabs, nulls, no HTML escaping).
        var json = JsonParser.parseString("{\"a\":[1,{\"b\":null}],\"c\":\"<x>\"}");
        check(
                Ecritures.serialiser(json, true)
                        .equals(
                                "{\n\t\"a\": [\n\t\t1,\n\t\t{\n\t\t\t\"b\": null\n\t\t}\n\t],\n"
                                        + "\t\"c\": \"<x>\"\n}"),
                "KubeJS layout: " + Ecritures.serialiser(json, true));
        check(Ecritures.serialiser(json, false).equals(json.toString()), "compact unchanged");
        System.out.println("Ecritures OK");
    }
}
