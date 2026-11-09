package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreCollecte;
import fr.assistantia.PlanMachines;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.item.ItemStack;

/** Une lecture de l'arbre natif pour le scan des coffres et le plan des machines. */
public record InstantaneArbreEmi(
        ArbreCollecte.Noeud collecte,
        PlanMachines.Noeud<EmiStack, EmiRecipe> machines,
        long lots,
        List<EmiStack> variantes,
        List<String> cles,
        List<String> cible) {

    private record Lecture(
            ArbreCollecte.Noeud collecte, PlanMachines.Noeud<EmiStack, EmiRecipe> machines) {}

    private static final AtomicLong revision = new AtomicLong();
    private static final AtomicLong revisionStructure = new AtomicLong();
    private static volatile boolean notificationNative;
    private static Object dernierArbre, derniereRacine;
    private static long derniereRevision, derniereLecture;
    private static InstantaneArbreEmi dernier;

    /** La reconstruction native des enfants peut se faire sur le même objet arbre. */
    public static void arbreModifie() {
        notificationNative = true;
        revisionStructure.incrementAndGet();
        invalider();
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
        long version = revision.get();
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
        var lecture =
                lireNoeud(
                        racine,
                        null,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        new int[] {0},
                        0,
                        variantes);
        var cible = (EmiIngredient) racine.getClass().getField("ingredient").get(racine);
        dernier =
                new InstantaneArbreEmi(
                        lecture.collecte(),
                        lecture.machines(),
                        lots,
                        List.copyOf(variantes),
                        cles(variantes),
                        cles(cible.getEmiStacks()));
        dernierArbre = arbre;
        derniereRacine = racine;
        derniereRevision = version;
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

    private static List<String> cles(List<EmiStack> stacks) {
        return stacks.stream().map(s -> s.getId() + "|" + s.getNbt()).sorted().toList();
    }

    private static Lecture lireNoeud(
            Object node,
            EmiRecipe parent,
            Set<Object> chemin,
            int[] visites,
            int profondeur,
            List<EmiStack> variantesCollecte)
            throws ReflectiveOperationException {
        if (node == null || profondeur > 128 || ++visites[0] > 4096 || !chemin.add(node))
            throw new IllegalArgumentException("Arbre EMI invalide ou trop grand");
        try {
            var type = node.getClass();
            var ingredient = (EmiIngredient) type.getField("ingredient").get(node);
            boolean reutilisable =
                    type.getField("catalyst").getBoolean(node)
                            || type.getField("consumeChance").getFloat(node) == 0;
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
                if (item.isEmpty()) continue;
                ids.add(copie.getId().toString());
                variantesCollecte.add(copie);
                outil &=
                        item.isDamageableItem()
                                || !pile.getRemainder().isEmpty()
                                        && pile.getRemainder().getItemStack().is(item.getItem());
            }
            reutilisable |= outil && !ids.isEmpty();
            var collecte = new ArrayList<ArbreCollecte.Noeud>();
            var machines = new ArrayList<PlanMachines.Noeud<EmiStack, EmiRecipe>>();
            if (type.getField("children").get(node) instanceof List<?> enfants) {
                for (Object enfant : enfants) {
                    var lu =
                            lireNoeud(
                                    enfant,
                                    recette,
                                    chemin,
                                    visites,
                                    profondeur + 1,
                                    variantesCollecte);
                    if (lu == null) continue;
                    collecte.add(lu.collecte());
                    machines.add(lu.machines());
                }
            }
            return new Lecture(
                    new ArbreCollecte.Noeud(ids, quantite, production, reutilisable, collecte),
                    new PlanMachines.Noeud<>(
                            pile ->
                                    variantesMachine.stream()
                                            .anyMatch(v -> ComparaisonEmi.meme(pile, v)),
                            quantite,
                            production,
                            reutilisable,
                            recette,
                            List.copyOf(machines)));
        } finally {
            chemin.remove(node);
        }
    }
}
