package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileDemandesTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("assistant-inbox-");
        try {
            for (int i = 0; i < 128; i++) {
                JsonObject request = new JsonObject();
                request.addProperty("requete", "request-" + i);
                request.addProperty("n", 1000 + i);
                request.addProperty("texte", "Demande " + i);
                FileDemandes.envoyer(root, request);
                FileDemandes.envoyer(root, request); // Double clic : un seul fichier.
            }
            var files = Files.list(root.resolve("demandes"));
            var entries = files.sorted().toList();
            files.close();
            assert entries.size() == 128;
            assert entries.stream().noneMatch(p -> p.toString().endsWith(".tmp"));
            for (int i = 0; i < entries.size(); i++) {
                var request =
                        JsonParser.parseString(Files.readString(entries.get(i))).getAsJsonObject();
                assert request.get("texte").getAsString().equals("Demande " + i);
            }
            var extra = new JsonObject();
            extra.addProperty("requete", "extra");
            extra.addProperty("n", 2000);
            boolean refused = false;
            try {
                FileDemandes.envoyer(root, extra);
            } catch (java.io.IOException e) {
                refused = true;
            }
            assert refused : "ne pas perdre silencieusement une demande quand la file est pleine";
        } finally {
            try (var paths = Files.walk(root)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }
}
