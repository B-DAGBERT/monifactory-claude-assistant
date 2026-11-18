package fr.assistantia;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.fml.loading.FMLPaths;

/** Préférence client pour les calculs de fabrication. L'inventaire physique reste indépendant. */
public final class SacsFabrication {
    public static final String CLE = "local_backpack_craft";
    private static final Path FICHIER =
            FMLPaths.CONFIGDIR.get().resolve("assistantia-sacs-craft.txt");

    private record Etat(boolean actif, long revision) {}

    private static volatile Etat etat = new Etat(true, 0);

    static {
        CompletableFuture.runAsync(SacsFabrication::charger);
    }

    private SacsFabrication() {}

    public static boolean actif() {
        return etat.actif();
    }

    static long revision() {
        return etat.revision();
    }

    public static String description() {
        return "Compte les objets des sacs portés dans les besoins de fabrication."
                + (Ecritures.erreur(FICHIER) == null
                        ? ""
                        : " Sauvegarde de la préférence en attente.");
    }

    public static synchronized void regler(boolean valeur) {
        if (etat.actif() == valeur) return;
        etat = new Etat(valeur, etat.revision() + 1);
        Ecritures.publier(FICHIER, Ecritures.texte(Boolean.toString(valeur)).persistant());
    }

    private static void charger() {
        try {
            if (!"false".equals(Files.readString(FICHIER).strip())) return;
        } catch (NoSuchFileException absent) {
            return;
        } catch (IOException | SecurityException inaccessible) {
            LogUtils.getLogger()
                    .warn("Lecture de la préférence des sacs pour les crafts", inaccessible);
            return;
        }
        synchronized (SacsFabrication.class) {
            // Une préférence changée pendant la lecture reste prioritaire sur l'ancien fichier.
            if (etat.revision() == 0) etat = new Etat(false, 1);
        }
    }
}
