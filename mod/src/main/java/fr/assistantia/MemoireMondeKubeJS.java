package fr.assistantia;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Les deux mémoires persistantes du script client (coffres, filons) : dans une partition de monde,
 * forcées sur le disque, ou sans isolation à la racine, au format qu'écrivait KubeJS. Elles sont
 * écrites sur le fil d'écriture du mod : le script n'attend jamais le disque, le plus récent
 * contenu d'un fichier gagne, et une relecture voit toujours la dernière écriture, même en file.
 */
public final class MemoireMondeKubeJS {
    private static final Gson JSON = new Gson();
    private static final Pattern MONDE = Pattern.compile("m1_[0-9a-f]{64}");

    private MemoireMondeKubeJS() {}

    private static Path racine() {
        return FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
    }

    private static void verifierNom(String nom) throws IOException {
        if (!"stockage.json".equals(nom) && !"filons.json".equals(nom))
            throw new IOException("Mémoire de monde invalide");
    }

    /** Fichier de la partition, sans aucune entrée-sortie. */
    private static Path chemin(String monde, String nom) throws IOException {
        if (monde == null || !MONDE.matcher(monde).matches())
            throw new IOException("Mémoire de monde invalide");
        verifierNom(nom);
        return racine().resolve("mondes").resolve(monde).resolve(nom);
    }

    private static Path verifierDossier(Path path, boolean creer) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && creer)
            try {
                Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // Le lecteur Python peut avoir préparé la partition entre-temps.
            }
        // Refus définitif (jamais retenté) : la partition ne sera pas écrite à travers un lien.
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new Ecritures.Refus("Dossier de mémoire invalide");
        return path;
    }

    private static Path fichier(String monde, String nom, boolean creer) throws IOException {
        Path fichier = chemin(monde, nom);
        Path racine = racine();
        verifierDossier(racine, creer);
        Path parent = verifierDossier(racine.resolve("mondes"), creer);
        verifierDossier(parent.resolve(monde), creer);
        if (Files.exists(fichier, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS))
            throw new Ecritures.Refus("Fichier de mémoire invalide");
        return fichier;
    }

    public static Map<?, ?> lire(String monde, String nom) throws IOException {
        Path fichier = fichier(monde, nom, false);
        var enFile = enFile(fichier);
        if (enFile != null) return enFile;
        if (!Files.exists(fichier, LinkOption.NOFOLLOW_LINKS)) return null;
        var contenu =
                JsonParser.parseString(Files.readString(fichier, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        return JSON.fromJson(contenu, Map.class);
    }

    /**
     * Sans attente : contrôle le nom et met le contenu en file. Le fil d'écriture prépare la
     * partition (sans suivre de lien), force le fichier sur le disque puis le remplace atomiquement
     * ; il retente jusqu'à réussite. Le contenu ne doit plus être modifié. Rend une erreur
     * d'écriture à signaler, une seule fois, ou un texte vide : le script n'a jamais à republier.
     */
    public static String ecrire(String monde, String nom, JsonObject contenu) throws IOException {
        return publier(chemin(monde, nom), new Memoire(contenu, false, monde, nom).durable());
    }

    /** Sans isolation : comme JsonIO.read de KubeJS (absent : null), dernière écriture comprise. */
    public static Map<?, ?> lireHistorique(String nom) throws IOException {
        verifierNom(nom);
        Path fichier = racine().resolve(nom);
        var enFile = enFile(fichier);
        if (enFile != null) return enFile;
        if (!Files.isRegularFile(fichier)) return null;
        JsonElement contenu =
                JsonParser.parseString(Files.readString(fichier, StandardCharsets.UTF_8));
        return contenu.isJsonObject() ? JSON.fromJson(contenu, Map.class) : null;
    }

    /**
     * Sans isolation : à la racine, indenté comme JsonIO.write de KubeJS, sans attente ni fsync,
     * mais retenté jusqu'à réussite comme dans une partition.
     */
    public static String ecrireHistorique(String nom, JsonObject contenu) throws IOException {
        verifierNom(nom);
        return publier(racine().resolve(nom), new Memoire(contenu, true, null, nom).persistant());
    }

    private static final Map<Path, String> SIGNALEES = new HashMap<>();

    /**
     * Ne lève jamais pour un échec passé : le fil d'écriture garde le contenu le plus récent et le
     * retente lui-même ; chaque nouvelle erreur est rendue une fois pour le journal du script, sans
     * bloquer un changement de monde ni faire republier les mêmes données.
     */
    private static synchronized String publier(Path fichier, Ecritures.Contenu memoire) {
        Ecritures.publier(fichier, memoire);
        String erreur = Ecritures.erreur(fichier);
        if (erreur == null) {
            SIGNALEES.remove(fichier);
            return "";
        }
        if (erreur.equals(SIGNALEES.put(fichier, erreur))) return "";
        return erreur;
    }

    /** La dernière écriture encore en file ou en cours, servie depuis la mémoire. */
    private static Map<?, ?> enFile(Path fichier) {
        return Ecritures.enAttente(fichier) instanceof Memoire memoire
                ? JSON.fromJson(memoire.json, Map.class)
                : null;
    }

    private static final class Memoire extends Ecritures.Contenu {
        final JsonObject json;
        private final boolean indente;
        private final String monde, nom;

        Memoire(JsonObject json, boolean indente, String monde, String nom) {
            this.json = json;
            this.indente = indente;
            this.monde = monde;
            this.nom = nom;
        }

        protected void preparer() throws IOException {
            if (monde != null) fichier(monde, nom, true);
        }

        protected byte[] produire() throws IOException {
            return Ecritures.serialiser(json, indente).getBytes(StandardCharsets.UTF_8);
        }
    }
}
