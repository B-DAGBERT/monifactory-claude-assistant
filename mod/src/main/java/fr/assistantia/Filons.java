package fr.assistantia;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;

/** Filons locaux : aucune IA, aucun envoi réseau pour consulter, scanner ou surligner. */
public final class Filons {
    private static final Path DOSSIER = FMLPaths.GAMEDIR.get().resolve("kubejs/assistant");
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Assistant-filons-sauvegarde"); t.setDaemon(true); return t;
    });
    private static FilonMemoire memoire = new FilonMemoire();
    private static FilonMemoire.Scan scan = new FilonMemoire.Scan();
    private static final Map<net.minecraft.world.level.block.Block, Boolean> MINERAIS = new HashMap<>();
    private static List<FilonMemoire.Bloc> controles = List.of(), contours = List.of();
    private static int controle;
    private static String monde = "", affiche = "", guide = "";
    private static long sauvegarde, revision, listeControle, prochainAffichage, prochainScan, prochainGuidage, controleGuidage, tpDebut, contoursDate;
    private static boolean modifie, tpEnCours;
    private static FilonMemoire.Position tpAttendue;
    private static volatile String message = "";
    private static final Set<String> DANGERS = Set.of("lava", "fire", "soul_fire", "cactus", "magma_block",
            "campfire", "soul_campfire", "powder_snow", "sweet_berry_bush", "wither_rose", "pointed_dripstone");
    private Filons() { }
    public static FilonMemoire memoire() { return memoire; }
    public static String monde() { return monde; }
    public static String affiche() { return affiche; }
    public static String guide() { return guide; }
    public static String message() { return message; }
    public static long revision() { return revision; }
    public static int avancee() { return scan.pourcentage(); }
    public static boolean scanActif() { return scan.actif(); }
    public static boolean scanPonctuel() { return scan.ponctuel(); }
    public static String etatScan() {
        if (scan.ponctuel()) return "Scan ponctuel : " + avancee() + " %";
        if (scan.actif()) return "Scan auto : " + avancee() + " %";
        return scan.termine() ? "Scan terminé : 100 % · en pause" : "Scan en pause";
    }
    public static void scannerAutour() {
        if (monde.isEmpty() || !boutonScanDisponible()) return;
        if (scan.ponctuel()) { reglerScan(false); return; }
        scan.lancerPonctuel(position()); prochainScan = System.currentTimeMillis() + 650;
        message = "Scan ponctuel démarré : arrêt automatique après un passage, sans chargement de chunks.";
        modifie = true; sauver(true);
    }
    public static boolean boutonScanDisponible() { return System.currentTimeMillis() >= prochainScan; }
    public static void reglerScan(boolean actif) {
        if (monde.isEmpty() || !boutonScanDisponible() || !scan.activer(actif)) return;
        prochainScan = System.currentTimeMillis() + 650;
        message = actif ? "Scan repris : repérage et vérification des minerais."
                : "Scan en pause : aucun repérage ni revérification. Filons conservés.";
        modifie = true;
        sauver(true); // une sauvegarde à la bascule, aucun scan pendant la pause
    }
    public static boolean tpEnCours() { return tpEnCours; }
    public static boolean affichageDisponible() { return System.currentTimeMillis() >= prochainAffichage; }
    public static FilonMemoire.Position position() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null || mc.level == null ? new FilonMemoire.Position("", 0, 0, 0)
                : position(mc.level, mc.player.blockPosition());
    }
    private static FilonMemoire.Position position(Level niveau, BlockPos p) {
        return new FilonMemoire.Position(niveau.dimension().location().toString(), p.getX(), p.getY(), p.getZ());
    }
    private static BlockPos bloc(FilonMemoire.Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private static String identite(Minecraft mc) {
        String origine = mc.getSingleplayerServer() != null
                ? "solo:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                : mc.getCurrentServer() != null ? "serveur:" + mc.getCurrentServer().ip : "";
        if (origine.isEmpty()) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(origine.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Path fichier(String id) { return DOSSIER.resolve("filons").resolve(id + ".json"); }
    private static void charger(String id) {
        memoire = new FilonMemoire(); scan = new FilonMemoire.Scan();
        controles = contours = List.of(); controle = 0; affiche = guide = "";
        tpEnCours = false; tpAttendue = null; message = ""; monde = id;
        MINERAIS.clear(); listeControle = contoursDate = sauvegarde = 0;
        if (Files.exists(fichier(id))) {
            try {
                JsonObject data = JsonParser.parseString(Files.readString(fichier(id))).getAsJsonObject();
                if (!id.equals(Pont.texte(data, "monde"))) throw new IllegalArgumentException("Mauvais monde");
                scan.activer(!data.has("scan_actif") || Pont.vrai(data, "scan_actif"));
                for (JsonElement e : Pont.liste(data, "blocs")) {
                    JsonObject b = e.getAsJsonObject();
                    FilonMemoire.Position p = lirePosition(b);
                    JsonObject ancre = b.getAsJsonObject("ancre");
                    memoire.restaurer(p, Pont.texte(b, "bloc"), Pont.vrai(b, "mine"),
                            (long) Pont.nombre(b, "t", 0), lirePosition(ancre));
                }
            } catch (Exception e) {
                try { Files.copy(fichier(id), fichier(id).resolveSibling(id + ".illisible-" + System.currentTimeMillis())); }
                catch (Exception copie) { LogUtils.getLogger().warn("Sauvegarde filons illisible", copie); }
                message = "Mémoire partiellement illisible : copie conservée, nouveau repérage en cours.";
                LogUtils.getLogger().warn("Lecture des filons", e);
            }
        }
        importerAnciens(id);
        modifie = true; revision++;
    }
    private static void importerAnciens(String id) {
        Path demande = DOSSIER.resolve("filons/imports").resolve(id + ".json");
        if (!Files.exists(demande)) return;
        try {
            JsonObject data = JsonParser.parseString(Files.readString(demande)).getAsJsonObject();
            if (!id.equals(Pont.texte(data, "monde")) || Pont.nombre(data, "v", 0) != 1)
                throw new IllegalArgumentException("L'import ne vise pas ce monde");
            int importes = 0, invalides = 0;
            for (JsonElement e : Pont.liste(data, "blocs")) {
                try {
                    JsonObject b = e.getAsJsonObject();
                    String minerai = Pont.texte(b, "bloc");
                    ResourceLocation nom = ResourceLocation.tryParse(minerai);
                    if (nom == null || !ForgeRegistries.BLOCKS.containsKey(nom)) { invalides++; continue; }
                    if (memoire.importer(lirePosition(b), minerai, Pont.vrai(b, "mine"),
                            (long) Pont.nombre(b, "t", 0))) importes++;
                } catch (RuntimeException invalide) { invalides++; }
            }
            if (importes > 0 || invalides > 0)
                message = importes + " anciens blocs intégrés ; état à revérifier dans les zones chargées."
                        + (invalides > 0 ? " " + invalides + " entrées invalides ignorées." : "");
            // Garder la demande permet de reprendre après une erreur de sauvegarde. L'import est idempotent.
        } catch (Exception e) {
            message = "Import des anciens filons impossible : demande conservée pour réessayer.";
            LogUtils.getLogger().warn("Import des anciens filons", e);
        }
    }

    private static FilonMemoire.Position lirePosition(JsonObject b) {
        String dim = Pont.texte(b, "dimension");
        int x = (int) Pont.nombre(b, "x", 0), y = (int) Pont.nombre(b, "y", 0), z = (int) Pont.nombre(b, "z", 0);
        if (ResourceLocation.tryParse(dim) == null || Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000
                || Math.abs((long)y) > 4096) throw new IllegalArgumentException("Coordonnées invalides");
        return new FilonMemoire.Position(dim, x, y, z);
    }
    private static JsonObject jsonPosition(FilonMemoire.Position p) {
        JsonObject j = new JsonObject(); j.addProperty("dimension", p.dimension());
        j.addProperty("x", p.x()); j.addProperty("y", p.y()); j.addProperty("z", p.z()); return j;
    }
    private static void sauver(boolean force) {
        if (monde.isEmpty() || (!force && (!modifie || System.currentTimeMillis() - sauvegarde < 10000))) return;
        JsonObject j = new JsonObject(); j.addProperty("v", 1); j.addProperty("monde", monde);
        j.addProperty("scan_actif", scan.actif() && !scan.ponctuel()); // un scan ponctuel ne reprend jamais en boucle au redémarrage
        j.addProperty("t", System.currentTimeMillis()); JsonArray blocs = new JsonArray();
        for (FilonMemoire.Bloc b : memoire.blocs()) {
            JsonObject o = jsonPosition(b.position); o.addProperty("bloc", b.id);
            o.addProperty("mine", b.retire); o.addProperty("t", b.vu); o.addProperty("cle", b.position.cle());
            o.add("ancre", jsonPosition(memoire.filon(b.filon).ancre)); blocs.add(o);
        }
        j.add("blocs", blocs); String texte = j.toString(); Path destination = fichier(monde);
        modifie = false; sauvegarde = System.currentTimeMillis();
        IO.execute(() -> {
            try { ecrire(destination, texte); ecrire(DOSSIER.resolve("filons_mod.json"), texte); }
            catch (Exception e) {
                message = "Sauvegarde des filons impossible : vérifier l'espace disque.";
                Minecraft.getInstance().execute(() -> modifie = true);
                LogUtils.getLogger().warn("Écriture des filons", e);
            }
        });
    }
    private static void ecrire(Path p, String texte) throws java.io.IOException {
        Files.createDirectories(p.getParent()); Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, texte, StandardCharsets.UTF_8);
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static boolean minerai(BlockState s) {
        return MINERAIS.computeIfAbsent(s.getBlock(), b -> {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
            return (id != null && (id.getPath().endsWith("_ore") || id.getPath().equals("ancient_debris")))
                    || s.getTags().anyMatch(t -> t.location().getNamespace().equals("forge")
                    && (t.location().getPath().equals("ores") || t.location().getPath().startsWith("ores/")));
        });
    }
    private static void observer(Level niveau, FilonMemoire.Position p, long now) {
        if (!niveau.dimension().location().toString().equals(p.dimension()) || niveau.isOutsideBuildHeight(bloc(p))
                || !niveau.hasChunkAt(bloc(p))) return;
        BlockState s = niveau.getBlockState(bloc(p));
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id != null && memoire.observer(p, id.toString(), minerai(s), now)) { modifie = true; revision++; }
    }
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance(); long now = System.currentTimeMillis();
        if (mc.level == null || mc.player == null) {
            if (!monde.isEmpty()) { sauver(true); monde = ""; affiche = guide = ""; tpEnCours = false; }
            return;
        }
        String id = identite(mc);
        if (id.isEmpty()) return;
        if (!id.equals(monde)) { sauver(true); charger(id); }
        if (tpEnCours && tpAttendue != null) {
            if (position().dimension().equals(tpAttendue.dimension()) && mc.player.position().distanceToSqr(
                    tpAttendue.x()+.5, tpAttendue.y(), tpAttendue.z()+.5) < .5) finirTP(monde, "Téléportation effectuée.");
            else if (now - tpDebut > 6000) finirTP(monde, "Téléportation non confirmée : vérifier le message du serveur.");
        }
        if (!mc.isPaused() && !guide.isEmpty() && now >= controleGuidage) {
            controleGuidage = now + 250;
            var cible = memoire.filon(guide);
            if (cible != null && cible.arrive(position())) {
                var destination = bloc(cible.destination(position()));
                if (mc.level.hasChunkAt(destination) && minerai(mc.level.getBlockState(destination))) {
                    guide = ""; message = "Tu es arrivé au filon : guidage arrêté.";
                    mc.player.displayClientMessage(Component.literal(message), true);
                }
            }
        }
        if (mc.isPaused() || !scan.actif()) { sauver(false); return; }
        if (now - listeControle > 10000) { controles = new ArrayList<>(memoire.blocs()); listeControle = now; }
        long limite = System.nanoTime() + 1_000_000; // 1 ms au plus entre deux observations, 224 blocs maximum
        for (int n = 0; n < 32 && !controles.isEmpty() && System.nanoTime() < limite; n++) {
            if (controle >= controles.size()) controle = 0;
            observer(mc.level, controles.get(controle++).position, now);
        }
        FilonMemoire.Position joueur = position();
        boolean ponctuel = scan.ponctuel();
        for (int n = 0; n < 192 && System.nanoTime() < limite; n++) {
            var suivante = scan.suivant(joueur);
            if (suivante == null) break;
            observer(mc.level, suivante, now);
        }
        if (ponctuel && !scan.actif()) {
            message = scan.termine() ? "Scan terminé : 100 % de la zone parcourue. Scan en pause ; relevé limité aux blocs chargés."
                    : "Scan interrompu : changement de dimension. Données conservées, scan en pause.";
            modifie = true; sauver(true);
            mc.player.displayClientMessage(Component.literal(message), true);
        }
        sauver(false);
    }
    public static void afficher(String id, boolean actif) {
        if (!affichageDisponible()) return;
        if (actif && memoire.filon(id) == null) return;
        affiche = actif ? id : ""; contoursDate = 0; prochainAffichage = System.currentTimeMillis() + 650;
        message = actif ? "Surbrillance activée pour ce filon." : "Surbrillance désactivée.";
    }
    public static boolean guidageDisponible() { return System.currentTimeMillis() >= prochainGuidage; }
    public static void basculerGuidage(String id) {
        if (!guidageDisponible()) return;
        FilonMemoire.Filon f = memoire.filon(id);
        if (f == null) return;
        if (id.equals(guide)) {
            guide = ""; message = "Guidage arrêté.";
        } else {
            if (!f.ancre.dimension().equals(position().dimension())) { message = "Rejoins d'abord la dimension du filon."; return; }
            guide = id; message = "Guidage activé : suis le faisceau bleu.";
        }
        prochainGuidage = System.currentTimeMillis() + 650;
    }
    public static String nom(String bloc) { return Pont.objet(bloc).getHoverName().getString(); }
    public static boolean utile(FilonMemoire.Filon f) {
        for (String ore : f.types().keySet()) {
            String mat = ore.substring(ore.indexOf(':') + 1).replaceFirst("^(deepslate_|netherrack_|endstone_|stone_)", "")
                    .replaceFirst("_ore$", "");
            for (String besoin : Pont.besoins()) {
                String chemin = besoin.substring(besoin.indexOf(':') + 1);
                if (chemin.equals(mat) || chemin.startsWith(mat + "_") || (chemin.equals("raw_" + mat) || chemin.startsWith("raw_" + mat + "_"))
                        || chemin.endsWith("_" + mat) || chemin.contains("_" + mat + "_")) return true;
            }
        }
        return false;
    }
    public static String raisonTP(FilonMemoire.Filon f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || f == null) return "Aucun filon sélectionné.";
        if (!mc.player.hasPermissions(2)) return "Téléportation : commandes / permission opérateur nécessaires.";
        if (tpEnCours) return "Téléportation en cours…";
        if (System.currentTimeMillis() - tpDebut < 1000) return "Patiente un instant.";
        if (mc.getSingleplayerServer() == null) {
            if (!f.ancre.dimension().equals(position().dimension())) return "Sur serveur, rejoins d'abord cette dimension.";
            if (!mc.level.hasChunkAt(bloc(f.destination(position())))) return "Sur serveur, approche-toi : zone non chargée, sécurité invérifiable.";
        }
        return "";
    }
    private static boolean sur(Level niveau, FilonMemoire.Position p) {
        BlockPos pieds = bloc(p);
        if (p.y() <= niveau.getMinBuildHeight() || p.y() + 1 >= niveau.getMaxBuildHeight()
                || !niveau.getWorldBorder().isWithinBounds(pieds)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!niveau.hasChunkAt(pieds.offset(dx, 0, dz))) return false;
            for (int dy = -1; dy <= 1; dy++) {
                BlockState s = niveau.getBlockState(pieds.offset(dx, dy, dz));
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
                if (!s.getFluidState().isEmpty() || (id != null && DANGERS.contains(id.getPath()))) return false;
            }
        }
        return niveau.getBlockState(pieds).isAir() && niveau.getBlockState(pieds.above()).isAir()
                && niveau.getBlockState(pieds.below()).isFaceSturdy(niveau, pieds.below(), Direction.UP)
                && niveau.noCollision(new AABB(p.x() + .2, p.y(), p.z() + .2, p.x() + .8, p.y() + 1.8, p.z() + .8));
    }
    public static void teleporter(String id) {
        Minecraft mc = Minecraft.getInstance(); FilonMemoire.Filon f = memoire.filon(id);
        String raison = raisonTP(f);
        if (!raison.isEmpty()) { message = raison; return; }
        FilonMemoire.Position cible = f.destination(position()); String origine = monde;
        tpEnCours = true; tpDebut = System.currentTimeMillis(); message = "Recherche d'un emplacement libre…";
        var serveur = mc.getSingleplayerServer();
        if (serveur != null) {
            UUID joueur = mc.player.getUUID();
            serveur.execute(() -> {
                try {
                    var p = serveur.getPlayerList().getPlayer(joueur);
                    ServerLevel niveau = serveur.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse(cible.dimension())));
                    if (p == null || !p.hasPermissions(2) || niveau == null) {
                        finirTP(origine, "Téléportation refusée : joueur, dimension ou permission indisponible."); return;
                    }
                    // Solo : seulement les chunks autour de la destination explicitement demandée (au plus 9).
                    for (int x = Math.floorDiv(cible.x()-9,16); x <= Math.floorDiv(cible.x()+9,16); x++)
                        for (int z = Math.floorDiv(cible.z()-9,16); z <= Math.floorDiv(cible.z()+9,16); z++) niveau.getChunk(x,z);
                    var libre = FilonMemoire.destinationSure(cible, pos -> sur(niveau, pos));
                    if (libre == null) { finirTP(origine, "Aucun emplacement sûr à 8 blocs du filon. Téléportation annulée."); return; }
                    p.teleportTo(niveau, libre.x()+.5, libre.y(), libre.z()+.5, p.getYRot(), p.getXRot());
                    finirTP(origine, "Téléportation effectuée près du filon.");
                } catch (Exception e) {
                    LogUtils.getLogger().warn("Téléportation filon", e); finirTP(origine, "Téléportation impossible : voir le journal du jeu.");
                }
            });
        } else {
            var libre = FilonMemoire.destinationSure(cible, p -> sur(mc.level, p));
            if (libre == null) { finirTP(origine, "Aucun emplacement sûr et chargé près du filon. Téléportation annulée."); return; }
            if (mc.player.position().distanceToSqr(libre.x()+.5,libre.y(),libre.z()+.5)<.5) {
                finirTP(origine,"Tu es déjà à cet emplacement près du filon."); return;
            }
            tpAttendue = libre;
            mc.player.connection.sendCommand("tp @s " + (libre.x()+.5) + " " + libre.y() + " " + (libre.z()+.5));
            message = "Commande envoyée : attente de la position confirmée…";
        }
    }
    private static void finirTP(String origine, String texte) {
        Minecraft.getInstance().execute(() -> {
            if (!monde.equals(origine)) return;
            tpEnCours = false; tpAttendue = null; message = texte;
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.displayClientMessage(Component.literal(texte), true);
        });
    }
    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return;
        FilonMemoire.Position joueur = position(); Vec3 camera = event.getCamera().getPosition();
        var pose = event.getPoseStack(); var buffers = mc.renderBuffers().bufferSource();
        FilonMemoire.Filon f = memoire.filon(affiche);
        if (f != null && f.ancre.dimension().equals(joueur.dimension())) {
            if (System.currentTimeMillis() - contoursDate > 500) {
                contours = f.blocs.stream().filter(b -> !b.retire && b.position.distance2(joueur) <= 64*64)
                        .sorted(Comparator.comparingDouble(b -> b.position.distance2(joueur))).limit(768).toList();
                contoursDate = System.currentTimeMillis();
            }
            VertexConsumer lignes = buffers.getBuffer(Lignes.TRAVERSANTES);
            for (var b : contours) {
                if (!mc.level.hasChunkAt(bloc(b.position)) || !minerai(mc.level.getBlockState(bloc(b.position)))) continue;
                int couleur = net.minecraft.util.Mth.hsvToRgb((b.id.hashCode() & 0xffff) / 65536F, .6F, 1F);
                AABB boite = new AABB(bloc(b.position)).inflate(.01).move(-camera.x, -camera.y, -camera.z);
                LevelRenderer.renderLineBox(pose, lignes, boite, ((couleur>>16)&255)/255F,
                        ((couleur>>8)&255)/255F, (couleur&255)/255F, .9F);
            }
            buffers.endBatch(Lignes.TRAVERSANTES);
        }
        FilonMemoire.Filon destination = memoire.filon(guide);
        if (destination != null && destination.ancre.dimension().equals(joueur.dimension())) {
            var p = destination.destination(joueur);
            if (p.distance2(joueur) < 9) return;
            pose.pushPose(); pose.translate(p.x()-camera.x, p.y()+1-camera.y, p.z()-camera.z);
            BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, event.getPartialTick(), 1F,
                    mc.level.getGameTime(), 0, 96, new float[]{.3F,.7F,1F}, .12F,.18F);
            pose.popPose(); buffers.endBatch();
        }
    }
}
