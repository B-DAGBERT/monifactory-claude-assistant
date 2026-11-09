package fr.assistantia.compat.emi;

import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contrats purs de l'onglet Recette : chaînes, comparaison, rangs, repères et « facile ». */
public final class RecetteMoteurTest {
    // ---------- Monde de recettes synthétique ----------

    private static final class Monde implements ChaineRecette.Source<String, String> {
        final Map<String, String> choix = new HashMap<>();
        final Map<String, ChaineRecette.Recette<String>> recettes = new HashMap<>();
        int lectures;

        Monde recette(String id, double production, Object poste, Object... entrees) {
            List<ChaineRecette.Entree<String>> liste = new ArrayList<>();
            for (int i = 0; i < entrees.length; i += 2) {
                String nom = (String) entrees[i];
                boolean outil = nom.startsWith("!");
                liste.add(
                        new ChaineRecette.Entree<>(
                                outil ? nom.substring(1) : nom,
                                ((Number) entrees[i + 1]).doubleValue(),
                                outil));
            }
            recettes.put(id, new ChaineRecette.Recette<>(liste, production, poste, 0, 0, false));
            return this;
        }

        Monde energie(String id, double eu, double ticks) {
            var r = recettes.get(id);
            recettes.put(
                    id,
                    new ChaineRecette.Recette<>(
                            r.entrees(), r.production(), r.poste(), eu, ticks, false));
            return this;
        }

        Monde resolution(String id, String choisie) {
            recettes.put(
                    id,
                    new ChaineRecette.Recette<>(
                            List.of(new ChaineRecette.Entree<>(choisie, 1, false)),
                            1,
                            null,
                            0,
                            0,
                            true));
            return this;
        }

        Monde choisir(String ingredient, String recette) {
            choix.put(ingredient, recette);
            return this;
        }

        @Override
        public String choix(String ingredient) {
            return choix.get(ingredient);
        }

        @Override
        public ChaineRecette.Recette<String> lire(String recette, String produit) {
            lectures++;
            return recettes.get(recette);
        }

        @Override
        public String cle(String ingredient) {
            return ingredient.startsWith("~")
                    ? ChaineRecette.FLUIDE + ingredient.substring(1)
                    : "objet:" + ingredient;
        }
    }

    private static final Map<String, String> NOMS =
            Map.of(
                    "objet:iron_ingot", "Iron Ingot",
                    "objet:copper", "Copper Ingot",
                    "objet:tin", "Tin Ingot",
                    "objet:ore", "Ore",
                    "objet:dust", "Dust",
                    "fluide:water", "Water");

    private static String nom(String cle) {
        return NOMS.getOrDefault(cle, cle);
    }

    private static boolean proche(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "plaque" -> plaque();
            case "bronze" -> bronze();
            case "douze" -> douze();
            case "cycle" -> cycle();
            case "budget" -> budget();
            case "transparente" -> transparente();
            case "fluide" -> fluide();
            case "comparaison" -> comparaison();
            case "nombres" -> nombres();
            case "rangs" -> rangs();
            case "reperes" -> reperes();
            case "ordre" -> ordre();
            case "facile" -> facile();
            case "racine" -> racine();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("PASS " + args[0]);
    }

    // ---------- Chaînes ----------

    /** Décision 2 du joueur : Iron Plate à l'établi (2 lingots + marteau) ou au Basic Bender. */
    private static void plaque() {
        var monde =
                new Monde()
                        .recette("craft_plate", 1, "etabli", "iron_ingot", 2, "!hammer", 1)
                        .recette("bend_plate", 1, "bender", "iron_ingot", 1)
                        .energie("bend_plate", 24 * 20, 20);
        var chaine = new ChaineRecette<>(monde);
        var etabli = chaine.evaluer("craft_plate", "iron_plate");
        var bender = chaine.evaluer("bend_plate", "iron_plate");
        assert etabli.complete() && bender.complete();
        assert proche(etabli.base().get("objet:iron_ingot"), 2) : etabli;
        assert etabli.outils().equals(Set.of("objet:hammer")) : "tool is not a per-unit cost";
        assert etabli.etapes() == 1 && bender.etapes() == 1;
        assert proche(bender.base().get("objet:iron_ingot"), 1) : bender;
        assert bender.postes().equals(Set.of("bender")) && proche(bender.eu(), 480);
        assert ComparaisonAlternatives.meilleure(bender, etabli);
        assert !ComparaisonAlternatives.meilleure(etabli, bender);
        String gain =
                ComparaisonAlternatives.gain(bender, etabli, RecetteMoteurTest::nom, "Iron Plate");
        assert gain.equals("1 Iron Ingot au lieu de 2 par Iron Plate") : ascii(gain);
        assert ComparaisonAlternatives.ORDRE.compare(bender, etabli) < 0;
        assert proche(ComparaisonAlternatives.economie(bender, etabli, 8), 8);
    }

    /** docs/integration-v2 : 8 Bronze Plate = 6 cuivre + 2 étain au bender, 9 + 3 au marteau. */
    private static void bronze() {
        var monde =
                new Monde()
                        .recette("alloy", 4, "alloy", "copper", 3, "tin", 1)
                        .recette("bend_bronze", 1, "bender", "bronze_ingot", 1)
                        .recette("hammer_bronze", 2, "hammer", "bronze_ingot", 3)
                        .choisir("bronze_ingot", "alloy");
        var chaine = new ChaineRecette<>(monde);
        var bender = chaine.evaluer("bend_bronze", "bronze_plate");
        var marteau = chaine.evaluer("hammer_bronze", "bronze_plate");
        assert proche(bender.base().get("objet:copper") * 8, 6) : bender;
        assert proche(bender.base().get("objet:tin") * 8, 2) : bender;
        assert proche(marteau.base().get("objet:copper") * 8, 9) : marteau;
        assert proche(marteau.base().get("objet:tin") * 8, 3) : marteau;
        assert bender.etapes() == 2 && marteau.etapes() == 2 : "alloy step counted once";
        assert bender.postes().equals(Set.of("bender", "alloy"));
        assert ComparaisonAlternatives.meilleure(bender, marteau);
        String gain =
                ComparaisonAlternatives.gain(
                        bender, marteau, RecetteMoteurTest::nom, "Bronze Plate");
        assert gain.equals("0,75 Copper Ingot au lieu de 1,13 par Bronze Plate (+1 autre)")
                : ascii(gain);
        int lectures = monde.lectures;
        chaine.evaluer("bend_bronze", "bronze_plate");
        assert monde.lectures == lectures : "recipe readings are cached per analysis";
    }

    /** Douze crafts à la main remplacés par deux étapes machine : proposé. */
    private static void douze() {
        var monde = new Monde();
        String precedent = "ore";
        for (int i = 1; i <= 11; i++) {
            monde.recette("main_" + i, 1, "etabli", precedent, 1);
            monde.choisir("part_" + i, "main_" + i);
            precedent = "part_" + i;
        }
        monde.recette("main_final", 1, "etabli", precedent, 1);
        monde.recette("machine_part", 1, "assembler", "ore", 1);
        monde.choisir("part_machine", "machine_part");
        monde.recette("machine_final", 1, "assembler", "part_machine", 1);
        var chaine = new ChaineRecette<>(monde);
        var main = chaine.evaluer("main_final", "circuit");
        var machine = chaine.evaluer("machine_final", "circuit");
        assert main.etapes() == 12 && machine.etapes() == 2
                : main.etapes() + " " + machine.etapes();
        assert proche(main.total(), machine.total());
        assert ComparaisonAlternatives.meilleure(machine, main);
        String gain =
                ComparaisonAlternatives.gain(machine, main, RecetteMoteurTest::nom, "Circuit");
        assert gain.equals("2 étapes au lieu de 12") : ascii(gain);
    }

    /** Lingot ↔ poudre : la coupure du chemin d'EMI, sans boucle ni mémoire faussée. */
    private static void cycle() {
        var monde =
                new Monde()
                        .recette("smelt_dust", 1, "four", "dust", 1)
                        .recette("macerate_ingot", 1, "macerator", "ingot", 1)
                        .recette("craft_x", 1, "etabli", "dust", 2)
                        .choisir("ingot", "smelt_dust")
                        .choisir("dust", "macerate_ingot");
        var chaine = new ChaineRecette<>(monde);
        var lingot = chaine.evaluer("smelt_dust", "ingot");
        assert lingot.complete() && proche(lingot.base().get("objet:ingot"), 1) : lingot;
        assert lingot.etapes() == 2 : "smelt + macerate";
        var x = chaine.evaluer("craft_x", "x");
        // dust -> macerate(ingot) -> smelt(dust) : macerate est déjà sur le chemin.
        assert proche(x.base().get("objet:dust"), 2) : x;
        assert x.complete();
        var encore = chaine.evaluer("craft_x", "x");
        assert encore.base().equals(x.base()) && encore.etapes() == x.etapes();
    }

    /** Une chaîne de 300 ingrédients est bornée et déclarée incomplète. */
    private static void budget() {
        var monde = new Monde();
        for (int i = 0; i < 300; i++) {
            monde.recette("r" + i, 1, "etabli", "i" + (i + 1), 1);
            monde.choisir("i" + i, "r" + i);
        }
        var chaine = new ChaineRecette<>(monde);
        long debut = System.nanoTime();
        var resultat = chaine.evaluer("r0", "i0");
        assert System.nanoTime() - debut < 500_000_000L;
        assert !resultat.complete() : "truncated chain must not be comparable";
        var autre = new Monde().recette("a", 1, "etabli", "x", 1);
        assert !ComparaisonAlternatives.meilleure(
                resultat, new ChaineRecette<>(autre).evaluer("a", "y"));
        assert new ChaineRecette<>(monde).evaluer("absente", "i0") == null;
    }

    /** Une résolution de tag choisit une variante : ce n'est pas une étape. */
    private static void transparente() {
        var monde =
                new Monde()
                        .recette("bend", 1, "bender", "#ingots/iron", 1)
                        .resolution("tag_iron", "iron_ingot")
                        .choisir("#ingots/iron", "tag_iron");
        var chaine = new ChaineRecette<>(monde).evaluer("bend", "plate");
        assert chaine.etapes() == 1 && chaine.postes().equals(Set.of("bender"));
        assert proche(chaine.base().get("objet:iron_ingot"), 1) : chaine;
    }

    /** Les fluides restent en mB ; le total les compte par 1 000 mB. */
    private static void fluide() {
        var monde =
                new Monde()
                        .recette("wash", 2, "washer", "dust", 1, "~water", 1000)
                        .recette("wash_small", 2, "washer", "dust", 1, "~water", 100);
        var chaine = new ChaineRecette<>(monde);
        var grand = chaine.evaluer("wash", "pure");
        var petit = chaine.evaluer("wash_small", "pure");
        assert proche(grand.base().get("fluide:water"), 500) : grand;
        assert proche(grand.total(), 0.5 + 0.5) : grand.total();
        assert ComparaisonAlternatives.meilleure(petit, grand);
        String gain =
                ComparaisonAlternatives.gain(petit, grand, RecetteMoteurTest::nom, "Pure Dust");
        assert gain.equals("50 mB Water au lieu de 500 par Pure Dust") : ascii(gain);
    }

    private static ChaineRecette.Chaine chaine(Map<String, Double> base, int etapes) {
        return new ChaineRecette.Chaine(base, Set.of(), Set.of(), etapes, 0, 0, true);
    }

    private static void comparaison() {
        var deux = chaine(Map.of("objet:a", 2.0), 3);
        var un = chaine(Map.of("objet:a", 1.0), 3);
        assert ComparaisonAlternatives.meilleure(un, deux);
        assert !ComparaisonAlternatives.meilleure(deux, deux) : "equal is not better";
        assert ComparaisonAlternatives.meilleure(chaine(Map.of("objet:a", 1.0), 4), deux)
                : "one extra step allowed for less material";
        assert !ComparaisonAlternatives.meilleure(chaine(Map.of("objet:a", 1.0), 5), deux)
                : "two extra steps refused";
        assert ComparaisonAlternatives.meilleure(chaine(Map.of("objet:a", 2.0), 2), deux)
                : "same material, fewer steps";
        assert !ComparaisonAlternatives.meilleure(chaine(Map.of("objet:a", 2.5), 1), deux)
                : "more material never better unless steps halved";
        var longue = chaine(Map.of("objet:a", 4.0), 12);
        assert ComparaisonAlternatives.meilleure(chaine(Map.of("objet:b", 4.0), 2), longue)
                : "halved steps with other material and no more total";
        assert !ComparaisonAlternatives.meilleure(chaine(Map.of("objet:b", 5.0), 2), longue);
        String autre =
                ComparaisonAlternatives.gain(
                        chaine(Map.of("objet:dust", 4.0), 2), longue, RecetteMoteurTest::nom, "X");
        assert autre.equals("avec Dust · 2 étapes au lieu de 12") : ascii(autre);
        var incomplete =
                new ChaineRecette.Chaine(
                        Map.of("objet:a", 1.0), Set.of(), Set.of(), 1, 0, 0, false);
        assert !ComparaisonAlternatives.meilleure(incomplete, deux);
        assert !ComparaisonAlternatives.meilleure(chaine(Map.of(), 1), deux) : "empty base";
        assert !ComparaisonAlternatives.meilleure(null, deux);
        assert ComparaisonAlternatives.domine(
                Map.of("objet:a", 1.0), Map.of("objet:a", 1.0, "objet:b", 1.0));
        assert !ComparaisonAlternatives.domine(Map.of("objet:c", 0.1), Map.of("objet:a", 1.0));
    }

    private static void nombres() {
        assert ComparaisonAlternatives.nombre(2.0).equals("2");
        assert ComparaisonAlternatives.nombre(1.5).equals("1,5");
        assert ComparaisonAlternatives.nombre(1.0 / 3).equals("0,33");
        assert ComparaisonAlternatives.nombre(1.125).equals("1,13");
        assert ComparaisonAlternatives.nombre(0.1).equals("0,1");
        assert ComparaisonAlternatives.etapes(1).equals("1 étape");
        assert ComparaisonAlternatives.etapes(3).equals("3 étapes");
    }

    // ---------- Rangs ----------

    private static OrdreMachines.Ligne<String> ligne(
            String recette, OrdreMachines.Mode mode, String raison, List<String> dependances) {
        var groupe =
                switch (mode) {
                    case PRET -> OrdreMachines.Groupe.PRET;
                    case PREPARER -> OrdreMachines.Groupe.PREPARER;
                    case EN_COURS -> OrdreMachines.Groupe.EN_COURS;
                    default -> OrdreMachines.Groupe.ETAPES;
                };
        return new OrdreMachines.Ligne<>(
                new PlanMachines.Operation<>(recette, 2),
                groupe,
                mode,
                mode == OrdreMachines.Mode.PRET ? 1 : 0,
                raison,
                dependances,
                false,
                false);
    }

    private static void rangs() {
        var lignes =
                List.of(
                        ligne(
                                "C",
                                OrdreMachines.Mode.BLOQUE,
                                "Attend : Bronze Ingot",
                                List.of("A")),
                        ligne("A", OrdreMachines.Mode.PRET, "Ingrédients alloués", List.of()),
                        ligne("D", OrdreMachines.Mode.BLOQUE, "Manque : Redstone", List.of()),
                        ligne(
                                "B",
                                OrdreMachines.Mode.PREPARER,
                                "À récupérer dans les poches",
                                List.of()),
                        ligne(
                                "E",
                                OrdreMachines.Mode.BLOQUE,
                                "Machine manquante : Basic Bender",
                                List.of()),
                        ligne(
                                "F",
                                OrdreMachines.Mode.INCONNU,
                                "Poste à vérifier : Furnace",
                                List.of()),
                        ligne("G", OrdreMachines.Mode.BLOQUE, "Manque : Steel", List.of()));
        var lots =
                List.of(
                        new RangsRecette.Lot<>(7, "L", 4, false),
                        new RangsRecette.Lot<>(9, "M", 1, true));
        var etapes = RangsRecette.numeroter(lignes, lots, r -> r.equals("G") ? 7 : 0);
        List<String> ordre = etapes.stream().map(RangsRecette.Etape::recette).toList();
        assert ordre.equals(List.of("A", "B", "L", "M", "C", "D", "E", "F", "G")) : ordre;
        for (int i = 0; i < etapes.size(); i++) assert etapes.get(i).rang() == i + 1;
        var parRecette = new HashMap<String, RangsRecette.Etape<String>>();
        etapes.forEach(e -> parRecette.put(e.recette(), e));
        assert parRecette.get("A").statut() == RangsRecette.Statut.PRETE;
        assert parRecette.get("B").statut() == RangsRecette.Statut.A_PREPARER;
        assert parRecette.get("L").statut() == RangsRecette.Statut.EN_MACHINE
                && parRecette.get("L").lot() == 7;
        assert parRecette.get("M").statut() == RangsRecette.Statut.A_RECUPERER;
        assert parRecette.get("C").statut() == RangsRecette.Statut.ATTEND
                && parRecette.get("C").attend() == 1;
        assert parRecette.get("D").statut() == RangsRecette.Statut.MANQUE;
        assert parRecette.get("E").statut() == RangsRecette.Statut.POSTE_MANQUANT;
        assert parRecette.get("F").statut() == RangsRecette.Statut.INCONNUE;
        assert parRecette.get("G").statut() == RangsRecette.Statut.ATTEND
                        && parRecette.get("G").attend() == 3
                : "missing input produced by a running lot waits for that lot";
        assert RangsRecette.suivante(etapes).recette().equals("A");
        assert RangsRecette.texte(parRecette.get("C"), s -> s).equals("Attend l’étape 1");
        assert RangsRecette.texte(parRecette.get("A"), s -> s).equals("Prête");
        assert RangsRecette.texte(parRecette.get("M"), s -> s).equals("À récupérer");
        assert RangsRecette.texte(parRecette.get("D"), s -> s).equals("Manque : Redstone");
        var bloquees =
                RangsRecette.numeroter(
                        List.of(ligne("Z", OrdreMachines.Mode.BLOQUE, "Manque : X", List.of())),
                        List.of(),
                        null);
        assert RangsRecette.suivante(bloquees).recette().equals("Z")
                : "first step when nothing is ready";
        assert RangsRecette.suivante(List.<RangsRecette.Etape<String>>of()) == null;
    }

    private static void reperes() {
        var etapes =
                List.of(
                        new RangsRecette.Etape<>(
                                1, "alloy", 2, 0, RangsRecette.Statut.PRETE, "", 0),
                        new RangsRecette.Etape<>(
                                2, "bend", 8, 0, RangsRecette.Statut.ATTEND, "", 1),
                        new RangsRecette.Etape<>(
                                3, "lot", 1, 7, RangsRecette.Statut.EN_MACHINE, "", 0));
        var positions = List.of("bender1", "bender2", "smelter", "inutile");
        var compatibles = Map.of("bender1", "bend", "bender2", "bend", "smelter", "alloy");
        var reperes =
                RangsRecette.reperes(
                        etapes,
                        positions,
                        (p, r) -> r.equals(compatibles.get(p)),
                        Map.of("bender2", 7),
                        16);
        assert reperes.keySet().equals(Set.of("bender1", "bender2", "smelter")) : reperes;
        var smelter = reperes.get("smelter");
        assert smelter.etoile() && smelter.texte().equals("★ 1") && !smelter.attenue();
        var bender = reperes.get("bender1");
        assert !bender.etoile() && bender.attenue() && bender.texte().equals("2");
        var lot = reperes.get("bender2");
        assert lot.texte().equals("3 · en cours") && !lot.attenue() && !lot.etoile();
        var deux =
                RangsRecette.reperes(
                        List.of(
                                new RangsRecette.Etape<>(
                                        1, "bend", 1, 0, RangsRecette.Statut.PRETE, "", 0)),
                        List.of("b1", "b2"),
                        (p, r) -> true,
                        Map.of(),
                        16);
        assert deux.get("b1").etoile() && !deux.get("b2").etoile() : "star on the nearest only";
        List<String> beaucoup = new ArrayList<>();
        for (int i = 0; i < 40; i++) beaucoup.add("m" + i);
        assert RangsRecette.reperes(etapes, beaucoup, (p, r) -> r.equals("bend"), Map.of(), 16)
                        .size()
                == 16;
    }

    /** Ordre réel d'OrdreMachines : alliage d'abord, la cintreuse attend l'étape 1. */
    private static void ordre() {
        PlanMachines.Noeud<String, String> lingot =
                new PlanMachines.Noeud<>(s -> s.equals("bronze"), 1, 4, false, "alloy", List.of());
        PlanMachines.Noeud<String, String> plaque =
                new PlanMachines.Noeud<>(
                        s -> s.equals("plate"), 1, 1, false, "bend", List.of(lingot));
        var operations =
                List.of(
                        new PlanMachines.Operation<>("bend", 8),
                        new PlanMachines.Operation<>("alloy", 2));
        Map<String, List<OrdreMachines.Regle<String>>> regles = new LinkedHashMap<>();
        regles.put(
                "alloy",
                List.of(
                        new OrdreMachines.Regle<>("copper"::equals, 3, false, "Copper"),
                        new OrdreMachines.Regle<>("tin"::equals, 1, false, "Tin")));
        regles.put(
                "bend",
                List.of(new OrdreMachines.Regle<>("bronze"::equals, 1, false, "Bronze Ingot")));
        var stock =
                List.of(
                        new OrdreMachines.StockSource<>(
                                new PlanRetrait.Stock<>("copper", 6, true),
                                OrdreMachines.Origine.POCHES),
                        new OrdreMachines.StockSource<>(
                                new PlanRetrait.Stock<>("tin", 2, true),
                                OrdreMachines.Origine.POCHES));
        var resultat =
                OrdreMachines.calculer(
                        plaque,
                        operations,
                        r -> regles.get(r),
                        stock,
                        r -> OrdreMachines.Condition.prete(),
                        true);
        var etapes = RangsRecette.numeroter(resultat.lignes(), List.of(), r -> 0);
        assert etapes.get(0).recette().equals("alloy")
                        && etapes.get(0).statut() == RangsRecette.Statut.PRETE
                : etapes;
        assert etapes.get(1).recette().equals("bend")
                && etapes.get(1).statut() == RangsRecette.Statut.ATTEND;
        assert etapes.get(1).attend() == 1;
        var manquante =
                OrdreMachines.calculer(
                        plaque,
                        operations,
                        r -> regles.get(r),
                        stock,
                        r ->
                                r.equals("bend")
                                        ? new OrdreMachines.Condition(
                                                OrdreMachines.Mode.BLOQUE,
                                                "Machine manquante : Basic Bender")
                                        : OrdreMachines.Condition.prete(),
                        true);
        var sans = RangsRecette.numeroter(manquante.lignes(), List.of(), r -> 0);
        assert sans.get(1).statut() == RangsRecette.Statut.POSTE_MANQUANT : sans;
        var reperes =
                RangsRecette.reperes(
                        etapes, List.of("bender"), (p, r) -> r.equals("bend"), Map.of(), 16);
        assert reperes.get("bender").attenue() && !reperes.get("bender").etoile();
    }

    // ---------- Racine ----------

    private static SelectionAlternatives.Noeud<String> noeud(
            String cle,
            int profondeur,
            boolean resolu,
            boolean outil,
            boolean variante,
            boolean objectif,
            int variantes,
            double besoin) {
        return new SelectionAlternatives.Noeud<>(
                cle, profondeur, resolu, outil, variante, objectif, variantes, besoin);
    }

    /** Exemple du joueur : Iron Plate choisie comme but avec la recette d'établi. */
    private static void racine() {
        var noeuds =
                List.of(
                        noeud("iron_plate", 0, true, false, false, false, 1, 8),
                        noeud("iron_ingot", 1, false, false, false, false, 1, 16),
                        noeud("hammer", 1, true, true, false, false, 1, 1),
                        noeud("#plates", 1, true, false, true, false, 3, 2),
                        noeud("iron_rod", 2, true, false, false, false, 1, 4),
                        noeud("iron_rod", 3, true, false, false, false, 1, 2),
                        noeud("#rods", 2, true, false, false, false, 2, 1));
        var choisis = SelectionAlternatives.choisir(noeuds, 256);
        assert choisis.size() == 2 : choisis;
        assert choisis.get(0).index() == 0 && choisis.get(0).racine()
                : "root analysed like any node";
        assert proche(choisis.get(0).besoin(), 8);
        assert choisis.get(1).index() == 4 && !choisis.get(1).racine();
        assert proche(choisis.get(1).besoin(), 6) : "one analysis per resolution key, needs summed";
        var objectif =
                SelectionAlternatives.choisir(
                        List.of(
                                noeud("book", 0, true, false, false, true, 1, 1),
                                noeud("iron_plate", 1, true, false, false, false, 1, 8)),
                        256);
        assert objectif.size() == 1 && objectif.get(0).index() == 1 && !objectif.get(0).racine()
                : "synthetic objective root skipped, its targets kept";
        assert SelectionAlternatives.choisir(noeuds, 1).size() == 1 : "cap respected";
        var monde =
                new Monde()
                        .recette("craft_plate", 1, "etabli", "iron_ingot", 2, "!hammer", 1)
                        .recette("bend_plate", 1, "bender", "iron_ingot", 1);
        var chaine = new ChaineRecette<>(monde);
        var actuelle = chaine.evaluer("craft_plate", "iron_plate");
        var bender = chaine.evaluer("bend_plate", "iron_plate");
        assert ComparaisonAlternatives.meilleure(bender, actuelle) : "Basic Bender proposed";
        assert proche(
                        ComparaisonAlternatives.economie(bender, actuelle, choisis.get(0).besoin()),
                        8)
                : "8 plates: 8 ingots saved";
        String gain =
                ComparaisonAlternatives.gain(
                        bender, actuelle, RecetteMoteurTest::nom, "Iron Plate");
        assert gain.equals("1 Iron Ingot au lieu de 2 par Iron Plate") : ascii(gain);
    }

    // ---------- Facile à fabriquer ----------

    private static FabricationFacile.Besoin besoin(long n, String... variantes) {
        return new FabricationFacile.Besoin(List.of(variantes), n, false);
    }

    private static void facile() {
        Map<String, List<FabricationFacile.Recette>> recettes = new HashMap<>();
        recettes.put(
                "lv_bender",
                List.of(
                        new FabricationFacile.Recette(
                                List.of(
                                        besoin(1, "lv_hull"),
                                        besoin(2, "lv_motor"),
                                        besoin(2, "lv_piston"),
                                        besoin(1, "basic_circuit", "basic_circuit_alt"),
                                        new FabricationFacile.Besoin(List.of("wrench"), 1, true)),
                                1)));
        recettes.put(
                "lv_piston",
                List.of(
                        new FabricationFacile.Recette(
                                List.of(besoin(1, "steel_plate"), besoin(1, "rod")), 1)));
        FabricationFacile.Recettes source = id -> recettes.getOrDefault(id, List.of());
        Map<String, Long> tout =
                Map.of(
                        "lv_hull",
                        1L,
                        "lv_motor",
                        2L,
                        "lv_piston",
                        2L,
                        "basic_circuit_alt",
                        1L,
                        "wrench",
                        1L);
        var direct = FabricationFacile.evaluer("lv_bender", source, tout, true);
        assert direct.facile() && direct.sousCrafts() == 0 : direct;
        Map<String, Long> sansPiston =
                Map.of(
                        "lv_hull",
                        1L,
                        "lv_motor",
                        2L,
                        "basic_circuit",
                        1L,
                        "wrench",
                        1L,
                        "steel_plate",
                        3L,
                        "rod",
                        2L);
        var sous = FabricationFacile.evaluer("lv_bender", source, sansPiston, true);
        assert sous.facile() && sous.sousCrafts() == 2 : sous;
        assert !FabricationFacile.evaluer("lv_bender", source, sansPiston, false).facile()
                : "no sub-craft allowed";
        Map<String, Long> pauvre =
                Map.of(
                        "lv_hull",
                        1L,
                        "lv_motor",
                        2L,
                        "basic_circuit",
                        1L,
                        "wrench",
                        1L,
                        "steel_plate",
                        1L,
                        "rod",
                        2L);
        var refus = FabricationFacile.evaluer("lv_bender", source, pauvre, true);
        assert !refus.facile() && refus.manque().equals("lv_piston") : refus;
        Map<String, Long> sansOutil =
                Map.of("lv_hull", 1L, "lv_motor", 2L, "lv_piston", 2L, "basic_circuit", 1L);
        assert !FabricationFacile.evaluer("lv_bender", source, sansOutil, true).facile()
                : "missing tool without recipe";
        assert !FabricationFacile.evaluer("inconnue", source, tout, true).facile();
    }

    private static String ascii(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray())
            b.append(c < 128 ? String.valueOf(c) : "\\u" + Integer.toHexString(c));
        return b.toString();
    }
}
