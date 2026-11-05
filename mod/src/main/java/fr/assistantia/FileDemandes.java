package fr.assistantia;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Un fichier par demande : deux clics distincts ne peuvent pas se remplacer. */
public final class FileDemandes {
    private FileDemandes() {}

    public static synchronized void envoyer(Path dossier, JsonObject demande) throws IOException {
        String id = demande.get("requete").getAsString();
        if (!id.matches("[a-zA-Z0-9-]{1,80}")) throw new IOException("Identifiant invalide");
        Path file = dossier.resolve("demandes");
        Files.createDirectories(file);
        Path cible =
                file.resolve(
                        String.format(
                                java.util.Locale.ROOT,
                                "%020d-%s.json",
                                demande.get("n").getAsLong(),
                                id));
        try (var fichiers = Files.list(file)) {
            if (!Files.exists(cible)
                    && fichiers.filter(p -> p.getFileName().toString().endsWith(".json"))
                                    .limit(128)
                                    .count()
                            >= 128)
                throw new IOException("File pleine : attends l’assistant avant de réessayer.");
        }
        Path temporaire = Files.createTempFile(file, ".demande-", ".tmp");
        try {
            Files.writeString(temporaire, demande.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaire,
                        cible,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaire);
        }
    }
}
