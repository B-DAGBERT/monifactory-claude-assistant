package fr.assistantia;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.opengl.GL11;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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
            MultiBufferSource.immediate(new BufferBuilder(65536));
    private static final LongSupplier HORLOGE = System::nanoTime;

    /** Budget du scan et des vérifications par tick, en nanosecondes. */
    private static final long BUDGET_TICK = 900_000;

    /**
     * Minerais connus relus à tour de rôle à chaque tick, et proches du joueur (filon surligné).
     */
    private static final int VERIFICATIONS_TICK = 192, VERIFICATIONS_PROCHES = 64;

    private static FilonMemoire memoire = new FilonMemoire();
    private static final Map<String, ScanFilon> SCANS = new LinkedHashMap<>();
    private static final VerificationFilons VERIFICATION = new VerificationFilons();
    private static final MondeClient MONDE = new MondeClient();

    /** Filon surligné par dimension : il disparaît ailleurs et revient dans sa dimension. */
    private static final Map<String, String> AFFICHES = new HashMap<>();

    private static String monde = "", generation = "", prioritaire = "";
    private static Object connexion;
    private static JsonObject contexteExport = new JsonObject();
    private static Object tpConnexion;
    private static String tpSession = "";
    private static long sauvegarde,
            revisionSauvee = -1,
            revision,
            prochainAffichage,
            prochainMarquage,
            prochainScan,
            lectureGregtech,
            tpDebut;
    private static boolean modifie, tpEnCours, charge;
    private static FilonMemoire.Position tpAttendue;
    private static volatile String message = "";
    private static CompletableFuture<FilonMemoire> chargement;
    private static volatile String messageChargement = "";
    private static final Set<Path> VERSIONS_VERIFIEES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Fichiers lus ou écrits sans erreur : seuls eux deviennent la copie de secours .bak. */
    private static final Set<Path> FICHIERS_SAINS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final Map<String, String> NOMS = new HashMap<>();
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

    /** Vrai tant que la mémoire du monde se lit en arrière-plan. */
    public static boolean chargement() {
        return !monde.isEmpty() && !charge;
    }

    /** Le mod suit les filons de ce monde : le relevé KubeJS de filons.json devient inutile. */
    public static boolean suiviNatif() {
        return !monde.isEmpty();
    }

    /** Filon surligné dans la dimension du joueur. */
    public static String affiche() {
        return AFFICHES.getOrDefault(position().dimension(), "");
    }

    /** Filon surligné dans une autre dimension que celle du joueur, ou null. */
    public static FilonMemoire.Filon afficheAilleurs() {
        String ici = position().dimension();
        for (var e : AFFICHES.entrySet())
            if (!e.getKey().equals(ici)) {
                var f = memoire.filon(e.getValue());
                if (f != null) return f;
            }
        return null;
    }

    public static String message() {
        if (!GregTechFilons.erreur().isEmpty()) return GregTechFilons.erreur();
        return message.isEmpty() ? messageChargement : message;
    }

    public static long revision() {
        return revision + memoire.revision();
    }

    public static void invaliderContours() {
        contours = Contours.VIDE;
    }

    // ---------- Scan ----------

    public static boolean scanDuFilon(String id) {
        return SCANS.containsKey(id);
    }

    public static int avancement(String id) {
        var f = memoire.filon(id);
        return f == null ? 0 : f.avancementScan();
    }

    /** Scans en cours dans la dimension du joueur. */
    public static int scansEnCours() {
        String dim = position().dimension();
        int n = 0;
        for (var s : SCANS.values()) if (s.filon.dimension().equals(dim)) n++;
        return n;
    }

    public static String raisonScan(String id) {
        Minecraft mc = Minecraft.getInstance();
        var f = memoire.filon(id);
        if (mc.level == null || mc.player == null || monde.isEmpty())
            return "Entre dans ta partie pour scanner.";
        if (!charge) return "Chargement des filons…";
        if (f == null) return "";
        if (!f.ancre.dimension().equals(position().dimension()))
            return "Rejoins d'abord la dimension du filon.";
        return "";
    }

    /** Lance le scan complet du filon, ou l'arrête s'il est déjà en cours. */
    public static void scannerSelection(String id) {
        if (!synchroniserMonde() || !boutonScanDisponible()) return;
        var f = memoire.filon(id);
        if (f == null) return;
        prochainScan = System.currentTimeMillis() + 650;
        if (SCANS.containsKey(id)) {
            SCANS.remove(id);
            f.colonnesRestantes = new long[0];
            f.scanTotal = f.scanFait = 0;
            f.revision++;
            memoire.toucher();
            message = "Scan arrêté : " + f.restants() + " minerais gardés.";
            sauver(true);
            return;
        }
        String raison = raisonScan(id);
        if (!raison.isEmpty()) {
            message = raison;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Bornes natives du générateur ; sans journal GregTech, celles du dernier scan.
        VolumeFilon volume = f.definitionNative != null ? GregTechFilons.volumeScan(f) : f.volume;
        if (volume == null)
            volume = VolumeFilon.inconnu(f.ancre.x(), f.ancre.y(), f.ancre.z(), 16, 16);
        var scan =
                ScanFilon.lancer(
                        memoire,
                        f,
                        volume,
                        memoire.filons(),
                        mc.level.getMinBuildHeight(),
                        mc.level.getMaxBuildHeight() - 1);
        SCANS.remove(id);
        SCANS.put(id, scan);
        prioritaire = id;
        message =
                "Scan du filon : volume "
                        + volume.nom()
                        + " de "
                        + (volume.bordMaxX() - volume.bordMinX() + 1)
                        + " × "
                        + (volume.bordMaxY() - volume.bordMinY() + 1)
                        + " × "
                        + (volume.bordMaxZ() - volume.bordMinZ() + 1)
                        + " blocs"
                        + (volume.marge() > 0 ? ", remplissage de proche en proche." : ".");
        sauver(true);
    }

    public static boolean boutonScanDisponible() {
        return System.currentTimeMillis() >= prochainScan;
    }

    private static void reprendreScans() {
        SCANS.clear();
        for (var f : memoire.filons())
            if (f.colonnesRestantes.length > 0 && f.volume != null) {
                var scan = ScanFilon.reprendre(memoire, f, memoire.filons());
                if (scan != null) SCANS.put(f.id, scan);
            }
    }

    private static void avancerScans(String dimension, long limite, long maintenant) {
        if (SCANS.isEmpty()) return;
        // Le filon demandé par le joueur passe en premier, les autres reprennent leurs zones.
        ScanFilon premier = SCANS.get(prioritaire);
        if (premier != null) avancerScan(premier, dimension, limite, maintenant);
        for (var it = SCANS.values().iterator(); it.hasNext() && HORLOGE.getAsLong() < limite; ) {
            var scan = it.next();
            if (scan != premier) avancerScan(scan, dimension, limite, maintenant);
        }
        SCANS.values().removeIf(s -> s.termine() && finirScan(s));
    }

    private static void avancerScan(
            ScanFilon scan, String dimension, long limite, long maintenant) {
        if (!scan.filon.dimension().equals(dimension) || HORLOGE.getAsLong() >= limite) return;
        if (!scan.colonnePrete(MONDE, maintenant)) return;
        if (scan.avancer(MONDE, HORLOGE, limite, maintenant)) modifie = true;
    }

    private static boolean finirScan(ScanFilon scan) {
        var f = scan.filon;
        if (f.id.equals(prioritaire)) {
            message =
                    "Scan terminé : "
                            + f.restants()
                            + " restants sur "
                            + f.trouves()
                            + " trouvés ("
                            + f.pourcentRestant()
                            + " %)."
                            + (f.presqueEpuise()
                                    ? " Presque épuisé : tu peux le marquer Épuisé."
                                    : "");
            var joueur = Minecraft.getInstance().player;
            if (joueur != null) joueur.displayClientMessage(Component.literal(message), true);
            prioritaire = "";
        }
        modifie = true;
        sauver(true);
        return true;
    }

    /** Texte d'état du scan d'un filon pour l'interface. */
    public static String etatScan(FilonMemoire.Filon f) {
        if (f == null) return "";
        var scan = SCANS.get(f.id);
        if (scan == null)
            return f.scanComplet()
                    ? "Scan complet"
                    : f.trouves() > 0 ? "Zone partielle" : "Pas scanné";
        return "Scan "
                + f.avancementScan()
                + " %"
                + (scan.attente()
                        ? " · " + scan.restantes() + " zones à charger : approche-toi"
                        : "");
    }

    /** Le scan du filon attend que des chunks se chargent. */
    public static boolean scanEnAttente(String id) {
        var scan = SCANS.get(id);
        return scan != null && scan.attente();
    }

    // ---------- Monde et chargement ----------

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
        sauver(true);
        PUBLICATION.invaliderExport(DOSSIER.resolve("filons_mod.json"));
        connexion = mc.getConnection();
        generation = actuelle;
        contexteExport = new JsonObject();
        Pont.annoterContexte(contexteExport);
        tpConnexion = null;
        tpSession = "";
        vider();
        if (id.isEmpty()) {
            monde = message = messageChargement = "";
            revision++;
            return false;
        }
        charger(id);
        return true;
    }

    private static void vider() {
        memoire = new FilonMemoire();
        charge = false;
        chargement = null;
        SCANS.clear();
        AFFICHES.clear();
        VERIFICATION.oublier();
        contours = Contours.VIDE;
        prioritaire = "";
        tpEnCours = modifie = false;
        tpAttendue = null;
        utiles.clear();
    }

    private static Path fichier(String id) {
        return DOSSIER.resolve("filons_gregtech").resolve(id + ".json");
    }

    /** Lecture en arrière-plan : la liste affiche « Chargement… » pendant ce temps. */
    private static void charger(String id) {
        monde = id;
        message = messageChargement = "";
        sauvegarde = lectureGregtech = 0;
        revisionSauvee = -1;
        Path chemin = fichier(id);
        chargement = CompletableFuture.supplyAsync(() -> lireMemoire(id, chemin), IO);
        revision++;
    }

    private static FilonMemoire lireMemoire(String id, Path chemin) {
        var attente = PUBLICATION.attente(chemin);
        // Reconnexion avant la fin de l'écriture : reprendre l'instantané sans relire le fichier.
        if (attente != null
                && attente.source() instanceof InstantaneFilons s
                && s.monde().equals(id)) return s.memoire();
        Path secours = chemin.resolveSibling(chemin.getFileName() + ".bak");
        try {
            String texte =
                    attente != null
                            ? attente.texte()
                            : Files.exists(chemin)
                                    ? Files.readString(chemin, StandardCharsets.UTF_8)
                                    : Files.exists(secours)
                                            ? Files.readString(secours, StandardCharsets.UTF_8)
                                            : null;
            var lue = texte == null ? new FilonMemoire() : FormatFilons.lire(texte, id);
            if (attente == null && Files.exists(chemin)) FICHIERS_SAINS.add(chemin);
            return lue;
        } catch (Exception e) {
            LogUtils.getLogger().warn("Lecture des filons", e);
            try {
                Files.copy(
                        chemin,
                        chemin.resolveSibling(id + ".illisible-" + System.currentTimeMillis()));
            } catch (Exception copie) {
                LogUtils.getLogger().warn("Sauvegarde filons illisible", copie);
            }
            try {
                if (Files.exists(secours)) {
                    var m =
                            FormatFilons.lire(
                                    Files.readString(secours, StandardCharsets.UTF_8), id);
                    messageChargement =
                            "Mémoire des filons illisible : copie de secours reprise, copie conservée.";
                    return m;
                }
            } catch (Exception illisible) {
                LogUtils.getLogger().warn("Copie de secours des filons illisible", illisible);
            }
            messageChargement =
                    "Mémoire partiellement illisible : copie conservée, nouveau repérage en cours.";
            return new FilonMemoire();
        }
    }

    private static void terminerChargement() {
        if (charge || chargement == null || !chargement.isDone()) return;
        FilonMemoire lue;
        try {
            lue = chargement.join();
        } catch (RuntimeException e) {
            LogUtils.getLogger().warn("Chargement des filons", e);
            lue = new FilonMemoire();
        }
        memoire = lue;
        chargement = null;
        charge = true;
        lectureGregtech = 0;
        actualiserGregtech();
        reprendreScans();
        modifie = true;
        revision++;
    }

    public static void actualiserGregtech() {
        if (monde.isEmpty() || !charge || System.currentTimeMillis() < lectureGregtech) return;
        lectureGregtech = System.currentTimeMillis() + 2000;
        if (GregTechFilons.lire(memoire)) {
            modifie = true;
            utiles.clear();
            revision++;
        }
    }

    private static void sauver(boolean force) {
        if (monde.isEmpty() || !charge) return;
        long maintenant = System.currentTimeMillis();
        boolean change = modifie || memoire.revision() != revisionSauvee;
        if (!force && (!change || maintenant - sauvegarde < 10000)) return;
        // Copie des tableaux seulement : le texte se calcule sur le thread d'écriture.
        var instantane = InstantaneFilons.de(memoire, monde, maintenant);
        JsonObject contexte = contexteExport.deepCopy();
        boolean exportable = !IdentiteMonde.isolation() || contexte.has("session_monde");
        Object connexionExport = connexion;
        String generationExport = generation;
        modifie = false;
        revisionSauvee = memoire.revision();
        sauvegarde = maintenant;
        PUBLICATION.enregistrer(
                fichier(monde),
                new SauvegardeFilons.Contenu(() -> FormatFilons.natif(instantane), instantane),
                exportable ? DOSSIER.resolve("filons_mod.json") : null,
                new SauvegardeFilons.Contenu(() -> FormatFilons.resume(instantane, contexte), null),
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

    /** Écriture atomique ; la sauvegarde native garde sa version précédente en .bak. */
    private static void ecrire(Path p, String texte) throws java.io.IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, texte, StandardCharsets.UTF_8);
        boolean natif = p.getParent().getFileName().toString().equals("filons_gregtech");
        if (natif && Files.exists(p)) {
            conserverAncienFormat(p);
            // Un fichier illisible ne remplace jamais une bonne copie de secours.
            if (FICHIERS_SAINS.contains(p))
                Files.copy(
                        p,
                        p.resolveSibling(p.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
        if (natif) FICHIERS_SAINS.add(p);
    }

    /** Avant la première sauvegarde v2, l'ancien fichier v1 est gardé une fois pour toutes. */
    private static void conserverAncienFormat(Path p) throws java.io.IOException {
        if (!VERSIONS_VERIFIEES.add(p)) return;
        byte[] debut = new byte[16];
        int n;
        try (var entree = Files.newInputStream(p)) {
            n = entree.read(debut);
        }
        String tete =
                n <= 0 ? "" : new String(debut, 0, n, StandardCharsets.UTF_8).replace(" ", "");
        Path ancien = p.resolveSibling(p.getFileName() + ".v1.bak");
        if (tete.startsWith("{\"v\":1") && !Files.exists(ancien)) Files.copy(p, ancien);
    }

    // ---------- Tick ----------

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PUBLICATION.reessayer();
        Minecraft mc = Minecraft.getInstance();
        if (!synchroniserMonde()) return;
        terminerChargement();
        if (!charge) return;
        actualiserGregtech();
        long maintenant = System.currentTimeMillis();
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
            else if (maintenant - tpDebut > 6000)
                finirTP(
                        monde,
                        tpConnexion,
                        tpSession,
                        "Téléportation non confirmée : vérifier le message du serveur.");
        }
        if (mc.isPaused() || mc.level == null) {
            sauver(false);
            return;
        }
        long limite = HORLOGE.getAsLong() + BUDGET_TICK;
        MONDE.preparer(mc.level, memoire);
        String dimension = mc.level.dimension().location().toString();
        // Filon surligné : les minerais proches du joueur sont relus à chaque tick.
        var affiche = memoire.filon(AFFICHES.get(dimension));
        if (affiche != null && contours.filon == affiche)
            VerificationFilons.proches(
                    memoire, affiche, contours.positions, VERIFICATIONS_PROCHES, MONDE);
        avancerScans(dimension, limite, maintenant);
        VERIFICATION.tour(memoire, dimension, MONDE, VERIFICATIONS_TICK, maintenant);
        sauver(false);
    }

    // ---------- Épuisement, affichage, recette ----------

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
        if (valeur) {
            AFFICHES.values().removeIf(id::equals);
            if (contours.filon == f) contours = Contours.VIDE;
        }
        message =
                valeur
                        ? "Filon marqué épuisé. Pour annuler : Épuisés visibles → Rétablir."
                        : "Marquage personnel retiré ; journal GregTech conservé.";
        modifie = true;
        memoire.toucher();
        sauver(true);
        return true;
    }

    public static void afficher(String id, boolean actif) {
        if (!affichageDisponible()) return;
        var f = memoire.filon(id);
        if (actif && (f == null || !f.visible())) return;
        if (actif) AFFICHES.put(f.dimension(), id);
        else AFFICHES.values().removeIf(id::equals);
        contours = Contours.VIDE;
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
        return NOMS.computeIfAbsent(bloc, b -> Pont.objet(b).getHoverName().getString());
    }

    // Matériaux demandés par l'arbre EMI choisi, relus au plus une fois par seconde.
    private static final Map<String, Boolean> utiles = new HashMap<>();
    private static Set<Object> besoinsRecette = Set.of();
    private static Set<String> feuillesRecette = Set.of();
    private static long lectureRecette;
    private static java.lang.reflect.Method feuillesArbre;
    private static boolean arbreIndisponible;

    /** Le filon fournit une matière première de l'arbre EMI choisi (Tin Ingot → cassiterite). */
    public static boolean utilePourRecette(FilonMemoire.Filon f) {
        actualiserRecette();
        if (besoinsRecette.isEmpty()) return false;
        return utiles.computeIfAbsent(f.id, k -> GregTechFilons.utile(f, besoinsRecette));
    }

    /** Un arbre EMI avec des matières premières est choisi. */
    public static boolean recetteChoisie() {
        actualiserRecette();
        return !feuillesRecette.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void actualiserRecette() {
        long maintenant = System.currentTimeMillis();
        if (arbreIndisponible || maintenant - lectureRecette < 1000) return;
        lectureRecette = maintenant;
        Set<String> feuilles;
        try {
            if (feuillesArbre == null)
                feuillesArbre =
                        Class.forName("fr.assistantia.compat.emi.MatieresArbreEmi")
                                .getMethod("feuilles");
            feuilles = (Set<String>) feuillesArbre.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            arbreIndisponible = true; // EMI absent : le tri par recette reste vide
            feuilles = Set.of();
        }
        if (feuilles.equals(feuillesRecette)) return;
        feuillesRecette = feuilles;
        besoinsRecette = GregTechFilons.besoins(feuilles);
        utiles.clear();
        revision++;
    }

    // ---------- Téléportation ----------

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

    // ---------- Accès au monde sans objet par position ----------

    /** Chunk et section courants mémorisés ; le type d'un bloc passe par une table d'identité. */
    private static final class MondeClient implements ScanFilon.Monde {
        private ClientLevel niveau;
        private LevelChunk chunk;
        private int chunkX, chunkZ;
        private FilonMemoire types;
        private int typesConnus = -1;
        private final Map<Block, Integer> typesBlocs = new IdentityHashMap<>();
        private Block[] blocs = new Block[0];
        private FilonMemoire.Filon filonPalette;
        private final Predicate<BlockState> palette =
                s -> {
                    int t = type(s.getBlock());
                    return t >= 0 && filonPalette.dansComposition(t);
                };

        void preparer(ClientLevel niveau, FilonMemoire memoire) {
            this.niveau = niveau;
            chunk = null; // un chunk peut être déchargé entre deux ticks
            if (memoire != types || memoire.typesConnus() != typesConnus) {
                types = memoire;
                typesConnus = memoire.typesConnus();
                typesBlocs.clear();
                blocs = new Block[typesConnus];
            }
        }

        int type(Block b) {
            Integer t = typesBlocs.get(b);
            if (t != null) return t;
            var id = ForgeRegistries.BLOCKS.getKey(b);
            int v = id == null ? -1 : types.typeConnu(id.toString());
            typesBlocs.put(b, v);
            return v;
        }

        /** Bloc d'un type de la mémoire (null s'il n'existe pas dans ce jeu). */
        Block bloc(int t) {
            if (t < 0 || t >= blocs.length) return null;
            Block b = blocs[t];
            if (b == null) {
                var rl = ResourceLocation.tryParse(types.typeId(t));
                b = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
                blocs[t] = b;
            }
            return b;
        }

        private LevelChunk chunk(int cx, int cz) {
            if (chunk != null && cx == chunkX && cz == chunkZ) return chunk;
            chunk = niveau == null ? null : niveau.getChunkSource().getChunkNow(cx, cz);
            chunkX = cx;
            chunkZ = cz;
            return chunk;
        }

        @Override
        public int minY() {
            return niveau.getMinBuildHeight();
        }

        @Override
        public int maxY() {
            return niveau.getMaxBuildHeight() - 1;
        }

        @Override
        public boolean charge(int cx, int cz) {
            return chunk(cx, cz) != null;
        }

        @Override
        public boolean sansMinerai(int cx, int sy, int cz, FilonMemoire.Filon filon) {
            LevelChunk c = chunk(cx, cz);
            if (c == null) return false;
            int i = sy - c.getMinSection();
            LevelChunkSection[] sections = c.getSections();
            if (i < 0 || i >= sections.length) return true;
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir()) return true;
            filonPalette = filon;
            return !s.maybeHas(palette);
        }

        @Override
        public int type(int x, int y, int z) {
            LevelChunk c = chunk(x >> 4, z >> 4);
            if (c == null) return -1;
            int i = (y >> 4) - c.getMinSection();
            LevelChunkSection[] sections = c.getSections();
            if (i < 0 || i >= sections.length) return -1;
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir()) return -1;
            return type(s.getBlockState(x & 15, y & 15, z & 15).getBlock());
        }
    }

    // ---------- Rendu ----------

    /** Minerais à surligner, recalculés quand le joueur bouge ou que le filon change. */
    private static final class Contours {
        static final Contours VIDE =
                new Contours(
                        null, new long[0], new int[0], new int[0], new byte[0], 0, 0, 0, -1, null);
        final FilonMemoire.Filon filon;
        final long[] positions;
        final int[] types, couleurs;
        final byte[] faces;
        final int x, y, z;
        final long revision;
        final StyleFilons style;
        long date;
        final boolean[] dessines;

        /** Carré de la distance à la caméra, recalculé à chaque image. */
        final float[] distances;

        Contours(
                FilonMemoire.Filon filon,
                long[] positions,
                int[] types,
                int[] couleurs,
                byte[] faces,
                int x,
                int y,
                int z,
                long revision,
                StyleFilons style) {
            this.filon = filon;
            this.positions = positions;
            this.types = types;
            this.couleurs = couleurs;
            this.faces = faces;
            this.x = x;
            this.y = y;
            this.z = z;
            this.revision = revision;
            this.style = style;
            dessines = new boolean[positions.length];
            distances = new float[positions.length];
        }
    }

    private static Contours contours = Contours.VIDE;
    private static final Map<String, Integer> COULEURS = new HashMap<>();
    private static final BlockPos.MutableBlockPos CURSEUR = new BlockPos.MutableBlockPos();
    private static final int[][] FACES = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private static int couleur(String bloc, String dimension) {
        return COULEURS.computeIfAbsent(
                bloc + "|" + dimension,
                k -> {
                    Object matiere = GregTechFilons.matiere(bloc);
                    String cle =
                            matiere == null
                                    ? CouleursFilons.cleBloc(bloc)
                                    : GregTechFilons.nomMatiere(matiere);
                    return CouleursFilons.couleur(
                            cle.isEmpty() ? CouleursFilons.cleBloc(bloc) : cle,
                            GregTechFilons.rgbMatiere(matiere),
                            dimension);
                });
    }

    private static void actualiserContours(
            FilonMemoire.Filon f, BlockPos joueur, StyleFilons style) {
        var c = contours;
        long maintenant = System.currentTimeMillis();
        if (c.filon == f
                && c.style == style
                && Math.abs(c.x - joueur.getX()) < 2
                && Math.abs(c.y - joueur.getY()) < 2
                && Math.abs(c.z - joueur.getZ()) < 2
                // Pendant un scan le filon change à chaque tick : au plus 4 calculs par seconde.
                && (c.revision == f.revision || maintenant - c.date < 250)) return;
        int[] indices =
                f.contours(
                        joueur.getX(),
                        joueur.getY(),
                        joueur.getZ(),
                        StyleFilons.RAYON,
                        style.maximum());
        long[] positions = new long[indices.length];
        int[] types = new int[indices.length], couleurs = new int[indices.length];
        byte[] faces = new byte[indices.length];
        String dimension = f.dimension();
        for (int i = 0; i < indices.length; i++) {
            long p = f.position(indices[i]);
            positions[i] = p;
            types[i] = f.typeBloc(indices[i]);
            couleurs[i] = couleur(memoire.typeId(types[i]), dimension);
            if (style.remplissage()) {
                int x = FilonMemoire.x(p), y = FilonMemoire.y(p), z = FilonMemoire.z(p), masque = 0;
                for (int k = 0; k < 6; k++)
                    if (!f.present(
                            FilonMemoire.pos(x + FACES[k][0], y + FACES[k][1], z + FACES[k][2])))
                        masque |= 1 << k; // face exposée : pas de face intérieure dessinée
                faces[i] = (byte) masque;
            }
        }
        contours =
                new Contours(
                        f,
                        positions,
                        types,
                        couleurs,
                        faces,
                        joueur.getX(),
                        joueur.getY(),
                        joueur.getZ(),
                        f.revision,
                        style);
        contours.date = maintenant;
    }

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !charge) return;
        String dimension = mc.level.dimension().location().toString();
        FilonMemoire.Filon f = memoire.filon(AFFICHES.get(dimension));
        // Un filon d'une autre dimension n'est jamais dessiné ici ; il revient dans la sienne.
        if (f == null || !f.dimension().equals(dimension)) return;
        var style = AffichageFilons.style();
        actualiserContours(f, mc.player.blockPosition(), style);
        var c = contours;
        if (c.positions.length == 0) return;
        Vec3 camera = event.getCamera().getPosition();
        MONDE.preparer(mc.level, memoire);
        long vise =
                mc.hitResult instanceof BlockHitResult cible
                                && cible.getType() == HitResult.Type.BLOCK
                        ? cible.getBlockPos().asLong()
                        : TableLong.VIDE;
        int dessinables = 0;
        for (int i = 0; i < c.positions.length; i++) {
            long p = c.positions[i];
            c.dessines[i] = false;
            // Laisser le bloc visé et son contour natif parfaitement lisibles pour miner.
            if (p == vise) continue;
            int x = FilonMemoire.x(p), y = FilonMemoire.y(p), z = FilonMemoire.z(p);
            if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
            Block attendu = MONDE.bloc(c.types[i]);
            if (attendu == null
                    || mc.level.getBlockState(CURSEUR.set(x, y, z)).getBlock() != attendu)
                continue; // miné : la vérification le comptera
            double dx = x + .5 - camera.x, dy = y + .5 - camera.y, dz = z + .5 - camera.z;
            c.distances[i] = (float) (dx * dx + dy * dy + dz * dz);
            c.dessines[i] = true;
            dessinables++;
        }
        if (dessinables == 0) return;
        // Sommets relatifs à la caméra, rotation appliquée par la matrice de vue : aucun calcul
        // de matrice par sommet.
        PoseStack vue = RenderSystem.getModelViewStack();
        vue.pushPose();
        vue.mulPoseMatrix(event.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        float debutBrume = RenderSystem.getShaderFogStart(),
                finBrume = RenderSystem.getShaderFogEnd();
        // Sans brouillard : la brume du Nether effaçait les contours dès quelques blocs.
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        try {
            float largeur = style.largeur(mc.getWindow().getWidth());
            for (boolean visible : new boolean[] {false, true}) {
                if (!visible && style.surface()) continue;
                if (style.remplissage()) {
                    var type = visible ? Lignes.FILONS_FACES_VISIBLES : Lignes.FILONS_FACES_CACHEES;
                    faces(type, c, camera, style, visible);
                    vider(type, visible);
                }
                if (style.lisere()) {
                    var type = Lignes.filons(largeur + 2.5f, visible);
                    lignes(type, c, camera, style, visible, true);
                    vider(type, visible);
                }
                var type = Lignes.filons(largeur, visible);
                lignes(type, c, camera, style, visible, false);
                vider(type, visible);
            }
        } finally {
            RenderSystem.setShaderFogStart(debutBrume);
            RenderSystem.setShaderFogEnd(finBrume);
            vue.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Termine un lot ; les contours cachés se dessinent sans test de profondeur (correctif Total).
     */
    private static void vider(RenderType type, boolean visible) {
        if (visible) {
            TAMPONS_FILONS.endBatch(type);
            return;
        }
        // NO_DEPTH_TEST leaves inherited depth state alone in Minecraft 1.20.1.
        boolean profondeurActive = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            TAMPONS_FILONS.endBatch(type);
        } finally {
            if (profondeurActive) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }

    private static void lignes(
            RenderType type,
            Contours c,
            Vec3 camera,
            StyleFilons style,
            boolean visible,
            boolean lisere) {
        VertexConsumer v = TAMPONS_FILONS.getBuffer(type);
        for (int i = 0; i < c.positions.length; i++) {
            if (!c.dessines[i]) continue;
            float a = style.alpha(c.distances[i], visible);
            if (a <= 0) continue;
            int couleur = lisere ? 0x101010 : c.couleurs[i];
            int alpha = Math.round((lisere ? a * .55f : a) * 255);
            long p = c.positions[i];
            float x0 = (float) (FilonMemoire.x(p) - camera.x) - .004f,
                    y0 = (float) (FilonMemoire.y(p) - camera.y) - .004f,
                    z0 = (float) (FilonMemoire.z(p) - camera.z) - .004f;
            boite(v, x0, y0, z0, x0 + 1.008f, y0 + 1.008f, z0 + 1.008f, couleur, alpha);
        }
    }

    /** Les 12 arêtes d'un cube ; la normale porte la direction du segment (shader des lignes). */
    private static void boite(
            VertexConsumer v,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            int couleur,
            int alpha) {
        int r = (couleur >> 16) & 255, g = (couleur >> 8) & 255, b = couleur & 255;
        segment(v, x0, y0, z0, x1, y0, z0, r, g, b, alpha, 1, 0, 0);
        segment(v, x0, y1, z0, x1, y1, z0, r, g, b, alpha, 1, 0, 0);
        segment(v, x0, y0, z1, x1, y0, z1, r, g, b, alpha, 1, 0, 0);
        segment(v, x0, y1, z1, x1, y1, z1, r, g, b, alpha, 1, 0, 0);
        segment(v, x0, y0, z0, x0, y1, z0, r, g, b, alpha, 0, 1, 0);
        segment(v, x1, y0, z0, x1, y1, z0, r, g, b, alpha, 0, 1, 0);
        segment(v, x0, y0, z1, x0, y1, z1, r, g, b, alpha, 0, 1, 0);
        segment(v, x1, y0, z1, x1, y1, z1, r, g, b, alpha, 0, 1, 0);
        segment(v, x0, y0, z0, x0, y0, z1, r, g, b, alpha, 0, 0, 1);
        segment(v, x1, y0, z0, x1, y0, z1, r, g, b, alpha, 0, 0, 1);
        segment(v, x0, y1, z0, x0, y1, z1, r, g, b, alpha, 0, 0, 1);
        segment(v, x1, y1, z0, x1, y1, z1, r, g, b, alpha, 0, 0, 1);
    }

    private static void segment(
            VertexConsumer v,
            float xa,
            float ya,
            float za,
            float xb,
            float yb,
            float zb,
            int r,
            int g,
            int b,
            int a,
            float nx,
            float ny,
            float nz) {
        v.vertex(xa, ya, za).color(r, g, b, a).normal(nx, ny, nz).endVertex();
        v.vertex(xb, yb, zb).color(r, g, b, a).normal(nx, ny, nz).endVertex();
    }

    /** Faces exposées des minerais, très translucides (option Remplissage). */
    private static void faces(
            RenderType type, Contours c, Vec3 camera, StyleFilons style, boolean visible) {
        VertexConsumer v = TAMPONS_FILONS.getBuffer(type);
        for (int i = 0; i < c.positions.length; i++) {
            if (!c.dessines[i] || c.faces[i] == 0) continue;
            float a = style.alphaFaces(c.distances[i], visible);
            if (a <= 0) continue;
            int couleur = c.couleurs[i], alpha = Math.round(a * 255);
            int r = (couleur >> 16) & 255, g = (couleur >> 8) & 255, b = couleur & 255;
            long p = c.positions[i];
            float x0 = (float) (FilonMemoire.x(p) - camera.x) - .002f,
                    y0 = (float) (FilonMemoire.y(p) - camera.y) - .002f,
                    z0 = (float) (FilonMemoire.z(p) - camera.z) - .002f;
            float x1 = x0 + 1.004f, y1 = y0 + 1.004f, z1 = z0 + 1.004f;
            int m = c.faces[i];
            if ((m & 1) != 0)
                quad(v, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, alpha);
            if ((m & 2) != 0)
                quad(v, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, alpha);
            if ((m & 4) != 0)
                quad(v, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, alpha);
            if ((m & 8) != 0)
                quad(v, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, alpha);
            if ((m & 16) != 0)
                quad(v, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, alpha);
            if ((m & 32) != 0)
                quad(v, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, alpha);
        }
    }

    private static void quad(
            VertexConsumer v,
            float xa,
            float ya,
            float za,
            float xb,
            float yb,
            float zb,
            float xc,
            float yc,
            float zc,
            float xd,
            float yd,
            float zd,
            int r,
            int g,
            int b,
            int a) {
        v.vertex(xa, ya, za).color(r, g, b, a).endVertex();
        v.vertex(xb, yb, zb).color(r, g, b, a).endVertex();
        v.vertex(xc, yc, zc).color(r, g, b, a).endVertex();
        v.vertex(xd, yd, zd).color(r, g, b, a).endVertex();
    }
}
