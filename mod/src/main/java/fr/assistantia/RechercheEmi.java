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
    private static java.util.function.LongSupplier horloge = System::currentTimeMillis;
    private static String recette = "", monde = "", dimension = "", contexte = "";
    private static long numero, fin, dernierClic, envoye;
    private static boolean attente, inventaireEnAttente;
    private static MemoireCollecte collecte;
    private static java.util.function.Predicate<net.minecraft.world.item.ItemStack> conforme =
            s -> false;
    private static int ticks, reessais;
    private static Object connexion;
    private static Set<String> objets = Set.of(), demandes = Set.of();
    private static List<Pont.Repere> reperes = List.of();
    private static Set<String> resultatsPrets = Set.of();
    private static JsonObject derniereVue;
    private static Map<String, Integer> quantites = Map.of(), fabricationsPrecedentes = Map.of();

    /** Nombre d'objets à sortir de chaque conteneur surligné (position du premier bloc). */
    private static Map<String, Integer> aSortir = Map.of();

    /**
     * Dernier relevé des coffres reçu de l'assistant, pour les objets qu'il demandait. La sélection
     * en est recalculée ici à chaque acquisition : rien ne disparaît pendant l'aller-retour d'une
     * nouvelle demande, et un résultat pris à la main est retiré tout de suite.
     */
    private record Releve(
            List<PlanRetrait.Stock<String>> stocks,
            List<String> groupes,
            Map<String, List<Pont.Repere>> positions,
            Set<String> objets) {}

    private static Releve releve;
    private static long versionAppliquee = -1, revision;
    private static boolean annoncer;

    private RechercheEmi() {}

    /**
     * Change dès que la sélection, les crédits ou le relevé changent : les panneaux se relisent.
     */
    public static long revision() {
        return revision + (collecte == null ? 0 : collecte.version());
    }

    /**
     * Nombre d'objets à sortir de ce conteneur pour la recherche en cours, 0 sinon. Lu à chaque
     * image pour chaque coffre : l'appelant vérifie {@link #actif()} une fois par image ; la table
     * est vidée dès que la recherche s'arrête.
     */
    public static int aSortir(Pont.Repere repere) {
        return aSortir.isEmpty() ? 0 : aSortir.getOrDefault(cle(repere), 0);
    }

    private static boolean contexteValide() {
        Minecraft mc = Minecraft.getInstance();
        return !recette.isEmpty()
                && Emi.arbreActif()
                && mc.level != null
                && mc.player != null
                && mc.getConnection() == connexion
                && mc.level.dimension().location().toString().equals(dimension)
                && Filons.monde().equals(monde)
                && !contexte.isEmpty()
                && contexte.equals(Pont.cleContexte());
    }

    public static boolean actif() {
        return contexteValide() && horloge.getAsLong() < fin;
    }

    public static boolean selectionnee(String id) {
        return actif() && recette.equals(id);
    }

    /**
     * Objet à surligner dans un conteneur ouvert : un ingrédient retenu dans les coffres, ou un
     * ingrédient qui ne se consomme pas (moule, presse, outil) encore demandé. Avant, un moule dans
     * le coffre surligné n'était pas toujours surligné lui-même.
     */
    public static boolean cherche(String id) {
        if (!actif() || !InventaireJoueur.synchronise()) return false;
        if (objets.contains(id)) return true;
        var suivi = collecte;
        return suivi != null && demandes.contains(id) && suivi.outils().contains(id);
    }

    public static List<Pont.Repere> reperes() {
        return actif() && InventaireJoueur.synchronise() ? reperes : List.of();
    }

    public static Map<String, Integer> resultatsDisponibles() {
        if (!actif() || collecte == null || !InventaireJoueur.synchronise()) return Map.of();
        Map<String, Integer> resultats = new HashMap<>();
        quantites.forEach(
                (id, n) -> {
                    if (collecte.enFabrication(id)) resultats.put(id, n);
                });
        // Le coffre ouvert compte tout de suite, sans attendre le relevé du serveur : le lot
        // passe « À récupérer » avant que le joueur ne prenne son résultat.
        Map<String, Integer> ici = new HashMap<>();
        for (var item : RetraitCoffre.coffreOuvert()) {
            var id = ForgeRegistries.ITEMS.getKey(item.getItem());
            if (id != null && collecte.enFabrication(id.toString()))
                ici.merge(id.toString(), item.getCount(), Integer::sum);
        }
        ici.forEach((id, n) -> resultats.merge(id, n, Math::max));
        return Map.copyOf(resultats);
    }

    public static boolean resultatPret(Pont.Repere repere) {
        return actif() && resultatsPrets.contains(cle(repere));
    }

    private static String cle(Pont.Repere r) {
        return r.dimension() + "|" + r.x() + "|" + r.y() + "|" + r.z();
    }

    public static RetraitCoffre.Plan retrait(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return retrait(menu, null);
    }

    /**
     * Ce que le scan prend dans le coffre ouvert : son contenu réel d'abord, puis les autres
     * coffres connus, avec les mêmes crédits que les contours. Un résultat qui vient d'arriver dans
     * le coffre se prend aussitôt, sans attendre le relevé du serveur.
     */
    public static RetraitCoffre.Plan retrait(
            net.minecraft.world.inventory.AbstractContainerMenu menu,
            net.minecraft.core.BlockPos ouvert) {
        if (!pret()) return new RetraitCoffre.Plan(List.of(), 0);
        return RetraitCoffre.planRecherche(
                menu, locaux -> retraitDirect(locaux, ouvert), RechercheEmi::identifiant);
    }

    /** L'identifiant compté par le scan, ou "" pour une variante refusée (NBT, autre forme). */
    private static String identifiant(net.minecraft.world.item.ItemStack item) {
        if (item.isEmpty() || !conforme.test(item)) return "";
        var id = ForgeRegistries.ITEMS.getKey(item.getItem());
        return id == null ? "" : id.toString();
    }

    /**
     * Quantités à prendre dans le coffre ouvert, lu en direct : ses piles passent avant celles des
     * coffres mémorisés, ce coffre excepté. Les prises renvoyées désignent ses seules piles.
     */
    private static List<PlanRetrait.Prise> retraitDirect(
            List<PlanRetrait.Stock<String>> locaux, net.minecraft.core.BlockPos ouvert) {
        if (!pret()) return List.of();
        actualiserCollecte();
        actualiserVue();
        var suivi = collecte;
        if (suivi == null || locaux.isEmpty()) return List.of();
        List<PlanRetrait.Stock<String>> tous = new ArrayList<>(locaux);
        tous.addAll(stocksConnus(ouvert));
        List<PlanRetrait.Prise> prises = new ArrayList<>();
        for (var prise : suivi.dansCoffres(tous))
            if (prise.index() < locaux.size()) prises.add(prise);
        return List.copyOf(prises);
    }

    private static boolean pret() {
        return Pont.retraitCoffres()
                && actif()
                && collecte != null
                && InventaireJoueur.synchronise();
    }

    /** Coffres mémorisés encore utiles, sans le stock ouvert (son contenu réel le remplace). */
    private static List<PlanRetrait.Stock<String>> stocksConnus(
            net.minecraft.core.BlockPos ouvert) {
        var r = releve;
        if (r == null) return List.of();
        String exclu = null;
        if (ouvert != null)
            for (var entree : r.positions().entrySet())
                for (var p : entree.getValue())
                    if (p.x() == ouvert.getX() && p.y() == ouvert.getY() && p.z() == ouvert.getZ())
                        exclu = entree.getKey();
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        for (int i = 0; i < r.stocks().size(); i++)
            if (!r.groupes().get(i).equals(exclu) && demandes.contains(r.stocks().get(i).objet()))
                stocks.add(r.stocks().get(i));
        return stocks;
    }

    private static void actualiserCollecte() {
        if (collecte == null
                || RetraitCoffre.occupe()
                || Emi.envoiMachineEnCours()
                || !InventaireJoueur.synchronise()) return;
        long version = collecte.version();
        var cumul = Emi.fabricationsConfirmees();
        Map<String, Integer> preuves = new HashMap<>();
        cumul.forEach(
                (id, n) -> {
                    int gain = n - fabricationsPrecedentes.getOrDefault(id, 0);
                    if (gain > 0) preuves.put(id, gain);
                });
        fabricationsPrecedentes = cumul;
        collecte.reserver(Emi.reservationsFabrication());
        collecte.outilsMachines(Emi.outilsMachines());
        Set<String> restants = collecte.actualiser(inventaire(), preuves);
        if (collecte.version() != version) derniereVue = null;
        if (inventaireEnAttente || !restants.equals(demandes)) {
            inventaireEnAttente = false;
            demandes = restants;
            transmettre();
        }
    }

    public static String bouton(String id) {
        return selectionnee(id)
                ? !InventaireJoueur.synchronise()
                        ? "Lecture du sac…"
                        : attente ? "Recherche…" : "Arrêter"
                : "Repérage";
    }

    public static void basculer(
            String id,
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte) {
        Set<String> ingredients = suivi.tous();
        long now = horloge.getAsLong();
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
        contexte = Pont.cleContexte();
        fin = now + 600_000;
        collecte = suivi;
        fabricationsPrecedentes = Emi.fabricationsConfirmees();
        conforme = accepte;
        inventaireEnAttente = true;
        demandes = Set.of();
        actualiserCollecte();
    }

    public static void recalculer(
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte,
            boolean conserver) {
        if (!actif()) return;
        RetraitCoffre.arreter();
        AideCoffre.vider();
        if (conserver) suivi.reprendre(collecte);
        collecte = suivi;
        fabricationsPrecedentes = Emi.fabricationsConfirmees();
        conforme = accepte;
        inventaireEnAttente = true;
        demandes = Set.of();
        // Invalider les quotas de l'ancien arbre. Le dernier relevé reste : ses coffres sont
        // recomptés pour le nouvel arbre dès que ses besoins sont connus.
        objets = Set.of();
        quantites = Map.of();
        aSortir = Map.of();
        reperes = List.of();
        resultatsPrets = Set.of();
        derniereVue = null;
        versionAppliquee = -1;
        revision++;
        fin = horloge.getAsLong() + 600_000;
        actualiserCollecte();
    }

    private static Map<String, Integer> inventaire() {
        Map<String, Integer> stock = new HashMap<>();
        var joueur = Minecraft.getInstance().player;
        if (joueur != null) {
            for (var item : InventaireJoueur.piles()) {
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
        transmettre(false);
    }

    private static void transmettre(boolean nouvelEssai) {
        if (!contexteValide()) {
            vider();
            return;
        }
        if (!nouvelEssai) reessais = 0;
        long now = horloge.getAsLong();
        numero = Math.max(now, numero + 1);
        envoye = now;
        attente = !demandes.isEmpty();
        // Annoncer seulement une recherche qui demande des objets encore jamais relevés.
        annoncer |= releve == null || !releve.objets().containsAll(demandes);
        // Garder le dernier relevé, limité aux objets encore demandés : panneau, contours et lots
        // restent justes pendant l'aller-retour, au lieu de disparaître une à deux secondes. Une
        // réponse à une demande plus ancienne reste rejetée grâce à son numéro.
        derniereVue = null;
        appliquer();
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
            if (reessais == 0) informer("Recherche non transmise · nouvel essai automatique.");
            // Garder la demande en attente, sans restaurer une ancienne sélection.
            attente = !demandes.isEmpty();
        }
        if (demandes.isEmpty())
            informer("Ingrédients récupérés : repérage terminé pour cette recherche.");
    }

    public static void arreter() {
        JsonObject demande = new JsonObject();
        demande.addProperty("n", Math.max(horloge.getAsLong(), numero + 1));
        demande.addProperty("actif", false);
        Pont.envoyerRechercheEmi(demande);
        vider(); // arrêt immédiat, même si l'assistant est arrêté
    }

    private static void vider() {
        RetraitCoffre.arreter();
        AideCoffre.vider();
        recette = contexte = "";
        attente = inventaireEnAttente = false;
        objets = demandes = Set.of();
        quantites = Map.of();
        aSortir = Map.of();
        reperes = List.of();
        resultatsPrets = Set.of();
        derniereVue = null;
        releve = null;
        versionAppliquee = -1;
        annoncer = false;
        revision++;
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
        long maintenant = horloge.getAsLong();
        if (contexteValide()
                && maintenant >= fin - 120_000
                && !RetraitCoffre.occupe()
                && !Emi.envoiMachineEnCours()) {
            // Renouveler seulement le scan déjà choisi, jamais un objectif mis en pause.
            fin = maintenant + 600_000;
            transmettre();
        }
        if (!actif()) {
            vider();
            return;
        }
        if (++ticks % 2 == 0) actualiserCollecte();
        actualiserVue();
        // Une acquisition (résultat pris à la main, objet rangé) refait la sélection tout de suite.
        var suivi = collecte;
        if (suivi != null
                && releve != null
                && suivi.version() != versionAppliquee
                && !RetraitCoffre.occupe()) appliquer();
    }

    private static void actualiserVue() {
        if (collecte == null || !InventaireJoueur.synchronise()) return;
        if (demandes.isEmpty())
            return; // Garder la recherche validée, sans revenir au guidage de l'objectif.
        if (RetraitCoffre.occupe() || Emi.envoiMachineEnCours()) return;
        long delai = Math.min(30_000L, 8_000L << Math.min(reessais, 2));
        if (attente && horloge.getAsLong() - envoye > delai) {
            reessais++;
            if (reessais == 1) informer("Recherche retardée · nouvel essai automatique.");
            transmettre(true);
            return;
        }
        JsonObject vue = Pont.partie("recherche_emi");
        if (vue == derniereVue
                || !Pont.contexteValide(vue)
                || (long) Pont.nombre(vue, "n", 0) != numero
                || !monde.equals(Pont.texte(vue, "monde"))
                || !dimension.equals(Pont.texte(vue, "dimension"))) return;
        derniereVue = vue;
        // Les objets que cette réponse a cherchés : un relevé ne dit rien des autres.
        Set<String> cherches = new java.util.HashSet<>();
        for (JsonElement e : Pont.liste(vue, "objets"))
            if (e.isJsonPrimitive()) cherches.add(e.getAsString());
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
                if (n == 0 || !cherches.contains(entry.getKey())) continue;
                groupes.add(groupe);
                stocks.add(new PlanRetrait.Stock<>(entry.getKey(), n, false));
            }
        }
        positions.replaceAll((groupe, blocs) -> List.copyOf(blocs));
        releve =
                new Releve(
                        List.copyOf(stocks),
                        List.copyOf(groupes),
                        java.util.Collections.unmodifiableMap(positions),
                        Set.copyOf(cherches));
        appliquer();
        if (attente && annoncer)
            informer(
                    reperes.isEmpty()
                            ? "Aucun stockage utile connu pour les branches restantes."
                            : reperes.size()
                                    + " stockage(s) utile(s) à l’arbre. Composants prêts à récupérer en priorité.");
        if (attente) annoncer = false;
        attente = false;
        reessais = 0;
    }

    /**
     * Sélection du dernier relevé pour les objets encore demandés, avec les crédits actuels : les
     * mêmes quotas que les contours, recalculés sans attendre l'assistant.
     */
    private static void appliquer() {
        var suivi = collecte;
        if (suivi == null) return;
        versionAppliquee = suivi.version();
        revision++;
        var r = releve;
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        List<String> groupes = new ArrayList<>();
        if (r != null)
            for (int i = 0; i < r.stocks().size(); i++)
                if (demandes.contains(r.stocks().get(i).objet())) {
                    stocks.add(r.stocks().get(i));
                    groupes.add(r.groupes().get(i));
                }
        Map<String, List<Pont.Repere>> positions = r == null ? Map.of() : r.positions();
        Set<String> utiles = new java.util.LinkedHashSet<>(),
                visibles = new java.util.LinkedHashSet<>();
        Map<String, Integer> selection = new HashMap<>(), parGroupe = new HashMap<>();
        Set<String> groupesPrets = new java.util.LinkedHashSet<>();
        for (var prise :
                stocks.isEmpty() ? List.<PlanRetrait.Prise>of() : suivi.dansCoffres(stocks)) {
            selection.merge(stocks.get(prise.index()).objet(), prise.quantite(), Integer::sum);
            parGroupe.merge(groupes.get(prise.index()), prise.quantite(), Integer::sum);
            utiles.add(stocks.get(prise.index()).objet());
            visibles.add(groupes.get(prise.index()));
            if (suivi.enFabrication(stocks.get(prise.index()).objet()))
                groupesPrets.add(groupes.get(prise.index()));
        }
        List<Pont.Repere> coffres = new ArrayList<>();
        for (String groupe : visibles) coffres.addAll(positions.get(groupe));
        objets = Set.copyOf(utiles);
        quantites = Map.copyOf(selection);
        reperes = List.copyOf(coffres);
        // Le total à sortir de chaque conteneur, sur son premier bloc (un seul nombre par stock).
        Map<String, Integer> parPosition = new HashMap<>();
        parGroupe.forEach(
                (groupe, n) -> {
                    var blocs = positions.get(groupe);
                    if (blocs != null && !blocs.isEmpty() && n > 0)
                        parPosition.put(cle(blocs.get(0)), n);
                });
        aSortir = Map.copyOf(parPosition);
        Set<String> prets = new java.util.LinkedHashSet<>();
        for (String groupe : groupesPrets)
            for (var bloc : positions.get(groupe)) prets.add(cle(bloc));
        resultatsPrets = Set.copyOf(prets);
    }
}
