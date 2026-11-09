package fr.assistantia.compat.backpacks;

import com.mojang.logging.LogUtils;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Inventaire des seuls sacs portés. Les réponses natives restent liées à leur requête. Lu à chaque
 * tick : sans changement, rien n'est copié ni recompté et le même relevé est rendu.
 */
public final class SacsPortes {
    public record Etat(List<ItemStack> objets, boolean synchronise) {}

    public interface Reponse {
        UUID assistantia$id();

        CompoundTag assistantia$contenu();

        Object assistantia$connexion();

        void assistantia$connexion(Object connexion);
    }

    public interface Lecteur {
        /** Les sacs portés, tels que le jeu les tient : ils ne sont ni copiés ni modifiés. */
        List<ItemStack> portes(Player player) throws ReflectiveOperationException;

        /** Les piles affichées du sac ouvert (non copiées), ou null s'il n'est pas ouvert. */
        List<ItemStack> ouvert(Player player, UUID id) throws ReflectiveOperationException;

        CompoundTag donnees(UUID id) throws ReflectiveOperationException;

        void demander(UUID id) throws ReflectiveOperationException;
    }

    private static final SacsPortes CLIENT = new SacsPortes(new Natif());

    public static SacsPortes client() {
        return CLIENT;
    }

    public static void reception(UUID id, CompoundTag nbt) {
        CLIENT.accepter(id, nbt);
    }

    private static final class Releve {
        List<ItemStack> objets = List.of(); // copies privées, jamais modifiées ensuite
        List<UUID> imbriques = List.of(); // sacs contenus, dans l'ordre des objets
        long requete = -10000, recu = -1, revisionRequete = -1, revisionRecue = -1;
        boolean attente;
        Tag marqueur;

        void contenu(List<ItemStack> copies) {
            objets = copies;
            List<UUID> sacs = new ArrayList<>();
            for (var item : copies) {
                UUID id = identite(item);
                if (id != null) sacs.add(id);
            }
            imbriques = sacs.isEmpty() ? List.of() : sacs;
        }
    }

    private final Lecteur lecteur;
    private final Map<UUID, Releve> releves = new LinkedHashMap<>();
    private Object connexion, menu;
    // Base déjà lue : une même instance de liste a toujours le même contenu.
    private List<ItemStack> baseLue;
    private Map<String, Long> quantitesBase = Map.of();
    private final Map<UUID, ItemStack> racinesBase = new LinkedHashMap<>();
    private long revision, maintenant;
    private boolean erreurSignalee;
    // Parcours d'un tick, réutilisés ; le relevé n'est recomposé que si le parcours change.
    private final Map<UUID, ItemStack> racines = new LinkedHashMap<>();
    private final Set<UUID> vus = new HashSet<>();
    private final List<ItemStack> portesSeuls = new ArrayList<>();
    private final List<Object> parcours = new ArrayList<>(), parcoursCompose = new ArrayList<>();
    private Etat dernier = new Etat(List.of(), true);

    public SacsPortes(Lecteur lecteur) {
        this.lecteur = lecteur;
    }

    public static UUID identite(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag() || !stack.getTag().hasUUID("contentsUuid"))
            return null;
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.getNamespace().equals("sophisticatedbackpacks")
                ? stack.getTag().getUUID("contentsUuid")
                : null;
    }

    public void vider() {
        releves.clear();
        connexion = menu = null;
        revision = 0;
        baseLue = null;
        quantitesBase = Map.of();
        racinesBase.clear();
        parcoursCompose.clear();
        dernier = new Etat(List.of(), true);
    }

    private static Map<String, Long> quantites(List<ItemStack> items) {
        Map<String, Long> result = new HashMap<>();
        for (var s : items) {
            var id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id != null) result.merge(id + ":" + identite(s), (long) s.getCount(), Long::sum);
        }
        return result;
    }

    public boolean ouvertPorte(Player joueur) {
        try {
            for (UUID id : releves.keySet()) if (lecteur.ouvert(joueur, id) != null) return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    /** Les grosses piles de Sophisticated utilisent realCount, pas le byte Count de Minecraft. */
    public static List<ItemStack> decoder(CompoundTag contenu) {
        List<ItemStack> resultat = new ArrayList<>();
        var inventaire = contenu.getCompound("inventory");
        var items = inventaire.getList("Items", Tag.TAG_COMPOUND);
        Set<Integer> vus = new HashSet<>();
        int taille = inventaire.getInt("Size");
        for (int i = 0; i < Math.min(items.size(), 4096); i++) {
            var nbt = items.getCompound(i);
            int slot = nbt.getInt("Slot");
            if (slot < 0 || slot >= taille || !vus.add(slot)) continue;
            ItemStack item = ItemStack.of(nbt);
            if (nbt.contains("realCount", Tag.TAG_ANY_NUMERIC))
                item.setCount(Math.max(0, nbt.getInt("realCount")));
            if (!item.isEmpty()) resultat.add(item.copy());
        }
        return List.copyOf(resultat);
    }

    public void accepter(UUID id, CompoundTag nbt) {
        var r = releves.get(id);
        if (r == null || nbt == null) return;
        r.contenu(decoder(nbt));
        r.marqueur = nbt.get("inventory");
        r.recu = maintenant;
        // Une réponse partie avant le dernier déplacement d'inventaire ne valide pas ce
        // déplacement.
        if (r.attente) r.revisionRecue = r.revisionRequete;
        r.attente = false;
    }

    /**
     * La base ne doit plus être modifiée après l'appel : la même instance est considérée comme le
     * même contenu au tick suivant.
     */
    public Etat lire(Player joueur, List<ItemStack> base, Object connexion, long temps) {
        maintenant = temps;
        if (joueur == null) {
            vider();
            return new Etat(List.of(), true);
        }
        if (this.connexion != connexion) {
            vider();
            this.connexion = connexion;
        }
        boolean quantitesChangees = false;
        if (base != baseLue) {
            // L'usure, la charge ou le déplacement entre deux cases ne modifient pas les quantités.
            // Le contenu conserve malgré tout son NBT exact pour les comparaisons EMI.
            var quantites = quantites(base);
            if (!quantites.equals(quantitesBase)) {
                quantitesChangees = true;
                quantitesBase = quantites;
            }
            baseLue = base;
            racinesBase.clear();
            for (var item : base) {
                UUID id = identite(item);
                if (id != null) racinesBase.putIfAbsent(id, item);
            }
        }
        if (quantitesChangees || menu != joueur.containerMenu) {
            revision++;
            menu = joueur.containerMenu;
        }
        racines.clear();
        racines.putAll(racinesBase);
        try {
            portesSeuls.clear();
            for (var item : lecteur.portes(joueur)) {
                UUID id = identite(item);
                if (id != null && !racines.containsKey(id)) {
                    racines.put(id, item);
                    portesSeuls.add(item); // Curios / emplacement porté.
                }
            }
            vus.clear();
            parcours.clear();
            for (var item : portesSeuls) {
                parcours.add(item);
                parcours.add(item.getTag());
            }
            boolean complet = true;
            for (UUID id : racines.keySet()) complet &= lireSac(joueur, id, 0);
            releves.keySet().retainAll(vus);
            return etat(complet);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurSignalee) {
                LogUtils.getLogger().warn("Assistant : lecture des backpacks indisponible", e);
                erreurSignalee = true;
            }
            return new Etat(List.of(), false);
        }
    }

    /** Même parcours (mêmes piles portées, mêmes contenus) : même relevé, sans recopier. */
    private Etat etat(boolean complet) {
        List<ItemStack> objets = dernier.objets();
        if (!memeParcours()) {
            List<ItemStack> resultat = new ArrayList<>();
            for (var item : portesSeuls) resultat.add(item.copy());
            Set<UUID> composes = new HashSet<>();
            for (UUID id : racines.keySet()) composer(id, resultat, composes, 0);
            objets = List.copyOf(resultat);
            parcoursCompose.clear();
            parcoursCompose.addAll(parcours);
        }
        if (objets != dernier.objets() || complet != dernier.synchronise())
            dernier = new Etat(objets, complet);
        return dernier;
    }

    private boolean memeParcours() {
        if (parcours.size() != parcoursCompose.size()) return false;
        for (int i = 0; i < parcours.size(); i++)
            if (parcours.get(i) != parcoursCompose.get(i)) return false;
        return true;
    }

    /** L'ordre et les limites de lireSac, sans effet de bord : contenu puis sacs imbriqués. */
    private void composer(UUID id, List<ItemStack> resultat, Set<UUID> composes, int profondeur) {
        if (composes.contains(id) || profondeur > 8 || composes.size() >= 64) return;
        composes.add(id);
        Releve r = releves.get(id);
        if (r == null) return;
        for (var item : r.objets) {
            resultat.add(item);
            UUID sous = identite(item);
            if (sous != null) composer(sous, resultat, composes, profondeur + 1);
        }
    }

    private boolean lireSac(Player joueur, UUID id, int profondeur)
            throws ReflectiveOperationException {
        if (vus.contains(id)) return true;
        if (profondeur > 8 || vus.size() >= 64) return false;
        vus.add(id);
        Releve r = releves.computeIfAbsent(id, k -> new Releve());
        var ouvert = lecteur.ouvert(joueur, id);
        if (ouvert != null) {
            if (!memes(ouvert, r.objets)) r.contenu(copies(ouvert));
            r.revisionRecue = revision;
            r.recu = maintenant;
        } else if (r.attente || demandeDue(r)) {
            CompoundTag donnees = lecteur.donnees(id);
            // Repli pour les versions où le hook de réception n'est pas disponible.
            if (r.attente && donnees.get("inventory") != r.marqueur) accepter(id, donnees);
            // Une seule requête en vol : une réponse tardive ne doit pas valider la suivante.
            if (demandeDue(r)) {
                r.marqueur = donnees.get("inventory");
                r.requete = maintenant;
                r.revisionRequete = revision;
                r.attente = true;
                try {
                    lecteur.demander(id);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // L'envoi a échoué : aucune réponse ne viendra libérer cette attente.
                    r.attente = false;
                    throw e;
                }
            }
        }
        boolean complet = r.revisionRecue == revision && maintenant - r.recu < 8000;
        parcours.add(r.objets);
        for (UUID sous : r.imbriques) complet &= lireSac(joueur, sous, profondeur + 1);
        return complet;
    }

    private boolean demandeDue(Releve r) {
        return !r.attente
                && maintenant - r.requete >= 500
                && (r.revisionRecue < revision || maintenant - r.recu >= 2000);
    }

    private static boolean memes(List<ItemStack> affiches, List<ItemStack> connus) {
        int n = 0;
        for (var item : affiches) {
            if (item.isEmpty()) continue;
            if (n >= connus.size() || !ItemStack.matches(item, connus.get(n))) return false;
            n++;
        }
        return n == connus.size();
    }

    private static List<ItemStack> copies(List<ItemStack> affiches) {
        List<ItemStack> result = new ArrayList<>();
        for (var item : affiches) if (!item.isEmpty()) result.add(item.copy());
        return List.copyOf(result);
    }

    /**
     * Aucune extraction : lecture des API natives et demande de contenu normalement utilisée par
     * l'infobulle.
     */
    private static final class Natif implements Lecteur {
        private boolean charge, absent;
        private Method provider, parcourir, storage, donnees, envoyer;
        private Constructor<?> requete;
        private Class<?> consumer;
        private Object canal, action;
        private final List<ItemStack> portes = new ArrayList<>();

        private void charger() throws ReflectiveOperationException {
            if (charge) return;
            try {
                var type =
                        Class.forName(
                                "net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider");
                consumer = Class.forName(type.getName() + "$BackpackInventorySlotConsumer");
                provider = type.getMethod("get");
                parcourir = type.getMethod("runOnBackpacks", Player.class, consumer);
                var st =
                        Class.forName(
                                "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
                storage = st.getMethod("get");
                donnees = st.getMethod("getOrCreateBackpackContents", UUID.class);
                var packet =
                        Class.forName(
                                "net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler");
                canal = packet.getField("INSTANCE").get(null);
                envoyer = packet.getMethod("sendToServer", Object.class);
                requete =
                        Class.forName(
                                        "net.p3pp3rf1y.sophisticatedbackpacks.network.RequestBackpackInventoryContentsMessage")
                                .getConstructor(UUID.class);
                // Un seul relais, réutilisé à chaque tick.
                action =
                        Proxy.newProxyInstance(
                                consumer.getClassLoader(),
                                new Class<?>[] {consumer},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("accept")) {
                                        portes.add((ItemStack) args[0]);
                                        return false;
                                    }
                                    if (method.getName().equals("toString"))
                                        return "AssistantBackpacks";
                                    if (method.getName().equals("hashCode"))
                                        return System.identityHashCode(proxy);
                                    if (method.getName().equals("equals")) return proxy == args[0];
                                    return null;
                                });
            } catch (ClassNotFoundException e) {
                absent = true;
            }
            charge = true;
        }

        /** Valable jusqu'à l'appel suivant (liste réutilisée). */
        public List<ItemStack> portes(Player p) throws ReflectiveOperationException {
            charger();
            if (absent) return List.of();
            portes.clear();
            parcourir.invoke(provider.invoke(null), p, action);
            return portes;
        }

        public CompoundTag donnees(UUID id) throws ReflectiveOperationException {
            charger();
            return absent
                    ? new CompoundTag()
                    : (CompoundTag) donnees.invoke(storage.invoke(null), id);
        }

        public void demander(UUID id) throws ReflectiveOperationException {
            charger();
            if (!absent) envoyer.invoke(canal, requete.newInstance(id));
        }

        public List<ItemStack> ouvert(Player p, UUID id) throws ReflectiveOperationException {
            var menu = p.containerMenu;
            if (!menu.getClass()
                    .getName()
                    .equals("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer"))
                return null;
            var wrapper = menu.getClass().getMethod("getStorageWrapper").invoke(menu);
            var uuid =
                    (Optional<?>) wrapper.getClass().getMethod("getContentsUuid").invoke(wrapper);
            if (!uuid.filter(id::equals).isPresent()) return null;
            var inv =
                    (IItemHandler)
                            wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
            List<ItemStack> result = new ArrayList<>();
            for (int i = 0; i < Math.min(4096, inv.getSlots()); i++) {
                var pile = inv.getStackInSlot(i);
                if (!pile.isEmpty()) result.add(pile);
            }
            return result;
        }
    }
}
