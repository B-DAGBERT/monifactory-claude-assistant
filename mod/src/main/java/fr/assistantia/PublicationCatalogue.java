package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Même JSON canonique que json.dumps(sort_keys=True,ensure_ascii=True,separators=(',',':')). */
public final class PublicationCatalogue {
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private PublicationCatalogue() {}

    public static String canonique(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            return "{"
                    + String.join(
                            ",",
                            object.keySet().stream()
                                    .sorted()
                                    .map(key -> chaine(key) + ":" + canonique(object.get(key)))
                                    .toList())
                    + "}";
        }
        if (value.isJsonArray()) {
            var values = new java.util.ArrayList<String>();
            value.getAsJsonArray().forEach(element -> values.add(canonique(element)));
            return "[" + String.join(",", values) + "]";
        }
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return chaine(primitive.getAsString());
        if (primitive.isBoolean()) return primitive.getAsBoolean() ? "true" : "false";
        String number = primitive.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Seuls les entiers sont admis dans ce catalogue");
        return number;
    }

    private static String chaine(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32 || c >= 127)
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    public static JsonObject enveloppe(String source, JsonObject payload) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "monifactory.production.catalogue");
        result.addProperty("version", 1);
        result.addProperty("source", source);
        result.addProperty("sha256", empreinte(canonique(payload)));
        result.add("catalogue", payload);
        return result;
    }

    public static String empreinte(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Path preparer(Path destination, JsonObject value) throws IOException {
        byte[] bytes = canonique(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES)
            throw new IOException("Catalogue supérieur à 64 Mio ; aucune publication partielle");
        Files.createDirectories(destination.getParent());
        if (Files.isSymbolicLink(destination) || Files.isSymbolicLink(destination.getParent()))
            throw new IOException("Chemin catalogue non sûr");
        Path temporary =
                destination.resolveSibling(
                        "." + destination.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(
                    temporary,
                    bytes,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            return temporary;
        } catch (IOException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    public static void publier(Path temporary, Path destination) throws IOException {
        // Refuse platforms that cannot provide atomic replacement; keep the last generation.
        Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
