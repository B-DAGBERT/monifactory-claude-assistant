package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;

/** API statique du client KubeJS ; aucune adresse ni identité de compte n'est exportée. */
@Mod.EventBusSubscriber(modid = "assistantia", value = Dist.CLIENT)
public final class IdentiteMonde {
    private enum Mode {
        HISTORIQUE,
        ISOLE,
        BLOQUE
    }

    /**
     * Le contexte Architectury Forge conserve la connexion d'origine, contrairement à getPlayer().
     */
    public interface OrigineForge {
        NetworkEvent.Context assistantia$contexte();
    }

    private static final Path DOSSIER = FMLPaths.GAMEDIR.get().resolve("kubejs/assistant");
    private static final Path MARKER = DOSSIER.resolve("isolation_mondes.json");
    private static final SessionMonde ETAT = new SessionMonde();
    private static Mode mode = Mode.HISTORIQUE;
    private static long prochaineLecture = Long.MIN_VALUE, prochaineEcriture = Long.MIN_VALUE;
    private static String derniereVue = "";
    private static Connection adresseConnexion;
    private static String adresseTexte = "", adresseNormalisee = "";
    private static boolean erreurEcriture;

    private IdentiteMonde() {}

    public static synchronized String monde() {
        actualiser();
        return disponible() ? ETAT.monde() : "";
    }

    public static synchronized String session() {
        actualiser();
        return disponible() ? ETAT.session() : "";
    }

    public static synchronized boolean isolation() {
        lireActivation();
        return mode != Mode.HISTORIQUE;
    }

    private static Connection connexion() {
        var lien = Minecraft.getInstance().getConnection();
        return lien == null ? null : lien.getConnection();
    }

    private static boolean disponible() {
        var mc = Minecraft.getInstance();
        return mode != Mode.BLOQUE && mc.level != null && mc.player != null;
    }

    private static void actualiser() {
        lireActivation();
        var mc = Minecraft.getInstance();
        Connection connexion = connexion();
        String type = "", source = "";
        if (connexion != null) {
            var solo = mc.getSingleplayerServer();
            if (solo != null) {
                type = "solo";
                source =
                        solo.getWorldPath(LevelResource.ROOT)
                                .toAbsolutePath()
                                .normalize()
                                .toString();
            } else {
                var serveur = mc.getCurrentServer();
                if (serveur != null) {
                    source = normaliserAdresse(connexion, serveur.ip);
                    if (!source.isEmpty()) type = "serveur";
                }
            }
        }
        ETAT.actualiser(connexion, type, source);
    }

    private static String normaliserAdresse(Connection connexion, String texte) {
        if (texte == null || texte.isEmpty()) return "";
        if (connexion == adresseConnexion && texte.equals(adresseTexte)) return adresseNormalisee;
        adresseConnexion = connexion;
        adresseTexte = texte;
        adresseNormalisee = "";
        if (ServerAddress.isValidAddress(texte)) {
            var adresse = ServerAddress.parseString(texte);
            String host = adresse.getHost().toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (!host.isEmpty()) adresseNormalisee = host + ":" + adresse.getPort();
        }
        return adresseNormalisee;
    }

    public static synchronized void recevoirGregTech(Connection origine, String identifiant) {
        actualiser();
        if (origine == connexion()) ETAT.recevoir(origine, identifiant);
        exporter();
    }

    /** Appelée juste avant l'événement JS, avec le contexte réseau conservé par Architectury. */
    public static synchronized boolean annoterPaquet(Object contexte, CompoundTag donnees) {
        actualiser();
        if (!(contexte instanceof OrigineForge source) || donnees == null) return false;
        NetworkEvent.Context forge = source.assistantia$contexte();
        if (forge == null
                || !ETAT.origineValide(forge.getNetworkManager())
                || forge.getNetworkManager() != connexion()) return false;
        String monde = disponible() ? ETAT.monde() : "";
        String session = disponible() ? ETAT.session() : "";
        // Supprimer une annotation éventuellement reçue du serveur avant de poser la preuve locale.
        donnees.remove("assistant_monde");
        donnees.remove("assistant_session");
        if (monde.isEmpty() || session.isEmpty()) return mode == Mode.HISTORIQUE;
        donnees.putString("assistant_monde", monde);
        donnees.putString("assistant_session", session);
        return true;
    }

    @SubscribeEvent
    public static synchronized void connexion(ClientPlayerNetworkEvent.LoggingIn event) {
        actualiser();
        exporter();
    }

    @SubscribeEvent
    public static synchronized void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!ETAT.deconnecter(event.getConnection())) return;
        // Ne pas réinterroger Minecraft ici : il peut encore référencer l'ancien listener.
        exporterEtat("", "", "");
    }

    @SubscribeEvent
    public static synchronized void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        actualiser();
        exporter();
    }

    private static void lireActivation() {
        long maintenant = System.nanoTime();
        if (maintenant < prochaineLecture) return;
        prochaineLecture = maintenant + 1_000_000_000L;
        try {
            if (Files.notExists(MARKER)) {
                mode = Mode.HISTORIQUE;
                return;
            }
            if (Files.isSymbolicLink(MARKER)
                    || !Files.isRegularFile(MARKER)
                    || Files.size(MARKER) > 8192) {
                mode = Mode.BLOQUE;
                return;
            }
            JsonObject marker = JsonParser.parseString(Files.readString(MARKER)).getAsJsonObject();
            if (!marker.has("v")
                    || !marker.get("v").isJsonPrimitive()
                    || !marker.getAsJsonPrimitive("v").isNumber()
                    || !marker.get("v").getAsString().equals("1")
                    || !marker.has("actif")
                    || !marker.get("actif").isJsonPrimitive()
                    || !marker.getAsJsonPrimitive("actif").isBoolean()) {
                mode = Mode.BLOQUE;
                return;
            }
            if (!marker.get("actif").getAsBoolean()) {
                mode = Mode.HISTORIQUE;
                return;
            }
            mode =
                    texteValide(marker, "monde", "m1_[0-9a-f]{64}")
                                    && texteValide(marker, "revision", "[0-9a-f]{64}")
                                    && texteValide(marker, "preuve_restauration", "[0-9a-f]{64}")
                            ? Mode.ISOLE
                            : Mode.BLOQUE;
        } catch (IOException | RuntimeException e) {
            mode = Mode.BLOQUE;
        }
    }

    private static boolean texteValide(JsonObject objet, String cle, String motif) {
        return objet.has(cle)
                && objet.get(cle).isJsonPrimitive()
                && objet.getAsJsonPrimitive(cle).isString()
                && objet.get(cle).getAsString().matches(motif);
    }

    private static void exporter() {
        var mc = Minecraft.getInstance();
        boolean valide = disponible();
        exporterEtat(
                valide ? ETAT.monde() : "",
                valide ? ETAT.session() : "",
                valide ? mc.level.dimension().location().toString() : "");
    }

    private static void exporterEtat(String monde, String session, String dimension) {
        String signature = monde + "|" + session + "|" + dimension + "|" + mode;
        long maintenant = System.nanoTime();
        if (signature.equals(derniereVue) && maintenant < prochaineEcriture) return;
        prochaineEcriture = maintenant + 1_000_000_000L;
        derniereVue = signature;
        Path temporaire = null;
        try {
            Files.createDirectories(DOSSIER);
            temporaire = Files.createTempFile(DOSSIER, "identite_monde-", ".tmp");
            JsonObject objet = new JsonObject();
            objet.addProperty("v", 1);
            objet.addProperty("monde", monde);
            objet.addProperty("session", session);
            objet.addProperty("dimension", dimension);
            objet.addProperty("t", System.currentTimeMillis());
            Files.writeString(temporaire, objet.toString(), StandardCharsets.UTF_8);
            Path cible = DOSSIER.resolve("identite_monde.json");
            try {
                Files.move(
                        temporaire,
                        cible,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
            }
            temporaire = null;
            derniereVue = signature;
            erreurEcriture = false;
        } catch (IOException | RuntimeException e) {
            if (!erreurEcriture)
                LogUtils.getLogger().warn("Export de l'identité du monde indisponible", e);
            erreurEcriture = true;
        } finally {
            if (temporaire != null) {
                try {
                    Files.deleteIfExists(temporaire);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
