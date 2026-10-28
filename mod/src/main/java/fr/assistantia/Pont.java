package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Échange avec assistant_mc.py par fichiers JSON dans kubejs/assistant :
 * il écrit vue_mod.json (ce que le mod doit montrer), le mod écrit demande_mod.json (ce que le joueur demande).
 */
public final class Pont {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path DOSSIER = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
    private static final Path VUE = DOSSIER.resolve("vue_mod.json");
    private static final Path DEMANDE = DOSSIER.resolve("demande_mod.json");

    /** Un faisceau à dessiner dans le monde : destination du guidage, ou machine à surveiller. */
    public record Cible(int x, int y, int z, String dimension, String label, float[] couleur, int hauteur) {
    }

    /** Un coffre qui contient ce que le joueur cherche, avec ce qu'il y a dedans (« Torch ×18 »). */
    public record Repere(int x, int y, int z, String dimension, String texte) {
    }

    private static long dateLue = -1;
    private static JsonObject vue = new JsonObject();
    private static Set<String> besoins = Set.of();
    private static List<Cible> cibles = List.of();
    private static Set<String> cherches = Set.of();     // objets cherchés : ids exacts...
    private static List<Pattern> motifs = List.of();   // ... et ids avec * (gtceu:*_iron_ore)
    private static List<Repere> reperes = List.of();

    private Pont() {
    }

    /** Relit vue_mod.json s'il a changé depuis la dernière lecture. */
    public static void rafraichir() {
        try {
            if (!Files.exists(VUE)) {
                return;
            }
            long date = Files.getLastModifiedTime(VUE).toMillis();
            if (date == dateLue) {
                return;
            }
            JsonObject lu = JsonParser.parseString(Files.readString(VUE, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> nouveauxBesoins = new HashSet<>();
            for (JsonElement e : tableau(lu, "besoins")) {
                nouveauxBesoins.add(e.getAsString());
            }
            List<Cible> nouvellesCibles = new ArrayList<>();
            lireCibles(tableau(lu, "guidage"), 256, nouvellesCibles);
            lireCibles(tableau(lu, "marqueurs"), 12, nouvellesCibles);
            JsonObject recherche = lu.has("recherche") && lu.get("recherche").isJsonObject()
                    ? lu.getAsJsonObject("recherche") : new JsonObject();
            Set<String> nouveauxCherches = new HashSet<>();
            List<Pattern> nouveauxMotifs = new ArrayList<>();
            for (JsonElement e : tableau(recherche, "objets")) {
                String motif = e.getAsString();
                if (motif.contains("*")) {
                    nouveauxMotifs.add(Pattern.compile(Pattern.quote(motif).replace("*", "\\E.*\\Q")));
                } else {
                    nouveauxCherches.add(motif);
                }
            }
            List<Repere> nouveauxReperes = new ArrayList<>();
            for (JsonElement e : tableau(recherche, "coffres")) {
                JsonObject c = e.getAsJsonObject();
                nouveauxReperes.add(new Repere((int) Math.floor(nombre(c, "x", 0)), (int) Math.floor(nombre(c, "y", 0)),
                        (int) Math.floor(nombre(c, "z", 0)), texte(c, "dimension"), texte(c, "texte")));
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
        return objet.has(cle) && objet.get(cle).isJsonArray() ? objet.getAsJsonArray(cle) : new JsonArray();
    }

    private static void lireCibles(JsonArray liste, int hauteur, List<Cible> sortie) {
        for (JsonElement e : liste) {
            JsonObject c = e.getAsJsonObject();
            if (!c.has("x") || c.get("x").isJsonNull() || !c.has("z") || c.get("z").isJsonNull()) {
                continue; // seulement une hauteur : pas de faisceau
            }
            int y = c.has("y") && !c.get("y").isJsonNull() ? c.get("y").getAsInt() : 64;
            String dimension = c.has("dimension") && !c.get("dimension").isJsonNull() ? c.get("dimension").getAsString() : null;
            sortie.add(new Cible(c.get("x").getAsInt(), y, c.get("z").getAsInt(), dimension,
                    texte(c, "label"), couleur(texte(c, "couleur")), hauteur));
        }
    }

    static String texte(JsonObject objet, String cle) {
        return objet.has(cle) && !objet.get(cle).isJsonNull() ? objet.get(cle).getAsString() : "";
    }

    static float[] couleur(String nom) {
        return switch (nom) {
            case "rouge" -> new float[]{1.0F, 0.3F, 0.25F};
            case "jaune" -> new float[]{1.0F, 0.85F, 0.2F};
            case "vert" -> new float[]{0.4F, 1.0F, 0.45F};
            default -> new float[]{0.35F, 0.7F, 1.0F};
        };
    }

    public static JsonObject vue() {
        return vue;
    }

    /** Une partie de la vue (hud, bulle, ecran, recette), ou un objet vide. */
    public static JsonObject partie(String cle) {
        return vue.has(cle) && vue.get(cle).isJsonObject() ? vue.getAsJsonObject(cle) : new JsonObject();
    }

    static JsonArray liste(JsonObject objet, String cle) {
        return tableau(objet, cle);
    }

    static double nombre(JsonObject objet, String cle, double parDefaut) {
        return objet.has(cle) && objet.get(cle).isJsonPrimitive() ? objet.get(cle).getAsDouble() : parDefaut;
    }

    static boolean vrai(JsonObject objet, String cle) {
        return objet.has(cle) && objet.get(cle).isJsonPrimitive() && objet.get(cle).getAsBoolean();
    }

    private static final Map<String, ItemStack> OBJETS = new HashMap<>();

    /** L'objet à dessiner pour un id (« gtceu:iron_rod »), gardé en cache. */
    public static ItemStack objet(String id) {
        return OBJETS.computeIfAbsent(id, cle -> {
            ResourceLocation rl = ResourceLocation.tryParse(cle);
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    public static Set<String> besoins() {
        return besoins;
    }

    public static List<Cible> cibles() {
        return cibles;
    }

    /** L'assistant tourne : il réécrit vue_mod.json au moins toutes les 5 secondes (dateLue en est la date). */
    public static boolean assistantActif() {
        return dateLue > 0 && System.currentTimeMillis() - dateLue < 20000;
    }

    private static long relanceA = 0;

    /** Vrai pendant la minute qui suit « Relancer l'assistant » (le temps qu'il démarre). */
    public static boolean relanceEnCours() {
        return !assistantActif() && System.currentTimeMillis() - relanceA < 60000;
    }

    /** Relance l'assistant avec son lanceur (Lancer assistant.bat), connu tant qu'il a tourné une fois. */
    public static boolean relancer() {
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

    /** Une recherche est en cours (« trouve-moi mes torches », « les minerais qui donnent du Iron Ingot »). */
    public static boolean rechercheActive() {
        return !cherches.isEmpty() || !motifs.isEmpty();
    }

    public static boolean cherche(String id) {
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
        return reperes;
    }

    /** Écrit un fichier texte pour l'assistant et Codex, d'un coup : il n'est jamais lu à moitié écrit. */
    static void ecrireTexte(String nom, List<String> lignes) {
        try {
            Files.createDirectories(DOSSIER);
            Path temporaire = DOSSIER.resolve(nom + ".tmp");
            Files.write(temporaire, lignes, StandardCharsets.UTF_8);
            Files.move(temporaire, DOSSIER.resolve(nom), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Assistant IA : {} non écrit", nom, e);
        }
    }

    /** Transmet une demande du joueur à l'assistant (question écrite, action, nouveau plan...). */
    public static void envoyer(JsonObject demande) {
        demande.addProperty("n", System.currentTimeMillis());
        try {
            Files.createDirectories(DOSSIER);
            Path temporaire = DOSSIER.resolve("demande_mod.json.tmp");
            Files.writeString(temporaire, demande.toString(), StandardCharsets.UTF_8);
            Files.move(temporaire, DEMANDE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Assistant IA : demande non transmise", e);
        }
    }
}
