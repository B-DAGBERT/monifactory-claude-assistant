package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.Pont;
import fr.assistantia.StockProduction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;

/**
 * Vue de recommandation : stocks physiques et préconditions natives, sans action ni réservation.
 */
final class OrdonnancementEmi {
    private record Objet(EmiStack pile, boolean machine) {}

    private record Cache(
            InstantaneArbreEmi vue,
            List<PlanMachines.Operation<EmiRecipe>> operations,
            Object connexion,
            Object niveau,
            Object machine,
            List<String> stock,
            List<OrdreMachines.Condition> conditions,
            boolean synchronise,
            OrdreMachines.Resultat<EmiRecipe> resultat) {}

    private static Cache dernier;

    private OrdonnancementEmi() {}

    static void invalider() {
        dernier = null;
    }

    static OrdreMachines.Resultat<EmiRecipe> calculer(
            InstantaneArbreEmi vue,
            List<PlanMachines.Operation<EmiRecipe>> operations,
            AbstractContainerScreen<?> screen) {
        var mc = Minecraft.getInstance();
        boolean synchronise = InventaireJoueur.disponible();
        GregTechMachines.Interface machine = null;
        if (screen != null && mc.player != null && mc.player.containerMenu == screen.getMenu())
            try {
                machine = GregTechMachines.ouvrir(screen.getMenu());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Une machine illisible ne devient pas une condition d'envoi validée.
            }
        var actuelle = machine;
        var physiques =
                synchronise
                        ? stocks(operations, actuelle)
                        : List.<OrdreMachines.StockSource<Objet>>of();
        var signature = signatureStock(physiques);
        var conditions = new java.util.LinkedHashMap<EmiRecipe, OrdreMachines.Condition>();
        for (var operation : operations)
            conditions.put(operation.recette(), condition(operation.recette(), screen, actuelle));
        var etats = List.copyOf(conditions.values());
        Object machineNative = actuelle == null ? null : actuelle.machine();
        var precedent = dernier;
        if (precedent != null
                && precedent.vue() == vue
                && precedent.operations().equals(operations)
                && precedent.connexion() == mc.getConnection()
                && precedent.niveau() == mc.level
                && precedent.machine() == machineNative
                && precedent.synchronise() == synchronise
                && precedent.stock().equals(signature)
                && precedent.conditions().equals(etats)) return precedent.resultat();
        var regles = new HashMap<EmiRecipe, List<OrdreMachines.Regle<Objet>>>();
        for (var operation : operations)
            regles.put(operation.recette(), regles(operation.recette(), actuelle));
        var resultat =
                OrdreMachines.calculer(
                        vue.machines(),
                        operations,
                        regles::get,
                        physiques,
                        conditions::get,
                        synchronise);
        dernier =
                new Cache(
                        vue,
                        List.copyOf(operations),
                        mc.getConnection(),
                        mc.level,
                        machineNative,
                        signature,
                        etats,
                        synchronise,
                        resultat);
        return resultat;
    }

    private static List<String> signatureStock(List<OrdreMachines.StockSource<Objet>> stocks) {
        var comptes = new java.util.TreeMap<String, Long>();
        for (var source : stocks) {
            var objet = source.stock().objet();
            var pile = objet.pile();
            String cle =
                    source.origine()
                            + "|"
                            + objet.machine()
                            + "|"
                            + (pile.getItemStack().isEmpty() ? "fluide" : "item")
                            + "|"
                            + pile.getId()
                            + "|"
                            + pile.getNbt();
            comptes.merge(cle, (long) source.stock().quantite(), Long::sum);
        }
        return comptes.entrySet().stream().map(e -> e.getKey() + "|" + e.getValue()).toList();
    }

    private static boolean gregTech(EmiRecipe recette) {
        return recette.getClass()
                .getName()
                .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
    }

    private static OrdreMachines.Condition condition(
            EmiRecipe recette,
            AbstractContainerScreen<?> screen,
            GregTechMachines.Interface machine) {
        if (!gregTech(recette)) {
            if ("minecraft:crafting".equals(recette.getCategory().getId().toString()))
                return OrdreMachines.Condition.prete();
            return new OrdreMachines.Condition(
                    OrdreMachines.Mode.INCONNU, "Poste de fabrication à vérifier");
        }
        if (machine == null || !GregTechMachines.compatible(machine.machine(), recette))
            return new OrdreMachines.Condition(OrdreMachines.Mode.INCONNU, "Machine à vérifier");
        var plan = EnvoiMachine.preparer(screen, recette, 1);
        if (plan.possible()) return OrdreMachines.Condition.prete();
        String probleme = plan.probleme();
        if (probleme.startsWith("Circuit machine")) {
            if (probleme.contains("indisponible") || probleme.contains("illisible"))
                return new OrdreMachines.Condition(
                        OrdreMachines.Mode.INCONNU, "Réglage du circuit inconnu");
            return OrdreMachines.Condition.preparer("Circuit à régler · " + probleme);
        }
        if (probleme.startsWith("À collecter :"))
            return OrdreMachines.Condition.preparer("À récupérer dans les poches");
        if (probleme.startsWith("Pose l’objet"))
            return OrdreMachines.Condition.preparer("Ranger l’objet du curseur");
        if (probleme.startsWith("Libère une case"))
            return OrdreMachines.Condition.preparer("Libérer une case pour le contenant");
        if (probleme.startsWith("Retire d’abord") || probleme.startsWith("Pas assez de place"))
            return OrdreMachines.Condition.preparer("Entrées de la machine à libérer");
        if (probleme.startsWith("Fluide manquant ou cuve pleine"))
            return new OrdreMachines.Condition(
                    OrdreMachines.Mode.INCONNU, "Fluide ou capacité de cuve à vérifier");
        if (probleme.equals("Ingrédients déjà en machine"))
            return OrdreMachines.Condition.preparer("Déjà en machine · vérifier le lancement");
        return new OrdreMachines.Condition(
                OrdreMachines.Mode.BLOQUE,
                probleme.isEmpty() ? "Entrées de la machine à vérifier" : probleme);
    }

    private static List<OrdreMachines.Regle<Objet>> regles(
            EmiRecipe recette, GregTechMachines.Interface machine) {
        var ingredients = new ArrayList<>(recette.getInputs());
        ingredients.addAll(recette.getCatalysts());
        var regles = new ArrayList<OrdreMachines.Regle<Objet>>();
        for (int index = 0; index < ingredients.size(); index++) {
            var ingredient = ingredients.get(index);
            boolean outil = reutilisable(ingredient, index >= recette.getInputs().size());
            if (ingredient.isEmpty() || ComparaisonEmi.circuitVirtuel(recette, ingredient, outil))
                continue;
            int quantite = PlanMachines.produit(ingredient.getAmount(), 1);
            String partage =
                    ingredient.getEmiStacks().stream()
                            .map(pile -> pile.getId() + "|" + pile.getNbt())
                            .sorted()
                            .toList()
                            .toString();
            if (quantite <= 0) continue;
            boolean dansMachine =
                    machine != null
                            && gregTech(recette)
                            && GregTechMachines.compatible(machine.machine(), recette);
            String nom =
                    ingredient.getEmiStacks().isEmpty()
                            ? "Ingrédient inconnu"
                            : ingredient.getEmiStacks().get(0).getName().getString();
            if (ingredient.getEmiStacks().stream()
                    .anyMatch(
                            pile ->
                                    pile.getKey()
                                            instanceof net.minecraft.world.level.material.Fluid))
                nom += " (mB)";
            regles.add(
                    new OrdreMachines.Regle<>(
                            source ->
                                    (!source.machine() || dansMachine)
                                            && ingredient.getEmiStacks().stream()
                                                    .anyMatch(
                                                            attendu ->
                                                                    ComparaisonEmi.meme(
                                                                            source.pile(),
                                                                            attendu)),
                            quantite,
                            outil,
                            nom,
                            partage));
        }
        return List.copyOf(regles);
    }

    private static boolean reutilisable(EmiIngredient ingredient, boolean catalyseur) {
        return catalyseur
                || ingredient.getChance() == 0
                || !ingredient.getEmiStacks().isEmpty()
                        && ingredient.getEmiStacks().stream()
                                .allMatch(
                                        pile -> {
                                            var item = pile.getItemStack();
                                            return !item.isEmpty()
                                                    && !pile.getRemainder().isEmpty()
                                                    && pile.getRemainder()
                                                            .getItemStack()
                                                            .is(item.getItem());
                                        });
    }

    private static List<OrdreMachines.StockSource<Objet>> stocks(
            List<PlanMachines.Operation<EmiRecipe>> operations,
            GregTechMachines.Interface machine) {
        var resultat = new ArrayList<OrdreMachines.StockSource<Objet>>();
        var mc = Minecraft.getInstance();
        if (mc.player == null) return List.of();
        var coffre = StockProduction.capturer(System.currentTimeMillis());
        for (var pile : mc.player.getInventory().items)
            ajouter(resultat, pile, OrdreMachines.Origine.POCHES, false, operations);
        if (coffre == null) {
            for (var pile : InventaireJoueur.sacs())
                ajouter(resultat, pile, OrdreMachines.Origine.SAC, false, operations);
            for (var pile : mc.player.getInventory().armor)
                ajouter(resultat, pile, OrdreMachines.Origine.DEPLACER, false, operations);
            for (var pile : mc.player.getInventory().offhand)
                ajouter(resultat, pile, OrdreMachines.Origine.DEPLACER, false, operations);
        } else {
            for (var source : coffre.getAsJsonArray("sources")) {
                var json = source.getAsJsonObject();
                if (!json.get("id").getAsString().equals("coffre")) continue;
                for (var item : json.getAsJsonObject("items").entrySet()) {
                    var pile = Pont.objet(item.getKey());
                    if (!pile.isEmpty())
                        resultat.add(
                                new OrdreMachines.StockSource<>(
                                        new PlanRetrait.Stock<>(
                                                new Objet(EmiStack.of(pile), false),
                                                item.getValue().getAsInt(),
                                                false),
                                        OrdreMachines.Origine.COFFRE));
                }
            }
        }
        ajouter(
                resultat,
                mc.player.containerMenu.getCarried(),
                OrdreMachines.Origine.DEPLACER,
                false,
                operations);
        if (machine != null) {
            var cases =
                    java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<
                                    net.minecraft.world.inventory.Slot, Boolean>());
            for (var slot : machine.entrees())
                if (slot.isActive() && cases.add(slot))
                    ajouter(
                            resultat,
                            slot.getItem(),
                            OrdreMachines.Origine.MACHINE,
                            true,
                            operations);
            var cuves = new java.util.IdentityHashMap<Object, java.util.Set<Integer>>();
            for (var cuve : machine.cuves())
                if (cuves.computeIfAbsent(cuve.handler(), key -> new java.util.HashSet<>())
                        .add(cuve.index())) {
                    var fluide = cuve.contenu();
                    if (!fluide.isEmpty())
                        resultat.add(
                                new OrdreMachines.StockSource<>(
                                        new PlanRetrait.Stock<>(
                                                new Objet(
                                                        EmiStack.of(
                                                                fluide.getFluid(),
                                                                fluide.getTag(),
                                                                fluide.getAmount()),
                                                        true),
                                                fluide.getAmount(),
                                                true),
                                        OrdreMachines.Origine.MACHINE));
                }
        }
        return List.copyOf(resultat);
    }

    private static void ajouter(
            List<OrdreMachines.StockSource<Objet>> resultat,
            ItemStack item,
            OrdreMachines.Origine origine,
            boolean machine,
            List<PlanMachines.Operation<EmiRecipe>> operations) {
        if (item.isEmpty()) return;
        var pile = EmiStack.of(item.copy());
        resultat.add(
                new OrdreMachines.StockSource<>(
                        new PlanRetrait.Stock<>(new Objet(pile, machine), item.getCount(), true),
                        origine));
        var contenant = item.copy();
        contenant.setCount(1);
        var fluide = FluidUtil.getFluidContained(contenant).orElse(null);
        if (fluide == null || fluide.isEmpty()) return;
        // Un contenant requis comme objet ne fournit pas aussi son fluide à une autre recette.
        boolean objetRequis =
                operations.stream()
                        .anyMatch(
                                op ->
                                        java.util.stream.Stream.concat(
                                                        op.recette().getInputs().stream(),
                                                        op.recette().getCatalysts().stream())
                                                .anyMatch(
                                                        i ->
                                                                i.getEmiStacks().stream()
                                                                        .anyMatch(
                                                                                v ->
                                                                                        !v.getItemStack()
                                                                                                        .isEmpty()
                                                                                                && ComparaisonEmi
                                                                                                        .meme(
                                                                                                                pile,
                                                                                                                v))));
        if (objetRequis) return;
        resultat.add(
                new OrdreMachines.StockSource<>(
                        new PlanRetrait.Stock<>(
                                new Objet(
                                        EmiStack.of(
                                                fluide.getFluid(),
                                                fluide.getTag(),
                                                fluide.getAmount()),
                                        machine),
                                PlanMachines.produit(fluide.getAmount(), item.getCount()),
                                true),
                        origine));
    }
}
