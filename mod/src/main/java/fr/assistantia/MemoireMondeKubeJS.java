package fr.assistantia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.regex.Pattern;

/** Écrit uniquement les deux mémoires persistantes du script client dans une partition. */
public final class MemoireMondeKubeJS {
    private static final Gson JSON = new Gson();
    private static final Pattern MONDE = Pattern.compile("m1_[0-9a-f]{64}");

    private MemoireMondeKubeJS() {}

    private static Path verifierDossier(Path path, boolean creer) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && creer)
            try {
                Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // Le lecteur Python peut avoir préparé la partition entre-temps.
            }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Dossier de mémoire invalide");
        return path;
    }

    private static Path fichier(String monde, String nom, boolean creer) throws IOException {
        if (!MONDE.matcher(monde).matches()
                || !(nom.equals("stockage.json") || nom.equals("filons.json")))
            throw new IOException("Mémoire de monde invalide");
        Path racine = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
        verifierDossier(racine, creer);
        Path parent = verifierDossier(racine.resolve("mondes"), creer);
        Path dossier = verifierDossier(parent.resolve(monde), creer);
        Path fichier = dossier.resolve(nom);
        if (Files.exists(fichier, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Fichier de mémoire invalide");
        return fichier;
    }

    public static Map<?, ?> lire(String monde, String nom) throws IOException {
        Path fichier = fichier(monde, nom, false);
        if (!Files.exists(fichier, LinkOption.NOFOLLOW_LINKS)) return null;
        var contenu =
                JsonParser.parseString(Files.readString(fichier, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        return JSON.fromJson(contenu, Map.class);
    }

    public static void ecrire(String monde, String nom, JsonObject contenu) throws IOException {
        Path fichier = fichier(monde, nom, true);
        Path temporaire = Files.createTempFile(fichier.getParent(), ".memoire-", ".tmp");
        try {
            Files.writeString(temporaire, contenu.toString(), StandardCharsets.UTF_8);
            try (var canal = FileChannel.open(temporaire, StandardOpenOption.WRITE)) {
                canal.force(true);
            }
            Files.move(
                    temporaire,
                    fichier,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaire);
        }
    }
}
