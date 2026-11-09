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

    /**
     * Chaque réglage absent ou illisible reprend sa valeur par défaut, sans effacer les autres.
     *
     * <p>Les fichiers sans version viennent d'avant les boutons Épaisseur et Intensité : leur
     * intensité 0 était la valeur par défaut d'alors (aucun bouton ne permettait de la choisir
     * durablement), elle devient l'intensité par défaut actuelle, bien plus visible.
     */
    private static StyleFilons charger() {
        try {
            var j = JsonParser.parseString(Files.readString(FICHIER)).getAsJsonObject();
            var d = StyleFilons.defaut();
            boolean ancien = !j.has("v");
            int intensite = entier(j, "intensite", d.intensite());
            if (ancien) intensite = intensite <= 0 ? d.intensite() : intensite;
            return new StyleFilons(
                    entier(j, "maximum", d.maximum()),
                    intensite,
                    booleen(j, "surface", true),
                    entier(j, "epaisseur", d.epaisseur()),
                    booleen(j, "remplissage", d.remplissage()));
        } catch (Exception e) {
            return StyleFilons.defaut();
        }
    }

    private static int entier(JsonObject j, String cle, int defaut) {
        try {
            return j.has(cle) ? j.get(cle).getAsInt() : defaut;
        } catch (RuntimeException e) {
            return defaut;
        }
    }

    private static boolean booleen(JsonObject j, String cle, boolean defaut) {
        try {
            return j.has(cle) ? j.get(cle).getAsBoolean() : defaut;
        } catch (RuntimeException e) {
            return defaut;
        }
    }

    public static StyleFilons style() {
        return style;
    }

    public static boolean sauver(StyleFilons nouveau) {
        Path tmp = null;
        try {
            var j = new JsonObject();
            j.addProperty("v", 2);
            j.addProperty("maximum", nouveau.maximum());
            j.addProperty("intensite", nouveau.intensite());
            j.addProperty("surface", nouveau.surface());
            j.addProperty("epaisseur", nouveau.epaisseur());
            j.addProperty("remplissage", nouveau.remplissage());
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
