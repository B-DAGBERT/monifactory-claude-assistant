package fr.assistantia;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/**
 * Adaptateur GTCEu 7 : données de prospection natives, bornes des générateurs, matériaux des
 * minerais. Aucune génération de terrain, uniquement de la lecture par réflexion.
 */
public final class GregTechFilons {
    private static final String BASE = "com.gregtechceu.gtceu.";
    private static final String VEINES = BASE + "api.data.worldgen.generator.veins.";
    private static Class<?> manager, client, worldCache, oreBlock;
    private static Field attente, cache, material;
    private static boolean initialise, indisponible;
    private static String erreur = "";
    private static final Map<Object, Set<String>> compositions = new IdentityHashMap<>();
    private static final Map<Object, Set<String>> blocsParMatiere = new HashMap<>();
    private static final Map<String, Object> matiereParBloc = new HashMap<>();
    private static String dernierMonde = "";
    private static Method nomVeine, materialStack, stackMatiere, propriete;
    private static Object proprieteMinerai;
    private static boolean chimie, chimieIndisponible;

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
                if (id == null) continue;
                Object matiere = material.get(b);
                blocsParMatiere.computeIfAbsent(matiere, k -> new HashSet<>()).add(id.toString());
                matiereParBloc.put(id.toString(), matiere);
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
            String dim = mc.level.dimension().location().toString();
            for (Object grille : grilles.values())
                for (Object vein : (List<?>) appel(grille, "getVeins")) {
                    BlockPos centre = (BlockPos) appel(vein, "center");
                    ChunkPos origine = (ChunkPos) appel(vein, "originChunk");
                    String type = appel(vein, "id").toString();
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
                    if (nomVeine == null)
                        nomVeine =
                                Class.forName(BASE + "integration.map.layer.builtin.OreRenderLayer")
                                        .getMethod("getName", vein.getClass());
                    String nom = ((Component) nomVeine.invoke(null, vein)).getString();
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
                    var f = memoire.filon(cle);
                    f.definitionNative = definition;
                    if (f.volume == null) {
                        f.volume = volumeScan(f);
                        if (f.volume != null) change = true;
                    }
                }
            erreur = "";
            return change;
        } catch (ReflectiveOperationException | RuntimeException e) {
            echec(e);
            return false;
        }
    }

    // ---------- Bornes des générateurs ----------

    private static final Map<String, Optional<Class<?>>> CLASSES = new HashMap<>();

    private static Class<?> classe(String nom) {
        return CLASSES.computeIfAbsent(
                        nom,
                        n -> {
                            try {
                                return Optional.of(Class.forName(VEINES + n));
                            } catch (ClassNotFoundException e) {
                                return Optional.empty();
                            }
                        })
                .orElse(null);
    }

    private static boolean est(Object generateur, String nom) {
        Class<?> c = classe(nom);
        return c != null && c.isInstance(generateur);
    }

    private static Field champ(Class<?> type, String nom) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            try {
                Field field = c.getDeclaredField(nom);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // champ hérité : continuer vers la classe mère
            }
        throw new NoSuchFieldException(nom);
    }

    private static int entier(Object objet, String nom) throws ReflectiveOperationException {
        return champ(objet.getClass(), nom).getInt(objet);
    }

    private static int couches(Object generateur, String nom, int defaut)
            throws ReflectiveOperationException {
        Object couche = champ(generateur.getClass(), nom).get(generateur);
        int nombre = couche.getClass().getField("layers").getInt(couche);
        return nombre == -1 ? defaut : nombre;
    }

    /**
     * Boîte du générateur natif du gisement, d'après ses paramètres réels (GTCEu 7.5) ; un
     * générateur inconnu donne un cube autour du centre avec une marge de remplissage.
     */
    static VolumeFilon volumeScan(FilonMemoire.Filon filon) {
        Object definition = filon.definitionNative;
        int cx = filon.ancre.x(), cy = filon.ancre.y(), cz = filon.ancre.z();
        if (definition == null) return VolumeFilon.inconnu(cx, cy, cz, 16, 16);
        try {
            Object generateur = appel(definition, "veinGenerator");
            int taille = ((IntProvider) appel(definition, "clusterSize")).getMaxValue();
            VolumeFilon v;
            if (est(generateur, "LayeredVeinGenerator"))
                v = VolumeFilon.couches(cx, cy, cz, taille);
            else if (est(generateur, "VeinedVeinGenerator"))
                v = VolumeFilon.veine(cx, cy, cz, taille);
            else if (est(generateur, "DikeVeinGenerator")) v = dyke(generateur, cx, cz, taille);
            else if (est(generateur, "CuboidVeinGenerator"))
                v =
                        VolumeFilon.cuboide(
                                cx,
                                cy,
                                cz,
                                taille,
                                entier(generateur, "minY"),
                                entier(generateur, "maxY"),
                                couches(generateur, "top", 2)
                                        + couches(generateur, "middle", 3)
                                        + couches(generateur, "bottom", 2));
            else if (est(generateur, "ClassicVeinGenerator"))
                v = VolumeFilon.classique(cx, cy, cz, taille, entier(generateur, "yRadius"));
            else if (est(generateur, "StandardVeinGenerator"))
                v = VolumeFilon.standard(cx, cy, cz, taille);
            else if (est(generateur, "GeodeVeinGenerator"))
                v =
                        VolumeFilon.geode(
                                cx,
                                cy,
                                cz,
                                entier(generateur, "minGenOffset"),
                                entier(generateur, "maxGenOffset"));
            else if (est(generateur, "NoopVeinGenerator")) return null;
            else v = VolumeFilon.inconnu(cx, cy, cz, taille > 0 ? (taille + 1) / 2 : 16, 16);
            return v.acceptable() ? v : VolumeFilon.inconnu(cx, cy, cz, 16, 16);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return VolumeFilon.inconnu(cx, cy, cz, 16, 16); // définition illisible : repli prudent
        }
    }

    /** Hauteurs du dyke : boucle du générateur bornée par les hauteurs de ses blocs. */
    private static VolumeFilon dyke(Object generateur, int cx, int cz, int taille)
            throws ReflectiveOperationException {
        int bas = entier(generateur, "minYLevel"), haut = entier(generateur, "maxYLevel");
        int basBlocs = Integer.MAX_VALUE, hautBlocs = Integer.MIN_VALUE;
        Object blocs = champ(generateur.getClass(), "blocks").get(generateur);
        if (blocs instanceof List<?> liste)
            for (Object b : liste) {
                basBlocs = Math.min(basBlocs, (int) appel(b, "minY"));
                hautBlocs = Math.max(hautBlocs, (int) appel(b, "maxY"));
            }
        if (basBlocs <= hautBlocs) {
            int a = Math.max(bas, basBlocs), b = Math.min(haut, hautBlocs);
            if (a <= b) {
                bas = a;
                haut = b;
            } else if (bas >= haut) {
                bas = basBlocs; // hauteurs du générateur absentes : celles des blocs
                haut = hautBlocs;
            }
        }
        return VolumeFilon.dyke(cx, cz, taille, bas, haut);
    }

    // ---------- Matériaux, couleurs et recettes ----------

    /** Matériau GregTech d'un bloc de minerai (null s'il n'en a pas). */
    static Object matiere(String bloc) {
        try {
            init();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        Object m = matiereParBloc.get(bloc);
        if (m != null) return m;
        try {
            var b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(bloc));
            return b == null ? null : matiereObjet(b);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean chimie() {
        if (chimie) return true;
        if (chimieIndisponible) return false;
        try {
            materialStack =
                    Class.forName(BASE + "api.data.chemical.ChemicalHelper")
                            .getMethod(
                                    "getMaterialStack", net.minecraft.world.level.ItemLike.class);
            stackMatiere =
                    Class.forName(BASE + "api.data.chemical.material.stack.MaterialStack")
                            .getMethod("material");
            Class<?> cle =
                    Class.forName(BASE + "api.data.chemical.material.properties.PropertyKey");
            proprieteMinerai = cle.getField("ORE").get(null);
            propriete =
                    Class.forName(BASE + "api.data.chemical.material.Material")
                            .getMethod("getProperty", cle);
            chimie = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            chimieIndisponible = true;
        }
        return chimie;
    }

    private static Object matiereObjet(net.minecraft.world.level.ItemLike objet) {
        if (!chimie()) return null;
        try {
            Object pile = materialStack.invoke(null, objet);
            Object m = pile == null ? null : stackMatiere.invoke(pile);
            return m == null || "null".equals(nomMatiere(m)) ? null : m;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    static String nomMatiere(Object matiere) {
        try {
            return matiere == null ? "" : (String) appel(matiere, "getName");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Couleur RGB native du matériau (-1 si inconnue). */
    static int rgbMatiere(Object matiere) {
        try {
            return matiere == null ? -1 : (int) appel(matiere, "getMaterialRGB") & 0xFFFFFF;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Matériaux des objets demandés, avec leurs composants directs (Steel → Iron, Bronze → Tin).
     */
    static Set<Object> besoins(Collection<String> objets) {
        Set<Object> besoins = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : objets) {
            var rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            var item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            Object m = matiereObjet(item);
            if (m == null) continue;
            besoins.add(m);
            besoins.addAll(composants(m));
        }
        return besoins;
    }

    private static final Set<String> GAZ =
            Set.of("oxygen", "hydrogen", "nitrogen", "chlorine", "fluorine", "argon", "helium");

    private static List<Object> composants(Object matiere) {
        List<Object> resultat = new ArrayList<>();
        if (!chimie()) return resultat;
        try {
            for (Object pile : (List<?>) appel(matiere, "getMaterialComponents")) {
                Object c = stackMatiere.invoke(pile);
                if (c != null && !GAZ.contains(nomMatiere(c))) resultat.add(c);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // matériau sans composants lisibles
        }
        return resultat;
    }

    private static Object fusion(Object matiere) {
        if (!chimie()) return null;
        try {
            Object minerai = propriete.invoke(matiere, proprieteMinerai);
            return minerai == null ? null : appel(minerai, "getDirectSmeltResult");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Un minerai de la composition fournit un matériau demandé : lui-même, son résultat de fusion
     * (Cassiterite → Tin) ou l'un de ses composants (Chalcopyrite → Copper).
     */
    static boolean utile(FilonMemoire.Filon filon, Set<Object> besoins) {
        if (besoins.isEmpty()) return false;
        Set<Object> vus = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String bloc : filon.composition) {
            Object m = matiere(bloc);
            if (m == null || !vus.add(m)) continue;
            if (besoins.contains(m)) return true;
            Object f = fusion(m);
            if (f != null && besoins.contains(f)) return true;
            for (Object c : composants(m)) if (besoins.contains(c)) return true;
        }
        return false;
    }
}
