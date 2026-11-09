package fr.assistantia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Conserve chaque dernier instantané jusqu'à sa publication par l'exécuteur IO séquentiel.
 *
 * <p>Le texte d'un instantané est calculé une seule fois, sur l'exécuteur IO (jamais sur le thread
 * client) : le thread client ne fait que copier ses tableaux.
 */
final class SauvegardeFilons {
    interface Ecriture {
        void ecrire(Path destination, String contenu) throws IOException;
    }

    /** Texte calculé à la demande et mémorisé ; {@link #source} garde l'objet d'origine. */
    static final class Contenu {
        private final Supplier<String> calcul;
        private final Object source;
        private volatile String texte;

        Contenu(Supplier<String> calcul, Object source) {
            this.calcul = calcul;
            this.source = source;
        }

        static Contenu texte(String texte) {
            var c = new Contenu(() -> texte, texte);
            c.texte = texte;
            return c;
        }

        String texte() {
            String t = texte;
            if (t != null) return t;
            synchronized (this) {
                if (texte == null) texte = calcul.get();
                return texte;
            }
        }

        Object source() {
            return source;
        }
    }

    private static final long DELAI_REPRISE = 10_000;

    private static final class Instantane {
        final Path destination, export;
        final Contenu contenu, contenuExport;
        final Consumer<Exception> erreur;
        final long revision;
        boolean enCours;
        long reprise;

        Instantane(
                Path destination,
                Contenu contenu,
                Path export,
                Contenu contenuExport,
                Consumer<Exception> erreur,
                long revision) {
            this.destination = destination;
            this.contenu = contenu;
            this.export = export;
            this.contenuExport = contenuExport;
            this.erreur = erreur;
            this.revision = revision;
        }
    }

    private final Executor io;
    private final Ecriture ecriture;
    private final LongSupplier horloge;
    private final Map<Path, Instantane> enAttente = new HashMap<>();
    private final Map<Path, Long> exports = new HashMap<>();
    private long revision;

    SauvegardeFilons(Executor io, Ecriture ecriture, LongSupplier horloge) {
        this.io = io;
        this.ecriture = ecriture;
        this.horloge = horloge;
    }

    /** Dernier contenu connu : instantané pas encore écrit, sinon le fichier, sinon null. */
    String lire(Path destination) throws IOException {
        Contenu contenu = attente(destination);
        if (contenu != null) return contenu.texte();
        // L'acquittement ne retire l'instantané qu'après le remplacement atomique du fichier.
        return Files.exists(destination)
                ? Files.readString(destination, StandardCharsets.UTF_8)
                : null;
    }

    /** Instantané pas encore écrit pour ce fichier, ou null. */
    synchronized Contenu attente(Path destination) {
        var instantane = enAttente.get(destination);
        return instantane == null ? null : instantane.contenu;
    }

    void enregistrer(
            Path destination,
            String contenu,
            Path export,
            String contenuExport,
            Consumer<Exception> erreur) {
        enregistrer(
                destination,
                Contenu.texte(contenu),
                export,
                contenuExport == null ? null : Contenu.texte(contenuExport),
                erreur);
    }

    void enregistrer(
            Path destination,
            Contenu contenu,
            Path export,
            Contenu contenuExport,
            Consumer<Exception> erreur) {
        Instantane instantane;
        synchronized (this) {
            instantane =
                    new Instantane(destination, contenu, export, contenuExport, erreur, ++revision);
            enAttente.put(destination, instantane);
            if (export != null) exports.put(export, instantane.revision);
        }
        soumettre(instantane);
    }

    synchronized void invaliderExport(Path export) {
        exports.put(export, ++revision);
    }

    void reessayer() {
        Instantane[] attente;
        synchronized (this) {
            if (enAttente.isEmpty()) return;
            attente = enAttente.values().toArray(Instantane[]::new);
        }
        for (var instantane : attente) soumettre(instantane);
    }

    private void soumettre(Instantane instantane) {
        synchronized (this) {
            if (enAttente.get(instantane.destination) != instantane
                    || instantane.enCours
                    || horloge.getAsLong() < instantane.reprise) return;
            instantane.enCours = true;
        }
        try {
            io.execute(() -> publier(instantane));
        } catch (RuntimeException e) {
            echouer(instantane, e);
        }
    }

    private void publier(Instantane instantane) {
        synchronized (this) {
            if (enAttente.get(instantane.destination) != instantane) return;
        }
        try {
            ecriture.ecrire(instantane.destination, instantane.contenu.texte());
            boolean exporter;
            synchronized (this) {
                exporter =
                        instantane.export != null
                                && Long.valueOf(instantane.revision)
                                        .equals(exports.get(instantane.export));
            }
            // Une reprise de l'ancien monde ne remplace jamais l'export du monde plus récent.
            if (exporter && instantane.contenuExport != null)
                ecriture.ecrire(instantane.export, instantane.contenuExport.texte());
            synchronized (this) {
                enAttente.remove(instantane.destination, instantane);
            }
        } catch (Exception e) {
            echouer(instantane, e);
        }
    }

    private void echouer(Instantane instantane, Exception erreur) {
        synchronized (this) {
            instantane.enCours = false;
            instantane.reprise = horloge.getAsLong() + DELAI_REPRISE;
        }
        instantane.erreur.accept(erreur);
    }
}
