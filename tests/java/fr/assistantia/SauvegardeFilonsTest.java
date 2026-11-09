package fr.assistantia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SauvegardeFilonsTest {
    static final String AVANT = "{\"monde\":\"a\",\"epuise_manuel\":false,\"blocs\":[]}";
    static final String APRES =
            "{\"monde\":\"a\",\"epuise_manuel\":true,\"blocs\":[{\"x\":1,\"mine\":true}]}";

    static void erreurInattendue(Exception e) {
        throw new AssertionError(e);
    }

    public static void main(String[] args) throws Exception {
        Path dossier = Path.of(args[0]);
        reconnexion(dossier);
        reprise(dossier);
        acquittementAncien(dossier);
        refusExecuteur(dossier);
        departSansPublication(dossier);
        contenuParesseux(dossier);
        copieVersion1(dossier);
        System.out.println(
                "Publication filons : reconnexion, reprise, ordre, acquittements et texte calcule"
                        + " sur le thread IO valides");
    }

    /** Copie v1 : complète ou absente, faite une seule fois, retentée après un échec. */
    static void copieVersion1(Path dossier) throws Exception {
        Path v1 = dossier.resolve("monde-v1.json"), copie = dossier.resolve("monde-v1.json.v1.bak");
        String ancien = "{\"v\":1,\"monde\":\"m\",\"blocs\":[]}";
        Files.writeString(v1, ancien);
        // Échec simulé : le nom temporaire est occupé par un dossier non vide.
        Path tmp = dossier.resolve("monde-v1.json.v1.bak.tmp");
        Files.createDirectories(tmp.resolve("occupe"));
        boolean echec = false;
        try {
            SauvegardeFilons.conserverVersion1(v1);
        } catch (java.io.IOException e) {
            echec = true;
        }
        assert echec && !Files.exists(copie) : "Échec : aucune copie partielle sous le nom final";
        Files.delete(tmp.resolve("occupe"));
        Files.delete(tmp);
        SauvegardeFilons.conserverVersion1(v1);
        assert Files.readString(copie).equals(ancien) && !Files.exists(tmp)
                : "Copie complète, sans temporaire restant";
        Files.writeString(v1, "{\"v\":2,\"monde\":\"m\"}");
        SauvegardeFilons.conserverVersion1(v1);
        assert Files.readString(copie).equals(ancien) : "La copie v1 n'est jamais remplacée";
        Path v2 = dossier.resolve("monde-v2.json");
        Files.writeString(v2, "{\"v\":2}");
        SauvegardeFilons.conserverVersion1(v2);
        assert !Files.exists(dossier.resolve("monde-v2.json.v1.bak"))
                : "Pas de copie d'un fichier v2";
    }

    /** Le texte se calcule une seule fois, sur l'exécuteur IO, jamais au moment d'enregistrer. */
    static void contenuParesseux(Path dossier) throws Exception {
        var file = new ArrayDeque<Runnable>();
        var calculs = new AtomicInteger();
        var threads = new java.util.ArrayList<String>();
        Object source = new Object();
        Path a = dossier.resolve("paresseux.json"),
                export = dossier.resolve("export-paresseux.json");
        var sauvegarde = new SauvegardeFilons(file::add, Files::writeString, () -> 0);
        var natif =
                new SauvegardeFilons.Contenu(
                        () -> {
                            calculs.incrementAndGet();
                            threads.add(Thread.currentThread().getName());
                            return APRES;
                        },
                        source);
        var resume = new SauvegardeFilons.Contenu(() -> "resume", null);
        sauvegarde.enregistrer(a, natif, export, resume, SauvegardeFilonsTest::erreurInattendue);
        assert calculs.get() == 0 : "Aucun calcul de texte sur le thread client";
        assert sauvegarde.attente(a).source() == source
                : "La reconnexion reprend l'instantané lui-même, sans relire le fichier";
        var io = new Thread(() -> file.remove().run(), "io-test");
        io.start();
        io.join();
        assert calculs.get() == 1 && threads.equals(java.util.List.of("io-test"))
                : "Texte calculé une fois, sur le thread d'écriture";
        assert Files.readString(a).equals(APRES) && Files.readString(export).equals("resume");
        assert sauvegarde.attente(a) == null : "Acquitté après écriture";
        assert sauvegarde.lire(a).equals(APRES) && calculs.get() == 1;
    }

    static void reconnexion(Path dossier) throws Exception {
        Path fichier = dossier.resolve("a.json"), export = dossier.resolve("filons_mod.json");
        Files.writeString(fichier, AVANT);
        var file = new ArrayDeque<Runnable>();
        AtomicLong temps = new AtomicLong();
        var sauvegarde = new SauvegardeFilons(file::add, Files::writeString, temps::get);
        sauvegarde.enregistrer(
                fichier, APRES, export, "session-A", SauvegardeFilonsTest::erreurInattendue);
        assert Files.readString(fichier).equals(AVANT) : "L'IO est volontairement suspendue";
        assert sauvegarde.lire(fichier).equals(APRES)
                : "Une reconnexion au même monde doit relire son dernier snapshot, même avant publication";
        file.remove().run();
        assert Files.readString(fichier).equals(APRES);
        assert Files.readString(export).equals("session-A");
        assert sauvegarde.lire(fichier).equals(APRES);
        assert sauvegarde.lire(dossier.resolve("absent.json")) == null;
        // Deux clics/connexions avant l'IO : seul le dernier contenu doit finalement être publié.
        sauvegarde.enregistrer(
                fichier, AVANT, export, "ancien", SauvegardeFilonsTest::erreurInattendue);
        sauvegarde.enregistrer(
                fichier, APRES, export, "nouveau", SauvegardeFilonsTest::erreurInattendue);
        file.remove().run();
        file.remove().run();
        assert Files.readString(fichier).equals(APRES);
        assert Files.readString(export).equals("nouveau");
    }

    static void reprise(Path dossier) throws Exception {
        Path a = dossier.resolve("reprise-a.json"), b = dossier.resolve("reprise-b.json");
        Path export = dossier.resolve("export.json");
        Files.writeString(a, AVANT);
        var file = new ArrayDeque<Runnable>();
        var temps = new AtomicLong();
        var echecs = new AtomicInteger();
        var bloquer = new AtomicBoolean(true);
        var sauvegarde =
                new SauvegardeFilons(
                        file::add,
                        (p, texte) -> {
                            if (p.equals(a) && bloquer.get())
                                throw new IOException("Disque temporairement indisponible");
                            Files.writeString(p, texte);
                        },
                        temps::get);
        sauvegarde.enregistrer(a, APRES, export, "session-A", e -> echecs.incrementAndGet());
        file.remove().run();
        assert echecs.get() == 1 && sauvegarde.lire(a).equals(APRES);
        sauvegarde.reessayer();
        temps.set(9999);
        sauvegarde.reessayer();
        assert file.isEmpty() : "Pas de boucle de reprise chaque tick";
        sauvegarde.enregistrer(
                b, "monde-B", export, "session-B", SauvegardeFilonsTest::erreurInattendue);
        file.remove().run();
        assert Files.readString(export).equals("session-B");
        bloquer.set(false);
        temps.set(10000);
        sauvegarde.reessayer();
        sauvegarde.reessayer();
        assert file.size() == 1 : "Une seule reprise en vol";
        file.remove().run();
        assert Files.readString(a).equals(APRES)
                : "L'ancien monde est sauvegardé même après départ";
        assert Files.readString(export).equals("session-B")
                : "Sa reprise ne remplace pas l'export récent";
        sauvegarde.reessayer();
        assert file.isEmpty();

        // L'échec du second fichier est aussi réessayé, même si la copie native a été écrite.
        var exportEchec = new AtomicBoolean(true);
        var repriseExport =
                new SauvegardeFilons(
                        file::add,
                        (p, texte) -> {
                            if (p.equals(export) && exportEchec.getAndSet(false))
                                throw new IOException("Export indisponible");
                            Files.writeString(p, texte);
                        },
                        temps::get);
        repriseExport.enregistrer(a, AVANT, export, "session-C", e -> echecs.incrementAndGet());
        file.remove().run();
        assert repriseExport.lire(a).equals(AVANT);
        temps.addAndGet(10000);
        repriseExport.reessayer();
        file.remove().run();
        assert Files.readString(export).equals("session-C");
    }

    static void acquittementAncien(Path dossier) throws Exception {
        var file = new ArrayDeque<Runnable>();
        Path a = dossier.resolve("ack.json");
        SauvegardeFilons[] reference = new SauvegardeFilons[1];
        reference[0] =
                new SauvegardeFilons(
                        file::add,
                        (p, texte) -> {
                            Files.writeString(p, texte);
                            if (texte.equals(AVANT))
                                reference[0].enregistrer(
                                        a, APRES, null, "", SauvegardeFilonsTest::erreurInattendue);
                        },
                        () -> 0);
        reference[0].enregistrer(a, AVANT, null, "", SauvegardeFilonsTest::erreurInattendue);
        file.remove().run();
        assert reference[0].lire(a).equals(APRES)
                : "Un ancien acquittement ne retire pas le dernier snapshot";
        file.remove().run();
        assert Files.readString(a).equals(APRES);
    }

    static void departSansPublication(Path dossier) throws Exception {
        var file = new ArrayDeque<Runnable>();
        var temps = new AtomicLong();
        var panne = new AtomicBoolean(true);
        Path a = dossier.resolve("depart.json"), export = dossier.resolve("export-depart.json");
        var sauvegarde =
                new SauvegardeFilons(
                        file::add,
                        (p, texte) -> {
                            if (p.equals(a) && panne.getAndSet(false))
                                throw new IOException("Disque indisponible");
                            Files.writeString(p, texte);
                        },
                        temps::get);
        sauvegarde.enregistrer(a, APRES, export, "session-A", e -> {});
        file.remove().run();
        sauvegarde.invaliderExport(export); // départ, aucune nouvelle partie encore chargée
        temps.set(10000);
        sauvegarde.reessayer();
        file.remove().run();
        assert Files.readString(a).equals(APRES) : "La copie native est conservée après départ";
        assert !Files.exists(export) : "Aucun export détaché ne réapparaît après départ";
        sauvegarde.enregistrer(
                a, AVANT, export, "session-B", SauvegardeFilonsTest::erreurInattendue);
        sauvegarde.invaliderExport(export); // changement de dimension avant la première IO
        file.remove().run();
        assert Files.readString(a).equals(AVANT);
        assert !Files.exists(export);
        sauvegarde.enregistrer(
                a, APRES, export, "session-C", SauvegardeFilonsTest::erreurInattendue);
        file.remove().run();
        assert Files.readString(export).equals("session-C") : "La prochaine portée peut publier";
    }

    static void refusExecuteur(Path dossier) throws Exception {
        var file = new ArrayDeque<Runnable>();
        var refus = new AtomicBoolean(true);
        var temps = new AtomicLong();
        var erreurs = new AtomicInteger();
        Path a = dossier.resolve("refus.json");
        var sauvegarde =
                new SauvegardeFilons(
                        r -> {
                            if (refus.getAndSet(false))
                                throw new java.util.concurrent.RejectedExecutionException();
                            file.add(r);
                        },
                        Files::writeString,
                        temps::get);
        sauvegarde.enregistrer(a, APRES, null, "", e -> erreurs.incrementAndGet());
        assert erreurs.get() == 1 && sauvegarde.lire(a).equals(APRES);
        temps.set(10000);
        sauvegarde.reessayer();
        file.remove().run();
        assert Files.readString(a).equals(APRES);
    }
}
