package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Recherche explicite d'une recette, locale et distincte de l'objectif et de la discussion. */
public final class RechercheEmi {
    private static String recette = "", monde = "", dimension = "";
    private static long numero, fin, dernierClic, envoye;
    private static boolean attente;
    private static Object connexion;
    private static Set<String> objets = Set.of();
    private static List<Pont.Repere> reperes = List.of();
    private static List<Pont.Cible> faisceaux = List.of();
    private static JsonObject derniereVue;

    private RechercheEmi() {}

    public static boolean actif() {
        Minecraft mc = Minecraft.getInstance();
        return !recette.isEmpty()
                && mc.level != null
                && mc.player != null
                && mc.getConnection() == connexion
                && System.currentTimeMillis() < fin
                && mc.level.dimension().location().toString().equals(dimension)
                && Filons.monde().equals(monde);
    }

    public static boolean selectionnee(String id) {
        return actif() && recette.equals(id);
    }

    public static boolean cherche(String id) {
        return actif() && objets.contains(id);
    }

    public static List<Pont.Repere> reperes() {
        return actif() ? reperes : List.of();
    }

    public static List<Pont.Cible> faisceaux() {
        return actif() ? faisceaux : List.of();
    }

    public static String bouton(String id) {
        return selectionnee(id) ? attente ? "Recherche…" : "Arrêter" : "Coffres";
    }

    public static void basculer(String id, Set<String> ingredients) {
        long now = System.currentTimeMillis();
        if (now - dernierClic < 650) return;
        dernierClic = now;
        if (selectionnee(id)) {
            arreter();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!Pont.assistantActif()) {
            informer("Lance l’assistant pour chercher dans les coffres mémorisés.");
            return;
        }
        if (mc.level == null || mc.player == null || !Filons.synchroniserMonde()) return;
        if (ingredients.isEmpty()) {
            informer(
                    "Cette recette n’a pas d’ingrédient objet à chercher (les fluides ne sont pas suivis).");
            return;
        }
        if (ingredients.size() > 4096) {
            informer("Trop de variantes dans cette recette pour une recherche précise.");
            return;
        }
        long n = Math.max(now, numero + 1), expiration = now + 600_000;
        JsonObject demande = new JsonObject();
        demande.addProperty("n", n);
        demande.addProperty("actif", true);
        demande.addProperty("recette", id);
        demande.addProperty("monde", Filons.monde());
        demande.addProperty("dimension", mc.level.dimension().location().toString());
        demande.addProperty("fin", expiration / 1000.0);
        JsonArray items = new JsonArray();
        ingredients.stream().sorted().forEach(items::add);
        demande.add("objets", items);
        if (!Pont.envoyerRechercheEmi(demande)) {
            informer("Recherche non transmise. Réessaie.");
            return;
        }
        vider();
        recette = id;
        monde = Filons.monde();
        dimension = mc.level.dimension().location().toString();
        connexion = mc.getConnection();
        numero = n;
        fin = expiration;
        envoye = now;
        attente = true;
        objets = Set.copyOf(ingredients);
    }

    public static void arreter() {
        JsonObject demande = new JsonObject();
        demande.addProperty("n", Math.max(System.currentTimeMillis(), numero + 1));
        demande.addProperty("actif", false);
        Pont.envoyerRechercheEmi(demande);
        vider(); // arrêt immédiat, même si l'assistant est arrêté
    }

    private static void vider() {
        recette = "";
        attente = false;
        objets = Set.of();
        reperes = List.of();
        faisceaux = List.of();
        derniereVue = null;
    }

    private static void informer(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(message), false);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || recette.isEmpty()) return;
        if (!actif()) {
            vider();
            return;
        }
        if (attente && System.currentTimeMillis() - envoye > 8000) {
            informer("L’assistant n’a pas répondu à la recherche EMI. Relance-le puis réessaie.");
            vider();
            return;
        }
        JsonObject vue = Pont.partie("recherche_emi");
        if (vue == derniereVue
                || (long) Pont.nombre(vue, "n", 0) != numero
                || !monde.equals(Pont.texte(vue, "monde"))
                || !dimension.equals(Pont.texte(vue, "dimension"))) return;
        derniereVue = vue;
        List<Pont.Repere> coffres = new ArrayList<>();
        List<Pont.Cible> rayons = new ArrayList<>();
        for (JsonElement e : Pont.liste(vue, "coffres")) {
            if (coffres.size() >= 16 || !e.isJsonObject()) break;
            JsonObject c = e.getAsJsonObject();
            if (!dimension.equals(Pont.texte(c, "dimension"))) continue;
            int x = (int) Pont.nombre(c, "x", 0),
                    y = (int) Pont.nombre(c, "y", 0),
                    z = (int) Pont.nombre(c, "z", 0);
            coffres.add(new Pont.Repere(x, y, z, dimension, Pont.texte(c, "texte")));
            rayons.add(
                    new Pont.Cible(
                            x,
                            y,
                            z,
                            dimension,
                            "Ingrédients EMI",
                            new float[] {0.25F, 0.88F, 1F},
                            48));
        }
        reperes = List.copyOf(coffres);
        faisceaux = List.copyOf(rayons);
        if (attente)
            informer(
                    Pont.texte(vue, "message")
                            + " Repérage pendant 10 min ; reclique sur Coffres / Arrêter pour couper.");
        attente = false;
    }
}
