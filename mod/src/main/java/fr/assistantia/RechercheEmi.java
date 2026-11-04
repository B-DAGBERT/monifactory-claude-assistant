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
    private static java.util.function.Predicate<net.minecraft.world.item.ItemStack> conforme =
            s -> false;
    private static int ticks;
    private static Object connexion;
    private static Set<String> objets = Set.of(), demandes = Set.of();
    private static List<Pont.Repere> reperes = List.of();
    private static JsonObject derniereVue;
    private static Map<String, Integer> quantites = Map.of(), fabricationsPrecedentes = Map.of();

    private RechercheEmi() {}

    public static boolean actif() {
        Minecraft mc = Minecraft.getInstance();
        return !recette.isEmpty()
                && Emi.arbreActif()
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

    /** Même sélection et mêmes crédits que les contours : aucun recalcul depuis l'objectif. */
    public static RetraitCoffre.Plan retrait(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (!Pont.retraitCoffres() || !actif() || collecte == null)
            return new RetraitCoffre.Plan(List.of(), 0);
        actualiserCollecte();
        actualiserVue();
        return RetraitCoffre.planRecherche(
                menu,
                stocks -> MemoireCollecte.retraitSelection(stocks, quantites),
                item ->
                        conforme.test(item)
                                ? String.valueOf(ForgeRegistries.ITEMS.getKey(item.getItem()))
                                : "");
    }

    private static void actualiserCollecte() {
        if (collecte == null || RetraitCoffre.occupe() || Emi.envoiMachineEnCours()) return;
        long version = collecte.version();
        var cumul = Emi.fabricationsConfirmees();
        Map<String, Integer> preuves = new HashMap<>();
        cumul.forEach(
                (id, n) -> {
                    int gain = n - fabricationsPrecedentes.getOrDefault(id, 0);
                    if (gain > 0) preuves.put(id, gain);
                });
        fabricationsPrecedentes = cumul;
        Set<String> restants = collecte.actualiser(inventaire(), preuves);
        if (collecte.version() != version) derniereVue = null;
        if (!restants.equals(demandes)) {
            demandes = restants;
            transmettre();
        }
    }

    public static String bouton(String id) {
        return selectionnee(id) ? attente ? "Recherche…" : "Arrêter" : "Coffres";
    }

    public static void basculer(
            String id,
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte) {
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
        fabricationsPrecedentes = Emi.fabricationsConfirmees();
        conforme = accepte;
        demandes = suivi.actualiser(inventaire());
        transmettre();
    }

    public static void recalculer(
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte) {
        if (!actif()) return;
        RetraitCoffre.arreter();
        AideCoffre.vider();
        collecte = suivi;
        fabricationsPrecedentes = Emi.fabricationsConfirmees();
        conforme = accepte;
        demandes = suivi.actualiser(inventaire());
        fin = System.currentTimeMillis() + 600_000;
        transmettre();
    }

    private static Map<String, Integer> inventaire() {
        Map<String, Integer> stock = new HashMap<>();
        var joueur = Minecraft.getInstance().player;
        if (joueur != null) {
            List<net.minecraft.world.item.ItemStack> piles =
                    new ArrayList<>(joueur.getInventory().items);
            piles.addAll(joueur.getInventory().armor);
            piles.addAll(joueur.getInventory().offhand);
            piles.add(joueur.containerMenu.getCarried());
            for (var item : piles) {
                if (item.isEmpty() || !conforme.test(item)) continue;
                var id = ForgeRegistries.ITEMS.getKey(item.getItem());
                if (id != null) stock.merge(id.toString(), item.getCount(), Integer::sum);
            }
            for (var credit : Emi.creditsFabrication()) {
                if (!conforme.test(credit.objet())) continue;
                var id = ForgeRegistries.ITEMS.getKey(credit.objet().getItem());
                if (id != null) stock.merge(id.toString(), credit.quantite(), Integer::sum);
            }
        }
        return stock;
    }

    private static void transmettre() {
        long now = System.currentTimeMillis();
        numero = Math.max(now, numero + 1);
        envoye = now;
        attente = !demandes.isEmpty();
        // Retirer immédiatement les anciens faisceaux. Une réponse de l'ancienne sélection
        // est rejetée grâce au numéro de requête, même si elle arrive après le transfert.
        reperes = List.of();
        objets = Set.of();
        quantites = Map.of();
        derniereVue = null;
        JsonObject demande = new JsonObject();
        demande.addProperty("n", numero);
        demande.addProperty("actif", attente);
        demande.addProperty("recette", recette);
        demande.addProperty("monde", monde);
        demande.addProperty("dimension", dimension);
        demande.addProperty("fin", fin / 1000.0);
        JsonArray items = new JsonArray();
        demandes.stream().sorted().forEach(items::add);
        demande.add("objets", items);
        if (!Pont.envoyerRechercheEmi(demande)) {
            informer("Recherche non transmise. Réessaie.");
            // Pas de retour à une ancienne liste d'ingrédients déjà validés.
            attente = false;
        }
        if (demandes.isEmpty())
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
        RetraitCoffre.arreter();
        AideCoffre.vider();
        recette = "";
        attente = false;
        objets = demandes = Set.of();
        quantites = Map.of();
        reperes = List.of();
        derniereVue = null;
        collecte = null;
        conforme = s -> false;
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
        if (++ticks % 2 == 0) actualiserCollecte();
        actualiserVue();
    }

    private static void actualiserVue() {
        if (collecte == null) return;
        if (demandes.isEmpty())
            return; // Garder la recherche validée, sans revenir au guidage de l'objectif.
        if (attente && System.currentTimeMillis() - envoye > 8000) {
            informer("L’assistant n’a pas répondu à la recherche EMI. Relance-le puis réessaie.");
            vider();
            return;
        }
        if (RetraitCoffre.occupe() || Emi.envoiMachineEnCours()) return;
        JsonObject vue = Pont.partie("recherche_emi");
        if (vue == derniereVue
                || (long) Pont.nombre(vue, "n", 0) != numero
                || !monde.equals(Pont.texte(vue, "monde"))
                || !dimension.equals(Pont.texte(vue, "dimension"))) return;
        derniereVue = vue;
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        List<String> groupes = new ArrayList<>();
        Map<String, List<Pont.Repere>> positions = new java.util.LinkedHashMap<>();
        for (JsonElement e : Pont.liste(vue, "coffres")) {
            if (!e.isJsonObject()) continue;
            JsonObject c = e.getAsJsonObject();
            if (!dimension.equals(Pont.texte(c, "dimension"))) continue;
            int x = (int) Pont.nombre(c, "x", 0),
                    y = (int) Pont.nombre(c, "y", 0),
                    z = (int) Pont.nombre(c, "z", 0);
            String groupe = Pont.texte(c, "stock");
            if (groupe.isEmpty()) groupe = x + "|" + y + "|" + z;
            boolean connu = positions.containsKey(groupe);
            positions
                    .computeIfAbsent(groupe, key -> new ArrayList<>())
                    .add(new Pont.Repere(x, y, z, dimension, Pont.texte(c, "texte")));
            if (connu || !c.has("objets") || !c.get("objets").isJsonObject()) continue;
            for (var entry : c.getAsJsonObject("objets").entrySet()) {
                int n =
                        (int)
                                Math.min(
                                        1_000_000,
                                        Math.max(
                                                0,
                                                Pont.nombre(
                                                        c.getAsJsonObject("objets"),
                                                        entry.getKey(),
                                                        0)));
                if (n == 0 || !demandes.contains(entry.getKey())) continue;
                groupes.add(groupe);
                stocks.add(new PlanRetrait.Stock<>(entry.getKey(), n, false));
            }
        }
        Set<String> utiles = new java.util.LinkedHashSet<>(),
                visibles = new java.util.LinkedHashSet<>();
        Map<String, Integer> selection = new HashMap<>();
        for (var prise : collecte.dansCoffres(stocks)) {
            selection.merge(stocks.get(prise.index()).objet(), prise.quantite(), Integer::sum);
            utiles.add(stocks.get(prise.index()).objet());
            visibles.add(groupes.get(prise.index()));
        }
        List<Pont.Repere> coffres = new ArrayList<>();
        for (String groupe : visibles) coffres.addAll(positions.get(groupe));
        objets = Set.copyOf(utiles);
        quantites = Map.copyOf(selection);
        reperes = List.copyOf(coffres);
        if (attente)
            informer(
                    coffres.isEmpty()
                            ? "Aucun stockage utile connu pour les branches restantes."
                            : coffres.size()
                                    + " stockage(s) utile(s) à l’arbre. Composants prêts à récupérer en priorité.");
        attente = false;
    }
}
