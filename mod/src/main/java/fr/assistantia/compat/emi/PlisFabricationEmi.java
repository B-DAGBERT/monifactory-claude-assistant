package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.lang.reflect.Field;
import java.util.*;

/** Pliage réversible des seules branches dont les résultats sont déjà en fabrication. */
final class PlisFabricationEmi {
    private record Pli(Field champ, Object avant, Object impose) {}

    private record Demande(Object noeud, int quantite) {}

    private final Map<Object, Pli> plis = new IdentityHashMap<>();
    private final Set<Object> ouvertsParJoueur = Collections.newSetFromMap(new IdentityHashMap<>());
    private Object arbre;

    boolean actualiser(
            Object courant,
            List<PlanRetrait.Stock<EmiStack>> personnels,
            List<PlanRetrait.Stock<EmiStack>> reservations)
            throws ReflectiveOperationException {
        boolean change = false;
        if (courant != arbre) {
            for (var e : plis.entrySet()) change |= restaurer(e.getKey(), e.getValue());
            plis.clear();
            ouvertsParJoueur.clear();
            arbre = courant;
        }
        Set<Object> necessaires = Collections.newSetFromMap(new IdentityHashMap<>());
        if (courant != null && !reservations.isEmpty()) {
            Object racine = courant.getClass().getField("goal").get(courant);
            long lots = courant.getClass().getField("batches").getLong(courant);
            var reel = new ArrayList<>(personnels);
            var attente = new ArrayList<>(reservations);
            List<Demande> niveau =
                    List.of(
                            new Demande(
                                    racine,
                                    PlanMachines.produit(
                                            racine.getClass().getField("amount").getLong(racine),
                                            lots)));
            int profondeur = 0, visites = 0;
            while (!niveau.isEmpty()) {
                if (++profondeur > 129 || (visites += niveau.size()) > 4096)
                    throw new IllegalArgumentException("Arbre EMI trop grand pour le pliage");
                var besoins = new ArrayList<PlanRetrait.Besoin<EmiStack>>();
                var outilsPresents = new ArrayList<Integer>();
                var outilsReserves = new ArrayList<Integer>();
                for (var demande : niveau) {
                    var ingredient =
                            (EmiIngredient)
                                    demande.noeud()
                                            .getClass()
                                            .getField("ingredient")
                                            .get(demande.noeud());
                    boolean outil = reutilisable(demande.noeud());
                    var besoin =
                            new PlanRetrait.Besoin<EmiStack>(
                                    s ->
                                            ingredient.getEmiStacks().stream()
                                                    .anyMatch(v -> ComparaisonEmi.meme(s, v)),
                                    outil ? 0 : demande.quantite());
                    besoins.add(besoin);
                    // Un catalyseur peut servir à plusieurs branches : observer avant toute
                    // consommation du niveau, comme le plan métier, sans débiter cet usage.
                    int present = outil ? disponible(reel, besoin, demande.quantite()) : 0;
                    outilsPresents.add(present);
                    outilsReserves.add(
                            outil ? disponible(attente, besoin, demande.quantite() - present) : 0);
                }
                var disponibles = PlanRetrait.calculer(reel, besoins);
                debiter(reel, disponibles.utilises());
                var restants = new ArrayList<PlanRetrait.Besoin<EmiStack>>();
                for (int i = 0; i < besoins.size(); i++)
                    restants.add(
                            new PlanRetrait.Besoin<>(
                                    besoins.get(i).accepte(),
                                    besoins.get(i).quantite() - disponibles.fournis().get(i)));
                var reserves = PlanRetrait.calculer(attente, restants);
                debiter(attente, reserves.utilises());
                var suite = new ArrayList<Demande>();
                for (int i = 0; i < niveau.size(); i++) {
                    Object node = niveau.get(i).noeud();
                    var type = node.getClass();
                    int reserve = reserves.fournis().get(i) + outilsReserves.get(i);
                    int manque =
                            niveau.get(i).quantite()
                                    - disponibles.fournis().get(i)
                                    - outilsPresents.get(i)
                                    - reserve;
                    Object raw = type.getField("children").get(node);
                    List<?> enfants = raw instanceof List<?> liste ? liste : List.of();
                    if (manque <= 0) {
                        if (reserve > 0 && !enfants.isEmpty()) necessaires.add(node);
                        continue;
                    }
                    long production = Math.max(1, type.getField("divisor").getLong(node));
                    long fabrications = manque / production + (manque % production == 0 ? 0 : 1);
                    for (Object enfant : enfants) {
                        var t = enfant.getClass();
                        boolean outil = reutilisable(enfant);
                        suite.add(
                                new Demande(
                                        enfant,
                                        PlanMachines.produit(
                                                t.getField("amount").getLong(enfant),
                                                outil ? 1 : fabrications)));
                    }
                }
                niveau = suite;
            }
        }
        for (var it = plis.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!necessaires.contains(e.getKey())) {
                change |= restaurer(e.getKey(), e.getValue());
                it.remove();
            } else if (e.getValue().champ().get(e.getKey()) != e.getValue().impose()) {
                // L'utilisateur a rouvert cette branche : conserver son choix pendant ce lot.
                ouvertsParJoueur.add(e.getKey());
                it.remove();
            }
        }
        ouvertsParJoueur.retainAll(necessaires);
        for (Object node : necessaires) {
            if (plis.containsKey(node) || ouvertsParJoueur.contains(node)) continue;
            Field champ = node.getClass().getField("state");
            Object avant = champ.get(node);
            Object ouvert = champ.getType().getField("EXPANDED").get(null);
            if (avant != ouvert) continue; // Préserver aussi les branches ignorées par le joueur.
            Object ferme = champ.getType().getField("COLLAPSED").get(null);
            plis.put(node, new Pli(champ, avant, ferme));
            champ.set(node, ferme);
            change = true;
        }
        return change;
    }

    private static boolean reutilisable(Object node) throws ReflectiveOperationException {
        var type = node.getClass();
        return type.getField("catalyst").getBoolean(node)
                || type.getField("consumeChance").getFloat(node) == 0;
    }

    private static int disponible(
            List<PlanRetrait.Stock<EmiStack>> stock,
            PlanRetrait.Besoin<EmiStack> besoin,
            int maximum) {
        long quantite = 0;
        for (var s : stock)
            if (besoin.accepte().test(s.objet())) {
                quantite += Math.max(0, s.quantite());
                if (quantite >= maximum) return maximum;
            }
        return (int) quantite;
    }

    private static boolean restaurer(Object node, Pli pli) throws ReflectiveOperationException {
        if (pli.champ().get(node) != pli.impose()) return false;
        pli.champ().set(node, pli.avant());
        return true;
    }

    private static void debiter(List<PlanRetrait.Stock<EmiStack>> stock, List<Integer> utilises) {
        for (int i = 0; i < stock.size(); i++) {
            var s = stock.get(i);
            stock.set(i, new PlanRetrait.Stock<>(s.objet(), s.quantite() - utilises.get(i), true));
        }
    }
}
