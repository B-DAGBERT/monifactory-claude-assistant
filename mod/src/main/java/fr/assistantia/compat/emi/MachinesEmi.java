package fr.assistantia.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.Lignes;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import fr.assistantia.OrdreMachines;
import fr.assistantia.LotFabrication;
import fr.assistantia.PlanRetrait;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Étapes machine du véritable arbre EMI : affichage rouge et dépôt explicite. */
public final class MachinesEmi {
    private static Object arbre, connexion;
    private static String dimension = "";
    private static InstantaneArbreEmi instantane;
    private static int ticks;
    private static List<PlanMachines.Operation<EmiRecipe>> operations = List.of(),
            locales = List.of();
    private static List<BlockPos> machines = List.of();
    private static AbstractContainerScreen<?> ecran;
    private static Disposition disposition;
    private static int choix, page;
    private static boolean choixExplicite;
    private static List<PlanMachines.Operation<EmiRecipe>> affichees = List.of();
    private static Disposition dispositionAffichee;
    private static EmiRecipe recetteAffichee;
    private static Object arbreAffiche;
    private static long revisionAffichee, quantiteAffichee;
    private static String contextePriorites = "", contexteAffiche = "";
    private static int pageAffichee;
    private static java.util.Map<EmiRecipe, OrdreMachines.Ligne<EmiRecipe>> prioritesLocales =
            java.util.Map.of();
    private static String problemeOrdre = "";
    private static EnvoiMachine.Plan plan;
    private static long actualise;
    private static final SuiviLotsEmi lots =
            new SuiviLotsEmi(
                    MachinesEmi::inventaire,
                    MachinesEmi::lotsModifies,
                    MachinesEmi::porteeProduction);
    private static List<PlanMachines.Operation<EmiRecipe>> toutes = List.of();
    private static String calculPrecedent = "";

    private record EntreesConnues(Object machine, List<PlanRetrait.Stock<EmiStack>> objets) {}

    private static final java.util.Map<BlockPos, EntreesConnues> entreesConnues =
            new java.util.LinkedHashMap<>();
    private static long entreesVersion, outilsVersion = -1;
    private static List<PlanMachines.Operation<EmiRecipe>> operationsOutils;
    private static List<PlanRetrait.Stock<EmiStack>> outilsConnus = List.of();
    private static boolean dernierTermine;
    private static final java.util.Map<Object, java.util.Map<EmiRecipe, Boolean>> compatibilites =
            new java.util.WeakHashMap<>();
    private static boolean erreurSignalee, erreurInterfaceSignalee;

    public record Ligne(
            int lot,
            EmiRecipe recette,
            int fois,
            String etat,
            boolean annulable,
            String detail,
            int groupe) {
        public Ligne(
                int lot,
                EmiRecipe recette,
                int fois,
                String etat,
                boolean annulable,
                String detail) {
            this(lot, recette, fois, etat, annulable, detail, lot > 0 ? 2 : 3);
        }
    }

    public static String problemeOrdre() {
        return problemeOrdre;
    }

    /** Identité de navigation, y compris lorsque le service Production n'est pas disponible. */
    public static String contexteSelection() {
        var vue = fr.assistantia.Pont.partie("ecran");
        String projet = "";
        var projets = vue.get("projets");
        if (projets != null && projets.isJsonObject())
            projet = ProjectionLotsEmi.texte(projets.getAsJsonObject(), "actif");
        String revision = "";
        var production = vue.get("production_v2");
        if (production != null && production.isJsonObject()) {
            var contexte = production.getAsJsonObject().get("contexte");
            if (contexte != null && contexte.isJsonObject())
                revision = ProjectionLotsEmi.texte(contexte.getAsJsonObject(), "revision_projet");
        }
        return fr.assistantia.Pont.cleContexte()
                + "|"
                + projet
                + "|"
                + ProjectionLotsEmi.texte(vue, "objectif_version")
                + "|"
                + revision;
    }

    private static ProjectionLotsEmi.Portee porteeProduction() {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread()
                || mc.player == null
                || mc.level == null
                || actif() == null
                || actif() != arbre
                || mc.getConnection() != connexion
                || !dimension.equals(mc.level.dimension().location().toString())) return null;
        var vue = fr.assistantia.Pont.partie("ecran");
        var projets = vue.get("projets");
        if (projets == null || !projets.isJsonObject()) return null;
        var production = vue.get("production_v2");
        if (production == null || !production.isJsonObject()) return null;
        var contexte = production.getAsJsonObject().get("contexte");
        if (contexte == null || !contexte.isJsonObject()) return null;
        long quantite;
        try {
            quantite = arbre.getClass().getField("batches").getLong(arbre);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
        return new ProjectionLotsEmi.Portee(
                arbre,
                connexion,
                EmiApi.getRecipeManager(),
                fr.assistantia.IdentiteMonde.monde(),
                fr.assistantia.IdentiteMonde.session(),
                dimension,
                ProjectionLotsEmi.texte(projets.getAsJsonObject(), "actif"),
                ProjectionLotsEmi.texte(contexte.getAsJsonObject(), "revision_projet"),
                InstantaneArbreEmi.revisionStructureProduction(),
                quantite);
    }

    /** Instantané du propriétaire, sans transfert ni actualisation forcée des réceptions. */
    public static com.google.gson.JsonObject lotsProduction(
            com.google.gson.JsonObject contexte, com.google.gson.JsonObject catalogue) {
        if (!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Projection des lots hors thread client");
        var vue = fr.assistantia.Pont.partie("ecran");
        var production = vue.get("production_v2");
        var actuel =
                production != null && production.isJsonObject()
                        ? production.getAsJsonObject().get("contexte")
                        : null;
        boolean courant = actuel != null && actuel.isJsonObject() && contexte != null;
        if (courant)
            for (String cle :
                    List.of(
                            "monde_actif",
                            "session_monde",
                            "dimension_monde",
                            "projet",
                            "revision_projet"))
                courant &=
                        contexte.has(cle)
                                && actuel.getAsJsonObject().has(cle)
                                && java.util.Objects.equals(
                                        contexte.get(cle), actuel.getAsJsonObject().get(cle));
        boolean synchronise =
                courant
                        && fr.assistantia.InventaireJoueur.disponible()
                        && !EnvoiMachine.occupe()
                        && !fr.assistantia.RetraitCoffre.occupe()
                        && !OrigineFabrication.receptionEnAttente();
        return ProjectionLotsEmi.lire(
                lots, porteeProduction(), inventaire(), synchronise, contexte, catalogue);
    }

    /**
     * Ajout, extension, réception, annulation ou vidage ; les étiquettes seules n'y figurent pas.
     */
    public static long revisionLotsProduction() {
        return lots.revision();
    }

    /** À utiliser au clic ou pour le défi natif ; aucune lecture par frame. */
    public static String empreinteLotsProduction(
            com.google.gson.JsonObject contexte, com.google.gson.JsonObject catalogue) {
        return lotsProduction(contexte, catalogue).get("empreinte").getAsString();
    }

    public static void termine() {
        dernierTermine = true;
    }

    public static boolean dernierTermine() {
        return dernierTermine;
    }

    public static void recevoir(List<PlanRetrait.Stock<ItemStack>> objets) {
        lots.recevoir(objets);
    }

    private static void lotsModifies() {
        InventaireArbreEmi.invalider();
        actualise = 0;
    }

    private static void ajouterFluide(List<PlanRetrait.Stock<EmiStack>> stock, ItemStack item) {
        if (item.isEmpty()) return;
        ItemStack un = item.copy();
        un.setCount(1);
        var f = FluidUtil.getFluidContained(un).orElse(null);
        if (f != null && !f.isEmpty())
            stock.add(
                    new PlanRetrait.Stock<>(
                            EmiStack.of(f.getFluid(), f.getTag(), f.getAmount()),
                            PlanMachines.produit(f.getAmount(), item.getCount()),
                            true));
    }

    public static void annulerLot(int id) {
        if (!EnvoiMachine.occupe() && lots.annuler(id)) actualiserArbre();
    }

    public static void recalculer() {
        if (EnvoiMachine.occupe()) return;
        calculPrecedent = "";
        compatibilites.clear();
        actualise = 0;
        actualiserArbre();
    }

    public static List<Ligne> lignes() {
        actualiserArbre();
        if (actif() == null || instantane == null) return List.of();
        List<Ligne> lignes = new ArrayList<>();
        var operationsVisibles =
                toutes.stream()
                        .filter(
                                o ->
                                        o.recette().getId() == null
                                                || !o.recette()
                                                        .getId()
                                                        .getNamespace()
                                                        .equals("assistantia"))
                        .toList();
        try {
            var ordre = OrdonnancementEmi.calculer(instantane, operationsVisibles, menuCourant());
            for (var recommandation : ordre.lignes()) {
                var operation = recommandation.operation();
                String detail =
                        operation.recette().getCategory().getName().getString()
                                + " · "
                                + (recommandation.foisPretes() > 0
                                        ? "1 fabrication recommandée sur " + operation.fois()
                                        : recommandation.raison());
                lignes.add(
                        new Ligne(
                                -1,
                                operation.recette(),
                                operation.fois(),
                                etat(recommandation),
                                false,
                                detail,
                                recommandation.groupe().ordinal()));
            }
        } catch (RuntimeException error) {
            problemeOrdre = "Ordre indisponible · vérifie l’arbre EMI.";
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : ordre EMI indisponible", error);
                erreurSignalee = true;
            }
            for (var operation : operationsVisibles)
                lignes.add(
                        new Ligne(
                                -1,
                                operation.recette(),
                                operation.fois(),
                                "Stock inconnu",
                                false,
                                problemeOrdre,
                                3));
        }
        for (var lot : lots.actifs())
            if (lot.suivi().etat() != LotFabrication.Etat.ANNULE)
                lignes.add(
                        new Ligne(
                                lot.id(),
                                lot.recette(),
                                lot.fois(),
                                lot.suivi().etat().texte,
                                lot.suivi().annulable(),
                                lot.suivi().totalRecu()
                                        + "/"
                                        + lot.suivi().total()
                                        + " récupérés · "
                                        + lot.lieu(),
                                2));
        lignes.sort(java.util.Comparator.comparingInt(Ligne::groupe));
        return List.copyOf(lignes);
    }

    private static AbstractContainerScreen<?> menuCourant() {
        var mc = Minecraft.getInstance();
        return ecran != null && mc.player != null && mc.player.containerMenu == ecran.getMenu()
                ? ecran
                : null;
    }

    private static String etat(OrdreMachines.Ligne<EmiRecipe> ligne) {
        if (ligne.cycle()) return "Cycle détecté";
        if (ligne.mode() == OrdreMachines.Mode.PRET) return "Prêt";
        if (ligne.conflit()) return "Stock partagé · autre priorité";
        String raison = ligne.raison();
        if (raison.startsWith("Circuit")) return "Circuit à régler";
        if (raison.startsWith("Machine")) return "Machine à vérifier";
        if (raison.startsWith("Poste")) return "Poste à vérifier";
        if (raison.startsWith("Réglage du circuit")) return "Circuit inconnu";
        if (raison.startsWith("Fluide ou capacité")) return "Fluide à vérifier";
        if (raison.startsWith("Déjà en machine")) return "Déjà en machine";
        if (ligne.mode() == OrdreMachines.Mode.PREPARER)
            return raison.isEmpty() ? "À préparer" : raison;
        if (ligne.mode() == OrdreMachines.Mode.INCONNU) return "Stock inconnu";
        return raison.isEmpty() ? "Étape suivante" : raison;
    }

    public static int declarables(EmiRecipe recette) {
        if (actif() == null
                || EnvoiMachine.occupe()
                || !fr.assistantia.InventaireJoueur.synchronise()
                || recette.getOutputs().stream()
                        .noneMatch(s -> !s.getItemStack().isEmpty() && s.getChance() >= 1))
            return 0;
        return toutes.stream()
                .filter(o -> o.recette() == recette)
                .mapToInt(PlanMachines.Operation::fois)
                .sum();
    }

    public static boolean completerLot(int id, EmiRecipe recette, int supplement) {
        actualiserArbre();
        if (supplement <= 0 || supplement > declarables(recette)) return false;
        if (!lots.completer(id, recette, supplement)) return false;
        actualiserArbre();
        return true;
    }

    public static Object arbreDeclaration() {
        return actif();
    }

    public static boolean declarer(EmiRecipe recette, int fois) {
        actualiserArbre();
        int max = declarables(recette);
        if (fois < 1 || fois > max) return false;
        if (lots.ajouter(recette, fois, null) == 0) return false;
        actualiserArbre();
        return true;
    }

    /** Stock de calcul temporaire pour EMI ; jamais ajouté à l'inventaire réel ni aux preuves. */
    public static List<PlanRetrait.Stock<EmiStack>> reservationsArbre() {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || actif() != arbre
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString())) return List.of();
        return lots.actifs().stream()
                .flatMap(l -> l.suivi().restants().stream())
                .map(s -> new PlanRetrait.Stock<>(s.objet().copy(), s.quantite(), true))
                .toList();
    }

    /** Historique acquis du craft courant, sans doublon avec les poches ni les cibles finales. */
    public static List<PlanRetrait.Stock<EmiStack>> creditsAcquis(
            List<PlanRetrait.Stock<EmiStack>> physiques,
            List<PlanRetrait.Stock<EmiStack>> dejaCredites) {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || actif() != arbre
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString())) return List.of();
        var acquis =
                lots.actifs().stream()
                        .flatMap(l -> l.suivi().obtenus().stream())
                        .filter(s -> !s.objet().getItemStack().isEmpty())
                        .toList();
        return fr.assistantia.ReceptionLots.credits(
                acquis, physiques, dejaCredites, ComparaisonEmi::meme);
    }

    /** Acquis hors des poches partagés avec le scan, sans les remettre dans les lots attendus. */
    public static List<PlanRetrait.Stock<ItemStack>> creditsObjets() {
        var finaux = CycleArbreEmi.credits();
        var deja =
                finaux.stream()
                        .map(
                                c ->
                                        new PlanRetrait.Stock<>(
                                                EmiStack.of(c.objet()), c.quantite(), true))
                        .toList();
        var resultat = new ArrayList<>(finaux);
        for (var credit : creditsAcquis(inventaire(), deja))
            resultat.add(
                    new PlanRetrait.Stock<>(
                            credit.objet().getItemStack(), credit.quantite(), true));
        return List.copyOf(resultat);
    }

    private static void retenirEntrees(GregTechMachines.Interface ui) {
        var objets =
                ui.entrees().stream()
                        .filter(net.minecraft.world.inventory.Slot::isActive)
                        .map(s -> s.getItem().copy())
                        .filter(s -> !s.isEmpty())
                        .map(s -> new PlanRetrait.Stock<>(EmiStack.of(s), s.getCount(), true))
                        .toList();
        var precedent =
                entreesConnues.put(
                        ui.position().immutable(), new EntreesConnues(ui.machine(), objets));
        if (precedent == null
                || !precedent.objets().equals(objets)
                || precedent.machine() != ui.machine()) {
            if (entreesConnues.size() > 64)
                entreesConnues.remove(entreesConnues.keySet().iterator().next());
            entreesVersion++;
            calculPrecedent = "";
            InventaireArbreEmi.invalider();
            actualiserArbre();
        }
    }

    private static List<PlanRetrait.Stock<EmiStack>> outilsMachines() {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString())) return List.of();
        if (entreesConnues
                .entrySet()
                .removeIf(
                        e ->
                                mc.level.hasChunkAt(e.getKey())
                                        && GregTechMachines.machine(
                                                        mc.level.getBlockEntity(e.getKey()))
                                                != e.getValue().machine())) entreesVersion++;
        if (outilsVersion == entreesVersion && operationsOutils == toutes) return outilsConnus;
        var resultat = new ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (var entree : entreesConnues.values())
            for (var objet : entree.objets()) {
                boolean utile =
                        toutes.stream()
                                .anyMatch(
                                        op -> {
                                            if (!compatible(entree.machine(), op.recette()))
                                                return false;
                                            var ingredients =
                                                    new ArrayList<>(op.recette().getInputs());
                                            ingredients.addAll(op.recette().getCatalysts());
                                            for (int i = 0; i < ingredients.size(); i++) {
                                                var ingredient = ingredients.get(i);
                                                boolean reutilisable =
                                                        i >= op.recette().getInputs().size()
                                                                || ingredient.getChance() == 0;
                                                if (reutilisable
                                                        && !ComparaisonEmi.circuitVirtuel(
                                                                op.recette(), ingredient, true)
                                                        && ingredient.getEmiStacks().stream()
                                                                .anyMatch(
                                                                        v ->
                                                                                ComparaisonEmi.meme(
                                                                                        objet
                                                                                                .objet(),
                                                                                        v)))
                                                    return true;
                                            }
                                            return false;
                                        });
                if (utile) resultat.add(objet);
            }
        outilsConnus = List.copyOf(resultat);
        outilsVersion = entreesVersion;
        operationsOutils = toutes;
        return outilsConnus;
    }

    public static List<PlanRetrait.Stock<EmiStack>> completerOutils(
            List<PlanRetrait.Stock<EmiStack>> deja) {
        return fr.assistantia.ReceptionLots.credits(
                outilsMachines(), deja, List.of(), ComparaisonEmi::meme);
    }

    public static java.util.Map<String, Integer> outilsObjets() {
        var outils = new java.util.HashMap<String, Integer>();
        for (var s : outilsMachines())
            outils.merge(s.objet().getId().toString(), s.quantite(), Integer::sum);
        return java.util.Map.copyOf(outils);
    }

    /** Réservations temporaires, distinctes de l'avancement réel et des acquisitions du scan. */
    public static java.util.Map<String, Integer> reservationsObjets() {
        var mc = Minecraft.getInstance();
        if (actif() == null
                || mc.level == null
                || connexion != mc.getConnection()
                || !dimension.equals(mc.level.dimension().location().toString()))
            return java.util.Map.of();
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (var lot : lots.actifs())
            for (var s : lot.suivi().restants()) {
                var item = s.objet().getItemStack();
                if (!item.isEmpty())
                    result.merge(
                            s.objet().getId().toString(),
                            s.quantite(),
                            (a, b) -> (int) Math.min(1_000_000L, (long) a + b));
            }
        return java.util.Map.copyOf(result);
    }

    private static boolean estGT(EmiRecipe recette) {
        return recette.getClass()
                .getName()
                .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
    }

    private static boolean compatible(Object machine, EmiRecipe recipe) {
        return compatibilites
                .computeIfAbsent(machine, k -> new java.util.HashMap<>())
                .computeIfAbsent(recipe, r -> GregTechMachines.compatible(machine, r));
    }

    private static Object actif() {
        return CoffresArbreEmi.arbreActif();
    }

    private static List<PlanRetrait.Stock<EmiStack>> inventaire() {
        return inventaire(true);
    }

    private static List<PlanRetrait.Stock<EmiStack>> inventaire(boolean avecSacs) {
        List<PlanRetrait.Stock<EmiStack>> stock = new ArrayList<>();
        var mc = Minecraft.getInstance();
        if (mc.player == null) return stock;
        for (ItemStack s :
                avecSacs
                        ? fr.assistantia.InventaireJoueur.piles()
                        : mc.player.getInventory().items) {
            if (s.isEmpty()) continue;
            stock.add(new PlanRetrait.Stock<>(EmiStack.of(s), s.getCount(), true));
            ItemStack un = s.copy();
            un.setCount(1);
            var fluid = FluidUtil.getFluidContained(un).orElse(null);
            if (fluid != null && !fluid.isEmpty())
                stock.add(
                        new PlanRetrait.Stock<>(
                                EmiStack.of(fluid.getFluid(), fluid.getTag(), fluid.getAmount()),
                                PlanMachines.produit(fluid.getAmount(), s.getCount()),
                                true));
        }
        return stock;
    }

    private static int compte(List<PlanRetrait.Stock<EmiStack>> stock, EmiStack objet) {
        return stock.stream()
                .filter(s -> s.objet().isEqual(objet))
                .mapToInt(PlanRetrait.Stock::quantite)
                .sum();
    }

    private static void envoyee(
            EmiRecipe recipe, int n, int demande, BlockPos position, Screen parent) {
        int id = lots.ajouter(recipe, n, position);
        actualiserArbre();
        if (demande > n && DeclarerFabricationEcran.demandeQuantite(recipe, demande))
            Minecraft.getInstance()
                    .setScreen(new DeclarerFabricationEcran(parent, recipe, n, demande, id));
    }

    private static String cleStock(List<PlanRetrait.Stock<EmiStack>> stock) {
        return stock.stream()
                .map(s -> s.objet().getId() + "|" + s.objet().getNbt() + "|" + s.quantite())
                .sorted()
                .toList()
                .toString();
    }

    private static void actualiserArbre() {
        var mc = Minecraft.getInstance();
        Object current = actif();
        String dim = mc.level == null ? "" : mc.level.dimension().location().toString();
        if (current != arbre || connexion != mc.getConnection() || !dim.equals(dimension)) {
            EnvoiMachine.arreter();
            if ((current == null && !fr.assistantia.PauseObjectifEmi.enPause())
                    || connexion != mc.getConnection()
                    || !dim.equals(dimension)) {
                lots.vider();
                entreesConnues.clear();
                entreesVersion++;
                outilsConnus = List.of();
                operationsOutils = null;
            }
            lots.oublierInterface();
            toutes = List.of();
            calculPrecedent = "";
            problemeOrdre = "";
            choixExplicite = false;
            affichees = List.of();
            dispositionAffichee = null;
            recetteAffichee = null;
            prioritesLocales = java.util.Map.of();
            compatibilites.clear();
            if (current != null || connexion != mc.getConnection()) dernierTermine = false;
            operations = locales = List.of();
            machines = List.of();
            disposition = null;
            instantane = null;
            arbre = current;
            connexion = mc.getConnection();
            dimension = dim;
            choix = page = 0;
        }
        if (arbre == null
                || mc.player == null
                || mc.level == null
                || EnvoiMachine.occupe()
                || !fr.assistantia.InventaireJoueur.synchronise()) return;
        try {
            var vue = InstantaneArbreEmi.lire(arbre);
            if (instantane != vue) {
                compatibilites.clear();
                calculPrecedent = "";
                instantane = vue;
            }
            var stock = inventaire();
            var finaux =
                    CycleArbreEmi.credits().stream()
                            .map(
                                    c ->
                                            new PlanRetrait.Stock<>(
                                                    EmiStack.of(c.objet()), c.quantite(), true))
                            .toList();
            stock.addAll(creditsAcquis(stock, finaux));
            stock.addAll(finaux);
            stock.addAll(completerOutils(stock));
            String cle = cleStock(stock) + "|" + lots.revision();
            if (cle.equals(calculPrecedent)) return;
            calculPrecedent = cle;
            // Les réceptions sorties des poches gardent un crédit de travail accompli.
            // Les quantités restantes seules restent réservées et recherchées en jaune.
            for (var lot : lots.actifs()) stock.addAll(lot.suivi().restants());
            toutes = PlanMachines.calculer(vue.machines(), vue.lots(), stock);
            operations = toutes.stream().filter(o -> estGT(o.recette())).toList();
            problemeOrdre = "";
        } catch (ReflectiveOperationException | RuntimeException e) {
            operations = toutes = List.of();
            calculPrecedent = "";
            problemeOrdre = "Arbre EMI invalide ou cyclique · choisis une autre branche.";
            if (!erreurSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : étapes machines EMI indisponibles", e);
                erreurSignalee = true;
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (actif() != arbre || ++ticks % 10 == 0) actualiserArbre();
        lots.actualiser();
        if (ticks % 100 == 0) compatibilites.clear();
        if (ticks % 40 != 0) return;
        var mc = Minecraft.getInstance();
        if (arbre == null || mc.level == null || mc.player == null || operations.isEmpty()) {
            machines = List.of();
            return;
        }
        List<BlockPos> trouvées = new ArrayList<>();
        BlockPos joueur = mc.player.blockPosition();
        for (int x = (joueur.getX() >> 4) - 3; x <= (joueur.getX() >> 4) + 3; x++)
            for (int z = (joueur.getZ() >> 4) - 3; z <= (joueur.getZ() >> 4) + 3; z++) {
                var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (be.getBlockPos().distSqr(joueur) > 48 * 48) continue;
                    Object machine = GregTechMachines.machine(be);
                    if (machine != null
                            && operations.stream().anyMatch(o -> compatible(machine, o.recette())))
                        trouvées.add(be.getBlockPos().immutable());
                }
            }
        machines = List.copyOf(trouvées);
    }

    @SubscribeEvent
    public static void monde(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || actif() == null
                || machines.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var buffers = mc.renderBuffers().bufferSource();
        var lignes = buffers.getBuffer(Lignes.TRAVERSANTES);
        var camera = event.getCamera().getPosition();
        for (var pos : machines)
            if (mc.level.hasChunkAt(pos) && !mc.level.getBlockState(pos).isAir())
                LevelRenderer.renderLineBox(
                        event.getPoseStack(),
                        lignes,
                        new AABB(pos).inflate(.006).move(-camera.x, -camera.y, -camera.z),
                        1f,
                        .12f,
                        .12f,
                        .95f);
        buffers.endBatch(Lignes.TRAVERSANTES);
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)
                || !GregTechMachines.estMachine(s.getMenu())) return;
        // Une recette EMI garde la machine en arrière-plan. Restaurer sa sélection au retour
        // comme lors d'un redimensionnement, au lieu de revenir au premier produit.
        boolean retour = ecran == s;
        if (!retour) {
            locales = affichees = List.of();
            choixExplicite = false;
            dispositionAffichee = null;
            recetteAffichee = null;
            choix = page = 0;
            EnvoiMachine.effacerStatut();
        }
        ecran = s;
        disposition = null;
        plan = null;
        actualise = 0;
    }

    private static boolean affiche(Screen s) {
        return s == ecran && ecran != null && actif() != null;
    }

    private static PlanMachines.Operation<EmiRecipe> selection() {
        return locales.isEmpty() ? null : locales.get(Math.min(choix, locales.size() - 1));
    }

    private static void actualiserInterface() {
        if (ecran == null || EnvoiMachine.occupe()) return;
        actualise = System.currentTimeMillis();
        actualiserArbre();
        try {
            var ui = GregTechMachines.ouvrir(ecran.getMenu());
            if (ui == null) {
                disposition = null;
                return;
            }
            retenirEntrees(ui);
            var precedent = selection();
            List<PlanMachines.Operation<EmiRecipe>> visibles =
                    new ArrayList<>(
                            operations.stream()
                                    .filter(
                                            o ->
                                                    GregTechMachines.compatible(
                                                            ui.machine(), o.recette()))
                                    .toList());
            var ordre = OrdonnancementEmi.calculer(instantane, visibles, ecran);
            var priorites =
                    new java.util.LinkedHashMap<EmiRecipe, OrdreMachines.Ligne<EmiRecipe>>();
            for (var ligne : ordre.lignes()) priorites.put(ligne.operation().recette(), ligne);
            prioritesLocales = java.util.Map.copyOf(priorites);
            visibles = new ArrayList<>();
            for (var ligne : ordre.lignes())
                if (ligne.groupe().ordinal() < 2) visibles.add(ligne.operation());
            for (var lot : lots.actifs())
                if (lot.ici(ui.position())
                        && lot.suivi().annulable()
                        && priorites.keySet().stream().noneMatch(r -> r == lot.recette())
                        && visibles.stream().noneMatch(o -> o.recette() == lot.recette()))
                    visibles.add(new PlanMachines.Operation<>(lot.recette(), 0));
            for (var ligne : ordre.lignes())
                if (ligne.groupe().ordinal() >= 2) visibles.add(ligne.operation());
            locales = List.copyOf(visibles);
            choix = 0;
            boolean conserve = false;
            if (choixExplicite && precedent != null)
                for (int i = 0; i < locales.size(); i++)
                    if (locales.get(i).recette() == precedent.recette()) {
                        choix = i;
                        conserve = true;
                        break;
                    }
            if (!conserve) choixExplicite = false;
            var selected = selection();
            if (selected == null) {
                disposition = null;
                plan = null;
                return;
            }
            plan =
                    selected.fois() == 0
                            ? null
                            : EnvoiMachine.preparer(ecran, selected.recette(), selected.fois());
            disposition =
                    DispositionCoffre.calculer(
                            ecran.width,
                            ecran.height,
                            PanneauAssistant.occupee(ecran),
                            locales.size(),
                            46,
                            104);
            page = Math.min(page, (locales.size() - 1) / disposition.parPage());
            if (choixExplicite
                    && (choix < page * disposition.parPage()
                            || choix >= (page + 1) * disposition.parPage()))
                page = choix / disposition.parPage();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Un panneau indisponible ne doit jamais interrompre le rendu du jeu entier.
            disposition = null;
            plan = null;
            locales = List.of();
            if (!erreurInterfaceSignalee) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : panneau machine indisponible", e);
                erreurInterfaceSignalee = true;
            }
        }
        contextePriorites = contexteSelection();
        actualise = System.currentTimeMillis();
    }

    public static Zone zoneEmi(Screen screen) {
        return affiche(screen) && disposition != null ? disposition.cadre() : null;
    }

    @SubscribeEvent
    public static void avant(ScreenEvent.Render.Pre event) {
        if (affiche(event.getScreen()) && System.currentTimeMillis() - actualise > 500)
            actualiserInterface();
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        var selected = selection();
        if (!affiche(event.getScreen()) || disposition == null || selected == null) {
            affichees = List.of();
            dispositionAffichee = null;
            recetteAffichee = null;
            return;
        }
        affichees = locales;
        pageAffichee = page;
        dispositionAffichee = disposition;
        recetteAffichee = selected.recette();
        arbreAffiche = actif();
        revisionAffichee = InstantaneArbreEmi.revisionStructureProduction();
        quantiteAffichee = instantane == null ? -1 : instantane.lots();
        contexteAffiche = contextePriorites;
        PanneauMachineEmi.afficher(
                event,
                new PanneauMachineEmi.Vue(
                        disposition,
                        locales,
                        choix,
                        page,
                        plan,
                        etatSelection(),
                        selected.fois() == 0 ? quantiteSelection() : "",
                        EnvoiMachine.statut().isEmpty() ? problemeOrdre : EnvoiMachine.statut(),
                        EnvoiMachine.occupe(),
                        circuitARegler(selected.recette()),
                        declarables(selected.recette()) > 0,
                        fr.assistantia.InventaireJoueur.synchronise(),
                        prioritesLocales));
    }

    private static String quantiteSelection() {
        var op = selection();
        int recu = 0, total = 0;
        for (var lot : lots.actifs())
            if (op != null && lot.recette() == op.recette() && lot.suivi().annulable()) {
                recu += lot.suivi().totalRecu();
                total += lot.suivi().total();
            }
        return recu + "/" + total;
    }

    private static boolean circuitARegler(EmiRecipe recette) {
        var selection = selection();
        return selection != null
                && selection.recette() == recette
                && plan != null
                && plan.probleme().startsWith("Circuit machine");
    }

    private static String etatSelection() {
        var op = selection();
        if (op == null) return "À collecter";
        if (op.fois() > 0) {
            if (circuitARegler(op.recette())) return "Circuit à régler";
            var priorite = prioritesLocales.get(op.recette());
            if (priorite != null) {
                if (priorite.conflit() && plan != null && plan.possible())
                    return "Autre priorité · stock partagé";
                return etat(priorite);
            }
            return "Stock inconnu";
        }
        return lots.actifs().stream()
                .filter(l -> l.recette() == op.recette() && l.suivi().annulable())
                .map(l -> l.suivi().etat().texte)
                .findFirst()
                .orElse("Terminé");
    }

    private static boolean surPanneau(Screen screen, double x, double y) {
        return affiche(screen)
                && disposition != null
                && dispositionAffichee != null
                && dispositionAffichee.cadre().contient(x, y);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (event.getButton() != 0 || EnvoiMachine.occupe()) return;
        var d = dispositionAffichee;
        double x = event.getMouseX(), y = event.getMouseY();
        if (d.prendre().contient(x, y)) {
            if (!selectionnerAffichee(recetteAffichee)) return;
            var op = selection();
            var envoi = plan;
            if (op != null && envoi != null && envoi.possible()) {
                PanneauAssistant.son();
                try {
                    var ui = GregTechMachines.ouvrir(ecran.getMenu());
                    if (ui != null)
                        EnvoiMachine.lancer(
                                ecran,
                                envoi,
                                () ->
                                        envoyee(
                                                op.recette(),
                                                envoi.fois(),
                                                op.fois(),
                                                ui.position(),
                                                ecran));
                } catch (ReflectiveOperationException ex) {
                    actualise = 0;
                }
            }
        } else if (d.tout().contient(x, y)) {
            if (!selectionnerAffichee(recetteAffichee)) return;
            var op = selection();
            if (op != null && op.fois() > 0 && declarables(op.recette()) > 0) {
                PanneauAssistant.son();
                DeclarerFabricationEcran.ouvrir(ecran, op.recette());
            }
        } else if (d.pages() && d.precedent().contient(x, y)) page = Math.max(0, pageAffichee - 1);
        else if (d.pages() && d.suivant().contient(x, y))
            page = Math.min((affichees.size() - 1) / d.parPage(), pageAffichee + 1);
        else
            for (int rang = 0; rang < d.parPage(); rang++) {
                int i = pageAffichee * d.parPage() + rang;
                if (i < affichees.size() && d.icone(rang).contient(x, y)) {
                    var recette = affichees.get(i).recette();
                    if (!selectionnerAffichee(recette)) return;
                    actualise = 0;
                    PanneauAssistant.son();
                    dev.emi.emi.api.EmiApi.displayRecipe(recette);
                    break;
                }
            }
    }

    /** Le clic désigne la recette rendue, même si les priorités ont changé entre deux ticks. */
    private static boolean selectionnerAffichee(EmiRecipe recette) {
        var mc = Minecraft.getInstance();
        if (recette == null
                || arbreAffiche != actif()
                || revisionAffichee != InstantaneArbreEmi.revisionStructureProduction()
                || !quantiteAfficheeValide()
                || !contexteAffiche.equals(contexteSelection())
                || mc.player == null
                || ecran == null
                || mc.player.containerMenu != ecran.getMenu()) {
            problemeOrdre = "Contexte modifié · attends le nouvel affichage.";
            return false;
        }
        actualiserInterface();
        for (int i = 0; i < locales.size(); i++)
            if (locales.get(i).recette() == recette) {
                choix = i;
                choixExplicite = true;
                var operation = locales.get(i);
                plan =
                        operation.fois() > 0
                                ? EnvoiMachine.preparer(ecran, recette, operation.fois())
                                : null;
                return true;
            }
        problemeOrdre = "Recette modifiée · attends le nouvel affichage.";
        return false;
    }

    private static boolean quantiteAfficheeValide() {
        try {
            return arbreAffiche != null
                    && arbreAffiche.getClass().getField("batches").getLong(arbreAffiche)
                            == quantiteAffichee;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void relache(ScreenEvent.MouseButtonReleased.Pre event) {
        if (surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY()))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void molette(ScreenEvent.MouseScrolled.Pre event) {
        if (!surPanneau(event.getScreen(), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        if (!EnvoiMachine.occupe())
            page =
                    Math.max(
                            0,
                            Math.min(
                                    (locales.size() - 1) / disposition.parPage(),
                                    page + (event.getScrollDelta() > 0 ? -1 : 1)));
    }
}
