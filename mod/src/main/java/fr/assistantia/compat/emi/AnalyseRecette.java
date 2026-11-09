package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import fr.assistantia.CoffresConnus;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.LotFabrication;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.Pont;
import fr.assistantia.VueRecette;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidUtil;

/**
 * Analyse de l'arbre choisi dans EMI Recipe Tree pour l'onglet Recette, le HUD et les rangs des
 * machines. Tout se calcule pendant le tick client, par petites tâches bornées : lecture de l'arbre
 * et ordre du plan à chaque changement, puis alternatives recette par recette (environ 1 ms par
 * tick). La vue publiée est immuable ; le rendu ne calcule rien.
 */
public final class AnalyseRecette {
    private static final long BUDGET_NS = 1_000_000L;

    /** Développements de chaînes au plus par tick (une évaluation en fait au plus 256). */
    static final int MAX_EXPANSIONS_TICK = 96;

    static final int MAX_CANDIDATS = 32,
            MAX_PRODUCTEURS = 1024,
            MAX_PRODUCTEURS_ESTIMES = 192,
            MAX_NOEUDS_ANALYSES = 256,
            MAX_ALTERNATIVES = 12,
            MAX_INTROUVABLES = 24,
            MAX_MACHINES = 6,
            MAX_REPERES = 16;

    /**
     * Identité de l'arbre réel analysé, sans la révision globale (augmentée aussi par les arbres
     * temporaires d'EMI) : chaque recalcul réel recrée la liste des enfants du but, et un choix de
     * recette pour le but change sa recette. Toutes les comparaisons sont des identités.
     */
    private static final class Cle {
        final Object arbre, racine, recetteRacine, enfants, gestion;
        final long lots;
        final int resolutions;

        Cle(
                Object arbre,
                Object racine,
                Object recetteRacine,
                Object enfants,
                long lots,
                int resolutions,
                Object gestion) {
            this.arbre = arbre;
            this.racine = racine;
            this.recetteRacine = recetteRacine;
            this.enfants = enfants;
            this.lots = lots;
            this.resolutions = resolutions;
            this.gestion = gestion;
        }

        boolean meme(Cle autre) {
            return memeStructure(autre) && lots == autre.lots;
        }

        /** Même arbre réel et mêmes choix ; seule la quantité (lots) peut différer. */
        boolean memeStructure(Cle autre) {
            return autre != null
                    && arbre == autre.arbre
                    && racine == autre.racine
                    && recetteRacine == autre.recetteRacine
                    && enfants == autre.enfants
                    && resolutions == autre.resolutions
                    && gestion == autre.gestion;
        }

        Object arbre() {
            return arbre;
        }

        Object racine() {
            return racine;
        }

        long lots() {
            return lots;
        }

        Object gestion() {
            return gestion;
        }
    }

    private static Cle cleActuelle(Object arbre) throws ReflectiveOperationException {
        return new Cle(
                arbre,
                ArbreRecette.racine(arbre),
                ArbreRecette.recetteRacine(arbre),
                ArbreRecette.enfantsRacine(arbre),
                ArbreRecette.lots(arbre),
                ArbreRecette.resolutions(arbre).size(),
                EmiApi.getRecipeManager());
    }

    private record Candidat(EmiRecipe recette, ChaineRecette.Chaine chaine, Object poste) {}

    private static final class Analyse {
        final ArbreRecette.Noeud noeud;
        final EmiStack produit;
        final boolean racine;
        double besoin;
        ChaineRecette.Chaine actuelle;
        final List<Candidat> candidats = new ArrayList<>();
        int restants = -1;
        // Décision mise en cache tant que les possessions, leur complétude et la liste des
        // candidats sont stables.
        long revisionDecision = -1;
        int candidatsDecision = -1;
        boolean completDecision;
        Candidat meilleure, avecMachine;
        Object posteManquant;

        Analyse(ArbreRecette.Noeud noeud, EmiStack produit, boolean racine) {
            this.noeud = noeud;
            this.produit = produit;
            this.racine = racine;
            this.besoin = noeud.besoin();
        }

        boolean pret() {
            return restants == 0;
        }
    }

    private sealed interface Tache permits Producteurs, Evaluation {}

    private record Producteurs(Analyse analyse) implements Tache {}

    private record Evaluation(Analyse analyse, EmiRecipe recette, boolean actuelle)
            implements Tache {}

    private record Proposition(Analyse analyse, Candidat candidat, Cle arbre) {}

    /**
     * {@code racine} : état exact du but avant « Appliquer », null pour un autre nœud. {@code
     * defaut} : recette par défaut d'EMI d'avant, null si elle n'a pas pu être changée.
     */
    private record Annulation(
            Object arbre,
            EmiIngredient cle,
            boolean avait,
            EmiRecipe precedente,
            EmiRecipe appliquee,
            String libelle,
            ArbreRecette.EtatRacine racine,
            ArbreRecette.Defaut defaut) {}

    private record CleReperes(Object etapes, Object machines, long lots, Object arbre) {}

    private static Cle cle;
    private static ArbreRecette.Lecture lecture;
    private static InstantaneArbreEmi instantane;
    private static ChaineRecette<EmiIngredient, EmiRecipe> evaluateur;
    private static final List<Analyse> analyses = new ArrayList<>();
    private static final ArrayDeque<Tache> taches = new ArrayDeque<>();
    private static int tachesTotales, tachesFaites;
    private static final Map<String, EmiStack> NOMS = new HashMap<>();
    private static final Map<String, FabricationFacile.Resultat> FACILES = new HashMap<>();
    private static final Map<String, List<FabricationFacile.Recette>> ETABLI = new HashMap<>();

    /** Recette (ou FALSE) qui fabriquerait une matière nouvelle, par génération de recettes EMI. */
    private static final Map<String, Object> PRODUCTEURS = new HashMap<>();

    private static final Map<String, Boolean> DECOMPOSITIONS = new HashMap<>();

    /**
     * Matières de base de l'arbre analysé (nœuds sans recette) : jamais fabriquées en estimation.
     */
    private static Set<String> basesArbre = Set.of();

    private static long revisionFaciles = -1;

    private static List<RangsRecette.Etape<EmiRecipe>> etapes = List.of();

    /** Étape en cours (étoile, HUD) : elle reste en tête jusqu'à ce qu'elle soit faite. */
    private static RangsRecette.Cle<EmiRecipe> enCours;

    private static Set<EmiRecipe> recettesPlan = Set.of();
    private static String clePlan = "";

    /** Raison pour laquelle le plan n'est pas (encore) calculé ; vide une fois calculé. */
    private static String planAttente = "";

    private static long generation;
    private static boolean erreurLectureSignalee, erreurDefautSignalee, diagnosticReperes;
    private static int attenteSuivi;
    private static Object gestionEtabli;
    private static final Map<EmiRecipe, ChaineRecette.Recette<EmiIngredient>> LECTURES =
            new IdentityHashMap<>();
    private static Map<String, Proposition> propositions = Map.of();
    private static final ArrayDeque<Annulation> annulations = new ArrayDeque<>();
    private static CleReperes cleReperes;
    private static String erreur = "";
    private static boolean sale, erreurSignalee, branche, connecte;
    private static long revisionVue, dernierePublication;
    private static int ticks, pause, prochainPlan;
    private static VueRecette.Etat etatPublie;
    private static java.lang.reflect.Method estCharge;

    // Caches de la vue : recalculés seulement si le plan ou les possessions changent.
    private static Object cleEtapes, cleIntrouvables;
    private static List<VueRecette.Etape> vuesEtapes = List.of();
    private static VueRecette.EtapeSuivante vueSuivante;
    private static List<VueRecette.Introuvable> vuesIntrouvables = List.of();

    private AnalyseRecette() {}

    // ---------- Tick ----------

    /** Appelé par {@link MachinesEmi#tick} à chaque tick client. Ne lève jamais d'exception. */
    static void tick() {
        if (pause > 0) {
            pause--;
            return;
        }
        try {
            if (!branche) {
                VueRecette.brancher(new Actions());
                branche = true;
            }
            avancer();
        } catch (RuntimeException | LinkageError e) {
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : onglet Recette indisponible", e);
                erreurSignalee = true;
            }
            oublier(true);
            pause = 100; // ne pas relire l'arbre à chaque tick après une erreur
            publierVide(
                    VueRecette.Etat.ERREUR,
                    "Analyse de l’arbre indisponible · détails dans le journal du jeu.");
        }
    }

    private static void avancer() {
        ticks++;
        var mc = Minecraft.getInstance();
        CoffresConnus.tick();
        if (mc.player == null || mc.level == null) {
            if (cle != null) oublier(true);
            if (connecte) PossessionsRecette.vider();
            connecte = false;
            annulations.clear();
            publierVide(VueRecette.Etat.SANS_MONDE, "");
            return;
        }
        connecte = true;
        if (!charge()) {
            if (cle != null) oublier(true);
            publierVide(VueRecette.Etat.CHARGEMENT, "");
            return;
        }
        PossessionsRecette.tick();
        Object arbre;
        try {
            arbre = ArbreEmi.consulte();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Arbre EMI illisible", e);
        }
        if (arbre == null) {
            if (cle != null) oublier(true);
            publierVide(VueRecette.Etat.SANS_ARBRE, "");
            return;
        }
        Cle actuelle;
        try {
            actuelle = cleActuelle(arbre);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Arbre EMI illisible", e);
        }
        // Un tick qui relit l'arbre ou refait le plan n'avance pas l'analyse des alternatives.
        boolean reconstruit = false;
        if (!actuelle.meme(cle)) {
            if (lecture != null && actuelle.memeStructure(cle)) nouvelleQuantite(actuelle);
            else nouvelArbre(actuelle);
            reconstruit = true;
        }
        if (lecture == null) {
            publierVide(VueRecette.Etat.ERREUR, erreur);
            return;
        }
        if (ticks >= prochainPlan && ticks % 10 == 0) reconstruit |= actualiserPlan();
        if (!reconstruit) {
            long limite = System.nanoTime() + BUDGET_NS;
            long expansions = evaluateur.expansions();
            while (!taches.isEmpty()
                    && System.nanoTime() < limite
                    && evaluateur.expansions() - expansions < MAX_EXPANSIONS_TICK) {
                executer(taches.pollFirst());
                tachesFaites++;
                sale = true;
            }
        }
        if (ticks % 10 == 5) actualiserReperes();
        long maintenant = System.currentTimeMillis();
        // Pendant l'analyse, une vue par seconde suffit ; ensuite chaque changement est publié.
        if (sale && (taches.isEmpty() || maintenant - dernierePublication >= 1000)) publier();
    }

    private static boolean charge() {
        try {
            if (estCharge == null)
                estCharge =
                        Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            return (boolean) estCharge.invoke(null);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * @param reperes vrai quand l'arbre disparaît : un simple changement d'arbre garde les anciens
     *     rangs jusqu'au prochain calcul, sans clignotement dans le monde
     */
    private static void oublier(boolean reperes) {
        cle = null;
        lecture = null;
        instantane = null;
        evaluateur = null;
        analyses.clear();
        taches.clear();
        tachesTotales = tachesFaites = 0;
        etapes = List.of();
        recettesPlan = Set.of();
        clePlan = "";
        planAttente = "";
        attenteSuivi = 0;
        LECTURES.clear();
        propositions = Map.of();
        cleReperes = null;
        cleEtapes = cleIntrouvables = null;
        vuesEtapes = List.of();
        vueSuivante = null;
        vuesIntrouvables = List.of();
        NOMS.clear();
        basesArbre = Set.of();
        enCours = null;
        if (reperes) {
            ReperesRecette.publier(Map.of());
            diagnosticReperes = false;
        }
    }

    private static void nouvelArbre(Cle suivante) {
        var arbrePrecedent = cle == null ? null : cle.arbre();
        boolean memeArbre = arbrePrecedent == suivante.arbre();
        // Même arbre réel modifié : les étapes et les repères restent visibles jusqu'au nouveau
        // plan, au lieu de disparaître pendant son calcul.
        var anciennes = RangsRecette.conservees(memeArbre, etapes);
        var anciennesRecettes = memeArbre ? recettesPlan : Set.<EmiRecipe>of();
        var etapeEnCours = memeArbre ? enCours : null;
        oublier(!memeArbre);
        etapes = anciennes;
        recettesPlan = anciennesRecettes;
        enCours = etapeEnCours;
        cle = suivante;
        generation++;
        erreur = "";
        if (!memeArbre) annulations.clear();
        try {
            lecture = ArbreRecette.lire(suivante.arbre());
            instantane = InstantaneArbreEmi.lire(suivante.arbre());
        } catch (IllegalArgumentException limite) {
            // Bornes de lecture (profondeur, nombre de nœuds) ou cycle : une limite, pas une panne.
            arbreIllisible(
                    "Arbre EMI trop grand ou cyclique : réduis la sélection dans Recipe Tree.");
            return;
        } catch (ReflectiveOperationException | RuntimeException illisible) {
            arbreIllisible("Arbre EMI illisible · détails dans le journal du jeu.");
            if (!erreurLectureSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : lecture de l'arbre EMI impossible", illisible);
                erreurLectureSignalee = true;
            }
            return;
        }
        Set<String> bases = new HashSet<>();
        for (var noeud : lecture.noeuds())
            if (noeud.recette() == null) bases.add(cle(noeud.ingredient()));
        basesArbre = Set.copyOf(bases);
        verifierGestion(); // estimations et décompositions de la génération EMI actuelle
        evaluateur = new ChaineRecette<>(new SourceEmi(suivante.arbre()));
        // La racine compte comme les autres : le but choisi peut lui-même avoir mieux.
        for (var choisi : SelectionAlternatives.choisir(descriptions(), MAX_NOEUDS_ANALYSES)) {
            var noeud = lecture.noeuds().get(choisi.index());
            var analyse =
                    new Analyse(noeud, noeud.ingredient().getEmiStacks().get(0), choisi.racine());
            analyse.besoin = choisi.besoin();
            analyses.add(analyse);
        }
        for (var analyse : analyses) taches.add(new Producteurs(analyse));
        tachesTotales = taches.size();
        // Une vraie modification vient du joueur (les arbres temporaires d'EMI ne comptent plus) :
        // le suivi relit l'arbre et le plan se refait tout de suite, sans attendre la limite de
        // coût réservée aux recalculs répétés du même arbre.
        if (memeArbre) MachinesEmi.relireArbre();
        if (etapes.isEmpty()) planAttente = "Calcul du plan…";
        actualiserPlan();
        sale = true;
    }

    /**
     * Arbre illisible : aucune étape ni aucun numéro d'avant ne reste affiché derrière l'erreur.
     */
    private static void arbreIllisible(String message) {
        lecture = null;
        erreur = message;
        etapes = List.of();
        recettesPlan = Set.of();
        ReperesRecette.publier(Map.of());
    }

    /**
     * Seule la quantité (lots) a changé, par exemple à la molette dans Recipe Tree : les chaînes et
     * les alternatives restent valables ; le plan suit au rythme habituel, sans disparaître.
     */
    private static void nouvelleQuantite(Cle suivante) {
        cle = suivante;
        generation++;
        propositions = Map.of();
        try {
            lecture = ArbreRecette.lire(suivante.arbre());
            instantane = InstantaneArbreEmi.lire(suivante.arbre());
        } catch (ReflectiveOperationException | RuntimeException illisible) {
            nouvelArbre(suivante); // lecture impossible : repartir proprement, avec son message
            return;
        }
        Map<EmiIngredient, Double> besoins = new HashMap<>();
        var noeuds = lecture.noeuds();
        for (var choisi : SelectionAlternatives.choisir(descriptions(), MAX_NOEUDS_ANALYSES))
            besoins.put(noeuds.get(choisi.index()).ingredient(), choisi.besoin());
        for (var analyse : analyses)
            analyse.besoin = besoins.getOrDefault(analyse.noeud.ingredient(), analyse.besoin);
        cleEtapes = cleIntrouvables = null;
        sale = true;
    }

    private static List<SelectionAlternatives.Noeud<EmiIngredient>> descriptions() {
        List<SelectionAlternatives.Noeud<EmiIngredient>> descriptions = new ArrayList<>();
        for (var noeud : lecture.noeuds())
            descriptions.add(
                    new SelectionAlternatives.Noeud<>(
                            noeud.ingredient(),
                            noeud.profondeur(),
                            noeud.recette() != null,
                            noeud.reutilisable(),
                            noeud.recette() != null && ArbreRecette.transparente(noeud.recette()),
                            noeud.recette() != null && assistant(noeud.recette()),
                            noeud.ingredient().getEmiStacks().size(),
                            noeud.besoin()));
        return descriptions;
    }

    private static boolean assistant(EmiRecipe r) {
        return r.getId() != null && r.getId().getNamespace().equals("assistantia");
    }

    // ---------- Alternatives ----------

    private record SourceEmi(Object arbre)
            implements ChaineRecette.Source<EmiIngredient, EmiRecipe> {
        @Override
        public EmiRecipe choix(EmiIngredient ingredient) {
            return ArbreRecette.choix(arbre, ingredient);
        }

        @Override
        public ChaineRecette.Recette<EmiIngredient> lire(EmiRecipe recette, EmiIngredient produit) {
            return AnalyseRecette.lire(recette, produit);
        }

        @Override
        public String cle(EmiIngredient ingredient) {
            return AnalyseRecette.cle(ingredient);
        }

        @Override
        public EmiRecipe producteur(EmiIngredient ingredient) {
            return AnalyseRecette.producteur(ingredient);
        }
    }

    /**
     * Recette qui fabriquerait une matière nouvelle d'une alternative, pour compter ses étapes et
     * ses matières. Reste une matière de base (null) : un fluide, une matière de base de l'arbre,
     * une matière déjà possédée, ou une matière sans recette sûre qui consomme quelque chose
     * (minerai, butin, générateur). Jamais une recette qui défait un objet fabriqué à partir de la
     * matière.
     */
    static EmiRecipe producteur(EmiIngredient ingredient) {
        var pile = premiere(ingredient);
        if (pile == null || pile.isEmpty() || pile.getKey() instanceof Fluid) return null;
        String cle = cle(ingredient);
        if (basesArbre.contains(cle)) return null;
        if (PossessionsRecette.complet()
                && PossessionsRecette.stock().getOrDefault(pile.getId().toString(), 0L) > 0)
            return null;
        verifierGestion();
        Object connu = PRODUCTEURS.get(cle);
        if (connu != null) return connu instanceof EmiRecipe r ? r : null;
        EmiRecipe choisie = null;
        int essais = 0; // borné : une matière très commune reste une matière de base
        for (var p : producteursSurs(pile, null, MAX_PRODUCTEURS_ESTIMES)) {
            if (p.cout() <= 0) continue; // générateur : ne consomme rien, matière brute
            if (++essais > 4) break;
            if (decomposition(p.recette(), pile)) continue;
            choisie = p.recette();
            break;
        }
        if (PRODUCTEURS.size() < 8192)
            PRODUCTEURS.put(cle, choisie == null ? Boolean.FALSE : choisie);
        return choisie;
    }

    /**
     * Recette qui défait un objet fabriqué à partir de son produit, comme extraire une batterie
     * pour récupérer sa coque : toutes les recettes sûres d'un des objets consommés utilisent le
     * produit.
     */
    static boolean decomposition(EmiRecipe recette, EmiStack produit) {
        String cleProduit = "objet:" + produit.getId();
        for (var entree : recette.getInputs()) {
            if (entree.isEmpty() || reutilisable(entree)) continue;
            if (ComparaisonEmi.circuitVirtuel(recette, entree, false)) continue;
            var pile = premiere(entree);
            if (pile == null || pile.isEmpty() || pile.getKey() instanceof Fluid) continue;
            String cle = cle(entree) + "|" + cleProduit;
            Boolean connu = DECOMPOSITIONS.get(cle);
            if (connu == null) {
                connu = faitDe(pile, produit);
                if (DECOMPOSITIONS.size() < 8192) DECOMPOSITIONS.put(cle, connu);
            }
            if (connu) return true;
        }
        return false;
    }

    /** Toutes les recettes sûres de {@code objet} (au moins une) consomment {@code produit}. */
    private static boolean faitDe(EmiStack objet, EmiStack produit) {
        List<EmiRecipe> recettes;
        try {
            recettes = EmiApi.getRecipeManager().getRecipesByOutput(objet);
        } catch (RuntimeException e) {
            return false;
        }
        int sures = 0, vues = 0;
        for (EmiRecipe r : recettes) {
            if (++vues > 256) return false; // matière très commune : jamais une décomposition
            if (sure(r, objet) <= 0) continue;
            sures++;
            boolean utilise = false;
            for (var entree : r.getInputs())
                if (!entree.isEmpty()
                        && !reutilisable(entree)
                        && entree.getEmiStacks().stream().anyMatch(s -> s.equals(produit))) {
                    utilise = true;
                    break;
                }
            if (!utilise) return false;
        }
        return sures > 0;
    }

    static String cle(EmiIngredient ingredient) {
        var piles = ingredient.getEmiStacks();
        if (piles.isEmpty()) return "vide";
        var pile = piles.get(0);
        String cle =
                (pile.getKey() instanceof Fluid ? ChaineRecette.FLUIDE : "objet:") + pile.getId();
        if (NOMS.size() < 8192) NOMS.putIfAbsent(cle, pile);
        return cle;
    }

    private static String nom(String cle) {
        var pile = NOMS.get(cle);
        if (pile == null) return cle.substring(cle.indexOf(':') + 1);
        return pile.getName().getString();
    }

    private static boolean reutilisable(EmiIngredient ingredient) {
        if (ingredient.getChance() == 0) return true;
        var piles = ingredient.getEmiStacks();
        if (piles.isEmpty()) return false;
        for (var pile : piles) if (!pile.getRemainder().equals(pile)) return false;
        return true;
    }

    /** Lecture d'une recette EMI pour le calcul des chaînes (circuits GT retirés). */
    static ChaineRecette.Recette<EmiIngredient> lire(EmiRecipe recette, EmiIngredient produit) {
        if (recette instanceof EmiResolutionRecipe resolution)
            return new ChaineRecette.Recette<>(
                    List.of(new ChaineRecette.Entree<>(resolution.stack, 1, false)),
                    1,
                    null,
                    0,
                    0,
                    true);
        double production = 0;
        for (EmiStack sortie : recette.getOutputs())
            if (sortie.equals(produit)) {
                float chance = sortie.getChance();
                production += sortie.getAmount() * (chance > 0 && chance < 1 ? chance : 1);
            }
        if (production <= 0) production = 1; // comme EMI : un diviseur nul vaut 1
        List<ChaineRecette.Entree<EmiIngredient>> entrees = new ArrayList<>();
        for (var entree : recette.getInputs()) {
            if (entree.isEmpty()) continue;
            boolean outil = reutilisable(entree);
            if (ComparaisonEmi.circuitVirtuel(recette, entree, outil)) continue;
            entrees.add(new ChaineRecette.Entree<>(entree, entree.getAmount(), outil));
        }
        for (var catalyseur : recette.getCatalysts()) {
            if (catalyseur.isEmpty() || ComparaisonEmi.circuitVirtuel(recette, catalyseur, true))
                continue;
            entrees.add(new ChaineRecette.Entree<>(catalyseur, catalyseur.getAmount(), true));
        }
        double eu = 0, duree = 0;
        try {
            Object gt = GregTechRecette.recette(recette);
            if (gt != null) {
                double[] cout = GregTechRecette.cout(gt);
                eu = cout[0];
                duree = cout[1];
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Énergie inconnue : seul critère de départage, jamais une raison de refuser.
        }
        return new ChaineRecette.Recette<>(
                entrees, production, PossessionsRecette.poste(recette), eu, duree, false);
    }

    private static void executer(Tache tache) {
        if (tache instanceof Producteurs p) {
            var liste = candidats(p.analyse());
            var analyse = p.analyse();
            analyse.restants = liste.size() + 1;
            taches.addLast(new Evaluation(analyse, analyse.noeud.recette(), true));
            for (var r : liste) taches.addLast(new Evaluation(analyse, r, false));
            tachesTotales += liste.size() + 1;
        } else if (tache instanceof Evaluation e) {
            var analyse = e.analyse();
            var chaine = evaluateur.evaluer(e.recette(), analyse.noeud.ingredient());
            if (e.actuelle()) analyse.actuelle = chaine;
            else if (chaine != null)
                analyse.candidats.add(
                        new Candidat(e.recette(), chaine, PossessionsRecette.poste(e.recette())));
            analyse.restants = Math.max(0, analyse.restants - 1);
        }
    }

    private record Prerang(EmiRecipe recette, double cout, String id) {}

    /**
     * Production certaine de {@code produit} par une vraie fabrication, ou 0 : butin, recyclage,
     * diagramme, recette désactivée, résolution de tag ou sortie seulement probable.
     */
    private static double sure(EmiRecipe r, EmiStack produit) {
        if (r == null || r instanceof EmiResolutionRecipe) return 0;
        var id = r.getId();
        if (id == null) return 0;
        String espace = id.getNamespace();
        if (espace.equals("emi_loot") || espace.equals("assistantia")) return 0;
        if (!r.supportsRecipeTree() || r.getCategory() == null) return 0;
        String categorie = r.getCategory().getId().toString();
        if (categorie.contains("recycling")
                || categorie.startsWith("emi:")
                || categorie.contains("diagram")) return 0;
        if (ArbreRecette.desactivee(r)) return 0;
        double production = 0;
        for (EmiStack sortie : r.getOutputs())
            if (sortie.equals(produit) && sortie.getChance() >= 1) production += sortie.getAmount();
        return production;
    }

    /** Producteurs exacts et sûrs du produit, les moins coûteux au premier niveau d'abord. */
    private static List<EmiRecipe> candidats(Analyse analyse) {
        List<EmiRecipe> liste = new ArrayList<>();
        int verifiees = 0;
        for (var p : producteursSurs(analyse.produit, analyse.noeud.recette(), MAX_PRODUCTEURS)) {
            if (liste.size() >= MAX_CANDIDATS || ++verifiees > 2 * MAX_CANDIDATS) break;
            // Défaire un objet fabriqué à partir du produit n'est jamais une meilleure façon.
            if (decomposition(p.recette(), analyse.produit)) continue;
            liste.add(p.recette());
        }
        return liste;
    }

    /** Recettes sûres du produit qui ne le consomment pas, triées par coût du premier niveau. */
    private static List<Prerang> producteursSurs(EmiStack produit, EmiRecipe exclue, int max) {
        List<EmiRecipe> tous;
        try {
            tous = EmiApi.getRecipeManager().getRecipesByOutput(produit);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Prerang> retenus = new ArrayList<>();
        int vus = 0;
        for (EmiRecipe r : tous) {
            if (++vus > max) break;
            if (r == exclue) continue;
            double production = sure(r, produit);
            if (production <= 0) continue; // sortie seulement probable : jamais proposée
            boolean consomme = false;
            double cout = 0;
            for (var entree : r.getInputs()) {
                if (entree.isEmpty()) continue;
                boolean outil = reutilisable(entree);
                if (ComparaisonEmi.circuitVirtuel(r, entree, outil) || outil) continue;
                if (entree.getEmiStacks().stream().anyMatch(s -> s.equals(produit)))
                    consomme = true;
                boolean fluide =
                        entree.getEmiStacks().stream().anyMatch(s -> s.getKey() instanceof Fluid);
                cout += fluide ? entree.getAmount() / 1000.0 : entree.getAmount();
            }
            if (consomme) continue; // consomme son propre produit
            retenus.add(new Prerang(r, cout / production, r.getId().toString()));
        }
        retenus.sort(Comparator.comparingDouble(Prerang::cout).thenComparing(Prerang::id));
        return retenus;
    }

    // ---------- Plan ----------

    /**
     * Refait l'ordre du plan si ses entrées ont changé, dans la limite de coût : jamais forcé.
     *
     * @return vrai si un calcul a eu lieu ; ce tick n'avance alors pas les alternatives
     */
    private static boolean actualiserPlan() {
        if (lecture == null) return false;
        if (!InventaireJoueur.synchronise()) {
            if (etapes.isEmpty()) attendre("Lecture du sac en cours…");
            return false;
        }
        // Toujours l'instantané de l'arbre actuel (mis en cache par InstantaneArbreEmi), jamais un
        // plus ancien.
        InstantaneArbreEmi vue;
        try {
            vue = InstantaneArbreEmi.lire(cle.arbre());
        } catch (ReflectiveOperationException | RuntimeException illisible) {
            if (etapes.isEmpty()) attendre("Arbre EMI illisible pour le plan.");
            return false;
        }
        instantane = vue;
        // Arbre suivi : reprendre les opérations du suivi (ses lots et ses crédits), y compris
        // après « Recalculer », seulement quand il a lu ce même instantané ; sinon attendre son
        // relevé, en gardant les étapes affichées.
        boolean suivi = MachinesEmi.arbreSuivi() == cle.arbre();
        List<PlanMachines.Operation<EmiRecipe>> operationsSuivies = null;
        switch (RangsRecette.source(suivi, MachinesEmi.instantaneSuivi(), vue, attenteSuivi, 3)) {
            case SUIVI -> {
                attenteSuivi = 0;
                operationsSuivies = MachinesEmi.operationsSuivies();
            }
            case ATTENDRE -> {
                attenteSuivi++;
                if (etapes.isEmpty()) attendre("Calcul du plan…");
                return false;
            }
            case LOCAL -> suivi = false; // arbre non suivi, ou le suivi ne le relit pas
        }
        String signature =
                System.identityHashCode(vue)
                        + "|"
                        + suivi
                        + "|"
                        + PossessionsRecette.revision()
                        + "|"
                        + (suivi ? System.identityHashCode(operationsSuivies) : 0)
                        + "|"
                        + InventaireJoueur.revision()
                        + "|"
                        + MachinesEmi.revisionLotsProduction()
                        + "|"
                        + CoffresConnus.etat().revision()
                        + "|"
                        + CoffresConnus.zone()
                        + "|"
                        + InventaireJoueur.disponible();
        if (signature.equals(clePlan)) return false;
        clePlan = signature;
        long debut = System.nanoTime();
        try {
            ordonner(vue, operationsSuivies, suivi);
        } finally {
            // Un grand plan coûte plusieurs millisecondes : le refaire moins souvent.
            long duree = System.nanoTime() - debut;
            prochainPlan = ticks + (duree > 5_000_000L ? 100 : duree > 1_500_000L ? 40 : 0);
        }
        return true;
    }

    /** Plan non calculé : l'onglet l'affiche avec sa raison, jamais « rien à fabriquer ». */
    private static void attendre(String raison) {
        if (!raison.equals(planAttente)) {
            planAttente = raison;
            sale = true;
        }
    }

    private static void ordonner(
            InstantaneArbreEmi vue,
            List<PlanMachines.Operation<EmiRecipe>> operationsSuivies,
            boolean suivi) {
        List<PlanMachines.Operation<EmiRecipe>> operations;
        try {
            if (operationsSuivies != null) operations = operationsSuivies;
            else {
                // Ce qui est déjà rangé dans un coffre connu n'est plus à fabriquer, seulement à
                // récupérer : comme pour la surbrillance des machines d'un arbre suivi.
                var stock = stockEmi();
                stock.addAll(MachinesEmi.stockCoffres(vue, List.of()));
                operations = PlanMachines.calculer(vue.machines(), vue.lots(), stock);
            }
        } catch (RuntimeException e) {
            etapes = List.of();
            recettesPlan = Set.of();
            attendre(
                    "Plan impossible : "
                            + (e.getMessage() == null ? "arbre illisible" : e.getMessage()));
            return;
        }
        if (suivi && operations.isEmpty() && !MachinesEmi.problemeOrdre().isEmpty()) {
            etapes = List.of();
            recettesPlan = Set.of();
            attendre(MachinesEmi.problemeOrdre());
            return;
        }
        var visibles = operations.stream().filter(o -> !assistant(o.recette())).toList();
        Map<EmiRecipe, List<OrdreMachines.Regle<OrdonnancementEmi.Objet>>> regles =
                new IdentityHashMap<>();
        for (var o : visibles) regles.put(o.recette(), OrdonnancementEmi.regles(o.recette(), null));
        List<OrdreMachines.StockSource<OrdonnancementEmi.Objet>> sources =
                new ArrayList<>(
                        InventaireJoueur.disponible()
                                ? OrdonnancementEmi.stocks(visibles, null)
                                : List.of());
        if (InventaireJoueur.disponible()) sources.addAll(coffres(visibles));
        var ordre =
                OrdreMachines.calculer(
                        vue.machines(),
                        visibles,
                        r -> regles.getOrDefault(r, List.of()),
                        sources,
                        AnalyseRecette::condition,
                        InventaireJoueur.disponible());
        List<RangsRecette.Lot<EmiRecipe>> lots = new ArrayList<>();
        List<LotMachine> actifs = suivi ? MachinesEmi.lotsSuivis() : List.of();
        for (var lot : actifs) {
            var etat = lot.suivi().etat();
            if (etat == LotFabrication.Etat.ANNULE || etat == LotFabrication.Etat.TERMINE) continue;
            lots.add(
                    new RangsRecette.Lot<>(
                            lot.id(),
                            lot.recette(),
                            lot.fois(),
                            etat == LotFabrication.Etat.A_RECUPERER));
        }
        // L'étape en cours garde la tête du plan tant qu'elle n'est pas faite : ramasser la matière
        // d'une autre étape ne la fait plus passer devant.
        etapes =
                RangsRecette.stabiliser(
                        RangsRecette.numeroter(ordre.lignes(), lots, r -> lotAttendu(r, actifs)),
                        enCours);
        var suivante = RangsRecette.suivante(etapes);
        enCours =
                suivante != null && suivante.statut().actionnable()
                        ? RangsRecette.cle(suivante)
                        : null;
        Set<EmiRecipe> recettes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (var o : visibles) recettes.add(o.recette());
        recettesPlan = recettes;
        planAttente = "";
        cleReperes = null;
        sale = true;
    }

    /** Stock de calcul des besoins quand l'arbre n'est pas suivi : poches, sac et contenants. */
    private static List<PlanRetrait.Stock<EmiStack>> stockEmi() {
        List<PlanRetrait.Stock<EmiStack>> stock = new ArrayList<>();
        for (ItemStack s : InventaireJoueur.piles()) {
            if (s.isEmpty()) continue;
            stock.add(new PlanRetrait.Stock<>(EmiStack.of(s), s.getCount(), true));
            ItemStack un = s.copy();
            un.setCount(1);
            var fluide = FluidUtil.getFluidContained(un).orElse(null);
            if (fluide != null && !fluide.isEmpty())
                stock.add(
                        new PlanRetrait.Stock<>(
                                EmiStack.of(fluide.getFluid(), fluide.getTag(), fluide.getAmount()),
                                PlanMachines.produit(fluide.getAmount(), s.getCount()),
                                true));
        }
        return stock;
    }

    /**
     * Mémoire des coffres à portée (même dimension, pas trop loin, jamais un coffre à butin) : à
     * récupérer, jamais une ressource directement utilisable.
     */
    private static List<OrdreMachines.StockSource<OrdonnancementEmi.Objet>> coffres(
            List<PlanMachines.Operation<EmiRecipe>> operations) {
        Set<String> utiles = new HashSet<>();
        for (var o : operations)
            for (var entree : o.recette().getInputs())
                for (var pile : entree.getEmiStacks())
                    if (!pile.getItemStack().isEmpty()) utiles.add(pile.getId().toString());
        List<OrdreMachines.StockSource<OrdonnancementEmi.Objet>> sources = new ArrayList<>();
        for (var e : CoffresConnus.proches().entrySet()) {
            if (!utiles.contains(e.getKey()) || sources.size() >= 2048) continue;
            var objet = Pont.objet(e.getKey());
            if (objet.isEmpty()) continue;
            sources.add(
                    new OrdreMachines.StockSource<>(
                            new PlanRetrait.Stock<>(
                                    new OrdonnancementEmi.Objet(EmiStack.of(objet), false),
                                    (int) Math.min(1_000_000L, e.getValue()),
                                    false),
                            OrdreMachines.Origine.COFFRE));
        }
        return sources;
    }

    /** Condition du plan : machine ou poste possédé, sans exiger d'ouvrir chaque machine. */
    private static OrdreMachines.Condition condition(EmiRecipe recette) {
        Object poste = PossessionsRecette.poste(recette);
        if (PossessionsRecette.etabli(poste)) return OrdreMachines.Condition.prete();
        var atelier = PossessionsRecette.atelier(poste);
        String nom = atelier == null ? recette.getCategory().getName().getString() : atelier.nom();
        boolean machine = poste instanceof PossessionsRecette.PosteGT;
        return switch (PossessionsRecette.possession(poste)) {
            case POSEE -> OrdreMachines.Condition.prete();
            case RANGEE -> OrdreMachines.Condition.preparer(
                    (machine ? "Machine à poser : " : "Poste à poser : ") + nom);
            case AUCUNE -> new OrdreMachines.Condition(
                    OrdreMachines.Mode.BLOQUE, manque(poste, nom));
            case INCONNUE -> new OrdreMachines.Condition(
                    OrdreMachines.Mode.INCONNU, "Poste à vérifier : " + nom);
        };
    }

    /**
     * Raison d'un poste absent. Décision C : quand la tension manque, on le dit sans nommer de
     * machine plus puissante ; seul un type de machine absent, dans les tensions déjà possédées,
     * est nommé « manquant ».
     */
    private static String manque(Object poste, String nom) {
        if (poste instanceof PossessionsRecette.PosteGT gt) {
            var possedee = PossessionsRecette.atelierSousTier(gt);
            var nommable = PossessionsRecette.atelier(gt);
            return DecisionTension.raison(
                    possedee == null ? null : possedee.nom(), nommable == null ? null : nom);
        }
        return "Poste manquant : " + nom;
    }

    /** Le poste manque faute de tension, pas de type de machine (décision C). */
    private static boolean tension(Object poste) {
        return poste instanceof PossessionsRecette.PosteGT gt
                && PossessionsRecette.possession(gt) == PossessionsRecette.Possession.AUCUNE
                && (PossessionsRecette.sousTier(gt) || PossessionsRecette.atelier(gt) == null);
    }

    /** Un lot en cours dont une sortie attendue est un ingrédient de cette recette. */
    private static int lotAttendu(EmiRecipe recette, List<LotMachine> lots) {
        for (var lot : lots) {
            var etat = lot.suivi().etat();
            if (etat == LotFabrication.Etat.ANNULE || etat == LotFabrication.Etat.TERMINE) continue;
            for (var reste : lot.suivi().restants())
                for (var entree : recette.getInputs())
                    for (var attendu : entree.getEmiStacks())
                        if (ComparaisonEmi.meme(reste.objet(), attendu)) return lot.id();
        }
        return 0;
    }

    // ---------- Repères dans le monde ----------

    private static void actualiserReperes() {
        var mc = Minecraft.getInstance();
        if (cle == null
                || MachinesEmi.arbreSuivi() != cle.arbre()
                || mc.player == null
                || mc.level == null) {
            if (!ReperesRecette.reperes().isEmpty()) ReperesRecette.publier(Map.of());
            cleReperes = null;
            return;
        }
        var machines = MachinesEmi.machinesSurlignees();
        var cleActuelle =
                new CleReperes(etapes, machines, MachinesEmi.revisionLotsProduction(), cle.arbre());
        if (cleActuelle.equals(cleReperes)) return;
        cleReperes = cleActuelle;
        var joueur = mc.player.blockPosition();
        List<BlockPos> positions = new ArrayList<>(machines);
        positions.sort(Comparator.comparingDouble(p -> p.distSqr(joueur)));
        Map<BlockPos, Integer> lotsParPosition = new HashMap<>();
        for (var lot : MachinesEmi.lotsSuivis()) {
            var etat = lot.suivi().etat();
            if (etat == LotFabrication.Etat.ANNULE || etat == LotFabrication.Etat.TERMINE) continue;
            lot.emplacement().ifPresent(p -> lotsParPosition.putIfAbsent(p, lot.id()));
        }
        Map<BlockPos, Object> machinesNatives = new HashMap<>();
        Map<String, Boolean> compatibles = new HashMap<>();
        var calcules =
                RangsRecette.reperes(
                        etapes,
                        positions,
                        (pos, recette) -> {
                            Object machine =
                                    machinesNatives.computeIfAbsent(
                                            pos,
                                            p ->
                                                    mc.level.hasChunkAt(p)
                                                            ? GregTechMachines.machine(
                                                                    mc.level.getBlockEntity(p))
                                                            : null);
                            // compatible() refuse déjà toute recette qui n'est pas GregTech.
                            return machine != null
                                    && compatibles.computeIfAbsent(
                                            pos.asLong() + "|" + System.identityHashCode(recette),
                                            k -> GregTechMachines.compatible(machine, recette));
                        },
                        lotsParPosition,
                        MAX_REPERES);
        ReperesRecette.publier(calcules);
        // Diagnostic pour le journal, une fois par arbre : des machines brillent sans numéro.
        if (calcules.isEmpty() && !positions.isEmpty() && !etapes.isEmpty() && !diagnosticReperes) {
            diagnosticReperes = true;
            com.mojang.logging.LogUtils.getLogger()
                    .info(
                            "Assistant : {} machine(s) surlignée(s) sans numéro ; {} étape(s), dont"
                                    + " {} machine(s) GregTech",
                            positions.size(),
                            etapes.size(),
                            etapes.stream()
                                    .filter(
                                            e ->
                                                    e.recette()
                                                            .getClass()
                                                            .getName()
                                                            .endsWith(".GTEmiRecipe"))
                                    .count());
        }
    }

    // ---------- Vue ----------

    private static void publierVide(VueRecette.Etat etat, String message) {
        var courante = VueRecette.courante();
        if (courante != null
                && courante.etat() == etat
                && courante.message().equals(message)
                && etatPublie == etat) return;
        etatPublie = etat;
        VueRecette.publier(VueRecette.vide(etat, message));
    }

    private static void publier() {
        sale = false;
        dernierePublication = System.currentTimeMillis();
        etatPublie = VueRecette.Etat.PRET;
        VueRecette.publier(construire());
    }

    private record CleVue(Object donnees, Object lecture, long possessions, Object autre) {}

    private static VueRecette construire() {
        var cleE = new CleVue(etapes, lecture, PossessionsRecette.revision(), null);
        if (!cleE.equals(cleEtapes)) {
            cleEtapes = cleE;
            var suivante = RangsRecette.suivante(etapes);
            Map<EmiRecipe, ArbreRecette.Noeud> noeuds = new IdentityHashMap<>();
            for (var n : lecture.noeuds())
                if (n.recette() != null) noeuds.putIfAbsent(n.recette(), n);
            List<VueRecette.Etape> vues = new ArrayList<>();
            VueRecette.EtapeSuivante prochaine = null;
            for (var e : etapes) {
                var vue =
                        etape(
                                e,
                                noeuds.get(e.recette()),
                                e == suivante && e.statut().actionnable());
                vues.add(vue);
                if (e == suivante) prochaine = suivante(e, vue);
            }
            vuesEtapes = List.copyOf(vues);
            vueSuivante = prochaine;
        }
        var cleI =
                new CleVue(
                        recettesPlan,
                        lecture,
                        PossessionsRecette.revision(),
                        PossessionsRecette.complet());
        if (!cleI.equals(cleIntrouvables)) {
            cleIntrouvables = cleI;
            vuesIntrouvables = introuvables();
        }
        List<VueRecette.Alternative> alternatives = new ArrayList<>();
        List<VueRecette.MachineAFabriquer> machines = new ArrayList<>();
        Map<String, Proposition> nouvelles = new HashMap<>();
        propositions(alternatives, machines, nouvelles);
        propositions = Map.copyOf(nouvelles);
        String remarque = "";
        if (MachinesEmi.arbreSuivi() != cle.arbre())
            remarque =
                    "Numéros sur les machines : active le mode fabrication de Recipe Tree ou Coffres.";
        else if (!MachinesEmi.problemeOrdre().isEmpty()) remarque = MachinesEmi.problemeOrdre();
        else if (!PossessionsRecette.complet())
            remarque = "Lecture du sac ou des coffres connus en cours…";
        String avancement =
                taches.isEmpty()
                        ? ""
                        : "analyse "
                                + Math.min(tachesFaites, tachesTotales)
                                + "/"
                                + Math.max(1, tachesTotales);
        return new VueRecette(
                VueRecette.Etat.PRET,
                "",
                cible(),
                vuesEtapes,
                alternatives,
                vuesIntrouvables,
                machines,
                annulation(),
                avancement,
                remarque,
                etapes.isEmpty() ? planAttente : "",
                ++revisionVue,
                etapes.isEmpty() ? null : vueSuivante);
    }

    private static VueRecette.Objet cible() {
        var racine = lecture.cible();
        if (racine == null) return null;
        var pile = premiere(racine.ingredient());
        long quantite = Math.round(racine.besoin());
        return new VueRecette.Objet(
                icone(pile),
                pile == null ? "" : pile.getName().getString(),
                String.valueOf(quantite),
                "");
    }

    private static EmiStack premiere(EmiIngredient ingredient) {
        var piles = ingredient.getEmiStacks();
        return piles.isEmpty() ? null : piles.get(0);
    }

    static ItemStack icone(EmiStack pile) {
        if (pile == null || pile.isEmpty()) return ItemStack.EMPTY;
        var item = pile.getItemStack();
        if (!item.isEmpty()) {
            var copie = item.copy();
            copie.setCount(1);
            return copie;
        }
        if (pile.getKey() instanceof Fluid fluide) {
            var seau = fluide.getBucket();
            return seau == null || seau == Items.AIR
                    ? new ItemStack(Items.BUCKET)
                    : new ItemStack(seau);
        }
        return ItemStack.EMPTY;
    }

    private static VueRecette.Etape etape(
            RangsRecette.Etape<EmiRecipe> e, ArbreRecette.Noeud noeud, boolean etoile) {
        var recette = e.recette();
        EmiStack produit =
                noeud != null
                        ? premiere(noeud.ingredient())
                        : recette.getOutputs().isEmpty() ? null : recette.getOutputs().get(0);
        long production =
                noeud != null
                        ? noeud.production()
                        : produit == null ? 1 : Math.max(1, produit.getAmount());
        long quantite = (long) e.fois() * production;
        String nomProduit =
                produit == null ? recette.getId().toString() : produit.getName().getString();
        Object poste = PossessionsRecette.poste(recette);
        var atelier = PossessionsRecette.etabli(poste) ? null : PossessionsRecette.atelier(poste);
        String nomPoste =
                PossessionsRecette.etabli(poste)
                        ? new ItemStack(Items.CRAFTING_TABLE).getHoverName().getString()
                        : atelier != null
                                ? atelier.nom()
                                : recette.getCategory().getName().getString();
        ItemStack iconePoste =
                PossessionsRecette.etabli(poste)
                        ? new ItemStack(Items.CRAFTING_TABLE)
                        : atelier != null ? atelier.icone() : ItemStack.EMPTY;
        String circuit = circuit(recette);
        String entrees = entrees(recette, e.fois());
        String etat = RangsRecette.texte(e, AnalyseRecette::court);
        VueRecette.Ton ton =
                switch (e.statut()) {
                    case PRETE -> VueRecette.Ton.PRET;
                    case A_PREPARER -> VueRecette.Ton.PREPARER;
                    case EN_MACHINE, A_RECUPERER -> VueRecette.Ton.EN_COURS;
                    case ATTEND, INCONNUE -> VueRecette.Ton.ATTENTE;
                    default -> VueRecette.Ton.PROBLEME;
                };
        List<String> detail = new ArrayList<>();
        detail.add(e.rang() + ". " + quantite + " × " + nomProduit);
        detail.add(
                "Poste : "
                        + nomPoste
                        + switch (PossessionsRecette.possession(poste)) {
                            case POSEE -> PossessionsRecette.etabli(poste) ? "" : " (posé)";
                            case RANGEE -> " (rangé, à poser)";
                            case AUCUNE -> tension(poste)
                                    ? " (tension insuffisante)"
                                    : " (manquant)";
                            case INCONNUE -> " (à vérifier)";
                        });
        if (!circuit.isEmpty()) detail.add(circuit);
        if (!entrees.isEmpty()) detail.add("Entrées : " + entrees);
        detail.add("État : " + (e.raison().isEmpty() ? etat : etat + " · " + e.raison()));
        detail.add(explication(e));
        return new VueRecette.Etape(
                e.rang(),
                etoile,
                new VueRecette.Objet(icone(produit), nomProduit, String.valueOf(quantite), ""),
                nomPoste,
                iconePoste,
                circuit,
                entrees,
                etat,
                ton,
                String.join("\n", detail));
    }

    private static String explication(RangsRecette.Etape<EmiRecipe> e) {
        return switch (e.statut()) {
            case PRETE -> "Ordre : ingrédients dans tes poches et poste disponible, à faire maintenant.";
            case A_PREPARER -> "Ordre : tout est possédé, il reste à le sortir du sac ou d’un coffre, ou à poser la machine.";
            case EN_MACHINE,
                    A_RECUPERER -> "Ordre : lot déjà lancé, ses sorties débloquent les étapes suivantes.";
            case ATTEND -> "Ordre : passe après l’étape qui fabrique son ingrédient.";
            case MANQUE -> "Ordre : une matière manque encore, les étapes possibles passent avant.";
            case POSTE_MANQUANT -> "Ordre : aucune machine possédée ne sait faire cette recette.";
            default -> e.raison().startsWith("Tension insuffisante")
                    ? "Ordre : tes machines de ce type n’ont pas la tension de cette recette."
                    : "Ordre : les étapes qui débloquent le plus d’autres étapes passent en premier.";
        };
    }

    /**
     * Recette de l'étape en cours (★) de l'arbre suivi, ou null : le backpack surligne ce qu'il
     * faut en sortir pour elle. Lecture d'un champ du thread client.
     */
    static EmiRecipe recetteEnCours() {
        var actuelle = enCours;
        if (actuelle == null
                || actuelle.lot() != 0
                || cle == null
                || MachinesEmi.arbreSuivi() != cle.arbre()) return null;
        return actuelle.recette();
    }

    private static boolean provenance(String raison) {
        return raison.startsWith(OrdreMachines.DU_SAC)
                || raison.startsWith(OrdreMachines.D_UN_COFFRE)
                || raison.startsWith(OrdreMachines.A_REPRENDRE);
    }

    /** Texte court pour la colonne d'état ; la raison complète reste au survol. */
    private static String court(String raison) {
        if (raison.startsWith("Manque : ")) return "Manque " + raison.substring(9);
        if (raison.startsWith("Machine manquante")) return "Machine manquante";
        if (raison.startsWith("Tension insuffisante")) return "Tension insuffisante";
        if (raison.startsWith("Poste manquant")) return "Poste manquant";
        if (raison.startsWith("Machine à poser")) return "Machine à poser";
        if (raison.startsWith("Poste à poser")) return "Poste à poser";
        if (raison.startsWith("Poste à vérifier")) return "Poste à vérifier";
        if (raison.startsWith(OrdreMachines.DU_SAC))
            return raison.contains(OrdreMachines.D_UN_COFFRE)
                    ? "Backpack + coffre"
                    : "Dans ton backpack";
        if (raison.startsWith(OrdreMachines.A_REPRENDRE)) return "À reprendre en main";
        if (raison.startsWith(OrdreMachines.D_UN_COFFRE)) return "Dans un coffre";
        if (raison.startsWith(OrdreMachines.A_RECUPERER)) return "À sortir (sac, coffre)";
        if (raison.startsWith("Dépendance cyclique")) return "Cycle dans l’arbre";
        if (raison.startsWith("Recette absente")) return "Arbre modifié";
        if (raison.startsWith("Inventaire non synchronisé")) return "Lecture du sac…";
        if (raison.startsWith("Trop de variantes") || raison.startsWith("Arbre trop grand"))
            return "Trop complexe";
        return raison;
    }

    private static String circuit(EmiRecipe recette) {
        try {
            Object gt = GregTechRecette.recette(recette);
            if (gt == null) return "";
            return "Circuit " + ConfigurationCircuit.lire(gt);
        } catch (ReflectiveOperationException | RuntimeException sansCircuit) {
            return "";
        }
    }

    private static String entrees(EmiRecipe recette, int fois) {
        List<String> parties = new ArrayList<>();
        Map<String, Long> quantites = new LinkedHashMap<>();
        Map<String, String> noms = new HashMap<>();
        Set<String> outils = new java.util.LinkedHashSet<>();
        var lue = LECTURES.get(recette);
        if (lue == null) {
            lue = lire(recette, null);
            if (LECTURES.size() < 4096) LECTURES.put(recette, lue);
        }
        for (var entree : lue.entrees()) {
            var pile = premiere(entree.ingredient());
            if (pile == null) continue;
            String nom = pile.getName().getString();
            boolean fluide = pile.getKey() instanceof Fluid;
            String cle = (fluide ? "f:" : "o:") + pile.getId();
            noms.putIfAbsent(cle, nom);
            if (entree.reutilisable()) outils.add(nom);
            else quantites.merge(cle, Math.round(entree.quantite() * fois), Long::sum);
        }
        quantites.forEach(
                (k, n) -> parties.add(n + (k.startsWith("f:") ? " mB " : " ") + noms.get(k)));
        for (String outil : outils) parties.add(outil + " (outil)");
        return String.join(", ", parties);
    }

    private static VueRecette.EtapeSuivante suivante(
            RangsRecette.Etape<EmiRecipe> e, VueRecette.Etape vue) {
        boolean action = e.statut().actionnable();
        String titre =
                (action ? "★ " : e.rang() + ". ")
                        + (vue.poste().isEmpty() ? vue.produit().nom() : vue.poste());
        String sortie = vue.produit().quantite() + " " + vue.produit().nom();
        String detail =
                switch (e.statut()) {
                    case PRETE -> vue.entrees().isEmpty() ? sortie : vue.entrees() + " → " + sortie;
                        // La provenance en toutes lettres : « À sortir du backpack : Iron Ingot ».
                    case A_PREPARER -> provenance(e.raison())
                            ? e.raison() + " → " + sortie
                            : vue.entrees().isEmpty()
                                    ? vue.etat() + " · " + sortie
                                    : vue.etat() + " · " + vue.entrees();
                    default -> sortie + " · " + vue.etat();
                };
        return new VueRecette.EtapeSuivante(
                vue.iconePoste().isEmpty() ? vue.produit().icone() : vue.iconePoste(),
                titre,
                detail);
    }

    /** Décision d'un nœud : meilleure alternative faisable, ou meilleure avec machine à faire. */
    private static void decider(Analyse analyse) {
        long revision = PossessionsRecette.revision();
        boolean complet = PossessionsRecette.complet();
        if (analyse.revisionDecision == revision
                && analyse.candidatsDecision == analyse.candidats.size()
                && analyse.completDecision == complet) return;
        analyse.revisionDecision = revision;
        analyse.candidatsDecision = analyse.candidats.size();
        analyse.completDecision = complet;
        analyse.meilleure = analyse.avecMachine = null;
        analyse.posteManquant = null;
        for (var c : analyse.candidats) {
            if (!ComparaisonAlternatives.meilleure(c.chaine(), analyse.actuelle)) continue;
            if (introduitIntrouvable(c.chaine(), analyse.actuelle)) continue;
            // Moules, lentilles, formes d'extrudeuse : un outil nouveau doit déjà être possédé.
            if (!ComparaisonAlternatives.outilsPossedes(
                    c.chaine(), analyse.actuelle, AnalyseRecette::possede, complet)) continue;
            Object manquant = null;
            boolean faisable = true;
            for (Object poste : c.chaine().postes()) {
                var p = PossessionsRecette.possession(poste);
                if (p == PossessionsRecette.Possession.POSEE
                        || p == PossessionsRecette.Possession.RANGEE) continue;
                // Seule la machine de la recette proposée peut manquer (« à fabriquer »).
                if (p == PossessionsRecette.Possession.AUCUNE
                        && Objects.equals(poste, c.poste())
                        && manquant == null) {
                    manquant = poste;
                    continue;
                }
                faisable = false;
                break;
            }
            if (!faisable) continue;
            if (manquant == null) {
                if (analyse.meilleure == null
                        || ComparaisonAlternatives.ORDRE.compare(
                                        c.chaine(), analyse.meilleure.chaine())
                                < 0) analyse.meilleure = c;
            } else if (!(manquant instanceof PossessionsRecette.PosteGT gt
                    && PossessionsRecette.sousTier(gt))) {
                // Jamais de « machine plus puissante » : seulement un type de machine absent.
                if (analyse.avecMachine == null
                        || ComparaisonAlternatives.ORDRE.compare(
                                        c.chaine(), analyse.avecMachine.chaine())
                                < 0) {
                    analyse.avecMachine = c;
                    analyse.posteManquant = manquant;
                }
            }
        }
    }

    private static String nomPoste(Object poste, EmiRecipe recette) {
        if (PossessionsRecette.etabli(poste))
            return new ItemStack(Items.CRAFTING_TABLE).getHoverName().getString();
        var atelier = PossessionsRecette.atelier(poste);
        return atelier != null ? atelier.nom() : recette.getCategory().getName().getString();
    }

    private static void propositions(
            List<VueRecette.Alternative> alternatives,
            List<VueRecette.MachineAFabriquer> machines,
            Map<String, Proposition> nouvelles) {
        record Retenue(Analyse analyse, Candidat candidat, double economie) {}
        record Machine(
                PossessionsRecette.Atelier atelier,
                FabricationFacile.Resultat facile,
                Analyse analyse,
                Candidat candidat,
                double economie) {}
        List<Retenue> retenues = new ArrayList<>();
        Map<String, Machine> aFabriquer = new LinkedHashMap<>();
        for (var analyse : analyses) {
            if (!analyse.pret()
                    || analyse.actuelle == null
                    || !recettesPlan.contains(analyse.noeud.recette())) continue;
            decider(analyse);
            var meilleure = analyse.meilleure;
            if (meilleure != null)
                retenues.add(
                        new Retenue(
                                analyse,
                                meilleure,
                                ComparaisonAlternatives.economie(
                                        meilleure.chaine(), analyse.actuelle, analyse.besoin)));
            var avecMachine = analyse.avecMachine;
            if (avecMachine == null
                    || meilleure != null
                            && !ComparaisonAlternatives.meilleure(
                                    avecMachine.chaine(), meilleure.chaine())) continue;
            var facile = facile(analyse.posteManquant);
            if (facile == null) continue;
            var reference = meilleure == null ? analyse.actuelle : meilleure.chaine();
            double economie =
                    ComparaisonAlternatives.economie(
                            avecMachine.chaine(), reference, analyse.besoin);
            var connue = aFabriquer.get(facile.atelier().id());
            if (connue == null || connue.economie() < economie)
                aFabriquer.put(
                        facile.atelier().id(),
                        new Machine(
                                facile.atelier(),
                                facile.resultat(),
                                analyse,
                                avecMachine,
                                economie));
        }
        retenues.sort(
                Comparator.comparingDouble(Retenue::economie)
                        .reversed()
                        .thenComparingInt(
                                r ->
                                        r.candidat().chaine().etapes()
                                                - r.analyse().actuelle.etapes()));
        for (var r : retenues) {
            if (alternatives.size() >= MAX_ALTERNATIVES) break;
            var analyse = r.analyse();
            var c = r.candidat();
            String nomProduit = analyse.produit.getName().getString();
            String poste = nomPoste(c.poste(), c.recette());
            var atelier =
                    PossessionsRecette.etabli(c.poste())
                            ? null
                            : PossessionsRecette.atelier(c.poste());
            String gain =
                    ComparaisonAlternatives.gain(
                            c.chaine(), analyse.actuelle, AnalyseRecette::nom, nomProduit);
            // Identité stable : lecture de l'arbre, ingrédient résolu et recette proposée.
            String id =
                    generation + "|" + cle(analyse.noeud.ingredient()) + "|" + c.recette().getId();
            nouvelles.put(id, new Proposition(analyse, c, cle));
            var actuelle = analyse.noeud.recette();
            String detail =
                    nomProduit
                            + " → "
                            + poste
                            + "\n"
                            + gain
                            + "\nProposée : "
                            + c.recette().getId()
                            + " · "
                            + ComparaisonAlternatives.etapes(c.chaine().etapes())
                            + "\nActuelle : "
                            + (actuelle.getId() == null ? "?" : actuelle.getId())
                            + " · "
                            + ComparaisonAlternatives.etapes(analyse.actuelle.etapes())
                            + "\nSous-ingrédients : choix de l’arbre ou recettes par défaut d’EMI ;"
                            + " une matière nouvelle se fabrique aussi (ses étapes comptent),"
                            + " sauf si tu en as déjà."
                            + "\nJamais une recette qui défait un objet fait avec ce produit."
                            + "\nPoste possédé (posé, porté ou rangé)."
                            + "\n« Appliquer » en fait aussi la recette par défaut d’EMI."
                            + (analyse.racine
                                    ? "\nRecette du but : la quantité visée ("
                                            + Math.round(analyse.besoin)
                                            + ") est conservée."
                                    : "");
            alternatives.add(
                    new VueRecette.Alternative(
                            id,
                            new VueRecette.Objet(icone(analyse.produit), nomProduit, "", ""),
                            poste,
                            atelier == null ? new ItemStack(Items.CRAFTING_TABLE) : atelier.icone(),
                            gain,
                            detail));
        }
        aFabriquer.values().stream()
                .sorted(Comparator.comparingDouble(Machine::economie).reversed())
                .limit(MAX_MACHINES)
                .forEach(
                        m -> {
                            String nomProduit = m.analyse().produit.getName().getString();
                            String gain =
                                    ComparaisonAlternatives.gain(
                                            m.candidat().chaine(),
                                            m.analyse().actuelle,
                                            AnalyseRecette::nom,
                                            nomProduit);
                            int sous = m.facile().sousCrafts();
                            String facilite =
                                    sous == 0
                                            ? "tu as tout"
                                            : sous
                                                    + " sous-craft"
                                                    + (sous > 1 ? "s" : "")
                                                    + " avec ce que tu as";
                            machines.add(
                                    new VueRecette.MachineAFabriquer(
                                            new VueRecette.Objet(
                                                    m.atelier().icone(), m.atelier().nom(), "", ""),
                                            facilite,
                                            nomProduit + " : " + gain,
                                            "Machine à fabriquer : "
                                                    + m.atelier().nom()
                                                    + " ("
                                                    + facilite
                                                    + ")\nPour "
                                                    + nomProduit
                                                    + " : "
                                                    + gain
                                                    + "\nRecette d’établi couverte par l’inventaire, le sac et les coffres connus."
                                                    + "\nTu n’as encore aucune machine de ce type."));
                        });
    }

    private record Facile(
            PossessionsRecette.Atelier atelier, FabricationFacile.Resultat resultat) {}

    /** Le premier atelier du poste fabricable avec ce qu'a le joueur, du moins puissant au plus. */
    private static Facile facile(Object poste) {
        if (!PossessionsRecette.complet()) return null;
        verifierGestion();
        if (revisionFaciles != PossessionsRecette.revision()) {
            FACILES.clear();
            revisionFaciles = PossessionsRecette.revision();
        }
        int essais = 0;
        for (var atelier : PossessionsRecette.ateliers(poste)) {
            // Décision C : jamais une machine électrique au-dessus des tensions déjà possédées.
            if (!PossessionsRecette.proposable(atelier)) continue;
            if (++essais > 3) break;
            var resultat =
                    FACILES.computeIfAbsent(
                            atelier.id(),
                            id ->
                                    FabricationFacile.evaluer(
                                            id,
                                            AnalyseRecette::etabli,
                                            PossessionsRecette.stock(),
                                            true));
            if (resultat.facile()) return new Facile(atelier, resultat);
        }
        return null;
    }

    /** Recettes d'établi produisant un objet, lues une fois par génération de recettes EMI. */
    /** Les recettes d'établi et les résultats « facile » dépendent de la génération EMI. */
    private static void verifierGestion() {
        var gestion = EmiApi.getRecipeManager();
        if (gestion != gestionEtabli) {
            ETABLI.clear();
            FACILES.clear();
            PRODUCTEURS.clear();
            DECOMPOSITIONS.clear();
            gestionEtabli = gestion;
        }
    }

    /**
     * Un outil (clé de matière) présent dans l'inventaire, le sac, un coffre connu ou une machine.
     */
    private static boolean possede(String cle) {
        return cle.startsWith("objet:")
                && PossessionsRecette.stock().getOrDefault(cle.substring(6), 0L) > 0;
    }

    private static List<FabricationFacile.Recette> etabli(String objet) {
        verifierGestion();
        var connues = ETABLI.get(objet);
        if (connues != null) return connues;
        List<FabricationFacile.Recette> liste = new ArrayList<>();
        var item = Pont.objet(objet);
        if (!item.isEmpty())
            try {
                var pile = EmiStack.of(item);
                for (var r : EmiApi.getRecipeManager().getRecipesByOutput(pile)) {
                    if (liste.size() >= FabricationFacile.MAX_RECETTES) break;
                    if (r == null || r.getCategory() == null || !r.supportsRecipeTree()) continue;
                    if (!r.getCategory().getId().toString().equals("minecraft:crafting")) continue;
                    long production = 0;
                    for (var sortie : r.getOutputs())
                        if (sortie.equals(pile)) production += sortie.getAmount();
                    if (production <= 0) continue;
                    List<FabricationFacile.Besoin> besoins = new ArrayList<>();
                    boolean lisible = true;
                    for (var entree : r.getInputs()) {
                        if (entree.isEmpty()) continue;
                        List<String> variantes = new ArrayList<>();
                        for (var v : entree.getEmiStacks()) {
                            if (v.getItemStack().isEmpty()) continue;
                            if (variantes.size() < 64) variantes.add(v.getId().toString());
                        }
                        if (variantes.isEmpty()) {
                            lisible = false;
                            break;
                        }
                        besoins.add(
                                new FabricationFacile.Besoin(
                                        variantes, entree.getAmount(), reutilisable(entree)));
                    }
                    if (lisible && !besoins.isEmpty())
                        liste.add(new FabricationFacile.Recette(besoins, production));
                }
            } catch (RuntimeException illisible) {
                liste.clear();
            }
        var resultat = List.copyOf(liste);
        if (ETABLI.size() < 4096) ETABLI.put(objet, resultat);
        return resultat;
    }

    /** Une nouvelle matière que le joueur n'a nulle part rendrait l'alternative inutile. */
    private static boolean introduitIntrouvable(
            ChaineRecette.Chaine alt, ChaineRecette.Chaine cur) {
        if (!PossessionsRecette.complet()) return false;
        var stock = PossessionsRecette.stock();
        for (String cle : alt.base().keySet()) {
            if (cur.base().containsKey(cle) || !cle.startsWith("objet:")) continue;
            if (stock.getOrDefault(cle.substring(6), 0L) <= 0) return true;
        }
        return false;
    }

    private static List<VueRecette.Introuvable> introuvables() {
        if (!PossessionsRecette.complet()) return List.of();
        var stock = PossessionsRecette.stock();
        Map<String, VueRecette.Introuvable> resultat = new LinkedHashMap<>();
        for (var n : lecture.noeuds()) {
            if (resultat.size() >= MAX_INTROUVABLES) break;
            if (n.recette() != null || n.parent() == null || !recettesPlan.contains(n.parent()))
                continue;
            var piles = n.ingredient().getEmiStacks();
            if (piles.isEmpty()) continue;
            boolean objet = false, trouve = false;
            for (var pile : piles) {
                var item = pile.getItemStack();
                if (item.isEmpty()) continue;
                if (item.hasTag() && item.getTag().contains("assistantia_objectif")) continue;
                objet = true;
                if (stock.getOrDefault(pile.getId().toString(), 0L) > 0) {
                    trouve = true;
                    break;
                }
            }
            if (!objet || trouve) continue;
            var pile = piles.get(0);
            String nom = pile.getName().getString();
            String cle = pile.getId().toString();
            if (resultat.containsKey(cle)) continue;
            resultat.put(
                    cle,
                    new VueRecette.Introuvable(
                            new VueRecette.Objet(icone(pile), nom, "", ""),
                            nom
                                    + " : introuvable dans l’inventaire, le sac, les coffres connus et les lots en cours."
                                    + (piles.size() > 1
                                            ? "\nAucune des "
                                                    + piles.size()
                                                    + " variantes acceptées."
                                            : "")
                                    + "\nDemandé par : "
                                    + n.parent().getId()));
        }
        return List.copyOf(resultat.values());
    }

    private static String annulation() {
        while (!annulations.isEmpty() && !valide(annulations.peekFirst())) annulations.pollFirst();
        var a = annulations.peekFirst();
        return a == null ? "" : "Annuler : " + a.libelle();
    }

    private static boolean valide(Annulation a) {
        try {
            if (cle == null || a.arbre() != cle.arbre()) return false;
            // Le but garde son objet nœud : seule sa recette dit s'il a changé depuis.
            if (a.racine() != null && ArbreRecette.recetteRacine(a.arbre()) != a.appliquee())
                return false;
            return ArbreRecette.resolutions(a.arbre()).get(a.cle()) == a.appliquee();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ---------- Actions ----------

    private static final class Actions implements VueRecette.Actions {
        @Override
        public String appliquer(String id) {
            try {
                return AnalyseRecette.appliquer(id);
            } catch (ReflectiveOperationException | RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : alternative non appliquée", e);
                return "Impossible de modifier l’arbre EMI · détails dans le journal.";
            }
        }

        @Override
        public String annuler() {
            try {
                return AnalyseRecette.annuler();
            } catch (ReflectiveOperationException | RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : annulation impossible", e);
                return "Impossible de rétablir l’arbre EMI · détails dans le journal.";
            }
        }

        @Override
        public String voir(String id) {
            try {
                return AnalyseRecette.voir(id);
            } catch (RuntimeException | LinkageError e) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : recette EMI non affichée", e);
                return "Impossible d’ouvrir EMI · détails dans le journal.";
            }
        }
    }

    private static String occupe() {
        if (EnvoiMachine.occupe() || fr.assistantia.RetraitCoffre.occupe())
            return "Transfert en cours · réessaie après.";
        return null;
    }

    /** Revérifie l'arbre, sa révision et le choix actuel avant d'écrire la résolution locale. */
    private static String appliquer(String id) throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return "Action refusée hors du jeu.";
        String occupe = occupe();
        if (occupe != null) return occupe;
        var proposition = propositions.get(id);
        if (proposition == null || cle == null)
            return "Alternative périmée · attends le nouvel affichage.";
        Object arbre = ArbreEmi.consulte();
        // Même arbre réel que celui analysé : but, structure, lots, résolutions et recettes EMI.
        if (arbre == null || !cleActuelle(arbre).meme(proposition.arbre()))
            return "Arbre EMI modifié · attends le nouvel affichage.";
        var analyse = proposition.analyse();
        var ingredient = analyse.noeud.ingredient();
        // Le but garde sa propre recette : getRecipe ne la décrit pas.
        var actuelle =
                analyse.racine
                        ? ArbreRecette.recetteRacine(arbre)
                        : ArbreRecette.choix(arbre, ingredient);
        if (actuelle != analyse.noeud.recette())
            return "Choix modifié dans EMI · attends le nouvel affichage.";
        var recette = proposition.candidat().recette();
        if (recette.getId() == null
                || EmiApi.getRecipeManager().getRecipe(recette.getId()) != recette)
            return "Recette EMI rechargée · attends le nouvel affichage.";
        var resolutions = ArbreRecette.resolutions(arbre);
        boolean avait = resolutions.containsKey(ingredient);
        EmiRecipe precedente = resolutions.get(ingredient);
        // D'abord la recette par défaut d'EMI (tous les arbres, enregistrée), puis la résolution de
        // cet arbre, qui le recalcule : il ne garde jamais l'ancien choix.
        ArbreRecette.Defaut defaut = null;
        try {
            defaut = ArbreRecette.definirDefaut(ingredient, recette);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!erreurDefautSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : recette par défaut EMI inchangée", e);
                erreurDefautSignalee = true;
            }
        }
        ArbreRecette.EtatRacine etatRacine = null;
        try {
            if (analyse.racine) etatRacine = ArbreRecette.resoudreRacine(arbre, recette);
            else ArbreRecette.resoudre(arbre, ingredient, recette);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (defaut != null) ArbreRecette.restaurerDefaut(defaut);
            throw e;
        }
        String libelle =
                analyse.produit.getName().getString() + " → " + nomPoste(proposition.candidat());
        annulations.addFirst(
                new Annulation(
                        arbre,
                        ingredient,
                        avait,
                        precedente,
                        recette,
                        libelle,
                        etatRacine,
                        defaut));
        while (annulations.size() > 8) annulations.pollLast();
        sale = true;
        return "Appliqué : "
                + libelle
                + (defaut != null ? " · recette par défaut d’EMI" : " · cet arbre seulement");
    }

    /** Ouvre EMI sur la recette proposée, depuis l'inventaire comme les autres liens vers EMI. */
    private static String voir(String id) {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null) return "Action refusée hors du jeu.";
        var proposition = propositions.get(id);
        if (proposition == null) return "Alternative périmée · attends le nouvel affichage.";
        var recette = proposition.candidat().recette();
        if (recette.getId() == null
                || EmiApi.getRecipeManager().getRecipe(recette.getId()) != recette)
            return "Recette EMI rechargée · attends le nouvel affichage.";
        mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
        EmiApi.displayRecipe(recette);
        return "";
    }

    private static String nomPoste(Candidat c) {
        return nomPoste(c.poste(), c.recette());
    }

    private static String annuler() throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return "Action refusée hors du jeu.";
        String occupe = occupe();
        if (occupe != null) return occupe;
        while (!annulations.isEmpty() && !valide(annulations.peekFirst())) annulations.pollFirst();
        var a = annulations.peekFirst();
        if (a == null) return "Rien à annuler dans cet arbre.";
        // Refusé : le choix reste annulable (recette par défaut d'EMI comprise).
        if (ArbreEmi.consulte() != a.arbre()) return "Arbre EMI modifié · rien n’a été changé.";
        annulations.pollFirst();
        // La recette par défaut d'abord : la résolution rétablie ensuite recalcule l'arbre avec.
        if (a.defaut() != null) ArbreRecette.restaurerDefaut(a.defaut());
        if (a.racine() != null) ArbreRecette.restaurerRacine(a.arbre(), a.racine());
        else if (a.avait()) ArbreRecette.resoudre(a.arbre(), a.cle(), a.precedente());
        else ArbreRecette.retirer(a.arbre(), a.cle());
        sale = true;
        return "Annulé : " + a.libelle();
    }
}
