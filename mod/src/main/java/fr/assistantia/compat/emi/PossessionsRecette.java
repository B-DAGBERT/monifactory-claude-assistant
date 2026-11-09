package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.CoffresConnus;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.Pont;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Ce que le joueur possède pour l'onglet Recette : machines posées (relevé local de 48 blocs,
 * relevé serveur de la Base, blocs déjà ouverts), objets portés ou rangés (inventaire, sac, coffres
 * connus) et sorties attendues des lots. Le relevé local avance d'une rangée de chunks par tick ;
 * les totaux ne sont recomposés qu'à leur changement.
 */
final class PossessionsRecette {
    private static final int RAYON_CHUNKS = 3, RAYON = 48, PAUSE_TICKS = 40;

    enum Possession {
        POSEE,
        RANGEE,
        AUCUNE,
        INCONNUE
    }

    /** Poste GregTech : type de recette, tension exigée et catégorie EMI de ses ateliers. */
    record PosteGT(Object type, long tension, String categorie) {}

    /** Poste d'une autre catégorie EMI (établi, four, machine d'un autre mod). */
    record PosteCategorie(String categorie) {}

    /** Ce que fait une machine GregTech : types de recettes, tension maximale, multibloc. */
    record DefinitionGT(Set<Object> types, long capacite, boolean multibloc) {
        boolean accepte(PosteGT poste) {
            return types.contains(poste.type()) && (multibloc || capacite >= poste.tension());
        }
    }

    /** Un atelier possible pour un poste, du moins puissant au plus puissant. */
    record Atelier(String id, ItemStack icone, String nom, long capacite) {}

    private static final DefinitionGT AUCUNE_DEFINITION = new DefinitionGT(Set.of(), 0, false);
    private static final Map<String, DefinitionGT> DEFINITIONS = new HashMap<>();
    private static final Map<String, List<Atelier>> ATELIERS = new HashMap<>();
    private static final Map<Object, Possession> POSSESSIONS = new HashMap<>();
    private static Object gestionAteliers;

    private static Set<String> proches = Set.of(), base = Set.of(), posees = Set.of();
    private static final Set<String> releve = new HashSet<>();
    private static int ligne = -1, pause, centreX, centreZ;
    private static Object sourceBase, connexion;
    private static Map<String, Long> stock = Map.of();
    private static long revision, revisionCoffres = -1, signatureInventaire, revisionCalculee = -1;
    private static Map<String, Integer> lotsConnus = Map.of(), outilsConnus = Map.of();
    private static List<String> gtPosees = List.of(), gtRangees = List.of();
    private static int ticks;

    private PossessionsRecette() {}

    static long revision() {
        return revision;
    }

    static Map<String, Long> stock() {
        return stock;
    }

    static Set<String> posees() {
        return posees;
    }

    /** Inventaire synchronisé et mémoire des coffres lue : un manque peut être annoncé. */
    static boolean complet() {
        return InventaireJoueur.disponible() && CoffresConnus.etat().pret();
    }

    static void vider() {
        proches = base = posees = Set.of();
        releve.clear();
        ligne = -1;
        pause = 0;
        stock = Map.of();
        signatureInventaire = 0;
        revisionCoffres = -1;
        lotsConnus = outilsConnus = Map.of();
        sourceBase = null;
        revision++;
    }

    static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (connexion != mc.getConnection()) {
            vider();
            connexion = mc.getConnection();
        }
        releverProches(mc);
        if (++ticks % 10 == 0) actualiserTotaux();
    }

    /** Une rangée de 7 chunks par tick, puis une pause : jamais tout le rayon dans un seul tick. */
    private static void releverProches(Minecraft mc) {
        if (ligne < 0) {
            if (pause-- > 0) return;
            BlockPos joueur = mc.player.blockPosition();
            centreX = joueur.getX() >> 4;
            centreZ = joueur.getZ() >> 4;
            releve.clear();
            ligne = 0;
        }
        BlockPos joueur = mc.player.blockPosition();
        int x = centreX - RAYON_CHUNKS + ligne;
        for (int z = centreZ - RAYON_CHUNKS; z <= centreZ + RAYON_CHUNKS; z++) {
            var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (var be : chunk.getBlockEntities().values()) {
                if (be.getBlockPos().distSqr(joueur) > RAYON * RAYON) continue;
                var id = ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock());
                if (id != null) releve.add(id.toString());
            }
        }
        if (++ligne > 2 * RAYON_CHUNKS) {
            ligne = -1;
            pause = PAUSE_TICKS;
            if (!releve.equals(proches)) {
                proches = Set.copyOf(releve);
                recomposerPosees();
            }
        }
    }

    private static void recomposerPosees() {
        Set<String> toutes = new HashSet<>(proches);
        toutes.addAll(base);
        toutes.addAll(CoffresConnus.etat().machines());
        if (!toutes.equals(posees)) {
            posees = Set.copyOf(toutes);
            revision++;
        }
    }

    private static void actualiserTotaux() {
        var vue = Pont.partie("ecran");
        var machines = vue.get("machines");
        if (machines != sourceBase) {
            sourceBase = machines;
            Set<String> ids = new HashSet<>();
            if (machines != null && machines.isJsonArray())
                for (var e : machines.getAsJsonArray())
                    if (e.isJsonObject()) {
                        var item = e.getAsJsonObject().get("item");
                        if (item != null && item.isJsonPrimitive()) ids.add(item.getAsString());
                    }
            base = Set.copyOf(ids);
            recomposerPosees();
        }
        var coffres = CoffresConnus.etat();
        long signature = signature(InventaireJoueur.piles());
        var lots = MachinesEmi.reservationsObjets();
        var outils = MachinesEmi.outilsObjets();
        if (signature == signatureInventaire
                && coffres.revision() == revisionCoffres
                && lots.equals(lotsConnus)
                && outils.equals(outilsConnus)) return;
        if (coffres.revision() != revisionCoffres) recomposerPosees();
        signatureInventaire = signature;
        revisionCoffres = coffres.revision();
        lotsConnus = lots;
        outilsConnus = outils;
        Map<String, Long> totaux = new HashMap<>(coffres.objets());
        for (ItemStack pile : InventaireJoueur.piles()) {
            if (pile.isEmpty()) continue;
            var id = ForgeRegistries.ITEMS.getKey(pile.getItem());
            if (id != null) totaux.merge(id.toString(), (long) pile.getCount(), Long::sum);
        }
        lots.forEach((id, n) -> totaux.merge(id, (long) n, Long::sum));
        outils.forEach((id, n) -> totaux.merge(id, (long) n, Long::sum));
        if (!totaux.equals(stock)) {
            stock = Map.copyOf(totaux);
            revision++;
        }
    }

    private static long signature(List<ItemStack> piles) {
        long h = piles.size();
        for (var pile : piles) {
            long e = 31L * System.identityHashCode(pile.getItem()) + pile.getCount();
            if (pile.hasTag()) e = 31 * e + pile.getTag().hashCode();
            h = h * 1_000_003L + e;
        }
        return h;
    }

    /** Définition GregTech d'un objet machine, mise en cache par identifiant. */
    static DefinitionGT definition(String id) {
        return DEFINITIONS.computeIfAbsent(id, PossessionsRecette::lireDefinition);
    }

    private static DefinitionGT lireDefinition(String id) {
        var rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || !machineGT(item.getClass())) return AUCUNE_DEFINITION;
        try {
            Object definition = item.getClass().getMethod("getDefinition").invoke(item);
            if (definition == null) return AUCUNE_DEFINITION;
            Object[] types =
                    (Object[]) definition.getClass().getMethod("getRecipeTypes").invoke(definition);
            int tier =
                    ((Number) definition.getClass().getMethod("getTier").invoke(definition))
                            .intValue();
            long capacite = 8L << (2 * Math.min(14, Math.max(0, tier)));
            // Les machines à vapeur acceptent les recettes jusqu'à 32 EU/t (GregTechMachines).
            if (id.toLowerCase(Locale.ROOT).contains("steam")) capacite = 32;
            boolean multibloc = definition.getClass().getName().contains("Multiblock");
            Set<Object> ensemble = Collections.newSetFromMap(new IdentityHashMap<>());
            if (types != null) for (Object t : types) if (t != null) ensemble.add(t);
            return new DefinitionGT(Collections.unmodifiableSet(ensemble), capacite, multibloc);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return AUCUNE_DEFINITION;
        }
    }

    private static boolean machineGT(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            if (c.getName().equals("com.gregtechceu.gtceu.api.item.MetaMachineItem")) return true;
        return false;
    }

    private static void calculer() {
        if (revisionCalculee == revision) return;
        revisionCalculee = revision;
        POSSESSIONS.clear();
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        for (String id : posees) if (!definition(id).types().isEmpty()) a.add(id);
        for (String id : stock.keySet()) if (!definition(id).types().isEmpty()) b.add(id);
        gtPosees = List.copyOf(a);
        gtRangees = List.copyOf(b);
    }

    /** Poste d'une recette pour l'analyse et l'ordre. */
    static Object poste(EmiRecipe recette) {
        String categorie = recette.getCategory().getId().toString();
        try {
            Object gt = GregTechMachines.recette(recette);
            if (gt != null) {
                Object type = gt.getClass().getField("recipeType").get(gt);
                Object energie = gt.getClass().getMethod("getInputEUt").invoke(gt);
                long tension =
                        ((Number) energie.getClass().getMethod("voltage").invoke(energie))
                                .longValue();
                return new PosteGT(type, tension, categorie);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new PosteCategorie(categorie);
        }
        return new PosteCategorie(categorie);
    }

    static boolean etabli(Object poste) {
        return poste instanceof PosteCategorie c && c.categorie().equals("minecraft:crafting");
    }

    /** Possession d'un poste : posé, rangé (inventaire, sac, coffre) ou absent. */
    static Possession possession(Object poste) {
        if (poste == null || etabli(poste)) return Possession.POSEE;
        calculer();
        var connue = POSSESSIONS.get(poste);
        if (connue != null) return connue;
        Possession resultat;
        if (poste instanceof PosteGT gt) {
            resultat = Possession.AUCUNE;
            for (String id : gtPosees)
                if (definition(id).accepte(gt)) {
                    resultat = Possession.POSEE;
                    break;
                }
            if (resultat == Possession.AUCUNE)
                for (String id : gtRangees)
                    if (definition(id).accepte(gt)) {
                        resultat = Possession.RANGEE;
                        break;
                    }
        } else {
            var ateliers = ateliers(poste);
            resultat = ateliers.isEmpty() ? Possession.INCONNUE : Possession.AUCUNE;
            for (var atelier : ateliers) {
                if (posees.contains(atelier.id())) {
                    resultat = Possession.POSEE;
                    break;
                }
                if (stock.getOrDefault(atelier.id(), 0L) > 0) resultat = Possession.RANGEE;
            }
        }
        POSSESSIONS.put(poste, resultat);
        return resultat;
    }

    /**
     * Une machine de ce type existe chez le joueur, mais trop faible : jamais de montée de tier.
     */
    static boolean sousTier(PosteGT poste) {
        calculer();
        for (var source : List.of(gtPosees, gtRangees))
            for (String id : source) {
                var d = definition(id);
                if (d.types().contains(poste.type()) && !d.accepte(poste)) return true;
            }
        return false;
    }

    /**
     * L'atelier que le joueur utiliserait : posé, sinon rangé, sinon le plus simple qui convient.
     */
    static Atelier atelier(Object poste) {
        calculer();
        if (poste instanceof PosteGT gt) {
            for (String id : gtPosees) if (definition(id).accepte(gt)) return depuis(id);
            for (String id : gtRangees) if (definition(id).accepte(gt)) return depuis(id);
        }
        var ateliers = ateliers(poste);
        for (var a : ateliers) if (posees.contains(a.id())) return a;
        for (var a : ateliers) if (stock.getOrDefault(a.id(), 0L) > 0) return a;
        return ateliers.isEmpty() ? null : ateliers.get(0);
    }

    private static Atelier depuis(String id) {
        var rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return new Atelier(id, ItemStack.EMPTY, id, 0);
        var pile = new ItemStack(item);
        return new Atelier(id, pile, pile.getHoverName().getString(), definition(id).capacite());
    }

    /** Ateliers EMI d'un poste qui savent faire la recette, du moins puissant au plus puissant. */
    static List<Atelier> ateliers(Object poste) {
        var gestion = EmiApi.getRecipeManager();
        if (gestion != gestionAteliers) {
            ATELIERS.clear();
            gestionAteliers = gestion;
        }
        String categorie =
                poste instanceof PosteGT gt
                        ? gt.categorie()
                        : poste instanceof PosteCategorie c ? c.categorie() : "";
        String cle = poste instanceof PosteGT gt ? categorie + "|" + gt.tension() : categorie;
        var connus = ATELIERS.get(cle);
        if (connus != null) return connus;
        List<Atelier> liste = new ArrayList<>();
        try {
            for (EmiRecipeCategory c : gestion.getCategories()) {
                if (!c.getId().toString().equals(categorie)) continue;
                for (var ingredient : gestion.getWorkstations(c))
                    for (EmiStack pile : ingredient.getEmiStacks()) {
                        if (pile.isEmpty() || !(pile.getKey() instanceof Item)) continue;
                        String id = pile.getId().toString();
                        long capacite = 0;
                        if (poste instanceof PosteGT gt) {
                            var d = definition(id);
                            if (!d.accepte(gt)) continue;
                            capacite = d.multibloc() ? Long.MAX_VALUE / 4 : d.capacite();
                        }
                        if (liste.stream().anyMatch(a -> a.id().equals(id))) continue;
                        var item = pile.getItemStack().copy();
                        item.setCount(1);
                        liste.add(new Atelier(id, item, item.getHoverName().getString(), capacite));
                    }
                break;
            }
        } catch (RuntimeException illisible) {
            liste.clear();
        }
        liste.sort(Comparator.comparingLong(Atelier::capacite));
        var resultat = List.copyOf(liste);
        if (ATELIERS.size() < 512) ATELIERS.put(cle, resultat);
        return resultat;
    }
}
