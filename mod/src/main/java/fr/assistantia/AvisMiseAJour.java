package fr.assistantia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Préférence locale : masquer cet avis, pas les mises à jour suivantes. */
public final class AvisMiseAJour {
    private final Path fichier;
    private String masque = "";

    public AvisMiseAJour(Path fichier) {
        this.fichier = fichier;
        try {
            if (Files.isRegularFile(fichier))
                masque = Files.readString(fichier, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            /* Un fichier absent ou illisible ne bloque pas le menu. */
        }
    }

    private String cle(String id, String texte) {
        return id.isBlank() ? texte : id;
    }

    public boolean visible(String id, String texte) {
        return !texte.isBlank() && !cle(id, texte).equals(masque);
    }

    public void masquer(String id, String texte) throws IOException {
        masque = cle(id, texte);
        Files.createDirectories(fichier.getParent());
        Path temp = fichier.resolveSibling(fichier.getFileName() + ".tmp");
        Files.writeString(temp, masque, StandardCharsets.UTF_8);
        Files.move(temp, fichier, StandardCopyOption.REPLACE_EXISTING);
    }
}
