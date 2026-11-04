package fr.assistantia.compat.backpacks;

import com.mojang.logging.LogUtils;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Inventaire des seuls sacs portés. Les réponses natives restent liées à leur requête. */
public final class SacsPortes {
    public record Etat(List<ItemStack> objets, boolean synchronise) {}

    public interface Reponse {
        UUID assistantia$id();

        CompoundTag assistantia$contenu();
    }

    public interface Lecteur {
        List<ItemStack> portes(Player player) throws ReflectiveOperationException;

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
        List<ItemStack> objets = List.of();
        long requete = -10000, recu = -1, revisionRequete = -1, revisionRecue = -1;
        boolean attente;
        Tag marqueur;
    }

    private final Lecteur lecteur;
    private final Map<UUID, Releve> releves = new LinkedHashMap<>();
    private Object connexion, menu;
    private List<ItemStack> precedent = List.of();
    private long revision, maintenant;
    private boolean erreurSignalee;

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
        precedent = List.of();
        connexion = menu = null;
        revision = 0;
    }

    private static Map<String, Long> quantites(List<ItemStack> items) {
        Map<String, Long> result = new HashMap<>();
        for (var s : items) {
            var id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id != null) result.merge(id + ":" + identite(s), (long) s.getCount(), Long::sum);
        }
        return result;
    }

    private static boolean meme(List<ItemStack> a, List<ItemStack> b) {
        // L'usure, la charge ou le déplacement entre deux cases ne modifient pas les quantités.
        // Le contenu conserve malgré tout son NBT exact pour les comparaisons EMI.
        return quantites(a).equals(quantites(b));
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
        r.objets = decoder(nbt);
        r.marqueur = nbt.get("inventory");
        r.recu = maintenant;
        // Une réponse partie avant le dernier déplacement d'inventaire ne valide pas ce
        // déplacement.
        if (r.attente) r.revisionRecue = r.revisionRequete;
        r.attente = false;
    }

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
        if (!meme(precedent, base) || menu != joueur.containerMenu) {
            revision++;
            precedent = base.stream().map(ItemStack::copy).toList();
            menu = joueur.containerMenu;
        }
        List<ItemStack> resultat = new ArrayList<>();
        Map<UUID, ItemStack> racines = new LinkedHashMap<>();
        Set<UUID> dansBase = new HashSet<>();
        for (var item : base) {
            UUID id = identite(item);
            if (id != null) {
                racines.putIfAbsent(id, item);
                dansBase.add(id);
            }
        }
        try {
            for (var item : lecteur.portes(joueur)) {
                UUID id = identite(item);
                if (id != null && !racines.containsKey(id)) {
                    racines.put(id, item);
                    if (!dansBase.contains(id))
                        resultat.add(item.copy()); // Curios / emplacement porté.
                }
            }
            Set<UUID> vus = new HashSet<>();
            boolean complet = true;
            for (var item : racines.values()) complet &= lireSac(joueur, item, resultat, vus, 0);
            releves.keySet().retainAll(vus);
            return new Etat(List.copyOf(resultat), complet);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurSignalee) {
                LogUtils.getLogger().warn("Assistant : lecture des backpacks indisponible", e);
                erreurSignalee = true;
            }
            return new Etat(List.of(), false);
        }
    }

    private boolean lireSac(
            Player joueur, ItemStack sac, List<ItemStack> resultat, Set<UUID> vus, int profondeur)
            throws ReflectiveOperationException {
        UUID id = identite(sac);
        if (id == null || vus.contains(id)) return true;
        if (profondeur > 8 || vus.size() >= 64) return false;
        vus.add(id);
        Releve r = releves.computeIfAbsent(id, k -> new Releve());
        var ouvert = lecteur.ouvert(joueur, id);
        if (ouvert != null) {
            r.objets = ouvert.stream().map(ItemStack::copy).toList();
            r.revisionRecue = revision;
            r.recu = maintenant;
        } else {
            CompoundTag donnees = lecteur.donnees(id);
            // Repli pour les versions où le hook de réception n'est pas disponible.
            if (r.attente && donnees.get("inventory") != r.marqueur) accepter(id, donnees);
            // Une seule requête en vol : une réponse tardive ne doit pas valider la suivante.
            if (!r.attente
                    && maintenant - r.requete >= 500
                    && (r.revisionRecue < revision || maintenant - r.recu >= 2000)) {
                r.marqueur = donnees.get("inventory");
                r.requete = maintenant;
                r.revisionRequete = revision;
                r.attente = true;
                lecteur.demander(id);
            }
        }
        boolean complet = r.revisionRecue == revision && maintenant - r.recu < 8000;
        for (var item : r.objets) {
            resultat.add(item.copy());
            if (identite(item) != null)
                complet &= lireSac(joueur, item, resultat, vus, profondeur + 1);
        }
        return complet;
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
        private Object canal;

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
            } catch (ClassNotFoundException e) {
                absent = true;
            }
            charge = true;
        }

        public List<ItemStack> portes(Player p) throws ReflectiveOperationException {
            charger();
            if (absent) return List.of();
            List<ItemStack> sacs = new ArrayList<>();
            Object action =
                    Proxy.newProxyInstance(
                            consumer.getClassLoader(),
                            new Class<?>[] {consumer},
                            (proxy, method, args) -> {
                                if (method.getName().equals("accept")) {
                                    sacs.add(((ItemStack) args[0]).copy());
                                    return false;
                                }
                                if (method.getName().equals("toString"))
                                    return "AssistantBackpacks";
                                if (method.getName().equals("hashCode"))
                                    return System.identityHashCode(proxy);
                                if (method.getName().equals("equals")) return proxy == args[0];
                                return null;
                            });
            parcourir.invoke(provider.invoke(null), p, action);
            return sacs;
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
            for (int i = 0; i < Math.min(4096, inv.getSlots()); i++)
                if (!inv.getStackInSlot(i).isEmpty()) result.add(inv.getStackInSlot(i).copy());
            return List.copyOf(result);
        }
    }
}
