package fr.assistantia;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Adaptateur GTCEu 7 : données de prospection natives, aucune génération de terrain. */
public final class GregTechFilons {
    private static final String BASE = "com.gregtechceu.gtceu.";
    private static Class<?> manager, client, worldCache, oreBlock;
    private static Field attente, cache, material;
    private static boolean initialise, indisponible;
    private static String erreur = "";
    private static final Map<Object, Set<String>> compositions = new IdentityHashMap<>();
    private static final Map<Object, Set<String>> blocsParMatiere = new HashMap<>();
    private static String dernierMonde = "";

    private GregTechFilons() {}

    private static void init() throws ReflectiveOperationException {
        if (indisponible) throw new ClassNotFoundException(erreur);
        if (initialise) return;
        manager = Class.forName(BASE + "integration.map.ClientCacheManager");
        client = Class.forName(BASE + "integration.map.cache.client.GTClientCache");
        worldCache = Class.forName(BASE + "integration.map.cache.WorldCache");
        oreBlock = Class.forName(BASE + "api.block.OreBlock");
        material = Class.forName(BASE + "api.block.MaterialBlock").getField("material");
        attente = manager.getDeclaredField("shouldInit");
        attente.setAccessible(true);
        cache = worldCache.getDeclaredField("cache");
        cache.setAccessible(true);
        for (Block b : ForgeRegistries.BLOCKS)
            if (oreBlock.isInstance(b)) {
                var id = ForgeRegistries.BLOCKS.getKey(b);
                if (id != null)
                    blocsParMatiere
                            .computeIfAbsent(material.get(b), k -> new HashSet<>())
                            .add(id.toString());
            }
        initialise = true;
    }

    private static Object appel(Object objet, String nom) throws ReflectiveOperationException {
        return objet.getClass().getMethod(nom).invoke(objet);
    }

    private static void echec(Exception e) {
        if (erreur.isEmpty())
            LogUtils.getLogger().warn("Lecture des filons GregTech indisponible", e);
        erreur = "Données GregTech indisponibles : vérifier la version du mod.";
    }

    public static String erreur() {
        return erreur;
    }

    public static String monde() {
        try {
            init();
            if (attente.getBoolean(null)) return "";
            File dossier = (File) manager.getMethod("getWorldFolder").invoke(null);
            String id =
                    dossier == null ? "" : dossier.toPath().toAbsolutePath().normalize().toString();
            if (!id.equals(dernierMonde)) {
                compositions.clear();
                dernierMonde = id;
            }
            return id;
        } catch (ReflectiveOperationException | RuntimeException e) {
            echec(e);
            indisponible = true;
            return "";
        }
    }

    private static Set<String> composition(Object definition) throws ReflectiveOperationException {
        if (compositions.containsKey(definition)) return compositions.get(definition);
        Set<String> ids = new TreeSet<>();
        if (definition != null) {
            Object generateur = appel(definition, "veinGenerator");
            for (Object mat : (List<?>) appel(generateur, "getAllMaterials"))
                ids.addAll(blocsParMatiere.getOrDefault(mat, Set.of()));
            for (Object bloc : (List<?>) appel(generateur, "getAllBlocks")) {
                var id = ForgeRegistries.BLOCKS.getKey(((BlockState) bloc).getBlock());
                if (id != null) ids.add(id.toString());
            }
        }
        compositions.put(definition, Set.copyOf(ids));
        return ids;
    }

    public static boolean lire(FilonMemoire memoire) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || monde().isEmpty()) return false;
        try {
            Object instance = client.getField("instance").get(null);
            Object dimension = ((Map<?, ?>) cache.get(instance)).get(mc.level.dimension());
            if (dimension == null) return false;
            Map<?, ?> grilles = (Map<?, ?>) appel(dimension, "getCache");
            boolean change = false;
            for (Object grille : grilles.values())
                for (Object vein : (List<?>) appel(grille, "getVeins")) {
                    BlockPos centre = (BlockPos) appel(vein, "center");
                    ChunkPos origine = (ChunkPos) appel(vein, "originChunk");
                    String type = appel(vein, "id").toString();
                    String dim = mc.level.dimension().location().toString();
                    String cle =
                            "gt|"
                                    + dim
                                    + "|"
                                    + origine.x
                                    + "|"
                                    + origine.z
                                    + "|"
                                    + type
                                    + "|"
                                    + centre.asLong();
                    String nom =
                            ((Component)
                                            Class.forName(
                                                            BASE
                                                                    + "integration.map.layer.builtin.OreRenderLayer")
                                                    .getMethod("getName", vein.getClass())
                                                    .invoke(null, vein))
                                    .getString();
                    Object definition = appel(vein, "definition");
                    change |=
                            memoire.gisement(
                                    cle,
                                    new FilonMemoire.Position(
                                            dim, centre.getX(), centre.getY(), centre.getZ()),
                                    type,
                                    nom,
                                    composition(definition),
                                    (boolean) appel(vein, "depleted"));
                    memoire.filon(cle).definitionNative = definition;
                }
            erreur = "";
            return change;
        } catch (ReflectiveOperationException | RuntimeException e) {
            echec(e);
            return false;
        }
    }

    static FilonMemoire.VolumeScan volumeScan(FilonMemoire.Filon filon) {
        if (filon.definitionNative == null) return null;
        try {
            Object generateur = appel(filon.definitionNative, "veinGenerator");
            Class<?> type = generateur.getClass();
            if (!type.getName()
                    .equals(BASE + "api.data.worldgen.generator.veins.CuboidVeinGenerator"))
                return null;
            var taille =
                    (net.minecraft.util.valueproviders.IntProvider)
                            appel(filon.definitionNative, "clusterSize");
            int couches =
                    couches(type, generateur, "top", 2)
                            + couches(type, generateur, "middle", 3)
                            + couches(type, generateur, "bottom", 2);
            return FilonMemoire.VolumeScan.cuboide(
                    filon.ancre,
                    taille.getMaxValue(),
                    champ(type, "minY").getInt(generateur),
                    champ(type, "maxY").getInt(generateur),
                    couches);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // définition absente ou inconnue : conserver le scan de proximité
        }
    }

    private static Field champ(Class<?> type, String nom) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(nom);
        field.setAccessible(true);
        return field;
    }

    private static int couches(Class<?> type, Object generateur, String nom, int defaut)
            throws ReflectiveOperationException {
        Object couche = champ(type, nom).get(generateur);
        int nombre = couche.getClass().getField("layers").getInt(couche);
        return nombre == -1 ? defaut : nombre;
    }

    /** En solo seulement : prospection GT native des 9 chunks déjà chargés au point de départ. */
    public static void prospecterSolo() {
        Minecraft mc = Minecraft.getInstance();
        var serveur = mc.getSingleplayerServer();
        if (serveur == null || mc.player == null || mc.level == null) return;
        var uuid = mc.player.getUUID();
        var dim = mc.level.dimension();
        var centre = mc.player.chunkPosition();
        serveur.execute(
                () -> {
                    try {
                        ServerPlayer joueur = serveur.getPlayerList().getPlayer(uuid);
                        ServerLevel niveau = serveur.getLevel(dim);
                        if (joueur == null
                                || niveau == null
                                || !joueur.level().dimension().equals(dim)) return;
                        Class<?> classe =
                                Class.forName(BASE + "integration.map.cache.server.ServerCache");
                        Object instance = classe.getField("instance").get(null);
                        classe.getMethod("maybeInitWorld", ServerLevel.class)
                                .invoke(instance, niveau);
                        Method prospecter =
                                classe.getMethod(
                                        "prospectAllInChunk",
                                        ResourceKey.class,
                                        ChunkPos.class,
                                        ServerPlayer.class);
                        for (int x = centre.x - 1; x <= centre.x + 1; x++)
                            for (int z = centre.z - 1; z <= centre.z + 1; z++)
                                if (niveau.hasChunk(x, z))
                                    prospecter.invoke(instance, dim, new ChunkPos(x, z), joueur);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        mc.execute(() -> echec(e));
                    }
                });
    }
}
