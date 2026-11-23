package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreCollecte;
import fr.assistantia.PlanMachines;
import fr.assistantia.MemoireCollecte;
import fr.assistantia.RessourceCollecte;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidUtil;

/** Une lecture de l'arbre natif pour le scan des coffres et le plan des machines. */
public record InstantaneArbreEmi(
        ArbreCollecte.Noeud collecte,
        PlanMachines.Noeud<EmiStack, EmiRecipe> machines,
        long lots,
        List<EmiStack> variantes,
        List<EmiStack> fluides,
        List<String> cles,
        List<String> cible) {

    private record Lecture(
            ArbreCollecte.Noeud collecte,
            PlanMachines.Noeud<EmiStack, EmiRecipe> machines,
            CleBranche branche) {}

    /** L'identité de recette et le détail des choix EMI doivent être communs aux deux branches. */
    private record RecetteNative(EmiRecipe recette) {
        @Override
        public boolean equals(Object autre) {
            return autre instanceof RecetteNative nativeEmi && recette == nativeEmi.recette;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(recette);
        }
    }

    private record CleBranche(
            RecetteNative recette,
            List<String> variantes,
            long quantite,
            long production,
            boolean reutilisable,
            float chance,
            List<CleBranche> enfants) {
        CleBranche sansQuantite() {
            return new CleBranche(recette, variantes, 0, production, reutilisable, chance, enfants);
        }
    }

    private record NoeudLu(
            Object noeud,
            EmiRecipe recette,
            long quantite,
            long production,
            boolean reutilisable,
            List<String> variantes,
            int enfants) {}

    private static final AtomicLong revision = new AtomicLong();
    private static final AtomicLong revisionStructure = new AtomicLong();
    private static volatile boolean notificationNative;
    private static Object dernierArbre, derniereRacine;
    private static long derniereRevision, derniereRevisionStructure, derniereLecture;
    private static InstantaneArbreEmi dernier;
    private static List<NoeudLu> derniereStructure = List.of();

    /** La reconstruction native des enfants peut se faire sur le même objet arbre. */
    public static void arbreModifie() {
        notificationNative = true;
        revisionStructure.incrementAndGet();
        invalider();
    }

    /**
     * Le crochet natif a été vu sur un arbre temporaire d'EMI : le cache reste fiable, sans
     * invalider les calculs de l'arbre réel (voir {@link ArbreReel}).
     */
    public static void notificationDisponible() {
        notificationNative = true;
    }

    /** Un recalcul du scan ne change pas les choix natifs de l'arbre. */
    public static long revisionStructureProduction() {
        return revisionStructure.get();
    }

    public static long revisionProduction() {
        return revision.get();
    }

    static void invalider() {
        revision.incrementAndGet();
    }

    static InstantaneArbreEmi lire(Object arbre) throws ReflectiveOperationException {
        var racine = arbre.getClass().getField("goal").get(arbre);
        long lots = arbre.getClass().getField("batches").getLong(arbre);
        long version = revision.get(), structureNative = revisionStructure.get();
        long maintenant = System.nanoTime();
        // Repli pour une version d'EMI sur laquelle le point d'injection serait indisponible.
        if (dernier != null
                && dernierArbre == arbre
                && derniereRacine == racine
                && dernier.lots() == lots
                && derniereRevision == version
                && (notificationNative || maintenant - derniereLecture < 500_000_000L))
            return dernier;
        var variantes = new ArrayList<EmiStack>();
        var structure = new ArrayList<NoeudLu>();
        var lecture =
                lireNoeud(
                        racine,
                        null,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        new int[] {0},
                        0,
                        variantes,
                        structure);
        // Une relecture de secours ou du scan peut retrouver exactement le même plan.
        // Garder son identité pour les retraits en cours, dont les quantités restent valables.
        if (dernier == null
                || dernierArbre != arbre
                || dernier.lots() != lots
                || derniereRevisionStructure != structureNative
                || !derniereStructure.equals(structure)) {
            var cible = (EmiIngredient) racine.getClass().getField("ingredient").get(racine);
            dernier =
                    new InstantaneArbreEmi(
                            lecture.collecte(),
                            lecture.machines(),
                            lots,
                            variantes.stream().filter(s -> !(s.getKey() instanceof Fluid)).toList(),
                            variantes.stream().filter(s -> s.getKey() instanceof Fluid).toList(),
                            cles(variantes),
                            cles(cible.getEmiStacks()));
            derniereStructure = List.copyOf(structure);
        }
        dernierArbre = arbre;
        derniereRacine = racine;
        derniereRevision = version;
        derniereRevisionStructure = structureNative;
        derniereLecture = maintenant;
        return dernier;
    }

    boolean memeCollecte(InstantaneArbreEmi autre) {
        return this == autre
                || autre != null
                        && lots == autre.lots
                        && collecte.equals(autre.collecte)
                        && cles.equals(autre.cles)
                        && cible.equals(autre.cible);
    }

    boolean accepte(ItemStack item) {
        var pile = EmiStack.of(item);
        return variantes.stream().anyMatch(v -> ComparaisonEmi.meme(pile, v));
    }

    private static final java.util.Map<Comparison, Long> comparaisons =
            new java.util.WeakHashMap<>();
    private static long numeroComparaison;

    private static String cleObjet(EmiStack pile) {
        String base =
                RessourceCollecte.cleObjet(
                        pile.getId().toString(),
                        pile.getItemStack().hasTag() ? pile.getNbt() : null);
        // L'API EMI donne le comparateur installé à cette fonction. Conserver l'instance :
        // deux exemples identiques peuvent imposer des comparaisons différentes à une vraie pile.
        long[] regle = {0};
        pile.comparison(
                c -> {
                    if (c != Comparison.DEFAULT_COMPARISON)
                        regle[0] = comparaisons.computeIfAbsent(c, k -> ++numeroComparaison);
                    return c;
                });
        return regle[0] == 0 ? base : base + "|comparaison:" + regle[0];
    }

    /**
     * Les clés des variantes sont calculées une fois pour la recherche, hors du rendu des cases.
     */
    java.util.function.Function<ItemStack, String> objets(MemoireCollecte suivi) {
        record Variante(EmiStack pile, String cle) {}
        var parObjet = new java.util.HashMap<Object, List<Variante>>();
        for (var variante : variantes)
            parObjet.computeIfAbsent(variante.getKey(), k -> new ArrayList<>())
                    .add(new Variante(variante, cleObjet(variante)));
        return item -> {
            if (item.isEmpty()) return "";
            var candidats = parObjet.get(item.getItem());
            if (candidats == null) return "";
            var reel = EmiStack.of(item);
            String premiere = "";
            Set<String> compatibles = null;
            for (var attendu : candidats) {
                if (!ComparaisonEmi.meme(reel, attendu.pile())) continue;
                String cle = attendu.cle();
                if (premiere.isEmpty()) premiere = cle;
                else if (!premiere.equals(cle)) {
                    if (compatibles == null) {
                        compatibles = new LinkedHashSet<>();
                        compatibles.add(premiere);
                    }
                    compatibles.add(cle);
                }
            }
            return compatibles == null ? premiere : suivi.groupe(compatibles);
        };
    }

    RessourceCollecte fluide(ItemStack objet) {
        if (fluides.isEmpty()) return null;
        var fluide = FluidUtil.getFluidContained(objet).orElse(null);
        if (fluide == null || fluide.isEmpty()) return null;
        var reel = EmiStack.of(fluide.getFluid(), fluide.getTag(), fluide.getAmount());
        for (var attendu : fluides)
            if (ComparaisonEmi.meme(reel, attendu))
                return new RessourceCollecte(cleFluide(attendu), fluide.getAmount());
        return null;
    }

    private static String cleFluide(EmiStack pile) {
        return RessourceCollecte.cleFluide(pile.getId().toString(), pile.getNbt());
    }

    private static List<String> cles(List<EmiStack> stacks) {
        return stacks.stream()
                .map(s -> s.getKey() instanceof Fluid ? cleFluide(s) : cleObjet(s))
                .sorted()
                .toList();
    }

    private static boolean produitGaranti(
            EmiRecipe recette, List<EmiStack> variantes, long production) {
        if (recette == null || variantes.size() != 1 || production <= 1) return false;
        var produit = variantes.get(0);
        long encore = production;
        for (var sortie : recette.getOutputs())
            if (!sortie.isEmpty() && sortie.getChance() >= 1 && memeVariante(sortie, produit)) {
                if (sortie.getAmount() >= encore) return true;
                encore -= sortie.getAmount();
            }
        return false;
    }

    private static boolean memeVariante(EmiStack a, EmiStack b) {
        return a.getItemStack().isEmpty() == b.getItemStack().isEmpty()
                && a.getId().equals(b.getId())
                && Objects.equals(a.getNbt(), b.getNbt());
    }

    private static Lecture lireNoeud(
            Object node,
            EmiRecipe parent,
            Set<Object> chemin,
            int[] visites,
            int profondeur,
            List<EmiStack> variantesCollecte,
            List<NoeudLu> structure)
            throws ReflectiveOperationException {
        if (node == null || profondeur > 128 || ++visites[0] > 4096 || !chemin.add(node))
            throw new IllegalArgumentException("Arbre EMI invalide ou trop grand");
        try {
            var type = node.getClass();
            var ingredient = (EmiIngredient) type.getField("ingredient").get(node);
            float chance = type.getField("consumeChance").getFloat(node);
            boolean reutilisable = type.getField("catalyst").getBoolean(node) || chance == 0;
            if (ComparaisonEmi.circuitVirtuel(parent, ingredient, reutilisable)) return null;
            var recette = (EmiRecipe) type.getField("recipe").get(node);
            long quantite = type.getField("amount").getLong(node);
            long production = type.getField("divisor").getLong(node);
            var variantesMachine = new ArrayList<EmiStack>();
            var ids = new LinkedHashSet<String>();
            boolean outil = true;
            for (var pile : ingredient.getEmiStacks()) {
                var item = pile.getItemStack();
                var copie = pile.copy();
                variantesMachine.add(copie);
                if (item.isEmpty()) {
                    if (copie.getKey() instanceof Fluid) {
                        ids.add(cleFluide(copie));
                        variantesCollecte.add(copie);
                        outil = false;
                    }
                    continue;
                }
                ids.add(cleObjet(copie));
                variantesCollecte.add(copie);
                outil &=
                        item.isDamageableItem()
                                || !pile.getRemainder().isEmpty()
                                        && pile.getRemainder().getItemStack().is(item.getItem());
            }
            reutilisable |= outil && !ids.isEmpty();
            var collecte = new ArrayList<ArbreCollecte.Noeud>();
            var machines = new ArrayList<PlanMachines.Noeud<EmiStack, EmiRecipe>>();
            var branches = new ArrayList<CleBranche>();
            if (type.getField("children").get(node) instanceof List<?> enfants) {
                for (Object enfant : enfants) {
                    var lu =
                            lireNoeud(
                                    enfant,
                                    recette,
                                    chemin,
                                    visites,
                                    profondeur + 1,
                                    variantesCollecte,
                                    structure);
                    if (lu == null) continue;
                    collecte.add(lu.collecte());
                    machines.add(lu.machines());
                    branches.add(lu.branche());
                }
            }
            structure.add(
                    new NoeudLu(
                            node,
                            recette,
                            quantite,
                            production,
                            reutilisable,
                            cles(variantesMachine),
                            machines.size()));
            var variantesExactes =
                    variantesMachine.stream()
                            .map(
                                    s ->
                                            (s.getItemStack().isEmpty() ? "fluide|" : "objet|")
                                                    + s.getId()
                                                    + "|"
                                                    + s.getNbt())
                            .sorted()
                            .toList();
            var branche =
                    new CleBranche(
                            new RecetteNative(recette),
                            variantesExactes,
                            quantite,
                            production,
                            reutilisable,
                            chance,
                            List.copyOf(branches));
            var lot =
                    !reutilisable && produitGaranti(recette, variantesMachine, production)
                            ? branche.sansQuantite()
                            : null;
            return new Lecture(
                    new ArbreCollecte.Noeud(ids, quantite, production, reutilisable, collecte, lot),
                    new PlanMachines.Noeud<>(
                            pile ->
                                    variantesMachine.stream()
                                            .anyMatch(v -> ComparaisonEmi.meme(pile, v)),
                            quantite,
                            production,
                            reutilisable,
                            recette,
                            List.copyOf(machines),
                            lot),
                    branche);
        } finally {
            chemin.remove(node);
        }
    }
}
