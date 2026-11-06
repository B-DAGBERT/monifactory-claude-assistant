package fr.assistantia;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final java.util.concurrent.ExecutorService IO =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "Assistant-filons-sauvegarde");
                        t.setDaemon(true);
                        return t;
                    });
    private static final SauvegardeFilons PUBLICATION =
            new SauvegardeFilons(IO, Filons::ecrire, System::currentTimeMillis);
    private static final MultiBufferSource.BufferSource TAMPONS_FILONS =
            MultiBufferSource.immediate(new BufferBuilder(32768));
    private static FilonMemoire memoire = new FilonMemoire(true);
    private static FilonMemoire.Scan scan = new FilonMemoire.Scan();
    private static final Map<net.minecraft.world.level.block.Block, Boolean> MINERAIS =
            new HashMap<>();
    private static List<FilonMemoire.Bloc> controles = List.of(), contours = List.of();
    private static int controle;
    private static String monde = "", affiche = "", cibleScan = "", generation = "";
    private static Object connexion;
    private static JsonObject contexteExport = new JsonObject();
    private static Object tpConnexion;
    private static String tpSession = "";
    private static int positionsLues, positionsNonChargees;
    private static long rotationDebut;
    private static long sauvegarde,
            revision,
            listeControle,
            prochainAffichage,
            prochainMarquage,
            prochainScan,
            lectureGregtech,
            tpDebut,
            contoursDate;
    private static boolean modifie, tpEnCours;
    private static FilonMemoire.Position tpAttendue;
    private static volatile String message = "";
    private static final Set<String> DANGERS =
            Set.of(
                    "lava",
                    "fire",
                    "soul_fire",
                    "cactus",
                    "magma_block",
                    "campfire",
                    "soul_campfire",
                    "powder_snow",
                    "sweet_berry_bush",
                    "wither_rose",
                    "pointed_dripstone");

    private Filons() {}

    public static FilonMemoire memoire() {
        return memoire;
    }

    public static String monde() {
        return monde;
    }

    public static String affiche() {
        return affiche;
    }

    public static String message() {
        return GregTechFilons.erreur().isEmpty() ? message : GregTechFilons.erreur();
    }

    public static long revision() {
        return revision;
    }

    public static void invaliderContours() {
        contoursDate = 0;
    }

    public static int avancee() {
        return scan.pourcentage();
    }

    public static boolean scanActif() {
        return scan.actif();
    }

    public static boolean scanPonctuel() {
        return scan.ponctuel();
    }

    public static String etatScan() {
        if (scan.ponctuel()) return "Scan ponctuel : " + avancee() + " %";
        if (scan.actif()) return "Scan auto : " + avancee() + " %";
        return scan.termine() ? "Scan terminé : 100 % · en pause" : "Scan en pause";
    }

    public static String raisonScan(String id) {
        Minecraft mc = Minecraft.getInstance();
        var f = memoire.filon(id);
        if (mc.level == null || mc.player == null || monde.isEmpty())
            return "Entre dans ta partie pour scanner.";
        if (f == null) return ""; // sans sélection : prospection autour du joueur
        if (!f.ancre.dimension().equals(position().dimension()))
            return "Rejoins d'abord la dimension du filon.";
        if (!mc.level.hasChunkAt(bloc(f.ancre)))
            return "Filon non chargé : rapproche-toi de ses coordonnées pour scanner.";
        return "";
    }

    public static boolean scanDuFilon(String id) {
        return scan.ponctuel() && cibleScan.equals(id);
    }

    public static void scannerSelection(String id) {
        if (!synchroniserMonde() || !boutonScanDisponible() || scan.actif()) return;
        var f = memoire.filon(id);
        if (f == null) return;
        String raison = raisonScan(id);
        if (!raison.isEmpty()) {
            message = raison;
            return;
        }
        cibleScan = id;
        positionsLues = positionsNonChargees = 0;
        scan.lancerPonctuel(f.ancre);
        memoire.protegerScan(f.ancre);
        rotationDebut = memoire.remplaces();
        prochainScan = System.currentTimeMillis() + 650;
        message =
                "Scan du filon sélectionné : 16 blocs autour de son centre, uniquement dans les chunks chargés.";
        modifie = true;
        sauver(true);
    }

    public static boolean boutonScanDisponible() {
        return System.currentTimeMillis() >= prochainScan;
    }

    public static void reglerScan(boolean actif) {
        if (monde.isEmpty() || !boutonScanDisponible() || !scan.activer(actif)) return;
        if (actif) {
            memoire.protegerScan(position());
            rotationDebut = memoire.remplaces();
        }
        prochainScan = System.currentTimeMillis() + 650;
        message =
                actif
                        ? "Scan repris : repérage et vérification des minerais."
                        : "Scan en pause : aucun repérage ni revérification. Filons conservés.";
        modifie = true;
        sauver(true); // une sauvegarde à la bascule, aucun scan pendant la pause
    }

    public static boolean tpEnCours() {
        return tpEnCours;
    }

    public static boolean affichageDisponible() {
        return System.currentTimeMillis() >= prochainAffichage;
    }

    public static FilonMemoire.Position position() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null || mc.level == null
                ? new FilonMemoire.Position("", 0, 0, 0)
                : position(mc.level, mc.player.blockPosition());
    }

    private static FilonMemoire.Position position(Level niveau, BlockPos p) {
        return new FilonMemoire.Position(
                niveau.dimension().location().toString(), p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos bloc(FilonMemoire.Position p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static String identite(Minecraft mc) {
        if (IdentiteMonde.isolation() && Pont.cleContexte().isEmpty()) return "";
        String origine = GregTechFilons.monde();
        if (origine.isEmpty()) return "";
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(origine.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Synchronisé avant d'afficher la liste, même entre deux ticks de changement de partie. */
    public static boolean synchroniserMonde() {
        Minecraft mc = Minecraft.getInstance();
        String id = mc.level == null || mc.player == null ? "" : identite(mc);
        String actuelle =
                IdentiteMonde.isolation()
                        ? IdentiteMonde.monde() + "|" + IdentiteMonde.session()
                        : "legacy";
        if (id.equals(monde) && connexion == mc.getConnection() && generation.equals(actuelle)) {
            // Une dimension différente conserve les filons du même monde, mais pas son enveloppe.
            JsonObject actuel = new JsonObject();
            Pont.annoterContexte(actuel);
            if (!actuel.equals(contexteExport)) {
                PUBLICATION.invaliderExport(DOSSIER.resolve("filons_mod.json"));
                contexteExport = actuel;
                modifie = true;
                sauvegarde = 0; // Republier aussi lorsque la dimension change sans nouveau bloc.
            }
            return !id.isEmpty();
        }
        cibleScan = "";
        positionsLues = positionsNonChargees = 0;
        sauver(true);
        PUBLICATION.invaliderExport(DOSSIER.resolve("filons_mod.json"));
        connexion = mc.getConnection();
        generation = actuelle;
        contexteExport = new JsonObject();
        Pont.annoterContexte(contexteExport);
        tpConnexion = null;
        tpSession = "";
        if (id.isEmpty()) {
            monde = affiche = message = "";
            memoire = new FilonMemoire(true);
            scan = new FilonMemoire.Scan();
            scan.activer(false);
            controles = contours = List.of();
            MINERAIS.clear();
            controle = 0;
            tpEnCours = modifie = false;
            tpAttendue = null;
            revision++;
            return false;
        }
        charger(id);
        return true;
    }

    private static Path fichier(String id) {
        return DOSSIER.resolve("filons_gregtech").resolve(id + ".json");
    }

    private static void charger(String id) {
        memoire = new FilonMemoire(true);
        scan = new FilonMemoire.Scan();
        scan.activer(false);
        controles = contours = List.of();
        controle = 0;
        affiche = "";
        tpEnCours = false;
        tpAttendue = null;
        message = "";
        monde = id;
        MINERAIS.clear();
        listeControle = contoursDate = sauvegarde = 0;
        try {
            String contenu = PUBLICATION.lire(fichier(id));
            if (contenu != null) {
                JsonObject data = JsonParser.parseString(contenu).getAsJsonObject();
                if (!id.equals(Pont.texte(data, "monde")))
                    throw new IllegalArgumentException("Mauvais monde");
                for (JsonElement e : Pont.liste(data, "gisements")) {
                    JsonObject g = e.getAsJsonObject();
                    Set<String> types = new TreeSet<>();
                    for (JsonElement type : Pont.liste(g, "composition"))
                        types.add(type.getAsString());
                    memoire.gisement(
                            Pont.texte(g, "id"),
                            lirePosition(g),
                            Pont.texte(g, "definition"),
                            Pont.texte(g, "nom"),
                            types,
                            Pont.vrai(g, "epuise"));
                    memoire.filon(Pont.texte(g, "id")).marquerEpuise(Pont.vrai(g, "epuise_manuel"));
                }
                for (JsonElement e : Pont.liste(data, "blocs")) {
                    JsonObject b = e.getAsJsonObject();
                    FilonMemoire.Position p = lirePosition(b);
                    memoire.restaurerReleve(
                            p,
                            Pont.texte(b, "bloc"),
                            Pont.vrai(b, "mine"),
                            (long) Pont.nombre(b, "t", 0),
                            Pont.texte(b, "filon"));
                }
                if (data.has("types_affiches_par_filon")
                        && data.get("types_affiches_par_filon").isJsonObject()) {
                    for (var entree : data.getAsJsonObject("types_affiches_par_filon").entrySet()) {
                        var filon = memoire.filon(entree.getKey());
                        if (filon == null || !entree.getValue().isJsonArray()) continue;
                        Set<String> choisis = new HashSet<>();
                        for (var type : entree.getValue().getAsJsonArray()) {
                            if (type.isJsonPrimitive()
                                    && type.getAsJsonPrimitive().isString()
                                    && ResourceLocation.tryParse(type.getAsString()) != null)
                                choisis.add(type.getAsString());
                        }
                        filon.choisirTypes(choisis);
                    }
                }
            }
        } catch (Exception e) {
            try {
                Files.copy(
                        fichier(id),
                        fichier(id)
                                .resolveSibling(id + ".illisible-" + System.currentTimeMillis()));
            } catch (Exception copie) {
                LogUtils.getLogger().warn("Sauvegarde filons illisible", copie);
            }
            message =
                    "Mémoire partiellement illisible : copie conservée, nouveau repérage en cours.";
            LogUtils.getLogger().warn("Lecture des filons", e);
        }
        lectureGregtech = 0;
        actualiserGregtech();
        modifie = true;
        revision++;
    }

    public static void actualiserGregtech() {
        if (monde.isEmpty() || System.currentTimeMillis() < lectureGregtech) return;
        lectureGregtech = System.currentTimeMillis() + 2000;
        int connus = memoire.filons().size();
        if (GregTechFilons.lire(memoire)) {
            if (memoire.filons().size() > connus && scan.ponctuel()) {
                scan.reprendrePonctuel();
                positionsLues = positionsNonChargees = 0;
            }
            modifie = true;
            revision++;
        }
    }

    public static void viderReleves() {
        if (!synchroniserMonde() || !boutonScanDisponible() || scan.actif()) return;
        int nombre = memoire.viderReleves();
        controles = contours = List.of();
        controle = 0;
        listeControle = contoursDate = 0;
        prochainScan = System.currentTimeMillis() + 650;
        message =
                nombre + " relevés supprimés ; gisements GregTech et choix d'affichage conservés.";
        modifie = true;
        revision++;
        sauver(true);
    }

    private static FilonMemoire.Position lirePosition(JsonObject b) {
        String dim = Pont.texte(b, "dimension");
        int x = (int) Pont.nombre(b, "x", 0),
                y = (int) Pont.nombre(b, "y", 0),
                z = (int) Pont.nombre(b, "z", 0);
        if (ResourceLocation.tryParse(dim) == null
                || Math.abs((long) x) > 30000000
                || Math.abs((long) z) > 30000000
                || Math.abs((long) y) > 4096)
            throw new IllegalArgumentException("Coordonnées invalides");
        return new FilonMemoire.Position(dim, x, y, z);
    }

    private static JsonObject jsonPosition(FilonMemoire.Position p) {
        JsonObject j = new JsonObject();
        j.addProperty("dimension", p.dimension());
        j.addProperty("x", p.x());
        j.addProperty("y", p.y());
        j.addProperty("z", p.z());
        return j;
    }

    private static void sauver(boolean force) {
        if (monde.isEmpty()
                || (!force && (!modifie || System.currentTimeMillis() - sauvegarde < 10000)))
            return;
        JsonObject j = new JsonObject();
        j.addProperty("v", 1);
        j.addProperty("monde", monde);
        j.addProperty(
                "scan_actif",
                scan.actif()
                        && !scan.ponctuel()); // un scan ponctuel ne reprend jamais en boucle au
        // redémarrage
        JsonArray gisements = new JsonArray();
        for (var f : memoire.filons()) {
            JsonObject g = jsonPosition(f.ancre);
            g.addProperty("id", f.id);
            g.addProperty("definition", f.definition);
            g.addProperty("nom", f.nom);
            g.addProperty("epuise", f.epuise);
            g.addProperty("epuise_manuel", f.epuiseManuel());
            JsonArray types = new JsonArray();
            f.composition.stream().sorted().forEach(types::add);
            g.add("composition", types);
            gisements.add(g);
        }
        j.add("gisements", gisements);
        j.addProperty("source", "gregtech");
        j.addProperty("capacite_blocs", FilonMemoire.MAX_BLOCS);
        j.addProperty("rotation_auto", true);
        j.addProperty("t", System.currentTimeMillis());
        JsonArray blocs = new JsonArray();
        for (FilonMemoire.Bloc b : memoire.blocs()) {
            JsonObject o = jsonPosition(b.position);
            o.addProperty("bloc", b.id);
            o.addProperty("filon", b.filon);
            o.addProperty("mine", b.retire);
            o.addProperty("t", b.vu);
            o.addProperty("cle", b.position.cle());
            o.add("ancre", jsonPosition(memoire.filon(b.filon).ancre));
            blocs.add(o);
        }
        JsonObject filtres = new JsonObject();
        for (var filon : memoire.filons()) {
            if (filon.typesChoisis() == null) continue;
            JsonArray choisis = new JsonArray();
            filon.typesChoisis().stream().sorted().forEach(choisis::add);
            filtres.add(filon.id, choisis);
        }
        j.add("types_affiches_par_filon", filtres);
        j.add("blocs", blocs);
        String texte = j.toString();
        // La sauvegarde native reste inchangée. L'export transitoire garde l'origine capturée,
        // même si l'exécuteur IO termine après une déconnexion ou un autre monde.
        JsonObject export = j.deepCopy();
        contexteExport.entrySet().forEach(e -> export.add(e.getKey(), e.getValue()));
        boolean exportable = !IdentiteMonde.isolation() || export.has("session_monde");
        String texteExport = export.toString();
        Object connexionExport = connexion;
        String generationExport = generation;
        Path destination = fichier(monde);
        modifie = false;
        sauvegarde = System.currentTimeMillis();
        PUBLICATION.enregistrer(
                destination,
                texte,
                exportable ? DOSSIER.resolve("filons_mod.json") : null,
                texteExport,
                e -> {
                    Minecraft.getInstance()
                            .execute(
                                    () -> {
                                        if (connexion != connexionExport
                                                || !generation.equals(generationExport)) return;
                                        message =
                                                "Sauvegarde des filons impossible : vérifier l'espace disque.";
                                    });
                    LogUtils.getLogger().warn("Écriture des filons", e);
                });
    }

    private static void ecrire(Path p, String texte) throws java.io.IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, texte, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean minerai(BlockState s) {
        return MINERAIS.computeIfAbsent(
                s.getBlock(),
                b -> {
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
                    return (id != null
                                    && (id.getPath().endsWith("_ore")
                                            || id.getPath().equals("ancient_debris")))
                            || s.getTags()
                                    .anyMatch(
                                            t ->
                                                    t.location().getNamespace().equals("forge")
                                                            && (t.location()
                                                                            .getPath()
                                                                            .equals("ores")
                                                                    || t.location()
                                                                            .getPath()
                                                                            .startsWith("ores/")));
                });
    }

    private static void observer(Level niveau, FilonMemoire.Position p, long now) {
        if (!niveau.dimension().location().toString().equals(p.dimension())
                || niveau.isOutsideBuildHeight(bloc(p))
                || !niveau.hasChunkAt(bloc(p))) return;
        BlockState s = niveau.getBlockState(bloc(p));
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        long avant = memoire.remplaces();
        if (id != null && memoire.observer(p, id.toString(), minerai(s), now)) {
            if (memoire.remplaces() != avant) {
                controles = contours = List.of();
                controle = 0;
                listeControle = contoursDate = 0;
            }
            modifie = true;
            revision++;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PUBLICATION.reessayer();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (!synchroniserMonde()) return;
        actualiserGregtech();
        if (tpEnCours && tpAttendue != null) {
            if (position().dimension().equals(tpAttendue.dimension())
                    && mc.player
                                    .position()
                                    .distanceToSqr(
                                            tpAttendue.x() + .5,
                                            tpAttendue.y(),
                                            tpAttendue.z() + .5)
                            < .5)
                finirTP(monde, tpConnexion, tpSession, "Téléportation effectuée.");
            else if (now - tpDebut > 6000)
                finirTP(
                        monde,
                        tpConnexion,
                        tpSession,
                        "Téléportation non confirmée : vérifier le message du serveur.");
        }
        if (mc.isPaused() || !scan.actif()) {
            sauver(false);
            return;
        }
        if (now - listeControle > 10000) {
            controles = new ArrayList<>(memoire.blocs());
            listeControle = now;
        }
        long limite =
                System.nanoTime()
                        + 1_000_000; // 1 ms au plus entre deux observations, 224 blocs maximum
        for (int n = 0; n < 32 && !controles.isEmpty() && System.nanoTime() < limite; n++) {
            if (controle >= controles.size()) controle = 0;
            observer(mc.level, controles.get(controle++).position, now);
        }
        FilonMemoire.Position joueur = position();
        boolean ponctuel = scan.ponctuel();
        for (int n = 0; n < 192 && System.nanoTime() < limite; n++) {
            var suivante = scan.suivant(joueur);
            if (suivante == null) break;
            memoire.protegerScan(scan.centre());
            if (!mc.level.isOutsideBuildHeight(bloc(suivante))) {
                if (mc.level.hasChunkAt(bloc(suivante))) {
                    observer(mc.level, suivante, now);
                    positionsLues++;
                } else positionsNonChargees++;
            }
        }
        if (ponctuel && !scan.actif()) {
            var f = memoire.filon(cibleScan);
            message =
                    !scan.termine()
                            ? "Scan interrompu : changement de dimension. Données conservées, scan en pause."
                            : positionsLues == 0
                                    ? "Scan sans données : zone non chargée. Rapproche-toi du filon ; quantité inconnue."
                                    : "Scan "
                                            + (positionsNonChargees > 0 ? "partiel" : "terminé")
                                            + (f == null
                                                    ? " autour de toi"
                                                    : " du filon : "
                                                            + f.restants()
                                                            + " blocs repérés")
                                            + " · "
                                            + positionsLues
                                            + " positions vérifiées"
                                            + (positionsNonChargees > 0
                                                    ? " · " + positionsNonChargees + " non chargées"
                                                    : "")
                                            + (memoire.remplaces() > rotationDebut
                                                    ? " · "
                                                            + (memoire.remplaces() - rotationDebut)
                                                            + " anciens relevés remplacés"
                                                    : "")
                                            + ". Total du gisement inconnu ; scan en pause.";
            modifie = true;
            sauver(true);
            mc.player.displayClientMessage(Component.literal(message), true);
        }
        sauver(false);
    }

    public static boolean marquageDisponible() {
        return System.currentTimeMillis() >= prochainMarquage;
    }

    public static boolean marquerEpuise(String origine, String id, boolean valeur) {
        if (!synchroniserMonde() || !monde.equals(origine) || !marquageDisponible()) return false;
        var f = memoire.filon(id);
        if (f == null
                || !f.ancre.dimension().equals(position().dimension())
                || !f.marquerEpuise(valeur)) return false;
        prochainMarquage = System.currentTimeMillis() + 650;
        if (valeur && id.equals(affiche)) {
            affiche = "";
            contours = List.of();
            contoursDate = 0;
        }
        message =
                valeur
                        ? "Filon marqué épuisé. Pour annuler : Épuisés visibles → Rétablir."
                        : "Marquage personnel retiré ; journal GregTech conservé.";
        modifie = true;
        revision++;
        sauver(true);
        return true;
    }

    public static void afficher(String id, boolean actif) {
        if (!affichageDisponible()) return;
        if (actif && (memoire.filon(id) == null || !memoire.filon(id).visible())) return;
        affiche = actif ? id : "";
        contoursDate = 0;
        prochainAffichage = System.currentTimeMillis() + 650;
        message = actif ? "Surbrillance activée pour ce filon." : "Surbrillance désactivée.";
    }

    public static boolean afficherSelection(String origine, String id, Map<String, Boolean> choix) {
        if (!synchroniserMonde() || !monde.equals(origine) || !affichageDisponible()) return false;
        var f = memoire.filon(id);
        if (f == null || !f.ancre.dimension().equals(position().dimension())) return false;
        var selection =
                f.typesReperes().entrySet().stream()
                        .filter(e -> e.getValue() > 0 && choix.getOrDefault(e.getKey(), false))
                        .map(Map.Entry::getKey)
                        .toList();
        if (selection.isEmpty()) {
            message = "Aucun bloc sélectionné encore repéré : lance un scan.";
            return false;
        }
        f.choisirTypes(selection);
        afficher(id, true);
        modifie = true;
        sauver(true);
        return true;
    }

    public static String nom(String bloc) {
        return Pont.objet(bloc).getHoverName().getString();
    }

    public static boolean utile(FilonMemoire.Filon f) {
        for (String ore : f.types().keySet()) {
            String mat =
                    ore.substring(ore.indexOf(':') + 1)
                            .replaceFirst("^(deepslate_|netherrack_|endstone_|stone_)", "")
                            .replaceFirst("_ore$", "");
            for (String besoin : Pont.besoins()) {
                String chemin = besoin.substring(besoin.indexOf(':') + 1);
                if (chemin.equals(mat)
                        || chemin.startsWith(mat + "_")
                        || (chemin.equals("raw_" + mat) || chemin.startsWith("raw_" + mat + "_"))
                        || chemin.endsWith("_" + mat)
                        || chemin.contains("_" + mat + "_")) return true;
            }
        }
        return false;
    }

    public static String raisonTP(FilonMemoire.Filon f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || f == null) return "Aucun filon sélectionné.";
        if (!mc.player.hasPermissions(2))
            return "Téléportation : commandes / permission opérateur nécessaires.";
        if (tpEnCours) return "Téléportation en cours…";
        if (System.currentTimeMillis() - tpDebut < 1000) return "Patiente un instant.";
        if (mc.getSingleplayerServer() == null) {
            if (!f.ancre.dimension().equals(position().dimension()))
                return "Sur serveur, rejoins d'abord cette dimension.";
        }
        return "";
    }

    private static boolean sur(Level niveau, FilonMemoire.Position p) {
        BlockPos pieds = bloc(p);
        if (p.y() <= niveau.getMinBuildHeight()
                || p.y() + 1 >= niveau.getMaxBuildHeight()
                || !niveau.getWorldBorder().isWithinBounds(pieds)) return false;
        // Rejeter la roche / les chunks absents avant les vérifications du voisinage.
        if (!niveau.hasChunkAt(pieds)
                || !niveau.getBlockState(pieds).isAir()
                || !niveau.getBlockState(pieds.above()).isAir()
                || !niveau.getBlockState(pieds.below())
                        .isFaceSturdy(niveau, pieds.below(), Direction.UP)) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (!niveau.hasChunkAt(pieds.offset(dx, 0, dz))) return false;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockState s = niveau.getBlockState(pieds.offset(dx, dy, dz));
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
                    if (!s.getFluidState().isEmpty()
                            || (id != null && DANGERS.contains(id.getPath()))) return false;
                }
            }
        return niveau.noCollision(
                new AABB(p.x() + .2, p.y(), p.z() + .2, p.x() + .8, p.y() + 1.8, p.z() + .8));
    }

    public static void teleporter(String id) {
        if (!synchroniserMonde()) return;
        Minecraft mc = Minecraft.getInstance();
        FilonMemoire.Filon f = memoire.filon(id);
        String raison = raisonTP(f);
        if (!raison.isEmpty()) {
            message = raison;
            return;
        }
        FilonMemoire.Position cible = f.ancre;
        String origine = monde;
        Object connexionOrigine = mc.getConnection();
        String sessionOrigine = IdentiteMonde.session();
        tpConnexion = connexionOrigine;
        tpSession = sessionOrigine;
        tpEnCours = true;
        tpDebut = System.currentTimeMillis();
        message = "Recherche du point libre le plus proche du filon…";
        var serveur = mc.getSingleplayerServer();
        if (serveur != null) {
            UUID joueur = mc.player.getUUID();
            serveur.execute(
                    () -> {
                        try {
                            var p = serveur.getPlayerList().getPlayer(joueur);
                            ServerLevel niveau =
                                    serveur.getLevel(
                                            ResourceKey.create(
                                                    Registries.DIMENSION,
                                                    ResourceLocation.tryParse(cible.dimension())));
                            if (p == null || !p.hasPermissions(2) || niveau == null) {
                                finirTP(
                                        origine,
                                        connexionOrigine,
                                        sessionOrigine,
                                        "Téléportation refusée : joueur, dimension ou permission indisponible.");
                                return;
                            }
                            // Solo : voisinage borné à 8 blocs plus la marge de sécurité (au plus 9
                            // chunks).
                            int rayon = FilonMemoire.RAYON_TP + 1;
                            for (int x = Math.floorDiv(cible.x() - rayon, 16);
                                    x <= Math.floorDiv(cible.x() + rayon, 16);
                                    x++)
                                for (int z = Math.floorDiv(cible.z() - rayon, 16);
                                        z <= Math.floorDiv(cible.z() + rayon, 16);
                                        z++) niveau.getChunk(x, z);
                            var libre =
                                    FilonMemoire.destinationProche(
                                            cible,
                                            niveau.getMinBuildHeight(),
                                            niveau.getMaxBuildHeight(),
                                            pos -> sur(niveau, pos));
                            boolean directe = libre == null;
                            if (directe)
                                libre =
                                        FilonMemoire.destinationDirecte(
                                                cible,
                                                niveau.getMinBuildHeight(),
                                                niveau.getMaxBuildHeight());
                            if (libre == null
                                    || !niveau.getWorldBorder().isWithinBounds(bloc(libre))) {
                                finirTP(
                                        origine,
                                        connexionOrigine,
                                        sessionOrigine,
                                        "Destination hors des limites du monde.");
                                return;
                            }
                            p.teleportTo(
                                    niveau,
                                    libre.x() + .5,
                                    libre.y(),
                                    libre.z() + .5,
                                    p.getYRot(),
                                    p.getXRot());
                            finirTP(
                                    origine,
                                    connexionOrigine,
                                    sessionOrigine,
                                    directe
                                            ? "TP directe au-dessus du filon : emplacement non vérifié."
                                            : "Téléportation effectuée près du filon.");
                        } catch (Exception e) {
                            LogUtils.getLogger().warn("Téléportation filon", e);
                            finirTP(
                                    origine,
                                    connexionOrigine,
                                    sessionOrigine,
                                    "Téléportation impossible : voir le journal du jeu.");
                        }
                    });
        } else {
            var libre =
                    FilonMemoire.destinationProche(
                            cible,
                            mc.level.getMinBuildHeight(),
                            mc.level.getMaxBuildHeight(),
                            p -> sur(mc.level, p));
            boolean directe = libre == null;
            if (directe)
                libre =
                        FilonMemoire.destinationDirecte(
                                cible, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight());
            if (libre == null || !mc.level.getWorldBorder().isWithinBounds(bloc(libre))) {
                finirTP(
                        origine,
                        connexionOrigine,
                        sessionOrigine,
                        "Destination hors des limites du monde.");
                return;
            }
            if (mc.player.position().distanceToSqr(libre.x() + .5, libre.y(), libre.z() + .5)
                    < .5) {
                finirTP(
                        origine,
                        connexionOrigine,
                        sessionOrigine,
                        "Tu es déjà à cet emplacement près du filon.");
                return;
            }
            tpAttendue = libre;
            mc.player.connection.sendCommand(
                    "tp @s " + (libre.x() + .5) + " " + libre.y() + " " + (libre.z() + .5));
            message =
                    directe
                            ? "TP directe demandée au-dessus du filon : emplacement non vérifié…"
                            : "Commande envoyée : attente de la position confirmée…";
        }
    }

    private static void finirTP(
            String origine, Object connexionOrigine, String sessionOrigine, String texte) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (!monde.equals(origine)
                                    || Minecraft.getInstance().getConnection() != connexionOrigine
                                    || !IdentiteMonde.session().equals(sessionOrigine)) return;
                            tpEnCours = false;
                            tpAttendue = null;
                            message = texte;
                            if (Minecraft.getInstance().player != null)
                                Minecraft.getInstance()
                                        .player
                                        .displayClientMessage(Component.literal(texte), true);
                        });
    }

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        FilonMemoire.Position joueur = position();
        Vec3 camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = TAMPONS_FILONS;
        FilonMemoire.Filon f = memoire.filon(affiche);
        if (f != null && f.ancre.dimension().equals(joueur.dimension())) {
            if (System.currentTimeMillis() - contoursDate > 500) {
                contours = f.contours(joueur, AffichageFilons.style().maximum());
                contoursDate = System.currentTimeMillis();
            }
            var style = AffichageFilons.style();
            for (boolean visible : new boolean[] {false, true}) {
                if (!visible && style.surface()) continue;
                var type = visible ? Lignes.FILONS_VISIBLES : Lignes.FILONS_CACHES;
                VertexConsumer lignes = buffers.getBuffer(type);
                for (var b : contours) {
                    BlockPos pos = bloc(b.position);
                    if (b.retire || !f.typeAffiche(b.id) || !mc.level.hasChunkAt(pos)) continue;
                    // Laisser le bloc visé et son contour natif parfaitement lisibles pour miner.
                    if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult vise
                            && vise.getBlockPos().equals(pos)) continue;
                    ResourceLocation reel =
                            ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(pos).getBlock());
                    if (reel == null || !reel.toString().equals(b.id)) continue;
                    float alpha = style.alpha(b.position.distance2(joueur), visible);
                    if (alpha <= 0) continue;
                    int couleur =
                            net.minecraft.util.Mth.hsvToRgb(
                                    (b.id.hashCode() & 0xffff) / 65536F, .4F, .85F);
                    AABB boite = new AABB(pos).inflate(.005).move(-camera.x, -camera.y, -camera.z);
                    LevelRenderer.renderLineBox(
                            pose,
                            lignes,
                            boite,
                            ((couleur >> 16) & 255) / 255F,
                            ((couleur >> 8) & 255) / 255F,
                            (couleur & 255) / 255F,
                            alpha);
                }
                buffers.endBatch(type);
            }
        }
    }
}
