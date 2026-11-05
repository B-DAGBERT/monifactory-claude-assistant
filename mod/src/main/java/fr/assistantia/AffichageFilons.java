package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraftforge.fml.loading.FMLPaths;

/** Préférence client, sans modifier les gisements ni le budget du scan. */
public final class AffichageFilons {
    private static final Path FICHIER =
            FMLPaths.CONFIGDIR.get().resolve("assistantia-filons-visuel.json");
    private static StyleFilons style = charger();

    private static StyleFilons charger() {
        try {
            var j = JsonParser.parseString(Files.readString(FICHIER)).getAsJsonObject();
            return new StyleFilons(
                    j.get("maximum").getAsInt(),
                    j.get("intensite").getAsInt(),
                    !j.has("surface") || j.get("surface").getAsBoolean());
        } catch (Exception e) {
            return StyleFilons.defaut();
        }
    }

    public static StyleFilons style() {
        return style;
    }

    public static boolean sauver(StyleFilons nouveau) {
        Path tmp = null;
        try {
            var j = new JsonObject();
            j.addProperty("maximum", nouveau.maximum());
            j.addProperty("intensite", nouveau.intensite());
            j.addProperty("surface", nouveau.surface());
            Files.createDirectories(FICHIER.getParent());
            tmp = Files.createTempFile(FICHIER.getParent(), ".filons-visuel-", ".tmp");
            Files.writeString(tmp, j.toString());
            try {
                Files.move(
                        tmp,
                        FICHIER,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FICHIER, StandardCopyOption.REPLACE_EXISTING);
            }
            style = nouveau;
            Filons.invaliderContours();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp != null)
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
        }
    }

    private AffichageFilons() {}
}
