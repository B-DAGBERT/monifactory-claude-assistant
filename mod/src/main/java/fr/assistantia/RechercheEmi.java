package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Recherche explicite d'une recette, locale et distincte de l'objectif et de la discussion. */
public final class RechercheEmi {
    private static java.util.function.LongSupplier horloge = System::currentTimeMillis;
    private static String recette = "", monde = "", dimension = "", contexte = "";
    private static long numero, fin, dernierClic, envoye;
    private static boolean attente, inventaireEnAttente;
    private static boolean sacsInclus;
    private static MemoireCollecte collecte;
    private static Function<ItemStack, String> objet = s -> "";
    private static Function<ItemStack, RessourceCollecte> fluide = s -> null;
    private static int ticks, reessais;
    private static Object connexion;
    private static Set<String> objets = Set.of(), demandes = Set.of(), idsDemandes = Set.of();
    private static List<Pont.Repere> reperes = List.of();
    private static Set<String> resultatsPrets = Set.of();
    private static JsonObject derniereVue;
    private static Map<String, Integer> quantites = Map.of(), fabricationsPrecedentes = Map.of();
    private static Map<String, Integer> portes = Map.of(), resultatsTampons = Map.of();

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
    private static Map<String, List<Pont.Repere>> positionsFiltrees;
    private static long revisionFiltre;
    private static long versionAppliquee = -1, versionTamponAppliquee = -1, revision;
    private static long revisionPortes = -1, revisionDemandeTampon = -1;
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
        return aSortir.isEmpty() || !stockCalculPret() ? 0 : aSortir.getOrDefault(cle(repere), 0);
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

    private static boolean stockCalculPret() {
        return sacsInclus == SacsFabrication.actif() && InventaireJoueur.fabricationSynchronisee();
    }

    /**
     * Objet à surligner dans un conteneur ouvert : un ingrédient retenu dans les coffres, ou un
     * ingrédient qui ne se consomme pas (moule, presse, outil) encore demandé. Avant, un moule dans
     * le coffre surligné n'était pas toujours surligné lui-même.
     */
    public static boolean cherche(ItemStack pile) {
        if (!actif() || !stockCalculPret() || collecte == null) return false;
        String cle = objetCollecte(pile);
        return !cle.isEmpty()
                && collecte.accepte(demandes, cle)
                && (objets.contains(identifiant(pile)) || collecte.accepte(collecte.outils(), cle));
    }

    /** Clé physique du scan actif, vide si aucune branche n'accepte la pile. */
    public static String objetCollecte(ItemStack pile) {
        return objet.apply(pile);
    }

    public static List<Pont.Repere> reperes() {
        return actif() && stockCalculPret() ? reperes : List.of();
    }

    public static Map<String, Integer> resultatsDisponibles() {
        if (!actif() || collecte == null || !stockCalculPret()) return Map.of();
        Map<String, Integer> tampons = new HashMap<>(resultatsTampons);
        Map<String, Integer> autres = new HashMap<>();
        quantites.forEach(
                (id, n) -> {
                    if (collecte.variantesObjet(id).equals(Set.of(id))
                            && collecte.enFabrication(id)) autres.put(id, n);
                });
        // Le coffre ouvert compte tout de suite, sans attendre le relevé du serveur : le lot
        // passe « À récupérer » avant le retrait. L'ouverture peut relire la même source que
        // le cache, donc son stock complète seulement le groupe de coffres auquel il appartient.
        Map<String, Integer> ici = new HashMap<>();
        var pilesOuvertes =
                CoffreTampon.menuExclu() ? List.<ItemStack>of() : RetraitCoffre.coffreOuvert();
        for (var item : pilesOuvertes) {
            String id = objet.apply(item);
            if (collecte.enFabrication(id)) ajouter(ici, id, item.getCount());
        }
        var groupeOuvert = CoffreTampon.menuObserve() ? tampons : autres;
        ici.forEach((id, n) -> groupeOuvert.merge(id, n, Math::max));
        autres.forEach((id, n) -> ajouter(tampons, id, n));
        return Map.copyOf(tampons);
    }

    public static boolean resultatPret(Pont.Repere repere) {
        return actif() && resultatsPrets.contains(cle(repere));
    }

    private static String cle(Pont.Repere r) {
        return r.dimension() + "|" + r.x() + "|" + r.y() + "|" + r.z();
    }

    /**
     * Ce que le scan prend dans le coffre ouvert : son contenu réel d'abord, puis les autres
     * coffres connus, avec les mêmes crédits que les contours. Un résultat qui vient d'arriver dans
     * le coffre se prend aussitôt, sans attendre le relevé du serveur.
     */
    public static RetraitCoffre.Plan retrait(
            net.minecraft.world.inventory.AbstractContainerMenu menu,
            net.minecraft.core.BlockPos ouvert) {
        if (!pret() || CoffreTampon.menuExclu()) return new RetraitCoffre.Plan(List.of(), 0);
        return RetraitCoffre.planRessources(
                menu, locaux -> retraitDirect(locaux, ouvert), RechercheEmi::ressource);
    }

    /** Identifiant de registre des relevés historiques sans variantes. */
    private static String identifiant(net.minecraft.world.item.ItemStack item) {
        if (item.isEmpty()) return "";
        var id = ForgeRegistries.ITEMS.getKey(item.getItem());
        return id == null ? "" : id.toString();
    }

    private static RessourceCollecte ressource(ItemStack item) {
        String id = objet.apply(item);
        // Si une branche demande le contenant comme objet, sa pile n'est comptée qu'une fois.
        if (!id.isEmpty()) return new RessourceCollecte(id, 1);
        return fluide.apply(item);
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
        boolean tamponOuvert = CoffreTampon.menuObserve();
        var tampons =
                stocksTampons(tamponOuvert ? CoffreTampon.pilesHorsMenu() : CoffreTampon.piles());
        List<PlanRetrait.Stock<String>> tous = new ArrayList<>(tampons);
        int debut = tamponOuvert ? 0 : tampons.size();
        tous.addAll(debut, locaux);
        tous.addAll(stocksConnus(ouvert));
        List<PlanRetrait.Prise> prises = new ArrayList<>();
        int nombrePilesTampons = tampons.size() + (tamponOuvert ? locaux.size() : 0);
        for (var prise : suivi.dansCoffres(tous, nombrePilesTampons, portes)) {
            int index = prise.index() - debut;
            if (index >= 0 && index < locaux.size())
                prises.add(new PlanRetrait.Prise(index, prise.quantite()));
        }
        return List.copyOf(prises);
    }

    private static boolean pret() {
        return Pont.retraitCoffres() && actif() && collecte != null && stockCalculPret();
    }

    /** Coffres mémorisés encore utiles, sans le stock ouvert (son contenu réel le remplace). */
    private static List<PlanRetrait.Stock<String>> stocksConnus(
            net.minecraft.core.BlockPos ouvert) {
        var r = releve;
        if (r == null) return List.of();
        var positions = positionsEligibles(r);
        String exclu = null;
        if (ouvert != null)
            for (var entree : positions.entrySet())
                for (var p : entree.getValue())
                    if (p.x() == ouvert.getX() && p.y() == ouvert.getY() && p.z() == ouvert.getZ())
                        exclu = entree.getKey();
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>();
        for (int i = 0; i < r.stocks().size(); i++) {
            String groupe = r.groupes().get(i);
            var stock = r.stocks().get(i);
            if (!groupe.equals(exclu)
                    && positions.containsKey(groupe)
                    && idsDemandes.contains(stock.objet())
                    && collecte.variantesObjet(stock.objet()).equals(Set.of(stock.objet())))
                stocks.add(stock);
        }
        return stocks;
    }

    /** Les deux moitiés d'un double sont filtrées grâce à l'identité native du coffre. */
    private static Map<String, List<Pont.Repere>> positionsEligibles(Releve r) {
        if (!CoffreTampon.filtrageActif()) return r.positions();
        long courante = CoffreTampon.revision();
        if (positionsFiltrees != null && courante == revisionFiltre) return positionsFiltrees;
        Map<String, List<Pont.Repere>> positions = new HashMap<>();
        r.positions()
                .forEach(
                        (groupe, blocs) -> {
                            var autres =
                                    blocs.stream()
                                            .filter(
                                                    p ->
                                                            !CoffreTampon.exclu(
                                                                            p.dimension(),
                                                                            p.x(),
                                                                            p.y(),
                                                                            p.z())
                                                                    && !CoffreTampon.selectionne(
                                                                            p.dimension(),
                                                                            p.x(),
                                                                            p.y(),
                                                                            p.z()))
                                            .toList();
                            if (!autres.isEmpty()) positions.put(groupe, autres);
                        });
        positionsFiltrees = positions;
        revisionFiltre = courante;
        return positions;
    }

    private static void actualiserCollecte() {
        if (collecte == null || RetraitCoffre.occupe() || Emi.envoiMachineEnCours()) return;
        boolean inclureSacs = SacsFabrication.actif();
        if (sacsInclus != inclureSacs) {
            sacsInclus = inclureSacs;
            // L'ancien maximum des observations peut contenir des objets observés dans un sac.
            // Les réservations et crédits natifs sont relus après ce changement de couverture.
            collecte.reprendreStockCourant();
            AideCoffre.vider();
            invaliderSelection();
        }
        if (!InventaireJoueur.fabricationSynchronisee()) return;
        long version = collecte.version();
        var cumul = Emi.fabricationsCollecte();
        Map<String, Integer> preuves = new HashMap<>();
        if (cumul != fabricationsPrecedentes)
            cumul.forEach(
                    (id, n) -> {
                        int gain = n - fabricationsPrecedentes.getOrDefault(id, 0);
                        if (gain > 0) preuves.put(id, gain);
                    });
        fabricationsPrecedentes = cumul;
        collecte.reserver(Emi.reservationsFabrication());
        collecte.outilsMachines(Emi.outilsMachines());
        long vuePortee = InventaireJoueur.revisionFabrication();
        boolean stockChange = false;
        if (revisionPortes != vuePortee) {
            revisionPortes = vuePortee;
            var physiques = compter(InventaireJoueur.pilesFabrication());
            if (!portes.equals(physiques)) {
                portes = physiques;
                versionAppliquee = -1;
                stockChange = true;
            }
        }
        var inventaire = new HashMap<>(portes);
        for (var credit : Emi.creditsFabrication())
            ajouter(inventaire, objet.apply(credit.objet()), credit.quantite());
        collecte.actualiser(inventaire, preuves);
        boolean suiviChange = collecte.version() != version;
        if (suiviChange) derniereVue = null;
        long tampon = CoffreTampon.revision();
        if (!inventaireEnAttente && !stockChange && !suiviChange && tampon == revisionDemandeTampon)
            return;
        revisionDemandeTampon = tampon;
        Set<String> restants =
                collecte.manquantsApresTampons(stocksTampons(CoffreTampon.piles()), portes);
        if (inventaireEnAttente || !restants.equals(demandes)) {
            inventaireEnAttente = false;
            demandes = restants;
            idsDemandes =
                    restants.stream()
                            .filter(v -> !RessourceCollecte.estFluide(v))
                            .map(RessourceCollecte::idObjet)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            transmettre();
        }
    }

    public static String bouton(String id) {
        return selectionnee(id)
                ? !InventaireJoueur.fabricationSynchronisee()
                        ? "Lecture du sac…"
                        : attente ? "Recherche…" : "Arrêter"
                : "Repérage";
    }

    public static void basculer(
            String id,
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte) {
        basculer(id, suivi, accepte, s -> null);
    }

    public static void basculer(
            String id,
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte,
            Function<ItemStack, RessourceCollecte> lireFluide) {
        basculerArbre(
                id,
                suivi,
                pile -> {
                    String cle = identifiant(pile);
                    return suivi.tous().contains(cle) && accepte.test(pile) ? cle : "";
                },
                lireFluide);
    }

    public static void basculerArbre(
            String id,
            MemoireCollecte suivi,
            Function<ItemStack, String> lireObjet,
            Function<ItemStack, RessourceCollecte> lireFluide) {
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
            informer("Cette recette n’a pas d’ingrédient à chercher.");
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
        sacsInclus = SacsFabrication.actif();
        fabricationsPrecedentes = Emi.fabricationsCollecte();
        objet = lireObjet;
        fluide = lireFluide;
        inventaireEnAttente = true;
        demandes = idsDemandes = Set.of();
        actualiserCollecte();
    }

    public static void recalculer(
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte,
            boolean conserver) {
        recalculer(suivi, accepte, s -> null, conserver);
    }

    public static void recalculer(
            MemoireCollecte suivi,
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepte,
            Function<ItemStack, RessourceCollecte> lireFluide,
            boolean conserver) {
        recalculerArbre(
                suivi,
                pile -> {
                    String cle = identifiant(pile);
                    return suivi.tous().contains(cle) && accepte.test(pile) ? cle : "";
                },
                lireFluide,
                conserver);
    }

    public static void recalculerArbre(
            MemoireCollecte suivi,
            Function<ItemStack, String> lireObjet,
            Function<ItemStack, RessourceCollecte> lireFluide,
            boolean conserver) {
        if (!actif()) return;
        RetraitCoffre.arreter();
        AideCoffre.vider();
        if (conserver) suivi.reprendre(collecte);
        collecte = suivi;
        fabricationsPrecedentes = Emi.fabricationsCollecte();
        objet = lireObjet;
        fluide = lireFluide;
        invaliderSelection();
        fin = horloge.getAsLong() + 600_000;
        actualiserCollecte();
    }

    private static void invaliderSelection() {
        inventaireEnAttente = true;
        demandes = idsDemandes = Set.of();
        // Invalider les quotas de l'ancien arbre. Le dernier relevé reste : ses coffres sont
        // recomptés pour le nouvel arbre dès que ses besoins sont connus.
        objets = Set.of();
        quantites = resultatsTampons = Map.of();
        portes = aSortir = Map.of();
        reperes = List.of();
        resultatsPrets = Set.of();
        derniereVue = null;
        versionAppliquee = -1;
        revisionPortes = revisionDemandeTampon = -1;
        revision++;
    }

    private static Map<String, Integer> compter(List<net.minecraft.world.item.ItemStack> piles) {
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (var pile : piles) {
            var unite = ressource(pile);
            if (unite != null) ajouter(stock, unite.id(), unite.quantite(pile.getCount()));
        }
        return stock;
    }

    private static void ajouter(Map<String, Integer> stock, String id, int nombre) {
        if (!id.isEmpty())
            stock.merge(id, nombre, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
    }

    private static List<PlanRetrait.Stock<String>> stocksTampons(
            List<net.minecraft.world.item.ItemStack> piles) {
        return compter(piles).entrySet().stream()
                .map(e -> new PlanRetrait.Stock<>(e.getKey(), e.getValue(), false))
                .toList();
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
        var objetsDemandes = idsDemandes;
        attente = !objetsDemandes.isEmpty();
        // Annoncer seulement une recherche qui demande des objets encore jamais relevés.
        annoncer |= attente && (releve == null || !releve.objets().containsAll(objetsDemandes));
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
        objetsDemandes.stream().sorted().forEach(items::add);
        demande.add("objets", items);
        if (!Pont.envoyerRechercheEmi(demande)) {
            if (reessais == 0) informer("Recherche non transmise · nouvel essai automatique.");
            // Garder la demande en attente, sans restaurer une ancienne sélection.
            attente = !objetsDemandes.isEmpty();
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
        objets = demandes = idsDemandes = Set.of();
        quantites = portes = resultatsTampons = Map.of();
        aSortir = Map.of();
        reperes = List.of();
        resultatsPrets = Set.of();
        derniereVue = null;
        releve = null;
        positionsFiltrees = null;
        versionAppliquee = -1;
        revisionPortes = revisionDemandeTampon = -1;
        annoncer = false;
        revision++;
        collecte = null;
        objet = s -> "";
        fluide = s -> null;
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
        if (++ticks % 2 == 0
                || CoffreTampon.revision() != versionTamponAppliquee
                || sacsInclus != SacsFabrication.actif()) actualiserCollecte();
        actualiserVue();
        // Une acquisition (résultat pris à la main, objet rangé) refait la sélection tout de suite.
        var suivi = collecte;
        if (suivi != null
                && stockCalculPret()
                && (suivi.version() != versionAppliquee
                        || CoffreTampon.revision() != versionTamponAppliquee)
                && !RetraitCoffre.occupe()) appliquer();
    }

    private static void actualiserVue() {
        if (collecte == null || !stockCalculPret()) return;
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
        positionsFiltrees = null;
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
        versionTamponAppliquee = CoffreTampon.revision();
        revision++;
        var r = releve;
        Map<String, List<Pont.Repere>> positions = r == null ? Map.of() : positionsEligibles(r);
        var tampons = stocksTampons(CoffreTampon.piles());
        List<PlanRetrait.Stock<String>> stocks = new ArrayList<>(tampons);
        List<String> groupes = new ArrayList<>(), idsReleves = new ArrayList<>();
        if (r != null)
            for (int i = 0; i < r.stocks().size(); i++) {
                String groupe = r.groupes().get(i);
                var stock = r.stocks().get(i);
                if (positions.containsKey(groupe) && idsDemandes.contains(stock.objet())) {
                    // Le relevé distant ne connaît que l'ID. Une seule réserve indique où regarder.
                    String possible = suivi.groupe(suivi.variantesObjet(stock.objet()));
                    stocks.add(new PlanRetrait.Stock<>(possible, stock.quantite(), false));
                    groupes.add(groupe);
                    idsReleves.add(stock.objet());
                }
            }
        Set<String> utiles = new java.util.LinkedHashSet<>(),
                visibles = new java.util.LinkedHashSet<>();
        Map<String, Integer> selection = new HashMap<>(), parGroupe = new HashMap<>();
        Map<String, Integer> dansTampons = new HashMap<>();
        Set<String> groupesPrets = new java.util.LinkedHashSet<>();
        for (var prise :
                stocks.isEmpty()
                        ? List.<PlanRetrait.Prise>of()
                        : suivi.dansCoffres(stocks, tampons.size(), portes)) {
            String id = stocks.get(prise.index()).objet();
            int index = prise.index() - tampons.size();
            if (index < 0) {
                if (suivi.enFabrication(id)) dansTampons.merge(id, prise.quantite(), Integer::sum);
                continue;
            }
            String groupe = groupes.get(index);
            id = idsReleves.get(index);
            selection.merge(id, prise.quantite(), Integer::sum);
            parGroupe.merge(groupe, prise.quantite(), Integer::sum);
            utiles.add(id);
            visibles.add(groupe);
            if (suivi.variantesObjet(id).equals(Set.of(id)) && suivi.enFabrication(id))
                groupesPrets.add(groupe);
        }
        resultatsTampons = Map.copyOf(dansTampons);
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
