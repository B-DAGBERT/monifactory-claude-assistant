package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Recherche explicite d'une recette, locale et distincte de l'objectif et de la discussion. */
public final class RechercheEmi {
    private static String recette = "", monde = "", dimension = "";
    private static long numero, fin, dernierClic, envoye;
    private static boolean attente;
    private static MemoireCollecte collecte;
    private static int ticks;
    private static Object connexion;
    private static Set<String> objets = Set.of();
    private static List<Pont.Repere> reperes = List.of();
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

    public static String bouton(String id) {
        return selectionnee(id) ? attente ? "Recherche…" : "Arrêter" : "Coffres";
    }

    public static void basculer(String id, MemoireCollecte suivi) {
        Set<String> ingredients = suivi.tous();
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
        vider();
        recette = id;
        monde = Filons.monde();
        dimension = mc.level.dimension().location().toString();
        connexion = mc.getConnection();
        fin = now + 600_000;
        collecte = suivi;
        objets = suivi.actualiser(inventaire());
        transmettre();
    }

    private static Map<String, Integer> inventaire() {
        Map<String, Integer> stock = new HashMap<>();
        var joueur = Minecraft.getInstance().player;
        if (joueur != null)
            for (var item : joueur.getInventory().items) {
                if (item.isEmpty()) continue;
                var id = ForgeRegistries.ITEMS.getKey(item.getItem());
                if (id != null) stock.merge(id.toString(), item.getCount(), Integer::sum);
            }
        return stock;
    }

    private static void transmettre() {
        long now = System.currentTimeMillis();
        numero = Math.max(now, numero + 1);
        envoye = now;
        attente = !objets.isEmpty();
        // Retirer immédiatement les anciens faisceaux. Une réponse de l'ancienne sélection
        // est rejetée grâce au numéro de requête, même si elle arrive après le transfert.
        reperes = List.of();
        derniereVue = null;
        JsonObject demande = new JsonObject();
        demande.addProperty("n", numero);
        demande.addProperty("actif", attente);
        demande.addProperty("recette", recette);
        demande.addProperty("monde", monde);
        demande.addProperty("dimension", dimension);
        demande.addProperty("fin", fin / 1000.0);
        JsonArray items = new JsonArray();
        objets.stream().sorted().forEach(items::add);
        demande.add("objets", items);
        if (!Pont.envoyerRechercheEmi(demande)) {
            informer("Recherche non transmise. Réessaie.");
            // Pas de retour à une ancienne liste d'ingrédients déjà validés.
            attente = false;
        }
        if (objets.isEmpty())
            informer("Ingrédients récupérés : repérage terminé pour cette recherche.");
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
        derniereVue = null;
        collecte = null;
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
        if (collecte != null && ++ticks % 2 == 0 && !RetraitCoffre.occupe()) {
            Set<String> restants = collecte.actualiser(inventaire());
            if (!restants.equals(objets)) {
                objets = restants;
                transmettre();
            }
        }
        if (objets.isEmpty())
            return; // Garder la recherche validée, sans revenir au guidage de l'objectif.
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
        for (JsonElement e : Pont.liste(vue, "coffres")) {
            if (!e.isJsonObject()) continue;
            JsonObject c = e.getAsJsonObject();
            if (!dimension.equals(Pont.texte(c, "dimension"))) continue;
            int x = (int) Pont.nombre(c, "x", 0),
                    y = (int) Pont.nombre(c, "y", 0),
                    z = (int) Pont.nombre(c, "z", 0);
            coffres.add(new Pont.Repere(x, y, z, dimension, Pont.texte(c, "texte")));
        }
        reperes = List.copyOf(coffres);
        if (attente)
            informer(
                    Pont.texte(vue, "message")
                            + " Repérage pendant 10 min ; reclique sur Arrêter scan pour couper.");
        attente = false;
    }
}
