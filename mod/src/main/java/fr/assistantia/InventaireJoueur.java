package fr.assistantia;

import com.google.gson.JsonObject;
import fr.assistantia.compat.backpacks.SacsPortes;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Une vue commune au scan, aux objectifs et à la planification machine. Lue à chaque tick sans
 * copie tant qu'aucune case ne change ; inventaire_joueur.json n'est réécrit qu'au changement, et
 * confirmé toutes les deux secondes pour que l'assistant le sache récent.
 */
public final class InventaireJoueur {
    /** Confirmation d'un relevé inchangé ; Python le juge récent pendant 5 s. */
    static final long CONFIRMATION_MS = 2000;

    /** Écart minimal entre deux exports d'un inventaire qui change. */
    static final long ECART_MS = 500;

    /** Contrôle complet périodique : un NBT modifié sans remplacer la pile est rattrapé. */
    static final int VERIFICATION_TICKS = 20;

    /** Horloge des exports (remplaçable par les tests). */
    static java.util.function.LongSupplier horloge = System::currentTimeMillis;

    private static List<ItemStack> piles = List.of(), sacs = List.of(), base = List.of();
    private static boolean synchronise = true, connecte, synchroniseExporte;
    private static long dernierExport, revision, revisionExportee = -1;
    private static int ticks;
    private static Object connexion, dimensionExportee, joueurExporte;
    private static JsonObject inventaireExporte, sacsExportes;
    // Cases surveillées : pile du jeu, quantité et NBT vus au dernier relevé de la base.
    private static ItemStack[] cases = new ItemStack[0];
    private static int[] nombres = new int[0];
    private static CompoundTag[] etiquettes = new CompoundTag[0];

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
        long temps = horloge.getAsLong();
        if (mc.player == null || mc.level == null) {
            if (connecte) revision++;
            piles = sacs = base = List.of();
            cases = new ItemStack[0];
            inventaireExporte = sacsExportes = null;
            dimensionExportee = joueurExporte = null;
            revisionExportee = -1;
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
        var inventaire = mc.player.getInventory();
        ItemStack curseur = mc.player.containerMenu.getCarried();
        boolean baseChangee = false;
        if (nouvelleConnexion
                || !memesCases(inventaire, curseur)
                || ++ticks % VERIFICATION_TICKS == 0 && !conforme(inventaire, curseur)) {
            List<ItemStack> nouvelle = new ArrayList<>();
            inventaire.items.forEach(s -> ajouter(nouvelle, s));
            inventaire.armor.forEach(s -> ajouter(nouvelle, s));
            inventaire.offhand.forEach(s -> ajouter(nouvelle, s));
            ajouter(nouvelle, curseur);
            retenirCases(inventaire, curseur);
            // Même contenu : garder la même liste, que SacsPortes n'a alors pas à recompter.
            if (nouvelleConnexion || !memePiles(nouvelle, base)) {
                base = List.copyOf(nouvelle);
                baseChangee = true;
            }
        }
        var etat = SacsPortes.client().lire(mc.player, base, mc.getConnection(), temps);
        boolean modifie = nouvelleConnexion || synchronise != etat.synchronise();
        if (baseChangee || etat.objets() != sacs) {
            List<ItemStack> tout = new ArrayList<>(base.size() + etat.objets().size());
            tout.addAll(base);
            tout.addAll(etat.objets());
            if (!memePiles(tout, piles)) {
                piles = List.copyOf(tout);
                modifie = true;
            }
            sacs = etat.objets();
        }
        if (modifie) revision++;
        synchronise = etat.synchronise();
        connexion = mc.getConnection();
        exporter(mc, temps);
    }

    /** Au changement (au plus deux fois par seconde), sinon une confirmation toutes les 2 s. */
    private static void exporter(Minecraft mc, long temps) {
        if (temps - dernierExport < ECART_MS) return;
        Object dimension = mc.level.dimension();
        boolean change =
                revision != revisionExportee
                        || synchronise != synchroniseExporte
                        || dimension != dimensionExportee
                        || mc.player != joueurExporte;
        if (!change && temps - dernierExport < CONFIRMATION_MS) return;
        dernierExport = temps;
        if (revision != revisionExportee || inventaireExporte == null) {
            // Jamais modifiés ensuite : partagés sans copie par les confirmations suivantes.
            inventaireExporte = totaux(piles);
            sacsExportes = totaux(sacs);
            revisionExportee = revision;
        }
        synchroniseExporte = synchronise;
        dimensionExportee = dimension;
        joueurExporte = mc.player;
        var json = new JsonObject();
        json.addProperty("actif", true);
        json.addProperty("synchronise", synchronise);
        json.addProperty("t", temps);
        json.addProperty("joueur", mc.player.getName().getString());
        json.addProperty("dimension", mc.level.dimension().location().toString());
        json.add("inventaire", inventaireExporte);
        json.add("backpacks", sacsExportes);
        Pont.envoyerInventaire(json);
    }

    /** Les cases du jeu tiennent les mêmes piles, quantités et NBT qu'au dernier relevé. */
    private static boolean memesCases(Inventory inventaire, ItemStack curseur) {
        if (cases.length != taille(inventaire)) return false;
        int k = 0;
        for (var s : inventaire.items) if (!memeCase(k++, s)) return false;
        for (var s : inventaire.armor) if (!memeCase(k++, s)) return false;
        for (var s : inventaire.offhand) if (!memeCase(k++, s)) return false;
        return memeCase(k, curseur);
    }

    private static boolean memeCase(int k, ItemStack s) {
        if (s.isEmpty()) return cases[k] == null;
        return s == cases[k] && s.getCount() == nombres[k] && s.getTag() == etiquettes[k];
    }

    private static void retenirCases(Inventory inventaire, ItemStack curseur) {
        int n = taille(inventaire);
        if (cases.length != n) {
            cases = new ItemStack[n];
            nombres = new int[n];
            etiquettes = new CompoundTag[n];
        }
        int k = 0;
        for (var s : inventaire.items) retenir(k++, s);
        for (var s : inventaire.armor) retenir(k++, s);
        for (var s : inventaire.offhand) retenir(k++, s);
        retenir(k, curseur);
    }

    private static void retenir(int k, ItemStack s) {
        boolean vide = s.isEmpty();
        cases[k] = vide ? null : s;
        nombres[k] = vide ? 0 : s.getCount();
        etiquettes[k] = vide ? null : s.getTag();
    }

    private static int taille(Inventory inventaire) {
        return inventaire.items.size() + inventaire.armor.size() + inventaire.offhand.size() + 1;
    }

    /** Sans copie : chaque pile non vide du jeu correspond encore, dans l'ordre, à la base. */
    private static boolean conforme(Inventory inventaire, ItemStack curseur) {
        verifiees = 0;
        for (var s : inventaire.items) if (!correspond(s)) return false;
        for (var s : inventaire.armor) if (!correspond(s)) return false;
        for (var s : inventaire.offhand) if (!correspond(s)) return false;
        return correspond(curseur) && verifiees == base.size();
    }

    private static int verifiees;

    private static boolean correspond(ItemStack s) {
        if (s.isEmpty()) return true;
        if (verifiees >= base.size()) return false;
        var connue = base.get(verifiees++);
        return s.getItem() == connue.getItem()
                && s.getCount() == connue.getCount()
                && Objects.equals(s.getTag(), connue.getTag());
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
