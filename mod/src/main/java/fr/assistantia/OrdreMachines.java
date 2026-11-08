package fr.assistantia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Présentation pure des opérations restantes, sans réservation ni transfert dans le jeu. */
public final class OrdreMachines {
    private static final int MAX_NOEUDS = 4096;
    private static final int MAX_LIENS = 16384;
    private static final int MAX_REGLES = 16384;
    private static final int MAX_STOCKS = 8192;
    private static final long MAX_COUT_FLOT = 25_000;
    private static final long MAX_PREDICATS = 4_000_000;

    private static final class LimiteAllocation extends RuntimeException {}

    public enum Origine {
        POCHES,
        MACHINE,
        SAC,
        COFFRE,
        DEPLACER
    }

    public enum Groupe {
        PRET,
        PREPARER,
        EN_COURS,
        ETAPES
    }

    public enum Mode {
        PRET,
        PREPARER,
        EN_COURS,
        INCONNU,
        BLOQUE
    }

    /** Les quantités conservent l'unité de la ressource (objets ou mB), sans conversion. */
    public record Regle<T>(
            Predicate<T> accepte, int quantite, boolean reutilisable, String nom, Object partage) {
        public Regle {
            Objects.requireNonNull(accepte);
            Objects.requireNonNull(nom);
            if (quantite < 0) throw new IllegalArgumentException("Quantité négative");
            if (partage == null) partage = new Object();
        }

        /** Sans clé explicite, deux règles ne supposent pas partager le même outil. */
        public Regle(Predicate<T> accepte, int quantite, boolean reutilisable, String nom) {
            this(accepte, quantite, reutilisable, nom, new Object());
        }
    }

    /** Sources physiques disjointes et attestées ; ni historique ni sorties attendues. */
    public record StockSource<T>(PlanRetrait.Stock<T> stock, Origine origine) {
        public StockSource {
            Objects.requireNonNull(stock);
            Objects.requireNonNull(origine);
        }
    }

    /** Les préconditions natives restent l'autorité, même si les ingrédients sont présents. */
    public record Condition(Mode mode, String raison) {
        public Condition {
            Objects.requireNonNull(mode);
            raison = raison == null ? "" : raison;
        }

        public static Condition prete() {
            return new Condition(Mode.PRET, "");
        }

        public static Condition preparer(String raison) {
            return new Condition(Mode.PREPARER, raison);
        }
    }

    public record Ligne<R>(
            PlanMachines.Operation<R> operation,
            Groupe groupe,
            Mode mode,
            int foisPretes,
            String raison,
            List<R> dependances,
            boolean conflit,
            boolean cycle) {
        public Ligne {
            dependances = List.copyOf(dependances);
        }
    }

    public record Resultat<R>(List<Ligne<R>> lignes, boolean cycle) {
        public Resultat {
            lignes = List.copyOf(lignes);
        }
    }

    private record Passage<R>(PlanMachines.Noeud<?, R> noeud, R parent) {}

    private record Graphe<R>(
            List<R> ordre,
            Map<R, Set<R>> dependances,
            Set<R> cycliques,
            Set<R> absentes,
            boolean limite) {}

    private record Outil(Object cle) {}

    private record Demande(Object cle, int quantite, BitSet sources, String nom) {}

    private record Composante(Map<Object, Demande> demandes, BitSet sources) {}

    private record Essai(
            boolean complet, List<Composante> remplacees, Composante composante, String manquant) {}

    private record Quantite(long quantite, String nom) {}

    private OrdreMachines() {}

    /**
     * Trie les opérations fournies sans changer leurs quantités ni en ajouter. Une ligne PRET
     * réserve virtuellement les ingrédients d'une seule fabrication ; elle ne garantit pas tout le
     * lot. Les clés de partage doivent désigner exactement le même catalyseur, variantes comprises.
     * Une condition inconnue ou un relevé non synchronisé ne peut jamais devenir prêt.
     */
    public static <T, R> Resultat<R> calculer(
            PlanMachines.Noeud<?, R> arbre,
            List<PlanMachines.Operation<R>> operations,
            Function<R, List<Regle<T>>> regles,
            List<StockSource<T>> stock,
            Function<R, Condition> conditions,
            boolean synchronise) {
        try {
            return calculerInterne(arbre, operations, regles, stock, conditions, synchronise);
        } catch (LimiteAllocation limite) {
            // Aucun effet réel n'a eu lieu : ne publier aucune recommandation partiellement
            // validée.
            return inconnu(operations, "Trop de variantes à vérifier · réduire l'arbre");
        }
    }

    private static <T, R> Resultat<R> calculerInterne(
            PlanMachines.Noeud<?, R> arbre,
            List<PlanMachines.Operation<R>> operations,
            Function<R, List<Regle<T>>> regles,
            List<StockSource<T>> stock,
            Function<R, Condition> conditions,
            boolean synchronise) {
        var parRecette = new LinkedHashMap<R, PlanMachines.Operation<R>>();
        for (var operation : operations) {
            if (operation.recette() == null || operation.fois() <= 0)
                throw new IllegalArgumentException("Opération restante invalide");
            if (parRecette.put(operation.recette(), operation) != null)
                throw new IllegalArgumentException("Recette restante dupliquée");
        }
        if (operations.isEmpty()) return new Resultat<>(List.of(), false);
        if (arbre == null || operations.size() > MAX_NOEUDS || stock.size() > MAX_STOCKS)
            return inconnu(operations, "Arbre ou stock non vérifiable");
        var graphe = graphe(arbre, parRecette.keySet());
        if (graphe.limite()) return inconnu(operations, "Arbre trop grand");
        if (!synchronise) return inconnu(operations, "Inventaire non synchronisé");

        var resultats = new HashMap<R, Ligne<R>>();
        var besoins = new HashMap<R, List<Regle<T>>>();
        var etats = new HashMap<R, Condition>();
        var allocation = new Allocation<T>(stock);
        int nombreRegles = 0;
        for (R recette : graphe.ordre()) {
            Condition condition = Objects.requireNonNull(conditions.apply(recette));
            etats.put(recette, condition);
            if (graphe.absentes().contains(recette)) {
                resultats.put(
                        recette,
                        ligne(
                                parRecette.get(recette),
                                graphe,
                                Mode.INCONNU,
                                "Recette absente de l'arbre actif",
                                false));
            } else if (graphe.cycliques().contains(recette)) {
                resultats.put(
                        recette,
                        ligne(
                                parRecette.get(recette),
                                graphe,
                                Mode.BLOQUE,
                                "Dépendance cyclique",
                                false));
            } else if (condition.mode() != Mode.PRET
                    && condition.mode() != Mode.PREPARER
                    && condition.mode() != Mode.INCONNU) {
                resultats.put(
                        recette,
                        ligne(
                                parRecette.get(recette),
                                graphe,
                                condition.mode(),
                                condition.raison(),
                                false));
            } else {
                var liste = List.copyOf(regles.apply(recette));
                nombreRegles += liste.size();
                if (nombreRegles > MAX_REGLES)
                    return inconnu(operations, "Trop d'ingrédients à vérifier");
                besoins.put(recette, liste);
            }
        }

        // D'abord les actions réellement disponibles : une manipulation ne leur prend pas le stock.
        for (R recette : graphe.ordre()) {
            if (resultats.containsKey(recette) || etats.get(recette).mode() != Mode.PRET) continue;
            var essai = allocation.essayer(besoins.get(recette), true, true);
            if (essai.complet()) {
                allocation.retenir(essai);
                resultats.put(
                        recette,
                        ligne(
                                parRecette.get(recette),
                                graphe,
                                Mode.PRET,
                                "Ingrédients alloués pour une fabrication",
                                false));
            }
        }
        // Les sacs/coffres restent à récupérer ; les lignes prêtes gardent leurs sources directes.
        for (R recette : graphe.ordre()) {
            if (resultats.containsKey(recette) || etats.get(recette).mode() == Mode.INCONNU)
                continue;
            var demandes = besoins.get(recette);
            var essai = allocation.essayer(demandes, false, true);
            if (essai.complet()) {
                allocation.retenir(essai);
                String raison = etats.get(recette).raison();
                if (raison.isBlank()) raison = "À récupérer dans les poches";
                resultats.put(
                        recette,
                        ligne(parRecette.get(recette), graphe, Mode.PREPARER, raison, false));
            } else {
                var seul = allocation.essayer(demandes, false, false);
                boolean conflit = seul.complet();
                String raison =
                        conflit
                                ? "Stock déjà alloué : " + essai.manquant()
                                : (graphe.dependances().get(recette).isEmpty()
                                                ? "Manque : "
                                                : "Attend : ")
                                        + seul.manquant();
                resultats.put(
                        recette,
                        ligne(parRecette.get(recette), graphe, Mode.BLOQUE, raison, conflit));
            }
        }
        // Une machine non vérifiée n'autorise rien. Les manques physiques restent explicables,
        // sans prendre de ressources aux vraies actions disponibles.
        for (R recette : graphe.ordre()) {
            if (resultats.containsKey(recette)) continue;
            var seul = allocation.essayer(besoins.get(recette), false, false);
            String raison =
                    seul.complet()
                            ? etats.get(recette).raison()
                            : (graphe.dependances().get(recette).isEmpty()
                                            ? "Manque : "
                                            : "Attend : ")
                                    + seul.manquant();
            resultats.put(
                    recette,
                    ligne(
                            parRecette.get(recette),
                            graphe,
                            seul.complet() ? Mode.INCONNU : Mode.BLOQUE,
                            raison,
                            false));
        }
        var lignes = new ArrayList<Ligne<R>>();
        for (R recette : graphe.ordre()) lignes.add(resultats.get(recette));
        // Tri stable : l'ordre topologique est préservé à l'intérieur de chaque groupe.
        lignes.sort(Comparator.comparing(Ligne::groupe));
        return new Resultat<>(lignes, !graphe.cycliques().isEmpty());
    }

    private static <R> Ligne<R> ligne(
            PlanMachines.Operation<R> operation,
            Graphe<R> graphe,
            Mode mode,
            String raison,
            boolean conflit) {
        Groupe groupe =
                switch (mode) {
                    case PRET -> Groupe.PRET;
                    case PREPARER -> Groupe.PREPARER;
                    case EN_COURS -> Groupe.EN_COURS;
                    default -> Groupe.ETAPES;
                };
        return new Ligne<>(
                operation,
                groupe,
                mode,
                mode == Mode.PRET ? 1 : 0,
                raison,
                new ArrayList<>(graphe.dependances().get(operation.recette())),
                conflit,
                graphe.cycliques().contains(operation.recette()));
    }

    private static <R> Resultat<R> inconnu(
            List<PlanMachines.Operation<R>> operations, String raison) {
        return new Resultat<>(
                operations.stream()
                        .map(
                                o ->
                                        new Ligne<>(
                                                o,
                                                Groupe.ETAPES,
                                                Mode.INCONNU,
                                                0,
                                                raison,
                                                List.<R>of(),
                                                false,
                                                false))
                        .toList(),
                false);
    }

    private static <R> Graphe<R> graphe(PlanMachines.Noeud<?, R> arbre, Set<R> recettes) {
        var dependances = new LinkedHashMap<R, Set<R>>();
        var suivants = new LinkedHashMap<R, Set<R>>();
        var indice = new HashMap<R, Integer>();
        for (R recette : recettes) {
            indice.put(recette, indice.size());
            dependances.put(recette, new LinkedHashSet<>());
            suivants.put(recette, new LinkedHashSet<>());
        }
        var visites = new IdentityHashMap<PlanMachines.Noeud<?, R>, Set<R>>();
        var pile = new ArrayDeque<Passage<R>>();
        pile.push(new Passage<>(arbre, null));
        int liens = 0;
        var presentes = new HashSet<R>();
        while (!pile.isEmpty()) {
            var passage = pile.pop();
            var noeud = passage.noeud();
            if (!visites.computeIfAbsent(noeud, n -> new HashSet<>()).add(passage.parent()))
                continue;
            if (visites.size() > MAX_NOEUDS || ++liens > MAX_LIENS)
                return new Graphe<>(List.copyOf(recettes), dependances, Set.of(), Set.of(), true);
            R recette = noeud.recette();
            R courant = recettes.contains(recette) ? recette : null;
            if (courant != null) presentes.add(courant);
            if (courant != null && passage.parent() != null) {
                dependances.get(passage.parent()).add(courant);
                suivants.get(courant).add(passage.parent());
            }
            // Une recette déjà couverte coupe la dépendance ; ne pas réintroduire ses sous-crafts.
            R parent = recette == null ? passage.parent() : courant;
            for (int i = noeud.enfants().size() - 1; i >= 0; i--)
                pile.push(new Passage<>(noeud.enfants().get(i), parent));
        }
        var degres = new HashMap<R, Integer>();
        var prets =
                new PriorityQueue<R>(
                        Comparator.<R>comparingInt(r -> -suivants.get(r).size())
                                .thenComparingInt(indice::get));
        for (R recette : recettes) {
            degres.put(recette, dependances.get(recette).size());
            if (degres.get(recette) == 0) prets.add(recette);
        }
        var ordre = new ArrayList<R>();
        while (!prets.isEmpty()) {
            R recette = prets.remove();
            ordre.add(recette);
            for (R suivant : suivants.get(recette))
                if (degres.merge(suivant, -1, Integer::sum) == 0) prets.add(suivant);
        }
        // Le reliquat comprend le cycle et ses dépendants : aucune action positive pour ce groupe.
        var cycliques = new LinkedHashSet<R>();
        for (R recette : recettes)
            if (degres.get(recette) > 0) {
                cycliques.add(recette);
                ordre.add(recette);
            }
        var absentes = new HashSet<>(recettes);
        absentes.removeAll(presentes);
        return new Graphe<>(List.copyOf(ordre), dependances, cycliques, absentes, false);
    }

    /**
     * Réaffecte exactement les variantes des recommandations précédentes si elles se chevauchent.
     * Les branches disjointes ne reconstruisent pas le flot de toutes les autres opérations.
     */
    private static final class Allocation<T> {
        private final List<StockSource<T>> stock;
        private final Map<Integer, Composante> parSource = new HashMap<>();
        private final Map<Object, Composante> parOutil = new HashMap<>();
        private final Map<Regle<T>, BitSet> correspondances = new IdentityHashMap<>();
        private long coutFlot;
        private long predicats;

        Allocation(List<StockSource<T>> stock) {
            this.stock = List.copyOf(stock);
        }

        Essai essayer(List<Regle<T>> regles, boolean direct, boolean retenues) {
            var demandes = new LinkedHashMap<Object, Demande>();
            for (var regle : regles) {
                if (regle.quantite() == 0) continue;
                var sources =
                        (BitSet) correspondances.computeIfAbsent(regle, this::sources).clone();
                if (direct)
                    for (int i = sources.nextSetBit(0); i >= 0; i = sources.nextSetBit(i + 1))
                        if (!directe(stock.get(i).origine())) sources.clear(i);
                Object cle = regle.reutilisable() ? new Outil(regle.partage()) : new Object();
                var demande = new Demande(cle, regle.quantite(), sources, regle.nom());
                // Les règles déjà normalisées peuvent partager un outil dans la même recette.
                demandes.merge(cle, demande, Allocation::partager);
            }
            var sources = union(demandes);
            var remplacees = new ArrayList<Composante>();
            if (retenues) {
                // Les composantes retenues sont disjointes. L'index retrouve celles que cette
                // recette relie sans parcourir toutes les branches indépendantes.
                var vues = new IdentityHashMap<Composante, Boolean>();
                for (int i = sources.nextSetBit(0); i >= 0; i = sources.nextSetBit(i + 1)) {
                    var precedente = parSource.get(i);
                    if (precedente != null && vues.put(precedente, true) == null)
                        remplacees.add(precedente);
                }
                for (var cle : demandes.keySet()) {
                    var precedente = parOutil.get(cle);
                    if (precedente != null && vues.put(precedente, true) == null)
                        remplacees.add(precedente);
                }
                for (var precedente : remplacees) {
                    precedente
                            .demandes()
                            .forEach((cle, d) -> demandes.merge(cle, d, Allocation::partager));
                    sources.or(precedente.sources());
                }
            }
            var ressources = new ArrayList<PlanRetrait.Stock<Integer>>();
            for (int i = sources.nextSetBit(0); i >= 0; i = sources.nextSetBit(i + 1))
                ressources.add(
                        new PlanRetrait.Stock<>(
                                i,
                                stock.get(i).stock().quantite(),
                                directe(stock.get(i).origine())));
            // Des besoins acceptant exactement les mêmes sources forment une seule capacité.
            // Cela évite un flot quadratique pour des centaines de recettes partageant un lingot.
            var quantites = new LinkedHashMap<BitSet, Quantite>();
            for (var demande : demandes.values())
                quantites.merge(
                        demande.sources(),
                        new Quantite(demande.quantite(), demande.nom()),
                        (a, b) ->
                                new Quantite(a.quantite() + b.quantite(), noms(a.nom(), b.nom())));
            var besoins = new ArrayList<PlanRetrait.Besoin<Integer>>();
            var noms = new ArrayList<String>();
            quantites.forEach(
                    (possibles, quantite) -> {
                        long reste = quantite.quantite();
                        while (reste > 0) {
                            int q = (int) Math.min(Integer.MAX_VALUE, reste);
                            besoins.add(new PlanRetrait.Besoin<>(possibles::get, q));
                            noms.add(quantite.nom());
                            reste -= q;
                        }
                    });
            coutFlot += (long) ressources.size() * besoins.size();
            if (coutFlot > MAX_COUT_FLOT) throw new LimiteAllocation();
            var resultat = PlanRetrait.calculer(ressources, besoins);
            String manquant = "";
            for (int i = 0; i < besoins.size(); i++)
                if (resultat.fournis().get(i) < besoins.get(i).quantite()) {
                    manquant = noms.get(i);
                    break;
                }
            return new Essai(
                    resultat.manquants() == 0,
                    remplacees,
                    new Composante(demandes, sources),
                    manquant);
        }

        void retenir(Essai essai) {
            var composante = essai.composante();
            for (int i = composante.sources().nextSetBit(0);
                    i >= 0;
                    i = composante.sources().nextSetBit(i + 1)) parSource.put(i, composante);
            for (var cle : composante.demandes().keySet())
                if (cle instanceof Outil) parOutil.put(cle, composante);
        }

        private BitSet sources(Regle<T> regle) {
            var sources = new BitSet(stock.size());
            for (int i = 0; i < stock.size(); i++) {
                if (stock.get(i).stock().quantite() <= 0) continue;
                if (++predicats > MAX_PREDICATS) throw new LimiteAllocation();
                if (regle.accepte().test(stock.get(i).stock().objet())) sources.set(i);
            }
            return sources;
        }

        private static String noms(String a, String b) {
            if (a.equals(b) || a.endsWith("…")) return a;
            String noms = a + " / " + b;
            return noms.length() > 160 ? noms.substring(0, 157) + "…" : noms;
        }

        private static Demande partager(Demande a, Demande b) {
            // Une clé partagée ne peut élargir les variantes ni déplacer un outil des poches au
            // sac.
            var sources = (BitSet) a.sources().clone();
            sources.and(b.sources());
            return new Demande(a.cle(), Math.max(a.quantite(), b.quantite()), sources, a.nom());
        }

        private static BitSet union(Map<Object, Demande> demandes) {
            var sources = new BitSet();
            for (var demande : demandes.values()) sources.or(demande.sources());
            return sources;
        }

        private static boolean directe(Origine origine) {
            return origine == Origine.POCHES || origine == Origine.MACHINE;
        }
    }
}
