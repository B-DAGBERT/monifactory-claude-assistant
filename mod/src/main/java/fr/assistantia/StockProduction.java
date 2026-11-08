package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Uniquement les cases d'un menu ouvert, attestées par un même paquet serveur complet. */
public final class StockProduction {
    private static final long DUREE = 60_000;
    private static final int MAX_CASES = 8192;
    private static final String SOPHISTIQUE =
            "net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu";
    private static final String CASE_STOCKAGE =
            "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot";

    private record Portee(Connection connexion, String monde, String session, String dimension) {}

    private record Case(Slot slot, ItemStack pile, int nature) {}

    private record Instantane(
            Portee portee,
            AbstractContainerMenu menu,
            int etat,
            long observe,
            List<Case> cases,
            ItemStack curseur,
            boolean sophistique) {}

    private static Instantane instantane;
    private static String revision = "";
    private static AbstractContainerMenu informationsMenu;
    private static Portee informationsPortee;
    private static AbstractContainerScreen<?> retour, transition;
    private static Portee retourPortee;

    private StockProduction() {}

    private static Portee portee() {
        var mc = Minecraft.getInstance();
        var listener = mc.getConnection();
        if (!mc.isSameThread()
                || mc.player == null
                || mc.level == null
                || listener == null
                || !listener.getConnection().isConnected()) return null;
        String monde = IdentiteMonde.monde(), session = IdentiteMonde.session();
        if (monde.isEmpty() || session.isEmpty()) return null;
        return new Portee(
                listener.getConnection(),
                monde,
                session,
                mc.level.dimension().location().toString());
    }

    private static boolean origine(Connection connexion) {
        var p = portee();
        return p != null && p.connexion() == connexion;
    }

    private static boolean sophistique(AbstractContainerMenu menu) {
        return menu != null && menu.getClass().getName().equals(SOPHISTIQUE);
    }

    private static boolean accepte(AbstractContainerMenu menu) {
        // Pas de sous-classe inconnue qui aurait ajouté des cases fantômes ou virtuelles.
        return menu != null
                && (menu.getClass() == ChestMenu.class
                        || menu.getClass() == ShulkerBoxMenu.class
                        || sophistique(menu));
    }

    private static boolean caseStockage(Slot slot) {
        // Le constructeur natif instancie StorageContainerMenuBase$1, sous-classe anonyme.
        for (Class<?> type = slot.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(CASE_STOCKAGE)) return true;
        return false;
    }

    private static int nombreCases(AbstractContainerMenu menu) throws ReflectiveOperationException {
        return sophistique(menu)
                ? (Integer) menu.getClass().getMethod("getTotalSlotsNumber").invoke(menu)
                : menu.slots.size();
    }

    /** Le hook TAIL intervient après initializeContents, sur le thread client. */
    public static void complet(
            Connection connexion,
            int menuId,
            int etat,
            List<ItemStack> piles,
            ItemStack curseur,
            boolean sophistique) {
        if (!origine(connexion)) return;
        invalider();
        var mc = Minecraft.getInstance();
        var contexte = portee();
        if (contexte == null) return;
        var menu = mc.player.containerMenu;
        if (!accepte(menu)
                || menu.containerId != menuId
                || etat < 0
                || sophistique(menu) != sophistique
                || piles == null
                || curseur == null
                || piles.size() < 37
                || piles.size() > MAX_CASES) return;
        try {
            if (nombreCases(menu) != piles.size()
                    || menu.getStateId() != etat
                    || !ItemStack.matches(menu.getCarried(), curseur)) return;
            List<Case> cases = new ArrayList<>();
            Set<Integer> joueur = new HashSet<>(), stockage = new HashSet<>();
            Set<Slot> distinctes =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int i = 0; i < piles.size(); i++) {
                Slot slot = menu.getSlot(i);
                ItemStack pile = piles.get(i);
                if (pile == null
                        || !distinctes.add(slot)
                        || !ItemStack.matches(slot.getItem(), pile)) return;
                int nature = 0;
                if (slot.container == mc.player.getInventory()) {
                    int index = slot.getContainerSlot();
                    if (index < 0 || index >= 36 || !joueur.add(index)) return;
                    nature = 2;
                } else if (!sophistique || caseStockage(slot)) {
                    if (!stockage.add(slot.getContainerSlot())) return;
                    nature = 1;
                }
                cases.add(new Case(slot, pile.copy(), nature));
            }
            if (joueur.size() != 36 || stockage.isEmpty()) return;
            instantane =
                    new Instantane(
                            contexte,
                            menu,
                            etat,
                            System.currentTimeMillis(),
                            List.copyOf(cases),
                            curseur.copy(),
                            sophistique);
            revision = UUID.randomUUID().toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            invalider();
        }
    }

    /**
     * Les quantités étendues et les filtres de Sophisticated ne passent pas par le paquet vanilla.
     */
    public interface Reponse {
        Connection assistantia$connexion();

        void assistantia$connexion(Connection connexion);
    }

    public interface Contenu extends Reponse {
        int assistantia$menu();

        int assistantia$etat();

        List<ItemStack> assistantia$piles();

        ItemStack assistantia$curseur();
    }

    /** Reçu après le full initial : les cases infinies/inaccessibles sont désormais connues. */
    public static void informations(Connection connexion) {
        if (!origine(connexion)) return;
        var menu = Minecraft.getInstance().player.containerMenu;
        if (!sophistique(menu)) return;
        informationsMenu = menu;
        informationsPortee = portee();
        // Un changement de droits doit aussi révoquer une capture déjà envoyée.
        revision = UUID.randomUUID().toString();
    }

    public static void variation(Connection connexion) {
        if (origine(connexion)) invalider();
    }

    public static void clic() {
        if (Minecraft.getInstance().isSameThread()) invalider();
    }

    private static void invalider() {
        instantane = null;
        revision = "";
    }

    private static boolean courant(long maintenant, boolean exigerInformations) {
        var s = instantane;
        var mc = Minecraft.getInstance();
        if (s == null
                || !s.portee().equals(portee())
                || mc.player.containerMenu != s.menu()
                || maintenant < s.observe()
                || maintenant - s.observe() > DUREE
                || mc.player.containerMenu.getStateId() != s.etat()
                || exigerInformations
                        && s.sophistique()
                        && (informationsMenu != s.menu() || !s.portee().equals(informationsPortee)))
            return false;
        try {
            if (nombreCases(s.menu()) != s.cases().size()
                    || !ItemStack.matches(s.menu().getCarried(), s.curseur())) return false;
            for (int i = 0; i < s.cases().size(); i++) {
                var c = s.cases().get(i);
                if (s.menu().getSlot(i) != c.slot()
                        || !ItemStack.matches(c.slot().getItem(), c.pile())) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Aucun historique, BE client, sac, armure ou offhand n'est additionné à cette trame. */
    public static JsonObject capturer(long maintenant) {
        if (!courant(maintenant, true)) return null;
        var s = instantane;
        List<TreeMap<String, Long>> sources =
                List.of(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        int exclues = 0;
        try {
            var infini =
                    s.sophistique()
                            ? s.menu().getClass().getMethod("isInfiniteSlot", int.class)
                            : null;
            for (int i = 0; i < s.cases().size(); i++) {
                var c = s.cases().get(i);
                if (c.nature() == 0) continue;
                if (c.nature() == 1
                        && (!c.slot().isActive()
                                || !c.slot().mayPickup(Minecraft.getInstance().player)
                                || infini != null && (Boolean) infini.invoke(s.menu(), i))) {
                    exclues++;
                    continue;
                }
                if (!ajouter(sources.get(c.nature() - 1), c.pile())) exclues++;
            }
            if (!ajouter(sources.get(2), s.curseur())) exclues++;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("disponible", true);
        json.addProperty("mode", "menu_serveur");
        json.addProperty("revision", revision);
        json.addProperty("observe_ms", s.observe());
        json.addProperty("capture_ms", maintenant);
        json.addProperty("coherence", "paquet_complet_sans_mutation");
        json.addProperty("container_id", s.menu().containerId);
        json.addProperty("state_id", s.etat());
        JsonArray liste = new JsonArray();
        String[] ids = {"coffre", "inventaire", "curseur"};
        String[] natures = {"coffre_ouvert", "inventaire_36", "curseur"};
        for (int i = 0; i < sources.size(); i++) {
            JsonObject source = new JsonObject(), items = new JsonObject();
            source.addProperty("id", ids[i]);
            source.addProperty("nature", natures[i]);
            sources.get(i).forEach(items::addProperty);
            source.add("items", items);
            liste.add(source);
        }
        json.add("sources", liste);
        JsonArray limites = new JsonArray();
        limites.add(
                "Seul le coffre ouvert et les 36 cases du joueur sont couverts, plus le curseur.");
        limites.add(
                "Sacs fermés, armure, seconde main, fluides et autres coffres exclus de cette trame.");
        if (exclues > 0)
            limites.add(
                    exclues
                            + " cases avec variantes NBT, accès restreint ou stock infini exclues.");
        json.add("limites", limites);
        return json;
    }

    private static boolean ajouter(TreeMap<String, Long> items, ItemStack pile) {
        if (pile.isEmpty()) return true;
        if (pile.getTag() != null && !pile.getTag().isEmpty()) return false;
        var id = ForgeRegistries.ITEMS.getKey(pile.getItem());
        if (id == null || pile.getCount() <= 0) return false;
        long total = items.getOrDefault(id.toString(), 0L) + pile.getCount();
        if (total > 1_000_000_000L) throw new IllegalArgumentException("Quantité hors borne");
        items.put(id.toString(), total);
        return true;
    }

    public static boolean valide(JsonObject stock) {
        if (stock == null || !courant(System.currentTimeMillis(), true)) return false;
        try {
            return revision.equals(stock.get("revision").getAsString())
                    && stock.get("observe_ms").getAsLong() == instantane.observe();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean menuDisponible() {
        return courant(System.currentTimeMillis(), true);
    }

    /** Uniquement pendant setScreen : empêcher removed de toucher les upgrades du coffre. */
    public static boolean garderMenu(Screen ecran) {
        return ecran == transition && retourPossible(ecran);
    }

    public static boolean retourPossible(Screen parent) {
        return parent == retour
                && retourPortee != null
                && retourPortee.equals(portee())
                && Minecraft.getInstance().player.containerMenu == retour.getMenu();
    }

    public static void ouvrir(AbstractContainerScreen<?> parent) {
        ouvrirSuivi(parent, EcranProduction.depuisCoffre(parent));
    }

    /** Préserve le coffre attesté pendant une consultation, sans accepter un ancien écran. */
    public static boolean ouvrirSuivi(Screen parent, Screen suivant) {
        var mc = Minecraft.getInstance();
        if (!(parent instanceof AbstractContainerScreen<?> conteneur)
                || mc.screen != parent
                || suivant == null
                || suivant == parent
                || !menuDisponible()
                || mc.player.containerMenu != conteneur.getMenu()) return false;
        retour = conteneur;
        retourPortee = portee();
        transition = conteneur;
        try {
            mc.setScreen(suivant);
            return true;
        } finally {
            transition = null;
        }
    }

    @SubscribeEvent
    public static void touche(ScreenEvent.KeyPressed.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> parent)
                || !accepte(parent.getMenu())
                || parent.getFocused() instanceof EditBox
                || Emi.rechercheActive()
                || !Touches.OUVRIR.matches(event.getKeyCode(), event.getScanCode())) return;
        if (menuDisponible()) ouvrir(parent);
        else
            Minecraft.getInstance()
                    .player
                    .displayClientMessage(
                            Component.literal(
                                    "Production : referme puis rouvre le coffre pour obtenir un relevé serveur complet."),
                            true);
    }

    @SubscribeEvent
    public static void clicEcran(ScreenEvent.MouseButtonPressed.Pre event) {
        // Couvre aussi tri, upgrades et boutons de transfert propres à d'autres mods.
        if (event.getScreen() instanceof AbstractContainerScreen<?>) clic();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (instantane != null && !courant(System.currentTimeMillis(), false)) invalider();
        if (informationsPortee != null
                && (!informationsPortee.equals(portee())
                        || Minecraft.getInstance().player.containerMenu != informationsMenu)) {
            informationsMenu = null;
            informationsPortee = null;
        }
        if (retour != null && !retourPossible(retour)) {
            retour = null;
            retourPortee = null;
        }
    }

    @SubscribeEvent
    public static void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        invalider();
        informationsMenu = null;
        informationsPortee = null;
        retour = transition = null;
        retourPortee = null;
    }
}
