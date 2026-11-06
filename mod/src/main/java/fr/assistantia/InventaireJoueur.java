package fr.assistantia;

import com.google.gson.JsonObject;
import fr.assistantia.compat.backpacks.SacsPortes;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Une vue commune au scan, aux objectifs et à la planification machine. */
public final class InventaireJoueur {
    private static List<ItemStack> piles = List.of(), sacs = List.of();
    private static boolean synchronise = true, connecte;
    private static long dernierExport;
    private static long revision;
    private static Object connexion;

    /** Un nouveau monde ne peut pas utiliser la dernière vue de la connexion précédente. */
    public static boolean disponible() {
        var mc = Minecraft.getInstance();
        return connecte && synchronise && mc.player != null && mc.getConnection() == connexion;
    }

    public static List<ItemStack> piles() {
        return piles;
    }

    public static List<ItemStack> sacs() {
        return sacs;
    }

    static long revision() {
        return revision;
    }

    public static boolean synchronise() {
        return synchronise;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        long temps = System.currentTimeMillis();
        if (mc.player == null || mc.level == null) {
            if (connecte) revision++;
            piles = sacs = List.of();
            synchronise = true;
            SacsPortes.client().vider();
            if (connecte) {
                var j = new JsonObject();
                j.addProperty("actif", false);
                Pont.envoyerInventaire(j);
            }
            connecte = false;
            connexion = null;
            return;
        }
        boolean nouvelleConnexion = !connecte || connexion != mc.getConnection();
        connecte = true;
        List<ItemStack> base = new ArrayList<>();
        mc.player.getInventory().items.forEach(s -> ajouter(base, s));
        mc.player.getInventory().armor.forEach(s -> ajouter(base, s));
        mc.player.getInventory().offhand.forEach(s -> ajouter(base, s));
        ajouter(base, mc.player.containerMenu.getCarried());
        var etat = SacsPortes.client().lire(mc.player, base, mc.getConnection(), temps);
        sacs = etat.objets();
        base.addAll(sacs);
        if (nouvelleConnexion || synchronise != etat.synchronise() || !memePiles(base, piles))
            revision++;
        synchronise = etat.synchronise();
        piles = List.copyOf(base);
        connexion = mc.getConnection();
        if (temps - dernierExport < 500) return;
        dernierExport = temps;
        var json = new JsonObject();
        json.addProperty("actif", true);
        json.addProperty("synchronise", synchronise);
        json.addProperty("t", temps);
        json.addProperty("joueur", mc.player.getName().getString());
        json.addProperty("dimension", mc.level.dimension().location().toString());
        json.add("inventaire", totaux(piles));
        json.add("backpacks", totaux(sacs));
        Pont.envoyerInventaire(json);
    }

    private static boolean memePiles(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }

    private static void ajouter(List<ItemStack> liste, ItemStack s) {
        if (!s.isEmpty()) liste.add(s.copy());
    }

    private static JsonObject totaux(List<ItemStack> liste) {
        var result = new JsonObject();
        Map<String, Long> totaux = new LinkedHashMap<>();
        for (var s : liste) {
            var id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id != null)
                totaux.merge(
                        id.toString(),
                        (long) s.getCount(),
                        (a, b) -> Math.min(1_000_000_000L, a + b));
        }
        totaux.forEach(result::addProperty);
        return result;
    }
}
