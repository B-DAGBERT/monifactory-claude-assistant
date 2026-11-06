package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Échange avec assistant_mc.py par fichiers JSON dans kubejs/assistant : il écrit vue_mod.json (ce
 * que le mod doit montrer), le mod écrit demande_mod.json (ce que le joueur demande).
 */
public final class Pont {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path DOSSIER =
            FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
    private static final Path VUE = DOSSIER.resolve("vue_mod.json");

    /** Un faisceau à dessiner dans le monde : destination du guidage, ou machine à surveiller. */
    public record Cible(
            int x, int y, int z, String dimension, String label, float[] couleur, int hauteur) {}

    /**
     * Un coffre qui contient ce que le joueur cherche, avec ce qu'il y a dedans (« Torch ×18 »).
     */
    public record Repere(int x, int y, int z, String dimension, String texte) {}

    private static long dateLue = -1;
    private static JsonObject vue = new JsonObject();
    private static Set<String> besoins = Set.of();
    private static List<Cible> cibles = List.of();
    private static Set<String> cherches = Set.of(); // objets cherchés : ids exacts...
    private static List<Pattern> motifs = List.of(); // ... et ids avec * (gtceu:*_iron_ore)
    private static List<Repere> reperes = List.of();

    private Pont() {}

    /** Identité de la connexion et de la dimension, jamais déduite d'une ancienne vue. */
    public static String cleContexte() {
        if (!IdentiteMonde.isolation()) return "legacy";
        String monde = IdentiteMonde.monde(), session = IdentiteMonde.session();
        var niveau = Minecraft.getInstance().level;
        if (monde.isEmpty() || session.isEmpty() || niveau == null) return "";
        return monde + "|" + session + "|" + niveau.dimension().location();
    }

    private static String chaine(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonPrimitive() && valeur.getAsJsonPrimitive().isString()
                ? valeur.getAsString()
                : "";
    }

    /** Une réponse d'une ancienne connexion ne peut pas redevenir active par son seul mtime. */
    public static boolean contexteValide(JsonObject objet) {
        if (!IdentiteMonde.isolation()) return true;
        String contexte = cleContexte();
        return !contexte.isEmpty()
                && contexte.equals(
                        chaine(objet, "monde_actif")
                                + "|"
                                + chaine(objet, "session_monde")
                                + "|"
                                + chaine(objet, "dimension_monde"));
    }

    /** Annote à la création ; une demande déjà annotée ne change jamais de propriétaire. */
    public static boolean annoterContexte(JsonObject objet) {
        if (!IdentiteMonde.isolation()) return true;
        if (objet.has("monde_actif") || objet.has("session_monde") || objet.has("dimension_monde"))
            return contexteValide(objet);
        String contexte = cleContexte();
        if (contexte.isEmpty()) return false;
        String[] valeurs = contexte.split("\\|", -1);
        objet.addProperty("monde_actif", valeurs[0]);
        objet.addProperty("session_monde", valeurs[1]);
        objet.addProperty("dimension_monde", valeurs[2]);
        return true;
    }

    private static void verifierVue() {
        if (contexteValide(vue)) return;
        vue = new JsonObject();
        besoins = cherches = Set.of();
        cibles = List.of();
        motifs = List.of();
        reperes = List.of();
        dateLue = -1;
    }

    /** Relit vue_mod.json s'il a changé depuis la dernière lecture. */
    public static void rafraichir() {
        verifierVue();
        try {
            if (!Files.exists(VUE)) {
                return;
            }
            long date = Files.getLastModifiedTime(VUE).toMillis();
            if (date == dateLue) {
                return;
            }
            JsonObject lu =
                    JsonParser.parseString(Files.readString(VUE, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!contexteValide(lu)) return;
            Set<String> nouveauxBesoins = new HashSet<>();
            for (JsonElement e : tableau(lu, "besoins")) {
                nouveauxBesoins.add(e.getAsString());
            }
            List<Cible> nouvellesCibles = new ArrayList<>();
            lireCibles(tableau(lu, "guidage"), 256, nouvellesCibles);
            JsonObject recherche =
                    lu.has("recherche") && lu.get("recherche").isJsonObject()
                            ? lu.getAsJsonObject("recherche")
                            : new JsonObject();
            Set<String> nouveauxCherches = new HashSet<>();
            List<Pattern> nouveauxMotifs = new ArrayList<>();
            for (JsonElement e : tableau(recherche, "objets")) {
                String motif = e.getAsString();
                if (motif.contains("*")) {
                    nouveauxMotifs.add(
                            Pattern.compile(Pattern.quote(motif).replace("*", "\\E.*\\Q")));
                } else {
                    nouveauxCherches.add(motif);
                }
            }
            List<Repere> nouveauxReperes = new ArrayList<>();
            for (JsonElement e : tableau(recherche, "coffres")) {
                JsonObject c = e.getAsJsonObject();
                nouveauxReperes.add(
                        new Repere(
                                (int) Math.floor(nombre(c, "x", 0)),
                                (int) Math.floor(nombre(c, "y", 0)),
                                (int) Math.floor(nombre(c, "z", 0)),
                                texte(c, "dimension"),
                                texte(c, "texte")));
            }
            vue = lu;
            besoins = nouveauxBesoins;
            cibles = nouvellesCibles;
            cherches = nouveauxCherches;
            motifs = nouveauxMotifs;
            reperes = nouveauxReperes;
            dateLue = date;
        } catch (Exception e) {
            // Le fichier est peut-être en train d'être écrit : on réessaiera au prochain tick
        }
    }

    private static JsonArray tableau(JsonObject objet, String cle) {
        return objet.has(cle) && objet.get(cle).isJsonArray()
                ? objet.getAsJsonArray(cle)
                : new JsonArray();
    }

    private static void lireCibles(JsonArray liste, int hauteur, List<Cible> sortie) {
        for (JsonElement e : liste) {
            JsonObject c = e.getAsJsonObject();
            if (!c.has("x") || c.get("x").isJsonNull() || !c.has("z") || c.get("z").isJsonNull()) {
                continue; // seulement une hauteur : pas de faisceau
            }
            int y = c.has("y") && !c.get("y").isJsonNull() ? c.get("y").getAsInt() : 64;
            String dimension =
                    c.has("dimension") && !c.get("dimension").isJsonNull()
                            ? c.get("dimension").getAsString()
                            : null;
            sortie.add(
                    new Cible(
                            c.get("x").getAsInt(),
                            y,
                            c.get("z").getAsInt(),
                            dimension,
                            texte(c, "label"),
                            couleur(texte(c, "couleur")),
                            hauteur));
        }
    }

    static String texte(JsonObject objet, String cle) {
        return objet.has(cle) && !objet.get(cle).isJsonNull() ? objet.get(cle).getAsString() : "";
    }

    static float[] couleur(String nom) {
        return switch (nom) {
            case "rouge" -> new float[] {1.0F, 0.3F, 0.25F};
            case "jaune" -> new float[] {1.0F, 0.85F, 0.2F};
            case "vert" -> new float[] {0.4F, 1.0F, 0.45F};
            default -> new float[] {0.35F, 0.7F, 1.0F};
        };
    }

    public static JsonObject vue() {
        verifierVue();
        return vue;
    }

    /**
     * Réglage « coffres » : le panneau de retrait de l'arbre EMI. Actif si l'assistant n'en dit
     * rien.
     */
    public static boolean retraitCoffres() {
        verifierVue();
        return !vue.has("retrait_coffres") || vrai(vue, "retrait_coffres");
    }

    /** Une partie de la vue (hud, bulle, ecran, recette), ou un objet vide. */
    public static JsonObject partie(String cle) {
        verifierVue();
        return vue.has(cle) && vue.get(cle).isJsonObject()
                ? vue.getAsJsonObject(cle)
                : new JsonObject();
    }

    static JsonArray liste(JsonObject objet, String cle) {
        return tableau(objet, cle);
    }

    static double nombre(JsonObject objet, String cle, double parDefaut) {
        return objet.has(cle) && objet.get(cle).isJsonPrimitive()
                ? objet.get(cle).getAsDouble()
                : parDefaut;
    }

    static boolean vrai(JsonObject objet, String cle) {
        return objet.has(cle) && objet.get(cle).isJsonPrimitive() && objet.get(cle).getAsBoolean();
    }

    private static final Map<String, ItemStack> OBJETS = new HashMap<>();

    /** L'objet à dessiner pour un id (« gtceu:iron_rod »), gardé en cache. */
    public static ItemStack objet(String id) {
        return OBJETS.computeIfAbsent(
                id,
                cle -> {
                    ResourceLocation rl = ResourceLocation.tryParse(cle);
                    Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
                    return item == null ? ItemStack.EMPTY : new ItemStack(item);
                });
    }

    public static Set<String> besoins() {
        verifierVue();
        return besoins;
    }

    public static List<Cible> cibles() {
        verifierVue();
        return cibles;
    }

    /**
     * L'assistant tourne : il réécrit vue_mod.json au moins toutes les 5 secondes (dateLue en est
     * la date).
     */
    public static boolean assistantActif() {
        verifierVue();
        return dateLue > 0 && System.currentTimeMillis() - dateLue < 20000;
    }

    private static long relanceA = 0;

    /** Vrai pendant la minute qui suit « Relancer l'assistant » (le temps qu'il démarre). */
    public static boolean relanceEnCours() {
        return !assistantActif() && System.currentTimeMillis() - relanceA < 60000;
    }

    /**
     * Relance l'assistant avec son lanceur (Lancer assistant.bat), connu tant qu'il a tourné une
     * fois.
     */
    public static boolean relancer() {
        verifierVue();
        String lanceur = texte(vue, "lanceur");
        if (lanceur.isEmpty() || !Files.exists(Path.of(lanceur)) || relanceEnCours()) {
            return false;
        }
        try {
            if (System.getProperty("os.name", "").startsWith("Mac")) {
                new ProcessBuilder("/usr/bin/open", lanceur).start();
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", lanceur).start();
            }
            relanceA = System.currentTimeMillis();
            return true;
        } catch (IOException e) {
            LOGGER.warn("Assistant IA : relance impossible", e);
            return false;
        }
    }

    /**
     * Une recherche est en cours (« trouve-moi mes torches », « les minerais qui donnent du Iron
     * Ingot »).
     */
    public static boolean rechercheActive() {
        verifierVue();
        return !cherches.isEmpty() || !motifs.isEmpty();
    }

    public static boolean cherche(String id) {
        verifierVue();
        if (cherches.contains(id)) {
            return true;
        }
        for (Pattern motif : motifs) {
            if (motif.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    public static List<Repere> reperes() {
        verifierVue();
        return reperes;
    }

    /**
     * Écrit un fichier texte pour l'assistant et Codex, d'un coup : il n'est jamais lu à moitié
     * écrit.
     */
    static boolean ecrireTexte(String nom, List<String> lignes) {
        if ("inventaire_details.txt".equals(nom) && IdentiteMonde.isolation()) {
            return ecrireInventaireDetaille(lignes);
        }
        try {
            Files.createDirectories(DOSSIER);
            Path temporaire = DOSSIER.resolve(nom + ".tmp");
            Files.write(temporaire, lignes, StandardCharsets.UTF_8);
            Files.move(
                    temporaire,
                    DOSSIER.resolve(nom),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Assistant IA : {} non écrit", nom, e);
            return false;
        }
    }

    private static boolean ecrireInventaireDetaille(List<String> lignes) {
        JsonObject contexte = new JsonObject();
        if (!annoterContexte(contexte)) return false;
        try {
            Path dossier = DOSSIER.resolve("mondes").resolve(chaine(contexte, "monde_actif"));
            Files.createDirectories(dossier);
            String texte = String.join(System.lineSeparator(), lignes) + System.lineSeparator();
            byte[] octets = texte.getBytes(StandardCharsets.UTF_8);
            contexte.addProperty(
                    "sha256",
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(octets)));
            Path temporaire = dossier.resolve("inventaire_details.txt.tmp");
            Files.write(temporaire, octets);
            Files.move(
                    temporaire,
                    dossier.resolve("inventaire_details.txt"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            temporaire = dossier.resolve("inventaire_details_contexte.json.tmp");
            Files.writeString(temporaire, contexte.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporaire,
                    dossier.resolve("inventaire_details_contexte.json"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            LOGGER.warn("Assistant IA : inventaire détaillé non écrit", e);
            return false;
        }
    }

    private static long dernierEnvoi;

    /** Transmet une demande et indique si son écriture atomique a réussi. */
    public static synchronized boolean envoyer(JsonObject demande) {
        if (!annoterContexte(demande)) return false;
        if (!demande.has("requete")) {
            dernierEnvoi = Math.max(System.currentTimeMillis(), dernierEnvoi + 1);
            demande.addProperty("n", dernierEnvoi);
            demande.addProperty("requete", java.util.UUID.randomUUID().toString());
            JsonObject ecran = partie("ecran");
            if (!demande.has("projet") && !"reglage".equals(texte(demande, "type"))) {
                JsonObject projets =
                        ecran.has("projets") && ecran.get("projets").isJsonObject()
                                ? ecran.getAsJsonObject("projets")
                                : new JsonObject();
                if (!texte(projets, "actif").isEmpty())
                    demande.addProperty("projet", texte(projets, "actif"));
                demande.addProperty("dimension_envoi", texte(ecran, "dimension_actuelle"));
            }
        }
        JsonObject ecran = partie("ecran");
        if (ecran.has("demandes")
                && ecran.get("demandes").isJsonObject()
                && ecran.getAsJsonObject("demandes").has("protocole")
                && ecran.getAsJsonObject("demandes").get("protocole").isJsonPrimitive()
                && ecran.getAsJsonObject("demandes").getAsJsonPrimitive("protocole").isNumber()
                && ecran.getAsJsonObject("demandes").get("protocole").getAsInt() == 2) {
            try {
                FileDemandes.envoyer(DOSSIER, demande);
                return true;
            } catch (IOException e) {
                LOGGER.warn("Assistant : demande non transmise", e);
                return false;
            }
        }
        return ecrireDemande("demande_mod.json", demande);
    }

    /** Les réglages disposent de leur canal : ne jamais écraser une question en attente. */
    public static synchronized boolean envoyerConfigurationIa(JsonObject demande) {
        dernierEnvoi = Math.max(System.currentTimeMillis(), dernierEnvoi + 1);
        demande.addProperty("n", dernierEnvoi);
        return ecrireDemande("configuration_ia.json", demande);
    }

    /** Canal indépendant : une recherche EMI ne remplace jamais une question en attente. */
    static boolean envoyerRechercheEmi(JsonObject demande) {
        return ecrireDemande("recherche_emi.json", demande);
    }

    public static boolean envoyerInventaire(JsonObject inventaire) {
        return ecrireDemande("inventaire_joueur.json", inventaire);
    }

    /** Capture de lecture V2 indépendante du relevé historique utilisé par les transferts. */
    static boolean envoyerInventaireProduction(JsonObject inventaire) {
        return ecrireDemande("production_v2_inventaire.json", inventaire);
    }

    public static boolean envoyerProgresFabrication(JsonObject progres) {
        return ecrireDemande("fabrication_emi.json", progres);
    }

    private static boolean ecrireDemande(String nom, JsonObject demande) {
        if (!annoterContexte(demande)) return false;
        try {
            Files.createDirectories(DOSSIER);
            Path temporaire = DOSSIER.resolve(nom + ".tmp");
            Files.writeString(temporaire, demande.toString(), StandardCharsets.UTF_8);
            for (int essai = 1; ; essai++) {
                try {
                    Files.move(
                            temporaire,
                            DOSSIER.resolve(nom),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                    return true;
                } catch (AccessDeniedException e) {
                    // Windows refuse le remplacement tant que l'assistant lit encore le fichier.
                    if (essai == 4) throw e;
                    Thread.sleep(5L * essai);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Assistant IA : demande non transmise", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
