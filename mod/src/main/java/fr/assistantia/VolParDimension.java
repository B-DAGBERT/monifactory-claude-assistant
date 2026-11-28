package fr.assistantia;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Répète la commande de vol après un changement de dimension sur la même connexion. */
public final class VolParDimension {
    public static final String CLE = "local_vol_dimension";
    private static final Path FICHIER =
            FMLPaths.CONFIGDIR.get().resolve("assistantia-vol-dimension.txt");
    private static final int DELAI_TICKS = 10;
    private static volatile boolean actif;
    private static boolean modifie;
    private static ClientPacketListener connexion;
    private static ResourceKey<Level> dimension;
    private static int envoisRestants;
    private static int ticksAvantEnvoi;

    static {
        CompletableFuture.runAsync(VolParDimension::charger);
    }

    private VolParDimension() {}

    public static boolean actif() {
        return actif;
    }

    public static String description() {
        return "Envoie /fly deux fois après chaque changement de dimension."
                + (Ecritures.erreur(FICHIER) == null
                        ? ""
                        : " Sauvegarde de la préférence en attente.");
    }

    public static synchronized void regler(boolean valeur) {
        if (actif == valeur) return;
        modifie = true;
        actif = valeur;
        if (!valeur) envoisRestants = 0;
        Ecritures.publier(FICHIER, Ecritures.texte(Boolean.toString(valeur)).persistant());
    }

    private static void charger() {
        try {
            if (!"true".equals(Files.readString(FICHIER).strip())) return;
        } catch (NoSuchFileException absent) {
            return;
        } catch (IOException | SecurityException inaccessible) {
            LogUtils.getLogger().warn("Lecture de la préférence de vol", inaccessible);
            return;
        }
        synchronized (VolParDimension.class) {
            if (!modifie) actif = true;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener liaison = mc.getConnection();
        if (liaison == null || !liaison.getConnection().isConnected()) return;
        if (liaison != connexion) {
            connexion = liaison;
            dimension = null;
            envoisRestants = 0;
        }
        if (mc.level == null || mc.player == null || mc.player.level() != mc.level) return;

        ResourceKey<Level> actuelle = mc.level.dimension();
        if (!actuelle.equals(dimension)) {
            envoisRestants = actif && dimension != null ? 2 : 0;
            dimension = actuelle;
            ticksAvantEnvoi = DELAI_TICKS;
            return;
        }
        if (!actif || envoisRestants == 0 || mc.screen instanceof ReceivingLevelScreen) return;
        if (--ticksAvantEnvoi > 0) return;

        envoisRestants--;
        ticksAvantEnvoi = DELAI_TICKS;
        liaison.sendCommand("fly");
    }

    @SubscribeEvent
    public static void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        connexion = null;
        dimension = null;
        envoisRestants = 0;
    }
}
