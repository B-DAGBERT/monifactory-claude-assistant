package fr.assistantia;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.fml.loading.FMLPaths;

/** Préférence client pour les repères des drawers pleins. */
public final class ReglageDrawers {
    public static final String CLE = "local_drawers_pleins";
    private static final Path FICHIER =
            FMLPaths.CONFIGDIR.get().resolve("assistantia-drawers-pleins.txt");
    private static volatile boolean actif = true;
    private static boolean modifie;

    static {
        CompletableFuture.runAsync(ReglageDrawers::charger);
    }

    private ReglageDrawers() {}

    public static boolean actif() {
        return actif;
    }

    public static String description() {
        return "Croix rouge sur un drawer plein. Croix orange si son surplus est détruit."
                + (Ecritures.erreur(FICHIER) == null
                        ? ""
                        : " Sauvegarde de la préférence en attente.");
    }

    public static synchronized void regler(boolean valeur) {
        if (actif == valeur) return;
        modifie = true;
        actif = valeur;
        Ecritures.publier(FICHIER, Ecritures.texte(Boolean.toString(valeur)).persistant());
    }

    private static void charger() {
        try {
            if (!"false".equals(Files.readString(FICHIER).strip())) return;
        } catch (NoSuchFileException absent) {
            return;
        } catch (IOException | SecurityException inaccessible) {
            LogUtils.getLogger().warn("Lecture de la préférence des drawers pleins", inaccessible);
            return;
        }
        synchronized (ReglageDrawers.class) {
            if (!modifie) actif = false;
        }
    }
}
