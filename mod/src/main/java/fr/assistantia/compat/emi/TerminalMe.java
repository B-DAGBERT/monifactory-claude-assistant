package fr.assistantia.compat.emi;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.CoffreTampon;
import fr.assistantia.CoffresConnus;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanRetrait;
import fr.assistantia.RelachementSouris;
import fr.assistantia.Theme;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;

/**
 * L'inventaire ME est celui du menu AE2 synchronisé, valable le temps de son ouverture. Le retrait
 * passe par le même panneau que la Collecte des coffres (voir DispositionCoffre) : posé hors du
 * terminal, réservé auprès d'EMI, et dont l'appui comme le relâchement ne vont jamais au terminal.
 */
public final class TerminalMe {
    private record Entree(
            long serial, Object cle, ItemStack pile, FluidStack fluide, long quantite) {}

    private record Lot(long serial, Object cle, ItemStack pile, FluidStack fluide, int quantite) {}

    private record AccesFluide(Class<?> cle, Method pile, Object remplir) {}

    private record ContenantMe(long serial, RemplissageMe remplissage) {}

    private record Acces(
            Class<?> menu,
            Class<?> ecran,
            Class<?> item,
            Method repo,
            Method powered,
            Method entrees,
            Method parIngredient,
            Method cle,
            Method quantite,
            Method serial,
            Method itemNatif,
            Method pile,
            Method retirer,
            Object un,
            Object pileEntiere,
            Object demiPile,
            Object pileDirecte) {}

    private record Vue(
            AbstractContainerMenu menu,
            InstantaneArbreEmi arbre,
            long inventaire,
            long tampon,
            long coffres,
            int menuRevision,
            List<Entree> stock,
            List<PlanRetrait.Stock<ItemStack>> stockCalcul,
            List<PlanRetrait.Stock<EmiStack>> fluidesMe,
            long lots,
            List<PlanRetrait.Stock<ItemStack>> credits,
            List<PlanRetrait.Stock<EmiStack>> outils,
            EntreesTerminalMe.Releve postes,
            Set<EmiRecipe> incertaines,
            Set<Long> aVerifier,
            List<Lot> retraits) {
        long stock(long serial) {
            for (var entree : stock) if (entree.serial() == serial) return entree.quantite();
            return 0;
        }
    }

    private record Recherche(
            InstantaneArbreEmi arbre,
            Map<Object, List<EmiStack>> variantes,
            Ingredient filtre,
            EntreesTerminalMe observateur) {}

    private static final String NOM = "Réseau ME", RETRAIT = "Retrait… ";
    private static final String PRENDRE = "Prendre", TOUT = "Tout", ARRETER = "Arrêter";
    private static final Acces ACCES = charger();
    private static final AccesFluide ACCES_FLUIDE = chargerFluides();

    /** Terminal ouvert et son panneau : objets proposés, choix de Prendre, page. */
    private static AbstractContainerScreen<?> affiche;

    private static Disposition disposition;
    private static List<Lot> objets = List.of();
    private static Long choisi;
    private static int page;
    private static long cleDisposition, placeLe;
    private static EntreesTerminalMe.Releve postes = EntreesTerminalMe.VIDE;
    private static boolean priseVerifiee;
    private static long demandePostes;
    private static boolean clicEnAttente;
    private static Vue vue;
    private static Recherche recherche;
    private static Class<?> indexIndisponible;
    private static Ingredient filtreMoules;
    private static AbstractContainerMenu menu;
    private static AbstractContainerScreen<?> ecran;
    private static InstantaneArbreEmi arbre;
    private static Object connexion, joueur, niveau;
    private static List<Lot> retraits = List.of();
    private static SuiviLotsEmi.ReceptionCollecte reception;
    private static int index, reste, attendus, avant, recus, depotAvant;
    private static Slot destination;
    private static ItemStack depotServeur;
    private static boolean direct;
    private static boolean soldeDistant;
    private static RemplissageMe remplissage;
    private static long millibuckets;
    private static boolean contenantManquant;
    private static long echeance;
    private static boolean erreur;
    private static int ticks;

    private TerminalMe() {}

    private static Acces charger() {
        try {
            var menu = Class.forName("appeng.menu.me.common.MEStorageMenu");
            var ecran = Class.forName("appeng.client.gui.me.common.MEStorageScreen");
            var repo = Class.forName("appeng.menu.me.common.IClientRepo");
            var entree = Class.forName("appeng.menu.me.common.GridInventoryEntry");
            var item = Class.forName("appeng.api.stacks.AEItemKey");
            var action = Class.forName("appeng.helpers.InventoryAction");
            return new Acces(
                    menu,
                    ecran,
                    item,
                    menu.getMethod("getClientRepo"),
                    menu.getMethod("isPowered"),
                    repo.getMethod("getAllEntries"),
                    methodeIndex(repo),
                    entree.getMethod("getWhat"),
                    entree.getMethod("getStoredAmount"),
                    entree.getMethod("getSerial"),
                    item.getMethod("getItem"),
                    item.getMethod("toStack"),
                    menu.getMethod("handleInteraction", long.class, action),
                    action.getField("PICKUP_SINGLE").get(null),
                    action.getField("PICKUP_OR_SET_DOWN").get(null),
                    actionOptionnelle(action, "SPLIT_OR_PLACE_SINGLE"),
                    actionOptionnelle(action, "SHIFT_CLICK"));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            signaler(incompatible);
            return null;
        }
    }

    private static Object actionOptionnelle(Class<?> actions, String nom)
            throws IllegalAccessException {
        try {
            return actions.getField(nom).get(null);
        } catch (NoSuchFieldException absente) {
            return null;
        }
    }

    private static AccesFluide chargerFluides() {
        if (ACCES == null) return null;
        try {
            var cle = Class.forName("appeng.api.stacks.AEFluidKey");
            var action = Class.forName("appeng.helpers.InventoryAction").getField("FILL_ITEM");
            return new AccesFluide(cle, cle.getMethod("toStack", int.class), action.get(null));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    private static Method methodeIndex(Class<?> repo) {
        try {
            return repo.getMethod("getByIngredient", Ingredient.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static AbstractContainerMenu courant() {
        var mc = Minecraft.getInstance();
        if (ACCES == null || mc.player == null || mc.level == null) return null;
        var actif = mc.player.containerMenu;
        return ACCES.menu().isInstance(actif) ? actif : null;
    }

    static boolean occupe() {
        return menu != null;
    }

    static List<PlanRetrait.Stock<ItemStack>> stock() {
        if (vue == null || vue.menu() != courant() || !InventaireJoueur.fabricationDisponible())
            return List.of();
        return vue.stockCalcul();
    }

    static List<PlanRetrait.Stock<EmiStack>> stockFluides() {
        if (vue == null || vue.menu() != courant() || !InventaireJoueur.fabricationDisponible())
            return List.of();
        return vue.fluidesMe();
    }

    private static List<PlanRetrait.Stock<ItemStack>> stocks(List<Entree> entrees) {
        return entrees.stream()
                .filter(e -> e.fluide() == null)
                .map(
                        e ->
                                new PlanRetrait.Stock<>(
                                        e.pile(), (int) Math.min(1_000_000L, e.quantite()), false))
                .toList();
    }

    private static List<PlanRetrait.Stock<EmiStack>> stocksFluides(List<Entree> entrees) {
        return entrees.stream()
                .filter(e -> e.fluide() != null)
                .map(
                        e ->
                                new PlanRetrait.Stock<>(
                                        ComparaisonEmi.pourInventaire(iconeFluide(e.fluide())),
                                        (int) Math.min(1_000_000L, e.quantite()),
                                        false))
                .toList();
    }

    private static EmiStack iconeFluide(FluidStack fluide) {
        return EmiStack.of(fluide.getFluid(), fluide.getTag(), 1);
    }

    private static boolean memeStock(List<Entree> avant, List<Entree> apres) {
        if (avant.size() != apres.size()) return false;
        for (int i = 0; i < avant.size(); i++) {
            var a = avant.get(i);
            var b = apres.get(i);
            if (a.serial() != b.serial()
                    || a.quantite() != b.quantite()
                    || !a.cle().equals(b.cle())) return false;
        }
        return true;
    }

    private static Recherche recherche(InstantaneArbreEmi arbre) {
        if (recherche != null && recherche.arbre() == arbre) return recherche;
        var variantes = new IdentityHashMap<Object, List<EmiStack>>();
        for (var pile : arbre.variantes())
            variantes.computeIfAbsent(pile.getKey(), k -> new ArrayList<>()).add(pile);
        // Le filtre natif sélectionne les types d'objet. ComparaisonEmi valide ensuite les
        // véritables variantes, les tags, l'usure et les autres règles propres aux recettes.
        var items =
                variantes.keySet().stream()
                        .filter(Item.class::isInstance)
                        .map(Item.class::cast)
                        .toArray(Item[]::new);
        recherche =
                new Recherche(arbre, variantes, Ingredient.of(items), new EntreesTerminalMe(arbre));
        return recherche;
    }

    private static Collection<?> entrees(Object repo, Ingredient filtre, boolean complet)
            throws ReflectiveOperationException {
        var index = ACCES.parIngredient();
        if (!complet && index != null && indexIndisponible != repo.getClass()) {
            try {
                if (index.invoke(repo, filtre) instanceof Collection<?> candidates)
                    return candidates;
            } catch (ReflectiveOperationException | RuntimeException incompatible) {
                // Une variante AE2 peut exposer la méthode sans prendre en charge son index.
            }
            indexIndisponible = repo.getClass();
        }
        return (Collection<?>) ACCES.entrees().invoke(repo);
    }

    private static List<Entree> lire(
            AbstractContainerMenu actif, InstantaneArbreEmi arbre, boolean complet)
            throws ReflectiveOperationException {
        if (!(boolean) ACCES.powered().invoke(actif)) return List.of();
        Object repo = ACCES.repo().invoke(actif);
        if (repo == null) return List.of();
        var recherche = recherche(arbre);
        var variantes = recherche.variantes();
        boolean avecFluides = ACCES_FLUIDE != null && !arbre.fluides().isEmpty();
        if (variantes.isEmpty() && !avecFluides) return List.of();
        var result = new ArrayList<Entree>();
        var vus = new HashSet<Long>();
        for (Object e : entrees(repo, recherche.filtre(), complet || avecFluides)) {
            long n = (long) ACCES.quantite().invoke(e);
            if (n <= 0) continue;
            Object cle = ACCES.cle().invoke(e);
            long serial = (long) ACCES.serial().invoke(e);
            if (serial == -1 || !vus.add(serial)) continue;
            if (ACCES.item().isInstance(cle)) {
                var choix = variantes.get(ACCES.itemNatif().invoke(cle));
                if (choix == null) continue;
                var pile = (ItemStack) ACCES.pile().invoke(cle);
                var emi = ComparaisonEmi.pourInventaire(EmiStack.of(pile));
                if (choix.stream().anyMatch(v -> ComparaisonEmi.meme(emi, v)))
                    result.add(new Entree(serial, cle, pile, null, n));
            } else if (avecFluides && ACCES_FLUIDE.cle().isInstance(cle)) {
                var fluide = (FluidStack) ACCES_FLUIDE.pile().invoke(cle, 1);
                var emi = ComparaisonEmi.pourInventaire(iconeFluide(fluide));
                if (arbre.fluides().stream().anyMatch(v -> ComparaisonEmi.meme(emi, v)))
                    result.add(new Entree(serial, cle, ItemStack.EMPTY, fluide, n));
            }
        }
        // Les indices du plan correspondent à la projection objets, suivie des fluides.
        result.sort(
                java.util.Comparator.comparing((Entree e) -> e.fluide() != null)
                        .thenComparingLong(Entree::serial));
        return List.copyOf(result);
    }

    private static Object arbreCourant() throws ReflectiveOperationException {
        return fr.assistantia.ArbreEmi.consulte();
    }

    private static void observer(boolean clic, Runnable suite) throws ReflectiveOperationException {
        // Le rappel de la prochaine prise relit le stock après la confirmation du déplacement.
        if (!clic && (clicEnAttente || occupe())) return;
        var menu = courant();
        var arbre = menu == null ? null : arbreCourant();
        var screen = affiche;
        long demande = ++demandePostes;
        if (arbre == null || screen == null || menu != screen.getMenu()) {
            postes = EntreesTerminalMe.VIDE;
            actualiser(false);
            return;
        }
        var instantane = InstantaneArbreEmi.lire(arbre);
        clicEnAttente = clic;
        var observateur = recherche(instantane).observateur();
        var quantites = new HashMap<Object, Long>();
        if (Minecraft.getInstance().getSingleplayerServer() != null
                && !observateur.recettes().isEmpty())
            for (var entree : lire(menu, instantane, clic))
                if (entree.fluide() == null) quantites.put(entree.cle(), entree.quantite());
        observateur.lire(
                menu,
                instantane,
                quantites,
                observation -> {
                    if (demande != demandePostes) return;
                    clicEnAttente = false;
                    try {
                        if (affiche != screen
                                || courant() != menu
                                || arbreCourant() != arbre
                                || InstantaneArbreEmi.lire(arbre) != instantane) return;
                        postes = observation;
                        if (suite != null) suite.run();
                        else actualiser(false);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        vue = null;
                        signaler(ex);
                    }
                });
    }

    private static void actualiser(boolean complet) throws ReflectiveOperationException {
        var actif = courant();
        releverMoules(actif);
        Object tree = actif == null ? null : arbreCourant();
        if (tree == null || !InventaireJoueur.fabricationDisponible()) {
            if (vue != null) InventaireArbreEmi.invalider();
            vue = null;
            recherche = null;
            return;
        }
        var instantane = InstantaneArbreEmi.lire(tree);
        var stock = lire(actif, instantane, complet);
        long revision = InventaireJoueur.revisionFabrication(),
                revisionTampon = CoffreTampon.revision(),
                revisionCoffres = CoffresConnus.revisionProches(),
                lots = MachinesEmi.revisionLotsProduction();
        var credits = CycleArbreEmi.credits();
        var outils = MachinesEmi.outilsInstalles();
        boolean stockIdentique =
                vue != null && vue.menu() == actif && memeStock(vue.stock(), stock);
        var incertaines = postes.inconnus();
        if (!complet
                && stockIdentique
                && vue.menu() == actif
                && vue.arbre() == instantane
                && vue.inventaire() == revision
                && vue.tampon() == revisionTampon
                && vue.coffres() == revisionCoffres
                && vue.menuRevision() == actif.getStateId()
                && vue.lots() == lots
                && vue.credits().equals(credits)
                && vue.outils().equals(outils)
                && vue.postes().equals(postes)
                && vue.incertaines().equals(incertaines)) return;
        var stockCalcul = stockIdentique ? vue.stockCalcul() : stocks(stock);
        var fluidesMe = stockIdentique ? vue.fluidesMe() : stocksFluides(stock);
        var couverture =
                StockFabricationEmi.courant(
                                InventaireArbreEmi.physiques(InventaireArbreEmi.personnel()),
                                stockCalcul,
                                fluidesMe)
                        .avecMoulesDesCoffres(instantane);
        var retraits = new ArrayList<Lot>();
        var prises =
                couverture.retraitsReseau(
                        instantane.machines(), instantane.lots(), postes.entrees(), incertaines);
        int[] quantites = new int[stock.size()];
        var indices = new java.util.TreeSet<Integer>();
        for (var prise : prises) {
            indices.add(prise.index());
            quantites[prise.index()] = prise.quantite();
        }
        // Garder visible une ligne ramenée à zéro, avec ses vrais boutons désactivés.
        var aVerifier = new HashSet<Long>();
        if (!postes.entrees().isEmpty() || !incertaines.isEmpty()) {
            var sansPostes = couverture.retraitsReseau(instantane.machines(), instantane.lots());
            for (var prise : sansPostes) indices.add(prise.index());
            if (!incertaines.isEmpty()) {
                var sansIncertitude =
                        postes.entrees().isEmpty()
                                ? sansPostes
                                : couverture.retraitsReseau(
                                        instantane.machines(),
                                        instantane.lots(),
                                        postes.entrees(),
                                        Set.of());
                for (var prise : sansIncertitude) {
                    if (prise.quantite() <= quantites[prise.index()]) continue;
                    indices.add(prise.index());
                    aVerifier.add(stock.get(prise.index()).serial());
                }
            }
        }
        for (int i : indices) {
            var entree = stock.get(i);
            retraits.add(
                    new Lot(
                            entree.serial(),
                            entree.cle(),
                            entree.pile(),
                            entree.fluide(),
                            quantites[i]));
        }
        vue =
                new Vue(
                        actif,
                        instantane,
                        revision,
                        revisionTampon,
                        revisionCoffres,
                        actif.getStateId(),
                        stock,
                        stockCalcul,
                        fluidesMe,
                        lots,
                        credits,
                        outils,
                        postes,
                        Set.copyOf(incertaines),
                        Set.copyOf(aVerifier),
                        List.copyOf(retraits));
        InventaireArbreEmi.invalider();
    }

    private static void releverMoules(AbstractContainerMenu actif)
            throws ReflectiveOperationException {
        if (actif == null) return; // La fermeture enlève les droits de retrait, pas le relevé.
        if (!(boolean) ACCES.powered().invoke(actif)) {
            MoulesDisponibles.oublierReseau();
            return;
        }
        Object repo = ACCES.repo().invoke(actif);
        if (repo == null) return;
        if (filtreMoules == null)
            filtreMoules =
                    Ingredient.of(
                            BuiltInRegistries.ITEM.entrySet().stream()
                                    .filter(
                                            e ->
                                                    MoulesDisponibles.estMoule(
                                                            e.getKey().location().toString()))
                                    .map(Map.Entry::getValue)
                                    .toArray(Item[]::new));
        var stock = new ArrayList<PlanRetrait.Stock<ItemStack>>();
        if (!filtreMoules.isEmpty())
            for (Object entree : entrees(repo, filtreMoules, false)) {
                long n = (long) ACCES.quantite().invoke(entree);
                if (n <= 0) continue;
                Object cle = ACCES.cle().invoke(entree);
                if (!ACCES.item().isInstance(cle)) continue;
                var item = (Item) ACCES.itemNatif().invoke(cle);
                if (!MoulesDisponibles.estMoule(BuiltInRegistries.ITEM.getKey(item).toString()))
                    continue;
                stock.add(
                        new PlanRetrait.Stock<>(
                                (ItemStack) ACCES.pile().invoke(cle),
                                (int) Math.min(1_000_000L, n),
                                false));
            }
        MoulesDisponibles.releverReseau(stock);
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        boolean terminal = ACCES != null && ACCES.ecran().isInstance(event.getScreen());
        if (terminal && affiche != event.getScreen()) MoulesDisponibles.oublierReseau();
        demandePostes++;
        clicEnAttente = false;
        postes = EntreesTerminalMe.VIDE;
        affiche = terminal ? (AbstractContainerScreen<?>) event.getScreen() : null;
        disposition = null;
        objets = List.of();
        choisi = null;
        page = 0;
    }

    static void zoneEmi(
            Screen screen, java.util.function.Consumer<dev.emi.emi.api.widget.Bounds> zones) {
        var d = disposition;
        if (d != null && screen == affiche && !objets.isEmpty())
            zones.accept(
                    new dev.emi.emi.api.widget.Bounds(
                            d.cadre().x(),
                            d.cadre().y(),
                            d.cadre().largeur(),
                            d.cadre().hauteur()));
    }

    /** Pendant un retrait, ses lots figés ; sinon ceux du relevé de ce terminal. */
    private static List<Lot> proposes(Screen screen) {
        if (occupe()) return screen == ecran ? retraits : List.of();
        return vue != null && affiche != null && vue.menu() == affiche.getMenu()
                ? vue.retraits()
                : List.of();
    }

    /** Ce qu'il reste à retirer du lot {@code n} pendant le retrait en cours. */
    private static int restant(int n) {
        if (!occupe() || n < index) return occupe() ? 0 : objets.get(n).quantite();
        return n == index ? reste : retraits.get(n).quantite();
    }

    private static long restantTotal() {
        long total = 0;
        for (int n = 0; n < objets.size(); n++) total += restant(n);
        return total;
    }

    private static Lot selection() {
        for (int n = 0; n < objets.size(); n++)
            if (choisi != null && objets.get(n).serial() == choisi) return objets.get(n);
        return null;
    }

    private static Component nom(Lot lot) {
        return lot.fluide() == null ? lot.pile().getHoverName() : lot.fluide().getDisplayName();
    }

    private static String unites(Lot lot) {
        return lot.fluide() == null ? "" : " mB";
    }

    private static String aPrendre(long total) {
        return Theme.nombre(total) + " à prendre";
    }

    private static String detailRetrait() {
        long objetsRestants = 0, fluidesRestants = 0;
        for (int i = 0; i < objets.size(); i++) {
            if (objets.get(i).fluide() == null) objetsRestants += restant(i);
            else fluidesRestants += restant(i);
        }
        if (fluidesRestants == 0)
            return occupe() ? RETRAIT + Theme.nombre(objetsRestants) : aPrendre(objetsRestants);
        String volume = Theme.nombre(fluidesRestants) + " mB";
        return objetsRestants == 0
                ? volume + " à prendre"
                : Theme.nombre(objetsRestants) + " objets + " + volume;
    }

    private static int derniere() {
        return (objets.size() - 1) / disposition.parPage();
    }

    /**
     * Liste et place tenues à jour avant le rendu ; la place ne bouge qu'avec le nombre d'objets.
     */
    @SubscribeEvent
    public static void avantRendu(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != affiche) return;
        objets = proposes(affiche);
        if (objets.isEmpty()) {
            disposition = null;
            PanneauAssistant.poser(affiche, null);
            return;
        }
        // Le choix de Prendre survit au retrait de Tout, qui ne montre que ses propres lots.
        if (!occupe() && selection() == null)
            choisi =
                    objets.stream()
                            .filter(l -> l.quantite() > 0)
                            .findFirst()
                            .orElse(objets.get(0))
                            .serial();
        long cle = (objets.size() * 31L + affiche.width) * 31L + affiche.height;
        long maintenant = System.currentTimeMillis();
        if (disposition == null || cle != cleDisposition || maintenant - placeLe >= 1000) {
            Font font = Minecraft.getInstance().font;
            disposition =
                    DispositionCoffre.calculer(
                            affiche.width,
                            affiche.height,
                            PanneauAssistant.occupee(affiche),
                            objets.size(),
                            PanneauAssistant.libelles(
                                    font,
                                    List.of(
                                            NOM,
                                            "Poste à vérifier",
                                            aPrendre(999_999),
                                            "999k objets + 999k mB",
                                            RETRAIT + Theme.nombre(999_999)),
                                    List.of(PRENDRE),
                                    List.of(TOUT, ARRETER),
                                    false));
            cleDisposition = cle;
            placeLe = maintenant;
        }
        page = Math.min(page, derniere());
        PanneauAssistant.poser(affiche, disposition.cadre());
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        Disposition d = disposition;
        if (d == null || event.getScreen() != affiche || objets.isEmpty()) return;
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        boolean occupe = occupe();
        if (!occupe && vue == null) return;
        PanneauAssistant.fond(g, d.cadre());
        long total = restantTotal();
        boolean incertain = !occupe && !vue.aVerifier().isEmpty();
        String detail = incertain ? "Poste à vérifier" : detailRetrait();
        PanneauAssistant.titre(g, font, d.titre(), NOM, detail);
        List<Component> infobulle = null;
        if (d.titre().contient(mx, my))
            infobulle =
                    List.of(
                            Component.literal(NOM + " · " + detailRetrait()),
                            gris(
                                    incertain
                                            ? "Destination du bus ou entrées machine non vérifiables."
                                            : "Objets de l’arbre EMI à retirer du réseau AE2"),
                            gris(
                                    occupe
                                            ? "Un clic hors du panneau arrête aussi le retrait"
                                            : "Garde le curseur vide pendant le retrait"));
        for (int rang = 0; rang < d.parPage(); rang++) {
            int n = page * d.parPage() + rang;
            if (n >= objets.size()) break;
            var lot = objets.get(n);
            var z = d.icone(rang);
            int quantite = restant(n);
            boolean posteInconnu = !occupe && vue.aVerifier().contains(lot.serial());
            PanneauAssistant.caseIcone(g, z, !occupe && choisi != null && lot.serial() == choisi);
            if (lot.fluide() == null) {
                ItemStack icone = lot.pile().copy();
                icone.setCount(1);
                g.renderItem(icone, z.x() + 1, z.y() + 1);
            } else {
                iconeFluide(lot.fluide())
                        .render(g, z.x() + 1, z.y() + 1, 0, EmiIngredient.RENDER_ICON);
            }
            if (posteInconnu && quantite == 0)
                PanneauAssistant.indication(g, font, z, "?", Theme.JAUNE);
            else PanneauAssistant.nombre(g, font, z, quantite);
            if (occupe && quantite == 0) PanneauAssistant.etat(g, z, Theme.VERT);
            if (z.contient(mx, my)) {
                infobulle = new ArrayList<>();
                infobulle.add(nom(lot));
                if (occupe) {
                    infobulle.add(
                            gris(quantite == 0 ? "Retiré" : quantite + unites(lot) + " restants"));
                } else {
                    infobulle.add(
                            gris(
                                    vue.stock(lot.serial())
                                            + unites(lot)
                                            + " en stock dans le réseau ME"));
                    if (quantite > 0) infobulle.add(gris(quantite + unites(lot) + " à prendre"));
                    if (quantite > 0 && lot.fluide() != null)
                        infobulle.add(
                                gris(
                                        "AE2 remplit le contenant jusqu’à sa capacité ou au stock disponible."));
                    if (posteInconnu) {
                        infobulle.add(gris("Entrées machine à vérifier pour cette branche."));
                        infobulle.add(gris("Retrait manuel dans le terminal AE2 pour le reste."));
                    } else if (quantite == 0)
                        infobulle.add(gris("Aucun retrait prévu pour l’arbre."));
                    if (Minecraft.getInstance().getSingleplayerServer() == null
                            && posteARevalider())
                        infobulle.add(
                                gris(
                                        "Quantité calculée sans lire les entrées des machines du serveur."));
                    if (quantite > 0)
                        infobulle.add(
                                Component.literal("Clic : choisir pour Prendre")
                                        .withStyle(s -> s.withColor(PanneauAssistant.BLEU)));
                }
            }
        }
        if (d.pages()) {
            boolean avant =
                    PanneauAssistant.bouton(
                            g, font, d.precedent(), "‹", Theme.Genre.NORMAL, page > 0, mx, my);
            boolean apres =
                    PanneauAssistant.bouton(
                            g,
                            font,
                            d.suivant(),
                            "›",
                            Theme.Genre.NORMAL,
                            page < derniere(),
                            mx,
                            my);
            if (avant || apres)
                infobulle =
                        List.of(Component.literal("Page " + (page + 1) + "/" + (derniere() + 1)));
        }
        Lot choisiActuel = selection();
        int choix = choisiActuel == null ? 0 : choisiActuel.quantite();
        if (PanneauAssistant.bouton(
                g, font, d.prendre(), PRENDRE, Theme.Genre.NORMAL, !occupe && choix > 0, mx, my))
            infobulle =
                    List.of(
                            Component.literal(
                                    "Prendre "
                                            + choix
                                            + unites(choisiActuel)
                                            + " "
                                            + nom(choisiActuel).getString()));
        if (occupe) {
            if (PanneauAssistant.bouton(
                    g, font, d.tout(), ARRETER, Theme.Genre.DANGER, true, mx, my))
                infobulle = List.of(Component.literal("Arrêter après l’action en cours"));
        } else if (PanneauAssistant.bouton(
                g, font, d.tout(), TOUT, Theme.Genre.PRINCIPAL, total > 0, mx, my))
            infobulle = List.of(Component.literal(detailRetrait() + " · arbre EMI"));
        if (infobulle != null) g.renderComponentTooltip(font, infobulle, mx, my);
    }

    private static Component gris(String texte) {
        return Component.literal(texte).withStyle(s -> s.withColor(PanneauAssistant.GRIS));
    }

    private static boolean surPanneau(Screen screen, double mx, double my) {
        var d = disposition;
        return d != null && screen == affiche && !objets.isEmpty() && d.cadre().contient(mx, my);
    }

    /**
     * L'appui sur le panneau est consommé avec son relâchement : relâché au-dessus du terminal, il
     * y poserait ou jetterait l'objet que le retrait tient au curseur. Tout autre clic dans le
     * terminal arrête le retrait en cours.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        double mx = event.getMouseX(), my = event.getMouseY();
        if (event.isCanceled() || !surPanneau(event.getScreen(), mx, my)) {
            if (event.getScreen() == ecran && occupe()) terminer("Retrait ME interrompu.");
            return;
        }
        RelachementSouris.consommer(event);
        if (event.getButton() != 0) return;
        Disposition d = disposition;
        if (d.tout().contient(mx, my)) {
            PanneauAssistant.son();
            if (occupe()) terminer("Retrait ME arrêté.");
            else verifierClic(affiche, null);
        } else if (d.pages() && d.precedent().contient(mx, my)) tourner(-1);
        else if (d.pages() && d.suivant().contient(mx, my)) tourner(1);
        else if (occupe()) return;
        else if (d.prendre().contient(mx, my)) {
            if (choisi != null) {
                PanneauAssistant.son();
                verifierClic(affiche, choisi);
            }
        } else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int n = page * d.parPage() + rang;
                if (n < objets.size() && d.icone(rang).contient(mx, my)) {
                    choisi = objets.get(n).serial();
                    return;
                }
            }
    }

    private static void tourner(int sens) {
        page = Math.max(0, Math.min(derniere(), page + sens));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (disposition.pages()) tourner(event.getScrollDelta() > 0 ? -1 : 1);
    }

    /**
     * Le clic relit le terminal et l'arbre : il retire ce qui manque à cet instant, jamais le plan
     * de l'image précédente. {@code selection} : un seul objet (Prendre), sinon tout (Tout).
     */
    private static void verifierClic(AbstractContainerScreen<?> screen, Long selection) {
        if (occupe() || clicEnAttente) return;
        try {
            observer(true, () -> lancer(screen, selection));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            clicEnAttente = false;
            signaler(ex);
        }
    }

    private static void lancer(AbstractContainerScreen<?> screen, Long selection) {
        var mc = Minecraft.getInstance();
        if (occupe()
                || screen == null
                || mc.screen != screen
                || courant() != screen.getMenu()
                || mc.getConnection() == null
                || fr.assistantia.RetraitCoffre.occupe()
                || EnvoiMachine.occupe()) return;
        if (!screen.getMenu().getCarried().isEmpty()) {
            message("Pose d’abord l’objet tenu par la souris.");
            return;
        }
        try {
            actualiser(true);
            var lots =
                    vue == null
                            ? List.<Lot>of()
                            : vue.retraits().stream()
                                    .filter(l -> l.quantite() > 0)
                                    .filter(l -> selection == null || l.serial() == selection)
                                    .toList();
            if (lots.isEmpty()) {
                message(
                        vue != null && !vue.incertaines().isEmpty()
                                ? "Entrées machine non vérifiables. Vérifie le poste avant un retrait manuel dans AE2."
                                : "Rien à prendre dans le réseau ME pour l’arbre EMI.");
                return;
            }
            reception =
                    MachinesEmi.receptionCollecte(
                            screen,
                            lots.stream().filter(l -> l.fluide() == null).map(Lot::pile).toList());
            ecran = screen;
            menu = screen.getMenu();
            arbre = vue.arbre();
            connexion = mc.getConnection();
            joueur = mc.player;
            niveau = mc.level;
            retraits = lots;
            objets = lots;
            index = recus = avant = attendus = 0;
            destination = null;
            depotServeur = null;
            direct = false;
            millibuckets = 0;
            contenantManquant = false;
            reste = retraits.get(0).quantite();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            terminer("Retrait ME indisponible.");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACCES == null) return;
        try {
            if (occupe()) avancer();
            if (++ticks % 10 == 0 || vue != null && vue.menu() != courant()) observer(false, null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            if (occupe()) terminer("Retrait ME interrompu.");
            vue = null;
        }
    }

    private static void avancer() throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        var joueurActuel = mc.player;
        Object tree = arbreCourant();
        if (joueurActuel == null
                || joueurActuel != joueur
                || mc.level != niveau
                || mc.getConnection() != connexion
                || tree == null
                || InstantaneArbreEmi.lire(tree) != arbre) {
            terminer("Retrait ME interrompu : terminal ou arbre modifié.");
            return;
        }
        var inventaire = joueurActuel.getInventory();
        boolean ouvert = mc.screen == ecran && courant() == menu;
        // Échap arrête la séquence, mais le dernier dépôt déjà demandé peut encore arriver.
        boolean ferme =
                !ouvert
                        && destination != null
                        && joueurActuel.containerMenu == joueurActuel.inventoryMenu
                        && destination.container == inventaire
                        && System.nanoTime() < echeance;
        if (!ouvert && !ferme) {
            terminer("Retrait ME interrompu : terminal ou arbre modifié.");
            return;
        }
        var curseur = (ferme ? joueurActuel.inventoryMenu : menu).getCarried();
        if (destination != null) {
            var pile = ferme && depotServeur != null ? depotServeur : destination.getItem();
            int gain = pile.getCount() - depotAvant;
            if (curseur.isEmpty()
                    && ItemStack.isSameItemSameTags(pile, retraits.get(index).pile())
                    && gain > 0
                    && (direct || soldeDistant ? gain <= attendus : gain == avant)) {
                if (direct || soldeDistant) {
                    reste -= gain;
                    attendus = 0;
                    direct = false;
                    soldeDistant = false;
                }
                recus += gain;
                avant = 0;
                var depotFerme =
                        ferme && depotServeur != null
                                ? new SuiviLotsEmi.DepotMe(
                                        inventaire, destination.getContainerSlot(), depotAvant)
                                : null;
                destination = null;
                if (reception != null) {
                    try {
                        reception.accepter(pile.copy(), gain, depotFerme);
                    } catch (RuntimeException ex) {
                        System.getLogger(TerminalMe.class.getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "Suivi du retrait ME indisponible",
                                        ex);
                    }
                }
                if (ferme) {
                    terminer(bilan());
                    return;
                }
            } else {
                attendre();
                return;
            }
        }
        if (reste == 0 && avant == 0) {
            if (++index == retraits.size()) {
                terminer(bilan());
                return;
            }
            reste = retraits.get(index).quantite();
        }
        var lot = retraits.get(index);
        if (lot.fluide() != null) {
            avancerFluide(lot);
            return;
        }
        boolean deposerPile = false;
        if (attendus > 0) {
            int volume = ItemStack.isSameItemSameTags(curseur, lot.pile()) ? curseur.getCount() : 0;
            if (volume == avant) {
                attendre();
                return;
            }
            int gain = volume - avant;
            if (gain < 1 || gain > attendus) {
                terminer("Retrait ME interrompu : le curseur a changé.");
                return;
            }
            avant = volume;
            deposerPile = attendus > 1;
            // Sur un serveur distant, ce curseur peut encore recevoir la suite du solde
            // déjà demandé. Seul le dépôt confirmera la quantité réellement prise.
            if (!soldeDistant) {
                reste -= gain;
                attendus = 0;
            }
        }
        if (avant != curseur.getCount()
                || avant > 0 && !ItemStack.isSameItemSameTags(curseur, lot.pile())) {
            terminer("Retrait ME interrompu : le curseur a changé.");
            return;
        }
        int taillePile = lot.pile().getMaxStackSize();
        int emplacementNatif = -1;
        if (avant == 0 && reste >= taillePile && ACCES.pileDirecte() != null) {
            // Reprendre l'ordre du Maj-clic d'AE2. Il peut choisir une pile déjà entamée.
            emplacementNatif = inventaire.getSlotWithRemainingSpace(lot.pile());
            if (emplacementNatif < 0) emplacementNatif = inventaire.getFreeSlot();
        }
        Slot cible = null;
        Slot cibleDirecte = null;
        int place = 0;
        boolean fusion = false;
        for (Slot slot : menu.slots) {
            if (slot.container != inventaire || !slot.isActive() || !slot.mayPlace(lot.pile()))
                continue;
            var ici = slot.getItem();
            if (!ici.isEmpty() && !ItemStack.isSameItemSameTags(ici, lot.pile())) continue;
            int libre = slot.getMaxStackSize(lot.pile()) - ici.getCount();
            if (emplacementNatif >= 0
                    && emplacementNatif < 36
                    && slot.getContainerSlot() == emplacementNatif
                    && ici.isEmpty()
                    && libre >= taillePile) cibleDirecte = slot;
            if (libre <= 0 || libre < (soldeDistant ? attendus : avant)) continue;
            // Une pile entière garde sa case. Pour le solde, compléter d'abord une pile entamée.
            boolean completer = !ici.isEmpty() && (avant > 0 || reste < taillePile);
            if (fusion && !completer) continue;
            if (completer == fusion && libre <= place) continue;
            cible = slot;
            place = libre;
            fusion = completer;
        }
        if (cible == null) {
            terminer("Retrait ME arrêté : manque de place dans l’inventaire.");
            return;
        }
        echeance = System.nanoTime() + 3_000_000_000L;
        if (avant > 0 && (reste == 0 || avant == place || deposerPile || posteARevalider())) {
            destination = cible;
            depotAvant = cible.getItem().getCount();
            // Pas de prédiction locale : attendre que le serveur confirme la case et le curseur.
            clicCase(cible, 0);
            return;
        }
        if (!(boolean) ACCES.powered().invoke(menu)) {
            terminer("Retrait ME arrêté : terminal hors tension.");
            return;
        }
        if (!verifierPrise(lot)) return;
        if (cibleDirecte != null) {
            destination = cibleDirecte;
            depotAvant = 0;
            direct = true;
            attendus = taillePile;
            retirer(lot, ACCES.pileDirecte(), 1);
            return;
        }
        int volume = volumeRetrait(reste, taillePile, place, avant);
        Object action = ACCES.un();
        int repetitions = 1;
        if (volume == taillePile && taillePile > 1) action = ACCES.pileEntiere();
        else if (volume > 1 && ACCES.demiPile() != null) action = ACCES.demiPile();
        else {
            // Le paquet AE2 ne précise pas de quantité. Réunir le solde au curseur.
            if (avant == 0) repetitions = Math.min(reste, place);
            volume = repetitions;
        }
        attendus = volume;
        retirer(lot, action, repetitions);
    }

    private static void avancerFluide(Lot lot) throws ReflectiveOperationException {
        if (remplissage != null) {
            int gain =
                    remplissage.avancer(
                            menu,
                            TerminalMe::clicCase,
                            () ->
                                    ACCES.retirer()
                                            .invoke(menu, lot.serial(), ACCES_FLUIDE.remplir()));
            if (gain < 0)
                terminer("Remplissage ME interrompu. Vérifie le contenant et le curseur.");
            if (gain > 0) {
                millibuckets += gain;
                reste = Math.max(0, reste - gain);
                remplissage = null;
            }
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            terminer("Pose d’abord l’objet tenu par la souris.");
            return;
        }
        if (!(boolean) ACCES.powered().invoke(menu)) {
            terminer("Retrait ME arrêté : terminal hors tension.");
            return;
        }
        if (!verifierPrise(lot)) return;
        var disponible = lot.fluide().copy();
        // La simulation mesure toute la place compatible, comme l'action native d'AE2.
        disponible.setAmount(Integer.MAX_VALUE);
        var inventaire = Minecraft.getInstance().player.getInventory();
        remplissage = RemplissageMe.choisir(menu.slots, inventaire, disponible, reste);
        if (remplissage != null) {
            remplissage.prendre(TerminalMe::clicCase);
            return;
        }
        var contenant = contenantMe(disponible, reste);
        if (contenant != null) {
            remplissage = contenant.remplissage();
            remplissage.prendreDansMe(
                    () -> ACCES.retirer().invoke(menu, contenant.serial(), ACCES.un()));
            return;
        }
        contenantManquant = true;
        reste = 0;
    }

    private static ContenantMe contenantMe(FluidStack liquide, int besoin)
            throws ReflectiveOperationException {
        Object repo = ACCES.repo().invoke(menu);
        if (repo == null) return null;
        var inventaire = Minecraft.getInstance().player.getInventory();
        ContenantMe choix = null;
        for (Object entree : (Collection<?>) ACCES.entrees().invoke(repo)) {
            if ((long) ACCES.quantite().invoke(entree) <= 0) continue;
            Object cle = ACCES.cle().invoke(entree);
            if (!ACCES.item().isInstance(cle)
                    || !RemplissageMe.drum((Item) ACCES.itemNatif().invoke(cle))) continue;
            long serial = (long) ACCES.serial().invoke(entree);
            if (serial == -1) continue;
            var candidat =
                    RemplissageMe.depuisMe(
                            menu.slots, inventaire, (ItemStack) ACCES.pile().invoke(cle), liquide);
            if (candidat != null
                    && candidat.preferableA(choix == null ? null : choix.remplissage(), besoin))
                choix = new ContenantMe(serial, candidat);
        }
        return choix;
    }

    private static void clicCase(Slot slot, int bouton) {
        Minecraft.getInstance()
                .getConnection()
                .send(
                        new ServerboundContainerClickPacket(
                                menu.containerId,
                                menu.getStateId(),
                                slot.index,
                                bouton,
                                ClickType.PICKUP,
                                menu.getCarried().copy(),
                                new Int2ObjectOpenHashMap<>()));
    }

    private static void retirer(Lot lot, Object action, int repetitions)
            throws ReflectiveOperationException {
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            // La connexion traite toutes les prises avant le clic de dépôt émis en réponse
            // au premier gain. Le clic vide donc le curseur réel, même après un refus partiel.
            soldeDistant = repetitions > 1;
            for (int i = 0; i < repetitions; i++)
                ACCES.retirer().invoke(menu, lot.serial(), action);
            return;
        }
        if (!posteARevalider() && repetitions == 1) {
            ACCES.retirer().invoke(menu, lot.serial(), action);
            return;
        }
        var retrait = retraits;
        int poche = direct ? destination.getContainerSlot() : -1;
        recherche(arbre)
                .observateur()
                .retirer(
                        menu,
                        arbre,
                        lot.serial(),
                        lot.cle(),
                        lot.pile().copy(),
                        poche,
                        action,
                        repetitions,
                        ACCES.retirer(),
                        reessayer -> {
                            if (retrait != retraits || !occupe() || lot != retrait.get(index))
                                return;
                            destination = null;
                            direct = false;
                            attendus = 0;
                            if (!reessayer)
                                terminer("Retrait ME interrompu : poste ou terminal indisponible.");
                        });
    }

    private static boolean posteARevalider() {
        return recherche != null && !recherche.observateur().recettes().isEmpty();
    }

    private static boolean verifierPrise(Lot lot) throws ReflectiveOperationException {
        if (priseVerifiee || !posteARevalider()) return true;
        if (clicEnAttente) return false;
        var retrait = retraits;
        observer(
                true,
                () -> {
                    try {
                        if (!occupe() || retrait != retraits || lot != retraits.get(index)) return;
                        actualiser(true);
                        int quota =
                                vue == null
                                        ? 0
                                        : vue.retraits().stream()
                                                .filter(l -> l.serial() == lot.serial())
                                                .mapToInt(Lot::quantite)
                                                .sum();
                        reste = Math.min(reste, quota);
                        priseVerifiee = true;
                        if (reste > 0) avancer();
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        signaler(ex);
                        terminer("Retrait ME interrompu.");
                    } finally {
                        priseVerifiee = false;
                    }
                });
        return false;
    }

    private static int volumeRetrait(int reste, int taillePile, int place, int curseur) {
        if (curseur > 0) return 1;
        int limite = Math.min(reste, place);
        if (limite >= taillePile) return taillePile;
        // AE2 arrondit la demi-pile vers le haut ; le stock réel peut réduire ce volume.
        int demi = (taillePile + 1) / 2;
        return limite >= demi ? demi : 1;
    }

    private static Slot destinationFermee(Object lien, int conteneur) {
        if (menu == null
                || destination == null
                || menu.containerId != conteneur
                || System.nanoTime() >= echeance) return null;
        var mc = Minecraft.getInstance();
        var joueurActuel = mc.player;
        var ecoute = mc.getConnection();
        return joueurActuel != null
                        && joueurActuel == joueur
                        && mc.level == niveau
                        && ecoute != null
                        && ecoute == connexion
                        && ecoute.getConnection() == lien
                        && joueurActuel.containerMenu == joueurActuel.inventoryMenu
                        && destination.container == joueurActuel.getInventory()
                ? destination
                : null;
    }

    /** Vanilla peut ignorer la confirmation d'une case si le menu vient d'être fermé. */
    public static void confirmer(Object lien, int conteneur, int index, ItemStack pile) {
        var slot = destinationFermee(lien, conteneur);
        if (slot != null && slot.index == index) depotServeur = pile.copy();
    }

    public static void confirmer(Object lien, int conteneur, List<ItemStack> piles) {
        var slot = destinationFermee(lien, conteneur);
        if (slot != null && slot.index >= 0 && slot.index < piles.size())
            depotServeur = piles.get(slot.index).copy();
    }

    private static void attendre() {
        if (System.nanoTime() >= echeance)
            terminer("Retrait ME interrompu : réponse du terminal absente. Vérifie le curseur.");
    }

    private static String bilan() {
        String resultat =
                millibuckets == 0
                        ? recus + " objets récupérés dans le réseau ME."
                        : (recus == 0 ? "" : recus + " objets et ")
                                + millibuckets
                                + " mB récupérés dans le réseau ME.";
        if (contenantManquant)
            return resultat
                    + " Certains fluides restent dans ME. Il faut un contenant compatible par fluide et une case libre pour un drum pris dans ME ou un contenant empilé.";
        return resultat;
    }

    private static void terminer(String texte) {
        soldeDistant = false;
        remplissage = null;
        menu = null;
        ecran = null;
        connexion = joueur = niveau = null;
        arbre = null;
        destination = null;
        depotServeur = null;
        reception = null;
        retraits = List.of();
        ticks = 0;
        try { // Relevé refait tout de suite : le panneau passe au reste sans disparaître.
            actualiser(true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            signaler(ex);
            vue = null;
        }
        InventaireArbreEmi.invalider();
        message(texte);
    }

    private static void message(String texte) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(texte), true);
    }

    private static void signaler(Throwable cause) {
        if (erreur) return;
        erreur = true;
        LogUtils.getLogger().warn("Assistant : terminal ME indisponible", cause);
    }
}
