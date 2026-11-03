package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Éclairage client uniquement : conserve le gamma utilisateur et ne crée aucun effet de potion. */
public final class Fullbright {
    public static final String CLE = "local_fullbright";
    private static final Path FICHIER = FMLPaths.CONFIGDIR.get().resolve("assistantia-visuel.json");
    private static Field textureField;
    private static boolean actif, applique;
    private static long prochainClic;
    private static String erreur = "";
    static {
        try {
            if (Files.exists(FICHIER)) {
                JsonObject donnees = JsonParser.parseString(Files.readString(FICHIER)).getAsJsonObject();
                actif = donnees.has("fullbright") && donnees.get("fullbright").getAsBoolean();
            }
        } catch (Exception e) {
            erreur = "Préférence illisible ; luminosité normale conservée.";
            LogUtils.getLogger().warn("Lecture des préférences de luminosité", e);
        }
    }
    private Fullbright() { }
    public static boolean actif() { return actif; }
    public static String description() {
        return "Éclaire les grottes au maximum, sans torche. Désactiver rétablit l’éclairage normal. Réglage local mémorisé."
                + (erreur.isEmpty() ? "" : " " + erreur);
    }
    public static void regler(boolean valeur) {
        long maintenant = System.nanoTime();
        if (maintenant < prochainClic || actif == valeur) return;
        prochainClic = maintenant + 650_000_000L;
        actif = valeur;
        erreur = "";
        if (!actif) restaurer();
        sauver();
    }
    private static void sauver() {
        try {
            // Conserver les autres préférences visuelles, y compris celles des anciennes versions.
            JsonObject donnees = Files.exists(FICHIER)
                    ? JsonParser.parseString(Files.readString(FICHIER)).getAsJsonObject() : new JsonObject();
            donnees.addProperty("fullbright", actif);
            Files.createDirectories(FICHIER.getParent());
            Path temporaire = FICHIER.resolveSibling(FICHIER.getFileName() + ".tmp");
            Files.writeString(temporaire, donnees.toString());
            Files.move(temporaire, FICHIER, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            erreur = "Impossible de mémoriser ce choix ; il reste valable pour cette session.";
            LogUtils.getLogger().warn("Sauvegarde de la luminosité", e);
        }
    }
    private static void restaurer() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level != null && applique) {
                mc.gameRenderer.lightTexture().tick();
                mc.gameRenderer.lightTexture().updateLightTexture(mc.getFrameTime());
            }
        } catch (Exception e) {
            erreur = "L’éclairage normal sera rétabli au prochain rafraîchissement.";
            LogUtils.getLogger().warn("Restauration de la lumière", e);
        } finally { applique = false; }
    }
    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || !actif) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            if (textureField == null) {
                // Recherche par type : fonctionne aussi avec les noms obfusqués du jeu installé.
                for (Field field : LightTexture.class.getDeclaredFields()) {
                    if (field.getType() == DynamicTexture.class) { textureField = field; break; }
                }
                if (textureField == null) throw new IllegalStateException("Lightmap inaccessible");
                textureField.setAccessible(true);
            }
            DynamicTexture texture = (DynamicTexture) textureField.get(mc.gameRenderer.lightTexture());
            NativeImage image = texture.getPixels();
            if (image == null) throw new IllegalStateException("Lightmap absente");
            boolean change = false;
            // Lightmap vanilla de 16 × 16 ; envoyer à la carte graphique seulement si nécessaire.
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                if (image.getPixelRGBA(x, y) != 0xFFFFFFFF) {
                    image.setPixelRGBA(x, y, 0xFFFFFFFF); change = true;
                }
            }
            applique = true;
            if (change) texture.upload();
        } catch (Exception e) {
            actif = false;
            restaurer();
            sauver();
            erreur = "Luminosité maximale indisponible avec ce rendu.";
            LogUtils.getLogger().warn("Luminosité maximale désactivée", e);
        }
    }
}
