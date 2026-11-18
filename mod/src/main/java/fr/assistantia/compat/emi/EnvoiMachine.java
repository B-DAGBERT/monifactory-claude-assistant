package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.SequenceEnvoi;
import fr.assistantia.RetraitCoffre;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Dépôt par les interactions normales du menu ; le serveur reste autoritaire. */
public final class EnvoiMachine {
    public record Plan(
            EmiRecipe recette,
            Object arbre,
            Object machine,
            int fois,
            List<String> details,
            String probleme) {
        public boolean possible() {
            return fois > 0 && probleme.isEmpty();
        }
    }

    private static SequenceEnvoi sequence;
    private static AbstractContainerScreen<?> ecran;
    private static Object arbre;
    private static Runnable succes;
    private static long dernierClic;
    private static String statut = "";

    public static boolean occupe() {
        return sequence != null;
    }

    public static String statut() {
        return sequence == null ? statut : "Envoi " + sequence.progression() + " %";
    }

    public static void effacerStatut() {
        if (!occupe()) statut = "";
    }

    public static boolean personnel(Slot slot) {
        var p = Minecraft.getInstance().player;
        return p != null && slot.container == p.getInventory() && slot.getContainerSlot() < 36;
    }

    private static boolean meme(ItemStack a, ItemStack b) {
        return a.isEmpty() && b.isEmpty()
                || ItemStack.isSameItemSameTags(a, b) && a.getCount() == b.getCount();
    }

    private static void clic(AbstractContainerMenu menu, Slot slot, int bouton) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu)
            throw new IllegalStateException("Interface fermée");
        mc.gameMode.handleInventoryMouseClick(
                menu.containerId, slot.index, bouton, ClickType.PICKUP, mc.player);
    }

    private static void verifier(boolean ok, String raison) {
        if (!ok) throw new IllegalStateException(raison);
    }

    private static List<EmiIngredient> ingredients(EmiRecipe r) {
        var all = new ArrayList<>(r.getInputs());
        all.addAll(r.getCatalysts());
        return all;
    }

    private static boolean accepte(EmiIngredient i, ItemStack s) {
        return !s.isEmpty()
                && i.getEmiStacks().stream()
                        .anyMatch(
                                v ->
                                        !v.getItemStack().isEmpty()
                                                && ComparaisonEmi.meme(EmiStack.of(s), v));
    }

    private static boolean accepte(EmiIngredient i, FluidStack f) {
        return !f.isEmpty()
                && i.getEmiStacks().stream()
                        .anyMatch(
                                v ->
                                        v.getKey() instanceof Fluid
                                                && EmiStack.of(
                                                                f.getFluid(),
                                                                f.getTag(),
                                                                f.getAmount())
                                                        .isEqual(v));
    }

    private static int circuitRequis(EmiRecipe recipe) throws ReflectiveOperationException {
        var tous = ingredients(recipe);
        for (int i = 0; i < tous.size(); i++) {
            var ingredient = tous.get(i);
            boolean outil = i >= recipe.getInputs().size() || ingredient.getChance() == 0;
            if (ComparaisonEmi.circuitVirtuel(recipe, ingredient, outil))
                return GregTechMachines.configurationCircuit(recipe);
        }
        return -1;
    }

    private static String problemeCircuit(GregTechMachines.Interface ui, int requis) {
        if (requis < 0) return "";
        try {
            int actuel = GregTechMachines.circuitConfigure(ui);
            return actuel == requis
                    ? ""
                    : "Circuit machine : régler sur "
                            + requis
                            + " (actuel : "
                            + (actuel < 0 ? "aucun" : actuel)
                            + ")";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "Circuit machine : lecture indisponible (requis : " + requis + ")";
        }
    }

    private record Entrees(
            AbstractContainerMenu menu,
            GregTechMachines.Interface ui,
            List<Slot> joueurs,
            Map<Slot, ItemStack> piles,
            List<FluidTank> cuves,
            List<FluidStack> fluides,
            List<EmiIngredient> ingredients,
            int circuit) {}

    private static Entrees lireEntrees(AbstractContainerMenu menu, EmiRecipe recette)
            throws ReflectiveOperationException {
        var ui = GregTechMachines.ouvrir(menu);
        verifier(
                ui != null && GregTechMachines.compatible(ui.machine(), recette),
                "Machine incompatible");
        verifier(!GregTechMachines.occupe(ui.machine()), "Machine en cours de fabrication");
        verifier(menu.getCarried().isEmpty(), "Pose l’objet tenu par la souris");
        int circuit;
        try {
            circuit = circuitRequis(recette);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "Circuit machine : configuration de la recette illisible", e);
        }
        String erreur = problemeCircuit(ui, circuit);
        verifier(erreur.isEmpty(), erreur);
        var joueurs =
                menu.slots.stream()
                        .filter(EnvoiMachine::personnel)
                        .filter(Slot::isActive)
                        .filter(s -> s.mayPickup(Minecraft.getInstance().player))
                        .toList();
        Map<Slot, ItemStack> piles = new IdentityHashMap<>();
        joueurs.forEach(s -> piles.put(s, s.getItem().copy()));
        ui.entrees().forEach(s -> piles.put(s, s.getItem().copy()));
        var cuves = ui.cuves().stream().map(GregTechMachines.Cuve::simulation).toList();
        return new Entrees(
                menu,
                ui,
                joueurs,
                piles,
                cuves,
                cuves.stream().map(c -> c.getFluid().copy()).toList(),
                ingredients(recette),
                circuit);
    }

    private static int borneObjets(Entrees entrees, EmiRecipe recette, int demande) {
        // Une recette fluide peut produire un contenant vide utilisable comme autre ingrédient.
        if (entrees.ingredients().stream()
                .anyMatch(
                        i -> i.getEmiStacks().stream().anyMatch(s -> s.getKey() instanceof Fluid)))
            return demande;
        int maximum = demande;
        for (var ingredient : recette.getInputs()) {
            if (ingredient.isEmpty() || ingredient.getChance() == 0 || ingredient.getAmount() <= 0)
                continue;
            long present =
                    entrees.piles().values().stream()
                            .filter(s -> accepte(ingredient, s))
                            .mapToLong(ItemStack::getCount)
                            .sum();
            // produit() borne déjà une demande à un million.
            if (present < PlanMachines.produit(ingredient.getAmount(), maximum))
                maximum = (int) (present / ingredient.getAmount());
        }
        return Math.max(1, maximum); // Garder la raison affichée quand même un seul craft échoue.
    }

    public static Plan preparer(AbstractContainerScreen<?> screen, EmiRecipe recipe, int demande) {
        if (demande <= 0) return vide("Ingrédients manquants");
        try {
            Object arbre = ArbreEmi.actif();
            if (arbre == null) return vide("Arbre EMI inactif");
            var entrees = lireEntrees(screen.getMenu(), recipe);
            Plan dernier = vide("Ingrédients manquants");
            for (int n = borneObjets(entrees, recipe, Math.min(64, demande)); n >= 1; n--) {
                dernier = calculer(entrees, recipe, arbre, n, null);
                if (dernier.possible()) return dernier;
            }
            return dernier;
        } catch (IllegalStateException e) {
            return vide(
                    e.getMessage() == null
                            ? "Entrées de cette machine non disponibles"
                            : e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return vide("Entrées de cette machine non disponibles");
        }
    }

    private static Plan vide(String message) {
        return new Plan(null, null, null, 0, List.of(), message);
    }

    private static Plan calculer(
            Entrees entrees,
            EmiRecipe recipe,
            Object arbre,
            int n,
            List<SequenceEnvoi.Action> actions)
            throws ReflectiveOperationException {
        var ui = entrees.ui();
        var menu = entrees.menu();
        int circuitRequis = entrees.circuit();
        if (actions != null)
            actions.add(
                    new SequenceEnvoi.Action() {
                        public void envoyer() {
                            verifier(
                                    GregTechMachines.compatible(ui.machine(), recipe)
                                            && !GregTechMachines.occupe(ui.machine()),
                                    "Machine modifiée ou déjà en cours de fabrication");
                            verifierCircuit(ui, circuitRequis);
                        }

                        public boolean confirmee() {
                            return true;
                        }
                    });
        boolean transfert = false;
        List<String> details = new ArrayList<>();
        if (circuitRequis >= 0) details.add("Circuit machine : " + circuitRequis + " · vérifié");
        List<Slot> joueurs = entrees.joueurs();
        Map<Slot, ItemStack> simulation = new IdentityHashMap<>(entrees.piles());
        List<FluidTank> cuves = entrees.cuves();
        for (int i = 0; i < cuves.size(); i++)
            cuves.get(i).setFluid(entrees.fluides().get(i).copy());
        var items = new ArrayList<PlanRetrait.Besoin<ItemStack>>();
        var nomsItems = new ArrayList<String>();
        var tous = entrees.ingredients();
        Map<String, Integer> fluidesDemandes = new HashMap<>();
        Map<String, Integer> outilsDemandes = new HashMap<>();
        for (int k = 0; k < tous.size(); k++) {
            var ingredient = tous.get(k);
            if (ingredient.isEmpty()) continue;
            boolean outil = k >= recipe.getInputs().size() || ingredient.getChance() == 0;
            if (ComparaisonEmi.circuitVirtuel(recipe, ingredient, outil)) continue;
            int besoin = PlanMachines.produit(ingredient.getAmount(), outil ? 1 : n);
            if (outil) {
                String key =
                        ingredient.getEmiStacks().stream()
                                .map(
                                        v ->
                                                v.getId()
                                                        + "|"
                                                        + (ComparaisonEmi.circuit(v)
                                                                ? ""
                                                                : v.getNbt()))
                                .sorted()
                                .toList()
                                .toString();
                int deja = outilsDemandes.getOrDefault(key, 0);
                outilsDemandes.merge(key, besoin, Math::max);
                besoin = Math.max(0, besoin - deja);
                if (besoin == 0) continue;
            }
            if (ingredient.getEmiStacks().stream().anyMatch(s -> !s.getItemStack().isEmpty())) {
                items.add(new PlanRetrait.Besoin<>(s -> accepte(ingredient, s), besoin));
                nomsItems.add(
                        ingredient.getEmiStacks().get(0).getName().getString()
                                + (outil ? " (non consommé)" : ""));
                details.add(
                        besoin + " × " + ingredient.getEmiStacks().get(0).getName().getString());
                continue;
            }
            if (ingredient.getEmiStacks().stream().noneMatch(s -> s.getKey() instanceof Fluid))
                return vide("Ingrédient non transférable");
            String cleFluide =
                    ingredient.getEmiStacks().stream()
                            .map(v -> v.getId() + "|" + v.getNbt())
                            .sorted()
                            .toList()
                            .toString();
            besoin = fluidesDemandes.merge(cleFluide, besoin, Integer::sum);
            int present = 0;
            for (var c : cuves)
                if (accepte(ingredient, c.getFluid())) present += c.getFluidAmount();
            int manque = Math.max(0, besoin - present), envoye = 0;
            int garde = 0;
            while (manque > 0 && garde++ < 128) {
                boolean trouve = false;
                for (Slot src : joueurs) {
                    ItemStack pile = simulation.get(src);
                    if (pile.isEmpty()) continue;
                    ItemStack un = pile.copy();
                    un.setCount(1);
                    FluidStack contenu = FluidUtil.getFluidContained(un).orElse(FluidStack.EMPTY);
                    if (!accepte(ingredient, contenu)) continue;
                    for (int t = 0; t < cuves.size(); t++) {
                        var cuve = cuves.get(t);
                        if (!cuve.getFluid().isEmpty() && !cuve.getFluid().isFluidEqual(contenu))
                            continue;
                        // Un clic natif peut aussi remplir un contenant : ne jamais inverser le
                        // transfert.
                        if (ui.cuves().get(t).peutRemplirContenant()
                                && !cuve.isEmpty()
                                && FluidUtil.tryFillContainer(
                                                un, cuve, Integer.MAX_VALUE, null, false)
                                        .isSuccess()) continue;
                        var essai =
                                FluidUtil.tryEmptyContainer(
                                        un, cuve, Integer.MAX_VALUE, null, false);
                        if (!essai.isSuccess()) continue;
                        Slot retour =
                                pile.getCount() == 1
                                        ? src
                                        : joueurs.stream()
                                                .filter(s -> simulation.get(s).isEmpty())
                                                .findFirst()
                                                .orElse(null);
                        if (retour == null) return vide("Libère une case pour le contenant vide");
                        int avant = cuve.getFluidAmount();
                        var resultat =
                                FluidUtil.tryEmptyContainer(
                                        un, cuve, Integer.MAX_VALUE, null, true);
                        int volume = cuve.getFluidAmount() - avant;
                        if (volume <= 0) continue;
                        ItemStack vide = resultat.getResult().copy();
                        if (actions != null)
                            actions.addAll(
                                    fluide(
                                            menu,
                                            src,
                                            retour,
                                            pile.copy(),
                                            un,
                                            vide,
                                            ui.cuves().get(t),
                                            ui,
                                            circuitRequis,
                                            recipe));
                        transfert = true;
                        ItemStack restant = pile.copy();
                        restant.shrink(1);
                        simulation.put(src, restant);
                        simulation.put(retour, vide);
                        manque = Math.max(0, manque - volume);
                        envoye += volume;
                        trouve = true;
                        break;
                    }
                    if (trouve) break;
                }
                if (!trouve)
                    return vide(
                            "Fluide manquant ou cuve pleine : "
                                    + ingredient.getEmiStacks().get(0).getName().getString());
            }
            if (manque > 0) return vide("Trop de contenants pour un envoi");
            details.add(
                    ingredient.getEmiStacks().get(0).getName().getString()
                            + " : "
                            + envoye
                            + " mB à envoyer (besoin "
                            + besoin
                            + " mB)");
        }
        // Réserver les entrées existantes avant les objets du joueur, avec les équivalences EMI.
        List<Slot> sources = new ArrayList<>(ui.entrees());
        sources.addAll(joueurs);
        List<PlanRetrait.Stock<ItemStack>> stocks = new ArrayList<>();
        for (Slot s : sources) {
            ItemStack item = simulation.get(s);
            if (!personnel(s)
                    && !item.isEmpty()
                    && items.stream().noneMatch(b -> b.accepte().test(item)))
                return vide("Retire d’abord les autres ingrédients de la machine");
            stocks.add(new PlanRetrait.Stock<>(item, item.getCount(), !personnel(s)));
        }
        var allocation = PlanRetrait.calculer(stocks, items);
        if (allocation.manquants() > 0) {
            var manquants = new ArrayList<String>();
            for (int i = 0; i < items.size(); i++) {
                int nManquant = items.get(i).quantite() - allocation.fournis().get(i);
                if (nManquant > 0) manquants.add(nManquant + " × " + nomsItems.get(i));
            }
            return new Plan(
                    null,
                    null,
                    null,
                    0,
                    List.copyOf(details),
                    "À collecter : " + String.join(", ", manquants));
        }
        Map<Slot, ItemStack> destinations = new IdentityHashMap<>();
        ui.entrees().forEach(s -> destinations.put(s, entrees.piles().get(s)));
        for (var prise : allocation.prises()) {
            Slot src = sources.get(prise.index());
            ItemStack original = simulation.get(src).copy();
            int restant = prise.quantite();
            for (Slot dest : ui.entrees()) {
                ItemStack contenu = destinations.get(dest);
                if (!dest.isActive()
                        || !dest.mayPlace(original)
                        || !contenu.isEmpty() && !ItemStack.isSameItemSameTags(original, contenu))
                    continue;
                int place =
                        Math.min(original.getMaxStackSize(), dest.getMaxStackSize(original))
                                - contenu.getCount();
                int quantite = Math.min(place, restant);
                if (quantite <= 0) continue;
                ItemStack attendu = simulation.get(src).copy();
                if (actions != null)
                    actions.add(
                            objets(menu, src, dest, attendu, quantite, ui, circuitRequis, recipe));
                transfert = true;
                ItemStack apres = attendu.copy();
                apres.shrink(quantite);
                simulation.put(src, apres);
                ItemStack nouveau = original.copy();
                nouveau.setCount(contenu.getCount() + quantite);
                destinations.put(dest, nouveau);
                restant -= quantite;
                if (restant == 0) break;
            }
            if (restant > 0) return vide("Pas assez de place dans les entrées de la machine");
        }
        if (!transfert) return vide("Ingrédients déjà en machine");
        return new Plan(recipe, arbre, ui.machine(), n, List.copyOf(details), "");
    }

    private static void verifierCircuit(GregTechMachines.Interface ui, int circuit) {
        String probleme = problemeCircuit(ui, circuit);
        verifier(probleme.isEmpty(), probleme);
    }

    private record SortieTemoin(EmiStack produit, long avant) {}

    private record SortiesDepot(
            List<Slot> objets, List<GregTechMachines.Cuve> fluides, List<SortieTemoin> temoins) {
        static SortiesDepot relever(AbstractContainerMenu menu, EmiRecipe recette)
                throws ReflectiveOperationException {
            var objets = GregTechMachines.sorties(menu).stream().filter(Slot::isActive).toList();
            var fluides = GregTechMachines.cuvesSortie(menu);
            var temoins = new ArrayList<SortieTemoin>();
            for (var produit : recette.getOutputs())
                if (!produit.isEmpty() && produit.getChance() >= 1 && produit.getAmount() > 0)
                    temoins.add(new SortieTemoin(produit, quantite(objets, fluides, produit)));
            return new SortiesDepot(objets, fluides, temoins);
        }

        boolean nouvellesSorties(long fabrications) {
            if (objets.stream().anyMatch(slot -> !slot.isActive())) return false;
            for (var temoin : temoins)
                if ((quantite(objets, fluides, temoin.produit()) - temoin.avant())
                                / temoin.produit().getAmount()
                        >= fabrications) return true;
            return false;
        }

        boolean seuleSortieVide() {
            return objets.size() == 1
                    && fluides.isEmpty()
                    && temoins.size() == 1
                    && objets.get(0).getItem().isEmpty();
        }

        boolean seuleSortieExacte() {
            var temoin = temoins.get(0);
            return objets.get(0).isActive()
                    && temoin.produit().getAmount() == quantite(objets, fluides, temoin.produit());
        }

        private static long quantite(
                List<Slot> objets, List<GregTechMachines.Cuve> fluides, EmiStack produit) {
            var item = produit.getItemStack();
            if (!item.isEmpty())
                return objets.stream()
                        .map(Slot::getItem)
                        .filter(s -> !s.isEmpty() && ItemStack.isSameItemSameTags(s, item))
                        .mapToLong(ItemStack::getCount)
                        .sum();
            return fluides.stream()
                    .map(GregTechMachines.Cuve::contenu)
                    .filter(
                            f ->
                                    !f.isEmpty()
                                            && f.getFluid().equals(produit.getKey())
                                            && Objects.equals(f.getTag(), produit.getNbt()))
                    .mapToLong(FluidStack::getAmount)
                    .sum();
        }
    }

    private abstract static class Depot implements SequenceEnvoi.Action {
        private final AbstractContainerMenu menu;
        private final GregTechMachines.Interface ui;
        private final EmiRecipe recette;
        private LongSupplier bilanConsommation;
        private SortiesDepot sorties;
        private long fabricationsDemarrees, fabricationsSorties;
        private boolean bilanNatifSimple, autreRecetteObservee;

        Depot(AbstractContainerMenu menu, GregTechMachines.Interface ui, EmiRecipe recette) {
            this.menu = menu;
            this.ui = ui;
            this.recette = recette;
        }

        final void avantDepot(
                Predicate<EmiIngredient> correspond,
                ToLongFunction<EmiIngredient> quantite,
                int envoye,
                ItemStack objetEnvoye) {
            var execution = GregTechMachines.execution(ui.machine(), recette);
            if (execution == null || execution.active()) return;
            var consommables =
                    recette.getInputs().stream()
                            .filter(i -> !i.isEmpty() && i.getChance() > 0 && correspond.test(i))
                            .toList();
            if (consommables.size() != 1) return;
            var ingredient = consommables.get(0);
            // L'entrée manquait avant le clic. Le solde doit correspondre à des cycles entiers.
            try {
                long requis = ingredient.getAmount();
                long avant = quantite.applyAsLong(ingredient);
                if (ingredient.getChance() < 1
                        || requis <= 0
                        || avant >= requis
                        || avant + envoye < requis) return;
                bilanConsommation =
                        () -> {
                            long consomme = avant + envoye - quantite.applyAsLong(ingredient);
                            return consomme > 0 && consomme % requis == 0 ? consomme / requis : 0;
                        };
                sorties = SortiesDepot.relever(menu, recette);
                var variantes = ingredient.getEmiStacks();
                bilanNatifSimple =
                        avant == 0
                                && envoye == requis
                                && !execution.autreRecette()
                                && sorties.seuleSortieVide()
                                && ui.entrees().stream().filter(Slot::isActive).count() == 1
                                && ui.entrees().stream()
                                        .filter(Slot::isActive)
                                        .allMatch(s -> s.getItem().isEmpty())
                                && ui.cuves().isEmpty()
                                && recette.getOutputs().size() == 1
                                && recette.getInputs().stream()
                                                .filter(i -> !i.isEmpty() && i.getChance() > 0)
                                                .count()
                                        == 1
                                && variantes.size() == 1
                                && !objetEnvoye.isEmpty()
                                && ItemStack.isSameItemSameTags(
                                        variantes.get(0).getItemStack(), objetEnvoye);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // L'interface peut ne pas exposer toutes les sorties. Le dépôt exact reste vérifié.
            }
        }

        @Override
        public final void observer() {
            if (bilanConsommation == null) return;
            var execution = GregTechMachines.execution(ui.machine(), recette);
            if (execution == null) return;
            if (execution.autreRecette()) {
                autreRecetteObservee = true;
                fabricationsDemarrees = fabricationsSorties = 0;
            }
            if (!execution.recetteAttendue()) return;
            try {
                long fabrications = bilanConsommation.getAsLong();
                if (execution.active())
                    fabricationsDemarrees = Math.max(fabricationsDemarrees, fabrications);
                // Au repos, la recette et la nouvelle sortie doivent être observées ensemble.
                else if (sorties != null
                        && fabrications > fabricationsSorties
                        && sorties.nouvellesSorties(fabrications))
                    fabricationsSorties = fabrications;
            } catch (RuntimeException ignored) {
                // Attendre un relevé de sortie exploitable.
            }
        }

        final boolean consommationConfirmee() {
            if (bilanConsommation == null) return false;
            try {
                long fabrications = bilanConsommation.getAsLong();
                // La dernière sortie peut être synchronisée après la disparition de l'ID.
                return fabrications == 1 && fabricationsDemarrees >= 1
                        || fabrications > 0
                                && fabrications
                                        <= Math.max(fabricationsDemarrees, fabricationsSorties)
                                && sorties != null
                                && sorties.nouvellesSorties(fabrications)
                        // LDLib peut publier uniquement l'état final d'un cycle très court.
                        || fabrications == 1
                                && bilanNatifSimple
                                && !autreRecetteObservee
                                && sorties.seuleSortieExacte();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    private static SequenceEnvoi.Action objets(
            AbstractContainerMenu menu,
            Slot src,
            Slot dest,
            ItemStack original,
            int n,
            GregTechMachines.Interface ui,
            int circuit,
            EmiRecipe recette) {
        ItemStack restant = original.copy();
        restant.shrink(n);
        return new Depot(menu, ui, recette) {
            private ItemStack destinationAttendue;

            public void envoyer() {
                verifierCircuit(ui, circuit);
                verifier(
                        menu.getCarried().isEmpty()
                                && src.isActive()
                                && src.mayPickup(Minecraft.getInstance().player)
                                && meme(src.getItem(), original),
                        "Inventaire modifié : envoi interrompu");
                verifier(
                        dest.isActive()
                                && dest.mayPlace(original)
                                && (dest.getItem().isEmpty()
                                        || ItemStack.isSameItemSameTags(dest.getItem(), original))
                                && dest.getMaxStackSize(original) - dest.getItem().getCount() >= n,
                        "Entrée modifiée ou pleine");
                destinationAttendue = original.copy();
                destinationAttendue.setCount(dest.getItem().getCount() + n);
                avantDepot(
                        i -> accepte(i, original),
                        i ->
                                ui.entrees().stream()
                                        .filter(Slot::isActive)
                                        .map(Slot::getItem)
                                        .filter(s -> accepte(i, s))
                                        .mapToLong(ItemStack::getCount)
                                        .sum(),
                        n,
                        original);
                clic(menu, src, 0);
                if (n == original.getCount()) clic(menu, dest, 0);
                else {
                    int surplus = original.getCount() - n;
                    if (surplus < n) {
                        for (int i = 0; i < surplus; i++) clic(menu, src, 1);
                        clic(menu, dest, 0);
                    } else {
                        for (int i = 0; i < n; i++) clic(menu, dest, 1);
                        clic(menu, src, 0);
                    }
                }
            }

            public boolean confirmee() {
                var actuel = dest.getItem();
                return menu.getCarried().isEmpty()
                        && meme(src.getItem(), restant)
                        && dest.isActive()
                        && (meme(actuel, destinationAttendue)
                                || (actuel.isEmpty()
                                                || ItemStack.isSameItemSameTags(actuel, original))
                                        && actuel.getCount() < destinationAttendue.getCount()
                                        && consommationConfirmee());
            }
        };
    }

    private static List<SequenceEnvoi.Action> fluide(
            AbstractContainerMenu menu,
            Slot src,
            Slot retour,
            ItemStack original,
            ItemStack un,
            ItemStack vide,
            GregTechMachines.Cuve cuve,
            GregTechMachines.Interface ui,
            int circuit,
            EmiRecipe recette) {
        ItemStack restant = original.copy();
        restant.shrink(1);
        class Vidage extends Depot {
            private String interruption = "";
            private FluidStack destinationAttendue;

            Vidage() {
                super(menu, ui, recette);
            }

            public void envoyer() throws Exception {
                verifier(meme(menu.getCarried(), un), "Contenant modifié");
                interruption = problemeCircuit(ui, circuit);
                if (!interruption.isEmpty()) {
                    verifier(
                            src.isActive()
                                    && src.mayPlace(un)
                                    && meme(src.getItem(), restant)
                                    && src.getMaxStackSize(un) >= original.getCount(),
                            interruption + " · range le contenant tenu par la souris.");
                    clic(menu, src, 0);
                    return;
                }
                var simulation = cuve.simulation();
                verifier(
                        !cuve.peutRemplirContenant()
                                || simulation.isEmpty()
                                || !FluidUtil.tryFillContainer(
                                                un, simulation, Integer.MAX_VALUE, null, false)
                                        .isSuccess(),
                        "Le réservoir ne peut plus recevoir ce contenant");
                int avant = simulation.getFluidAmount();
                var essai =
                        FluidUtil.tryEmptyContainer(un, simulation, Integer.MAX_VALUE, null, true);
                verifier(
                        essai.isSuccess() && meme(essai.getResult(), vide),
                        "Volume disponible dans la cuve modifié");
                destinationAttendue = simulation.getFluid().copy();
                avantDepot(
                        i -> accepte(i, destinationAttendue),
                        i ->
                                ui.cuves().stream()
                                        .map(GregTechMachines.Cuve::contenu)
                                        .filter(f -> accepte(i, f))
                                        .mapToLong(FluidStack::getAmount)
                                        .sum(),
                        destinationAttendue.getAmount() - avant,
                        ItemStack.EMPTY);
                cuve.viderContenant();
            }

            public boolean confirmee() {
                if (interruption.isEmpty()) {
                    var contenu = cuve.contenu();
                    return meme(menu.getCarried(), vide)
                            && meme(src.getItem(), restant)
                            && (contenu.getAmount() == destinationAttendue.getAmount()
                                            && contenu.isFluidEqual(destinationAttendue)
                                    || (contenu.isEmpty()
                                                    || contenu.isFluidEqual(destinationAttendue))
                                            && contenu.getAmount() < destinationAttendue.getAmount()
                                            && consommationConfirmee());
                }
                return menu.getCarried().isEmpty() && meme(src.getItem(), original);
            }
        }
        var vidage = new Vidage();
        return List.of(
                new SequenceEnvoi.Action() {
                    public void envoyer() {
                        verifierCircuit(ui, circuit);
                        verifier(
                                menu.getCarried().isEmpty()
                                        && src.isActive()
                                        && src.mayPickup(Minecraft.getInstance().player)
                                        && meme(src.getItem(), original),
                                "Contenant déplacé");
                        verifier(
                                src == retour || retour.getItem().isEmpty(),
                                "Case du contenant vide occupée");
                        clic(menu, src, 0);
                        for (int i = 1; i < original.getCount(); i++) clic(menu, src, 1);
                    }

                    public boolean confirmee() {
                        return meme(menu.getCarried(), un) && meme(src.getItem(), restant);
                    }
                },
                vidage,
                new SequenceEnvoi.Action() {
                    public void envoyer() {
                        // Ce garde arrive après le retour confirmé du plein par Vidage,
                        // ou laisse ranger le vide après un vidage déjà accepté.
                        verifier(vidage.interruption.isEmpty(), vidage.interruption);
                        verifier(meme(menu.getCarried(), vide), "Contenant vide non confirmé");
                        verifier(
                                retour.isActive()
                                        && retour.getItem().isEmpty()
                                        && (vide.isEmpty() || retour.mayPlace(vide)),
                                "Case du contenant vide indisponible");
                        if (!vide.isEmpty()) clic(menu, retour, 0);
                    }

                    public boolean confirmee() {
                        return menu.getCarried().isEmpty() && meme(retour.getItem(), vide);
                    }
                });
    }

    public static void lancer(AbstractContainerScreen<?> screen, Plan plan, Runnable termine) {
        var mc = Minecraft.getInstance();
        if (occupe()
                || RetraitCoffre.occupe()
                || !plan.possible()
                || mc.player == null
                || mc.gameMode == null
                || mc.screen != screen
                || mc.player.containerMenu != screen.getMenu()
                || !screen.getMenu().getCarried().isEmpty()
                || System.nanoTime() - dernierClic < 1_000_000_000L) return;
        var actions = new ArrayList<SequenceEnvoi.Action>();
        try {
            Object courant = ArbreEmi.actif();
            verifier(courant != null && courant == plan.arbre(), "Arbre EMI changé");
            var entrees = lireEntrees(screen.getMenu(), plan.recette());
            verifier(entrees.ui().machine() == plan.machine(), "Machine changée");
            var confirme = calculer(entrees, plan.recette(), courant, plan.fois(), actions);
            verifier(confirme.possible(), confirme.probleme());
            arbre = courant;
        } catch (IllegalStateException e) {
            statut =
                    e.getMessage() == null
                            ? "Entrées de cette machine non disponibles"
                            : e.getMessage();
            mc.player.displayClientMessage(Component.literal(statut), false);
            return;
        } catch (ReflectiveOperationException | RuntimeException e) {
            statut = "Entrées de cette machine non disponibles";
            mc.player.displayClientMessage(Component.literal(statut), false);
            return;
        }
        dernierClic = System.nanoTime();
        ecran = screen;
        succes = termine;
        sequence = new SequenceEnvoi(actions);
        statut = "Envoi en cours…";
    }

    public static void arreter() {
        if (sequence != null) sequence.annuler("Envoi interrompu");
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || sequence == null) return;
        var mc = Minecraft.getInstance();
        try {
            if (mc.player == null
                    || mc.screen != ecran
                    || mc.player.containerMenu != ecran.getMenu()
                    || ArbreEmi.actif() != arbre)
                sequence.annuler("Envoi interrompu : interface ou arbre EMI changé");
            sequence.tick();
            if (sequence.terminee()) {
                if (sequence.reussie()) {
                    succes.run();
                    statut = "Envoyé · récupère le résultat dans la machine";
                } else {
                    statut = sequence.erreur();
                    if (mc.player != null)
                        mc.player.displayClientMessage(Component.literal(statut), false);
                }
                sequence = null;
                ecran = null;
                succes = null;
            }
        } catch (Exception e) {
            sequence = null;
            ecran = null;
            succes = null;
            arbre = null;
            statut = "Envoi interrompu : vérifie les entrées de la machine";
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(statut), false);
        }
    }
}
