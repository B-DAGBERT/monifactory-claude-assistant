package fr.assistantia;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/** Terminal AE2 simulé avec réponses différées, piloté par le vrai traitement client. */
public final class RetraitTerminalMeTest {
    private enum Action {
        PICKUP_SINGLE,
        PICKUP_OR_SET_DOWN,
        SPLIT_OR_PLACE_SINGLE,
        SHIFT_CLICK
    }

    private static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack("", 0, 64);
        final String id, tag;
        final int count, taillePile;

        ItemStack(String id, int count, int taillePile) {
            this(id, "", count, taillePile);
        }

        ItemStack(String id, String tag, int count, int taillePile) {
            this.id = id;
            this.tag = tag;
            this.count = count;
            this.taillePile = taillePile;
        }

        int getCount() {
            return count;
        }

        int getMaxStackSize() {
            return taillePile;
        }

        boolean isEmpty() {
            return count == 0;
        }

        ItemStack copy() {
            return new ItemStack(id, tag, count, taillePile);
        }

        static boolean isSameItemSameTags(ItemStack a, ItemStack b) {
            return !a.isEmpty() && !b.isEmpty() && a.id.equals(b.id) && a.tag.equals(b.tag);
        }
    }

    private static final class Slot {
        final Inventory container;
        final int index;
        private final int inventaire, capacite;

        Slot(Inventory container, int index, int inventaire, int capacite) {
            this.container = container;
            this.index = index;
            this.inventaire = inventaire;
            this.capacite = capacite;
        }

        int getContainerSlot() {
            return inventaire;
        }

        ItemStack getItem() {
            return container.getItem(inventaire);
        }

        boolean isActive() {
            return true;
        }

        boolean mayPlace(ItemStack item) {
            return true;
        }

        int getMaxStackSize(ItemStack item) {
            return Math.min(capacite, item.getMaxStackSize());
        }
    }

    private static final class Inventory {
        private final List<ItemStack> cases = new ArrayList<>();

        Inventory(int nombre) {
            for (int i = 0; i < nombre; i++) cases.add(ItemStack.EMPTY);
        }

        ItemStack getItem(int n) {
            return cases.get(n);
        }

        void setItem(int n, ItemStack item) {
            cases.set(n, item);
        }

        int getSlotWithRemainingSpace(ItemStack reference) {
            for (int i = 0; i < cases.size(); i++) {
                var pile = cases.get(i);
                if (ItemStack.isSameItemSameTags(pile, reference)
                        && pile.getCount() < pile.getMaxStackSize()) return i;
            }
            return -1;
        }

        int getFreeSlot() {
            for (int i = 0; i < cases.size(); i++) if (cases.get(i).isEmpty()) return i;
            return -1;
        }

        int quantite(String variante) {
            return cases.stream()
                    .filter(s -> variante.equals(s.id))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }
    }

    private static final class Player {
        final Inventory inventory;
        final AbstractContainerMenu inventoryMenu = new AbstractContainerMenu();
        AbstractContainerMenu containerMenu = inventoryMenu;

        Player(Inventory inventory) {
            this.inventory = inventory;
        }

        Inventory inventory() {
            return inventory;
        }

        Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AbstractContainerMenu {
        final int containerId = 21;
        final List<Slot> slots = new ArrayList<>();
        ItemStack carried = ItemStack.EMPTY;
        boolean powered = true;
        int stock;

        ItemStack getCarried() {
            return carried;
        }

        int getStateId() {
            return 11;
        }

        public boolean isPowered() {
            return powered;
        }

        public void handleInteraction(long serial, Action action) {
            verifier(serial == 7, "entrée ME du lot");
            MC.getConnection().actions.add(action);
            MC.getConnection().ajouter(() -> appliquer(action));
        }

        private void appliquer(Action action) {
            if (!powered) return;
            var pile = PRODUIT;
            int max = pile.getMaxStackSize();
            switch (action) {
                case SHIFT_CLICK -> {
                    var poche = MC.player.getInventory();
                    int libre = poche.getSlotWithRemainingSpace(pile);
                    if (libre == -1) libre = poche.getFreeSlot();
                    if (libre == -1) return;
                    int present = poche.getItem(libre).getCount();
                    int pris = Math.min(stock, max - present);
                    stock -= pris;
                    poche.setItem(libre, new ItemStack(pile.id, present + pris, max));
                }
                case PICKUP_OR_SET_DOWN, SPLIT_OR_PLACE_SINGLE -> {
                    if (!carried.isEmpty()) {
                        int rendu = action == Action.PICKUP_OR_SET_DOWN ? carried.getCount() : 1;
                        stock += rendu;
                        carried = new ItemStack(pile.id, carried.getCount() - rendu, max);
                        return;
                    }
                    int disponible = Math.min(stock, max);
                    int pris =
                            action == Action.PICKUP_OR_SET_DOWN ? disponible : (disponible + 1) / 2;
                    stock -= pris;
                    carried = new ItemStack(pile.id, pris, max);
                }
                case PICKUP_SINGLE -> {
                    if (stock == 0 || carried.getCount() >= max) return;
                    if (!carried.isEmpty() && !ItemStack.isSameItemSameTags(carried, pile)) return;
                    stock--;
                    carried = new ItemStack(pile.id, carried.getCount() + 1, max);
                }
            }
        }
    }

    private record AbstractContainerScreen<T>(T menu) {
        T getMenu() {
            return menu;
        }
    }

    private enum ClickType {
        PICKUP
    }

    private static final class Int2ObjectOpenHashMap<T> {}

    private record ServerboundContainerClickPacket(
            int containerId,
            int revision,
            int slot,
            int bouton,
            ClickType type,
            ItemStack curseur,
            Int2ObjectOpenHashMap<?> modifications) {}

    private static final class Connection {
        final AbstractContainerMenu actif;
        private final Object transport = new Object();
        final ArrayDeque<Runnable> paquets = new ArrayDeque<>();
        final List<Action> actions = new ArrayList<>();
        int depots;

        Connection(AbstractContainerMenu actif) {
            this.actif = actif;
        }

        Object getConnection() {
            return transport;
        }

        void ajouter(Runnable paquet) {
            verifier(paquets.isEmpty(), "une seule action attend la confirmation du serveur");
            paquets.add(paquet);
        }

        void confirmer() {
            paquets.remove().run();
        }

        void send(ServerboundContainerClickPacket paquet) {
            depots++;
            ajouter(
                    () -> {
                        verifier(
                                paquet.containerId() == actif.containerId
                                        && paquet.revision() == actif.getStateId()
                                        && paquet.type() == ClickType.PICKUP
                                        && paquet.bouton() == 0,
                                "dépôt par clic natif dans le bon terminal");
                        var cible =
                                actif.slots.stream()
                                        .filter(s -> s.index == paquet.slot())
                                        .findFirst()
                                        .orElseThrow();
                        var curseur = actif.carried;
                        int volume = curseur.getCount() + cible.getItem().getCount();
                        verifier(
                                paquet.curseur().getCount() == curseur.getCount()
                                        && volume <= cible.getMaxStackSize(curseur),
                                "aucun dépassement de la capacité de la case");
                        cible.container.setItem(
                                cible.getContainerSlot(),
                                new ItemStack(curseur.id, volume, curseur.taillePile));
                        actif.carried = ItemStack.EMPTY;
                    });
        }
    }

    private static final class Minecraft {
        Player player;
        Object screen, level;
        Connection connection;

        static Minecraft getInstance() {
            return MC;
        }

        Connection getConnection() {
            return connection;
        }
    }

    private static final class InstantaneArbreEmi {
        static InstantaneArbreEmi lire(Object tree) {
            return (InstantaneArbreEmi) tree;
        }
    }

    private static final class InventaireArbreEmi {
        static void invalider() {}
    }

    private static final Minecraft MC = new Minecraft();
    private static ItemStack PRODUIT;
    private static String message;
    private static Object vue;
    private static int ticks;
    private static InstantaneArbreEmi arbreActuel;

    // EXTRACTION_CHAMPS

    private static Acces accesFixture() throws ReflectiveOperationException {
        var composants = Acces.class.getRecordComponents();
        var types = new Class<?>[composants.length];
        var valeurs = new Object[composants.length];
        for (int i = 0; i < composants.length; i++) {
            types[i] = composants[i].getType();
            valeurs[i] =
                    switch (composants[i].getName()) {
                        case "powered" -> AbstractContainerMenu.class.getMethod("isPowered");
                        case "retirer" -> AbstractContainerMenu.class.getMethod(
                                "handleInteraction", long.class, Action.class);
                        case "un" -> Action.PICKUP_SINGLE;
                        case "pileEntiere" -> Action.PICKUP_OR_SET_DOWN;
                        case "demiPile" -> Action.SPLIT_OR_PLACE_SINGLE;
                        case "direct", "pileDirecte", "shift" -> Action.SHIFT_CLICK;
                        default -> null;
                    };
        }
        return Acces.class.getDeclaredConstructor(types).newInstance(valeurs);
    }

    private static Acces ACCES;

    private static Object arbreCourant() {
        return arbreActuel;
    }

    private static AbstractContainerMenu courant() {
        return MC.player.containerMenu;
    }

    private static void actualiser(boolean complet) throws ReflectiveOperationException {}

    private static void signaler(Throwable cause) {
        throw new AssertionError(cause);
    }

    private static void message(String texte) {
        message = texte;
    }

    // EXTRACTION_TRAITEMENT

    private static AbstractContainerMenu preparer(int demande, int max, int... capacites) {
        var inventaire = new Inventory(capacites.length);
        joueur = MC.player = new Player(inventaire);
        var actif = new AbstractContainerMenu();
        for (int i = 0; i < capacites.length; i++)
            actif.slots.add(new Slot(inventaire, i, i, capacites[i]));
        actif.stock = 1_000;
        MC.connection = new Connection(actif);
        menu = actif;
        MC.player.containerMenu = actif;
        niveau = MC.level = new Object();
        ecran = new AbstractContainerScreen<>(actif);
        MC.screen = ecran;
        connexion = MC.connection;
        arbreActuel = arbre = new InstantaneArbreEmi();
        PRODUIT = new ItemStack("cable", 1, max);
        retraits = List.of(new Lot(7L, PRODUIT, demande));
        index = attendus = avant = recus = 0;
        destination = null;
        depotServeur = null;
        direct = false;
        reste = demande;
        message = "";
        return actif;
    }

    private static void tick() throws ReflectiveOperationException {
        if (menu != null) avancer();
    }

    private static void finir() throws ReflectiveOperationException {
        for (int i = 0; menu != null && i < 512; i++) {
            tick();
            if (!MC.connection.paquets.isEmpty()) {
                int confirmes = recus;
                tick();
                verifier(recus == confirmes, "le contenu local ne précède pas la confirmation");
                MC.connection.confirmer();
            }
        }
        verifier(menu == null, "le retrait se termine");
    }

    private static void entierDemiEtUnites() throws ReflectiveOperationException {
        var terminal = preparer(130, 64, 64, 64, 64);
        finir();
        verifier(
                MC.connection.actions.equals(
                        List.of(
                                Action.SHIFT_CLICK,
                                Action.SHIFT_CLICK,
                                Action.PICKUP_SINGLE,
                                Action.PICKUP_SINGLE)),
                "130 en deux piles directes de 64 et deux unités");
        verifier(
                terminal.stock == 870
                        && recus == 130
                        && terminal.carried.isEmpty()
                        && MC.player.inventory().quantite("cable") == 130
                        && MC.connection.depots == 1,
                "deux confirmations directes et un dépôt de reliquat");

        terminal = preparer(112, 64, 64, 64);
        finir();
        var actions = new ArrayList<>(List.of(Action.SHIFT_CLICK, Action.SPLIT_OR_PLACE_SINGLE));
        actions.addAll(Collections.nCopies(16, Action.PICKUP_SINGLE));
        verifier(MC.connection.actions.equals(actions), "112 en 64 + 32 + 16 unités");
        verifier(
                terminal.stock == 888 && recus == 112 && terminal.carried.isEmpty(),
                "aucun surplus ni curseur occupé");
    }

    private static void taillesEtCapacite() throws ReflectiveOperationException {
        var terminal = preparer(34, 16, 64, 64, 64);
        finir();
        verifier(
                MC.connection.actions.equals(
                                List.of(
                                        Action.SHIFT_CLICK,
                                        Action.SHIFT_CLICK,
                                        Action.PICKUP_SINGLE,
                                        Action.PICKUP_SINGLE))
                        && recus == 34
                        && terminal.stock == 966,
                "la taille de pile 16 est utilisée");

        terminal = preparer(3, 1, 64, 64, 64);
        finir();
        verifier(
                MC.connection.actions.size() == 3 && recus == 3 && terminal.stock == 997,
                "trois poches pour trois objets limités à une unité");

        terminal = preparer(10, 16, 16);
        MC.player.inventory().setItem(0, new ItemStack("cable", 12, 16));
        finir();
        verifier(
                MC.connection.actions.equals(Collections.nCopies(4, Action.PICKUP_SINGLE))
                        && terminal.stock == 996
                        && recus == 4
                        && MC.player.inventory().getItem(0).getCount() == 16
                        && terminal.carried.isEmpty(),
                "retrait limité aux quatre places libres");
    }

    private static void choixServeurEtAnnulation() throws ReflectiveOperationException {
        var terminal = preparer(64, 64, 64, 64);
        MC.player.inventory().setItem(0, new ItemStack("cable", 60, 64));
        finir();
        verifier(
                MC.connection.actions.equals(List.of(Action.PICKUP_OR_SET_DOWN))
                        && MC.connection.depots == 1
                        && terminal.stock == 936
                        && recus == 64,
                "le serveur fusionnerait l'ancienne pile, le client garde le retrait curseur");

        terminal = preparer(64, 64, 64, 64);
        terminal.slots.remove(0);
        finir();
        verifier(
                MC.connection.actions.equals(List.of(Action.PICKUP_OR_SET_DOWN))
                        && MC.player.inventory().getItem(0).isEmpty()
                        && MC.player.inventory().getItem(1).getCount() == 64,
                "la poche que le serveur choisirait doit être visible dans le terminal");

        terminal = preparer(130, 64, 64, 64, 64);
        tick();
        verifier(
                recus == 0 && MC.connection.actions.equals(List.of(Action.SHIFT_CLICK)),
                "aucun crédit avant la réponse du serveur");
        arbreActuel = new InstantaneArbreEmi();
        tick();
        verifier(menu == null && recus == 0, "le changement de recette arrête le retrait");
        MC.connection.confirmer();
        tick();
        verifier(
                MC.connection.actions.size() == 1
                        && terminal.stock == 936
                        && MC.player.inventory().quantite("cable") == 64
                        && terminal.carried.isEmpty(),
                "le lot déjà envoyé arrive seul, sans reprise du reste");
    }

    private static void confirmationsPartiellesEtRefus() throws ReflectiveOperationException {
        var terminal = preparer(64, 64, 64);
        terminal.stock = 20;
        tick();
        MC.connection.confirmer();
        verifier(
                recus == 0 && MC.player.inventory().quantite("cable") == 20,
                "le gain physique partiel n'est compté qu'au prochain tick");
        terminal.stock = 200;
        tick();
        verifier(recus == 20 && reste == 44, "le gain réel remplace la taille de pile demandée");
        finir();
        var actions = new ArrayList<>(List.of(Action.SHIFT_CLICK, Action.SPLIT_OR_PLACE_SINGLE));
        actions.addAll(Collections.nCopies(12, Action.PICKUP_SINGLE));
        verifier(
                MC.connection.actions.equals(actions) && recus == 64 && terminal.stock == 156,
                "après une pile partielle, le retrait se limite aux 44 objets restants");

        terminal = preparer(64, 64, 64);
        terminal.stock = 0;
        tick();
        MC.connection.confirmer();
        tick();
        verifier(menu != null && recus == 0, "un refus sans delta ne compte rien");
        echeance = 0;
        tick();
        verifier(
                menu == null
                        && MC.connection.actions.equals(List.of(Action.SHIFT_CLICK))
                        && MC.connection.depots == 0
                        && terminal.carried.isEmpty(),
                "la fin de l'attente n'envoie aucune relance");
    }

    private static List<Integer> surveillerReceptions() {
        List<Integer> confirmations = new ArrayList<>();
        reception =
                (pile, quantite) -> {
                    verifier(
                            ItemStack.isSameItemSameTags(pile, PRODUIT)
                                    && pile.getCount() >= quantite,
                            "le suivi reçoit le dépôt de la variante exacte");
                    confirmations.add(quantite);
                };
        return confirmations;
    }

    private static void echap() {
        MC.screen = null;
        MC.player.containerMenu = MC.player.inventoryMenu;
    }

    private static void receptionApresEchap() throws ReflectiveOperationException {
        preparer(130, 64, 64, 64, 64);
        var confirmations = surveillerReceptions();
        tick();
        echap();
        tick();
        verifier(menu != null && confirmations.isEmpty(), "attend le dépôt direct déjà envoyé");
        MC.connection.confirmer();
        tick();
        tick();
        verifier(
                menu == null
                        && confirmations.equals(List.of(64))
                        && MC.connection.actions.equals(List.of(Action.SHIFT_CLICK)),
                "reçoit la première pile et annule le reste en fermant");

        preparer(1, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        MC.connection.confirmer();
        tick();
        echap();
        tick();
        verifier(
                menu != null && confirmations.isEmpty(), "attend le dépôt par curseur déjà envoyé");
        MC.connection.confirmer();
        tick();
        verifier(
                menu == null && confirmations.equals(List.of(1)) && MC.connection.depots == 1,
                "une unité déposée après fermeture est comptée une fois");

        preparer(1, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        MC.connection.confirmer();
        echap();
        tick();
        verifier(
                menu == null && confirmations.isEmpty() && MC.connection.depots == 0,
                "un ancien curseur sans demande de dépôt ne suffit pas");

        preparer(64, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        echap();
        MC.connection.confirmer();
        MC.player.inventory().setItem(0, new ItemStack(PRODUIT.id, "autre", 64, 64));
        tick();
        verifier(menu != null && confirmations.isEmpty(), "rejette le mauvais tag après Échap");
        echeance = 0;
        tick();
        verifier(menu == null && confirmations.isEmpty(), "l’attente après Échap reste bornée");

        for (Runnable changer :
                List.<Runnable>of(
                        () -> MC.level = new Object(),
                        () -> MC.player = new Player(MC.player.inventory()),
                        () -> MC.connection = new Connection(MC.connection.actif),
                        () -> arbreActuel = new InstantaneArbreEmi(),
                        () -> MC.player.containerMenu = new AbstractContainerMenu())) {
            preparer(64, 64, 64);
            confirmations = surveillerReceptions();
            tick();
            echap();
            MC.connection.confirmer();
            changer.run();
            tick();
            verifier(menu == null && confirmations.isEmpty(), "refuse toute autre portée");
        }
    }

    private static void confirmationAncienneFenetre() throws ReflectiveOperationException {
        var terminal = preparer(130, 64, 64, 64, 64);
        var confirmations = surveillerReceptions();
        tick();
        echap();
        Object lien = MC.connection.getConnection();
        int conteneur = terminal.containerId;
        var paquet = new ItemStack(PRODUIT.id, 64, 64);
        confirmer(new Object(), conteneur, 0, paquet);
        confirmer(lien, conteneur + 1, 0, paquet);
        confirmer(lien, conteneur, 1, paquet);
        confirmer(lien, conteneur, List.of());
        tick();
        verifier(
                menu != null && confirmations.isEmpty(), "ignore les paquets sans identité exacte");
        confirmer(lien, conteneur, 0, paquet);
        confirmer(lien, conteneur, 0, new ItemStack(PRODUIT.id, "autre", 64, 64));
        tick();
        verifier(menu != null && confirmations.isEmpty(), "une correction de variante prévaut");
        confirmer(lien, conteneur, 0, new ItemStack(PRODUIT.id, 65, 64));
        tick();
        verifier(menu != null && confirmations.isEmpty(), "aucun retrait supérieur à la demande");
        confirmer(lien, conteneur, 0, paquet);
        confirmer(lien, conteneur, 0, paquet);
        tick();
        confirmer(lien, conteneur, 0, paquet);
        tick();
        verifier(
                menu == null
                        && confirmations.equals(List.of(64))
                        && MC.player.inventory().getItem(0).isEmpty()
                        && MC.connection.actions.equals(List.of(Action.SHIFT_CLICK)),
                "confirmation directe unique même si le client a ignoré le paquet");

        terminal = preparer(1, 64, 64);
        MC.player.inventory().setItem(0, new ItemStack(PRODUIT.id, 9, 64));
        confirmations = surveillerReceptions();
        tick();
        MC.connection.confirmer();
        tick();
        echap();
        lien = MC.connection.getConnection();
        confirmer(lien, terminal.containerId, List.of(new ItemStack(PRODUIT.id, 11, 64)));
        tick();
        verifier(menu != null && confirmations.isEmpty(), "rejette un delta supérieur au curseur");
        confirmer(lien, terminal.containerId, List.of(new ItemStack(PRODUIT.id, 10, 64)));
        tick();
        verifier(
                menu == null
                        && confirmations.equals(List.of(1))
                        && MC.player.inventory().getItem(0).getCount() == 9,
                "le contenu complet confirme le delta exact sans écrire dans les poches");

        terminal = preparer(64, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        echap();
        confirmer(MC.connection.getConnection(), terminal.containerId, 0, paquet);
        arbreActuel = new InstantaneArbreEmi();
        tick();
        verifier(
                menu == null && confirmations.isEmpty(), "rejette une confirmation d’ancien arbre");
    }

    private static void receptionParLeSuivi() throws ReflectiveOperationException {
        var terminal = preparer(66, 64, 64, 64);
        terminal.stock = 20;
        var confirmations = surveillerReceptions();
        tick();
        tick();
        verifier(confirmations.isEmpty(), "l’envoi direct seul ne confirme rien");
        MC.connection.confirmer();
        terminal.stock = 100;
        tick();
        verifier(confirmations.equals(List.of(20)), "seules les 20 unités confirmées sont reçues");
        MC.connection.confirmer();
        tick();
        verifier(
                confirmations.equals(List.of(20)) && !terminal.getCarried().isEmpty(),
                "un retrait qui attend au curseur ne compte pas comme réception");
        finir();
        tick();
        verifier(
                confirmations.equals(List.of(20, 32, 14)) && reception == null,
                "le dépôt du reliquat compte une fois, la fin efface le callback");

        terminal = preparer(1, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        MC.connection.confirmer();
        tick();
        MC.connection.confirmer();
        MC.player.inventory().setItem(0, new ItemStack(PRODUIT.id, "autre", 1, 64));
        tick();
        verifier(confirmations.isEmpty(), "une correction serveur de variante ne valide rien");
        echeance = 0;
        tick();
        verifier(confirmations.isEmpty() && reception == null, "aucun crédit après délai");

        terminal = preparer(64, 64, 64);
        confirmations = surveillerReceptions();
        tick();
        arbreActuel = new InstantaneArbreEmi();
        tick();
        MC.connection.confirmer();
        tick();
        verifier(
                confirmations.isEmpty() && terminal.stock == 936 && reception == null,
                "la réponse en retard n’est pas attribuée à un arbre différent");
    }

    private static void verifier(boolean vrai, String raison) {
        if (!vrai) throw new AssertionError(raison);
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        ACCES = accesFixture();
        switch (args[0]) {
            case "quantites" -> entierDemiEtUnites();
            case "capacites" -> taillesEtCapacite();
            case "contexte" -> choixServeurEtAnnulation();
            case "confirmation" -> confirmationsPartiellesEtRefus();
            case "reception" -> receptionParLeSuivi();
            case "fermeture" -> receptionApresEchap();
            case "paquets-fermes" -> confirmationAncienneFenetre();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
