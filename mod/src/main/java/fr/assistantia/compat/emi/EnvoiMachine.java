package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import fr.assistantia.SequenceEnvoi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
            int fois, List<SequenceEnvoi.Action> actions, List<String> details, String probleme) {
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
        return statut;
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
                        .anyMatch(v -> !v.getItemStack().isEmpty() && EmiStack.of(s).isEqual(v));
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

    private static boolean circuit(EmiIngredient i) {
        return i.getEmiStacks().stream()
                .anyMatch(v -> v.getId().toString().equals("gtceu:programmed_circuit"));
    }

    public static Plan preparer(AbstractContainerScreen<?> screen, EmiRecipe recipe, int demande) {
        try {
            var ui = GregTechMachines.ouvrir(screen.getMenu());
            if (ui == null || !GregTechMachines.compatible(ui.machine(), recipe))
                return vide("Machine incompatible");
            if (GregTechMachines.occupe(ui.machine()))
                return vide("Machine en cours de fabrication");
            if (!screen.getMenu().getCarried().isEmpty())
                return vide("Pose l’objet tenu par la souris");
            Plan dernier = vide("Ingrédients manquants");
            // Un lot borné par les piles et les cuves. Le reste restera proposé ensuite.
            for (int n = Math.min(64, demande); n >= 1; n--) {
                dernier = calculer(screen.getMenu(), ui, recipe, n);
                if (dernier.possible()) return dernier;
            }
            return dernier;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return vide("Entrées de cette machine non disponibles");
        }
    }

    private static Plan vide(String message) {
        return new Plan(0, List.of(), List.of(), message);
    }

    private static Plan calculer(
            AbstractContainerMenu menu, GregTechMachines.Interface ui, EmiRecipe recipe, int n)
            throws ReflectiveOperationException {
        List<SequenceEnvoi.Action> actions = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<Slot> joueurs =
                menu.slots.stream().filter(EnvoiMachine::personnel).filter(Slot::isActive).toList();
        Map<Slot, ItemStack> simulation = new IdentityHashMap<>();
        joueurs.forEach(s -> simulation.put(s, s.getItem().copy()));
        List<FluidTank> cuves = ui.cuves().stream().map(GregTechMachines.Cuve::simulation).toList();
        var items = new ArrayList<PlanRetrait.Besoin<ItemStack>>();
        var tous = ingredients(recipe);
        Map<String, Integer> fluidesDemandes = new HashMap<>();
        for (int k = 0; k < tous.size(); k++) {
            var ingredient = tous.get(k);
            if (ingredient.isEmpty()) continue;
            boolean outil = k >= recipe.getInputs().size() || ingredient.getChance() == 0;
            int besoin = PlanMachines.produit(ingredient.getAmount(), outil ? 1 : n);
            if (circuit(ingredient) && ui.circuit() != null) {
                int valeur =
                        GregTechMachines.circuit(ingredient.getEmiStacks().get(0).getItemStack());
                actions.add(
                        new SequenceEnvoi.Action() {
                            public void envoyer() throws Exception {
                                GregTechMachines.reglerCircuit(ui.circuit(), valeur);
                            }

                            public boolean confirmee() throws Exception {
                                ItemStack s =
                                        (ItemStack)
                                                GregTechMachines.appeler(ui.circuit(), "getItem");
                                return !s.isEmpty() && GregTechMachines.circuit(s) == valeur;
                            }
                        });
                details.add("Circuit : " + valeur);
                continue;
            }
            if (ingredient.getEmiStacks().stream().anyMatch(s -> !s.getItemStack().isEmpty())) {
                items.add(new PlanRetrait.Besoin<>(s -> accepte(ingredient, s), besoin));
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
                        ItemStack attendu = pile.copy(), vide = resultat.getResult().copy();
                        var cible = ui.cuves().get(t);
                        actions.addAll(fluide(menu, src, retour, attendu, un, vide, cible));
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
            ItemStack item = simulation.getOrDefault(s, s.getItem());
            if (!personnel(s)
                    && !item.isEmpty()
                    && items.stream().noneMatch(b -> b.accepte().test(item)))
                return vide("Retire d’abord les autres ingrédients de la machine");
            stocks.add(new PlanRetrait.Stock<>(item, item.getCount(), !personnel(s)));
        }
        var allocation = PlanRetrait.calculer(stocks, items);
        if (allocation.manquants() > 0) return vide("Ingrédients manquants dans ton inventaire");
        Map<Slot, ItemStack> destinations = new IdentityHashMap<>();
        ui.entrees().forEach(s -> destinations.put(s, s.getItem().copy()));
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
                actions.add(objets(menu, src, dest, attendu, quantite));
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
        if (actions.isEmpty()) return vide("Ingrédients déjà en machine");
        return new Plan(n, List.copyOf(actions), List.copyOf(details), "");
    }

    private static SequenceEnvoi.Action objets(
            AbstractContainerMenu menu, Slot src, Slot dest, ItemStack original, int n) {
        ItemStack restant = original.copy();
        restant.shrink(n);
        return new SequenceEnvoi.Action() {
            public void envoyer() {
                verifier(
                        menu.getCarried().isEmpty() && meme(src.getItem(), original),
                        "Inventaire modifié : envoi interrompu");
                verifier(
                        dest.mayPlace(original)
                                && (dest.getItem().isEmpty()
                                        || ItemStack.isSameItemSameTags(dest.getItem(), original))
                                && dest.getMaxStackSize(original) - dest.getItem().getCount() >= n,
                        "Entrée modifiée ou pleine");
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
                return menu.getCarried().isEmpty() && meme(src.getItem(), restant);
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
            GregTechMachines.Cuve cuve) {
        ItemStack restant = original.copy();
        restant.shrink(1);
        return List.of(
                new SequenceEnvoi.Action() {
                    public void envoyer() {
                        verifier(
                                menu.getCarried().isEmpty() && meme(src.getItem(), original),
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
                new SequenceEnvoi.Action() {
                    public void envoyer() throws Exception {
                        verifier(meme(menu.getCarried(), un), "Contenant modifié");
                        var simulation = cuve.simulation();
                        verifier(
                                !cuve.peutRemplirContenant()
                                        || simulation.isEmpty()
                                        || !FluidUtil.tryFillContainer(
                                                        un,
                                                        simulation,
                                                        Integer.MAX_VALUE,
                                                        null,
                                                        false)
                                                .isSuccess(),
                                "Le réservoir ne peut plus recevoir ce contenant");
                        var essai =
                                FluidUtil.tryEmptyContainer(
                                        un, simulation, Integer.MAX_VALUE, null, false);
                        verifier(
                                essai.isSuccess() && meme(essai.getResult(), vide),
                                "Volume disponible dans la cuve modifié");
                        cuve.viderContenant();
                    }

                    public boolean confirmee() {
                        return meme(menu.getCarried(), vide);
                    }
                },
                new SequenceEnvoi.Action() {
                    public void envoyer() {
                        verifier(meme(menu.getCarried(), vide), "Contenant vide non confirmé");
                        verifier(retour.getItem().isEmpty(), "Case du contenant vide occupée");
                        if (!vide.isEmpty()) clic(menu, retour, 0);
                    }

                    public boolean confirmee() {
                        return menu.getCarried().isEmpty() && meme(retour.getItem(), vide);
                    }
                });
    }

    public static void lancer(AbstractContainerScreen<?> screen, Plan plan, Runnable termine) {
        if (occupe() || !plan.possible() || System.nanoTime() - dernierClic < 1_000_000_000L)
            return;
        try {
            arbre = ArbreEmi.actif();
        } catch (Exception e) {
            return;
        }
        if (arbre == null) return;
        dernierClic = System.nanoTime();
        ecran = screen;
        succes = termine;
        sequence = new SequenceEnvoi(plan.actions());
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
            statut = "Envoi interrompu";
        }
    }
}
