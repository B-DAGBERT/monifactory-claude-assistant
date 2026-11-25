package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

/** Entrées physiques des postes de l'arbre, relues sur le thread du serveur intégré. */
final class EntreesTerminalMe {
    record Releve(
            Map<EmiRecipe, List<PlanRetrait.Stock<EmiStack>>> entrees,
            Set<EmiRecipe> verifies,
            Set<EmiRecipe> inconnus) {}

    static final Releve VIDE = new Releve(Map.of(), Set.of(), Set.of());

    private final List<EmiRecipe> recettes;
    // Le menu, la grille, la dernière observation et les mouvements appartiennent au serveur
    // intégré.
    private final Map<BlockPos, WeakReference<BlockEntity>> destinations = new HashMap<>();
    private WeakReference<AbstractContainerMenu> terminal = new WeakReference<>(null);
    private WeakReference<Object> reseau = new WeakReference<>(null);

    private final Mouvements mouvements = new Mouvements();
    private Mesure derniere;

    // Référence du serveur immédiatement après chaque retrait natif, par clé AE2 (NBT compris).
    private static final class Mouvements {
        final Map<Object, Long> niveaux = new HashMap<>();
        final Set<EmiRecipe> aVerifier = new LinkedHashSet<>();

        void relever(
                Map<Object, Long> annonce,
                Map<Object, Long> actuels,
                List<EmiRecipe> recettes,
                Set<EmiRecipe> verifies) {
            actuels.forEach(
                    (cle, n) -> {
                        long precedent = niveaux.getOrDefault(cle, annonce.getOrDefault(cle, n));
                        if (n < precedent) aVerifier.addAll(recettes);
                    });
            niveaux.putAll(actuels);
            aVerifier.removeAll(verifies);
        }

        void oublier() {
            niveaux.clear();
            aVerifier.clear();
        }
    }

    EntreesTerminalMe(InstantaneArbreEmi arbre) {
        recettes =
                PlanMachines.calculer(arbre.machines(), arbre.lots(), List.of()).stream()
                        .map(PlanMachines.Operation::recette)
                        .filter(MachinesEmi::estGT)
                        .toList();
    }

    List<EmiRecipe> recettes() {
        return recettes;
    }

    private record Mesure(Map<EmiRecipe, List<ItemStack>> piles, Set<EmiRecipe> inconnus) {
        boolean identique(Mesure autre) {
            if (!inconnus.equals(autre.inconnus) || !piles.keySet().equals(autre.piles.keySet()))
                return false;
            for (var entree : piles.entrySet()) {
                var a = entree.getValue();
                var b = autre.piles.get(entree.getKey());
                if (a.size() != b.size()) return false;
                for (int i = 0; i < a.size(); i++)
                    if (!ItemStack.matches(a.get(i), b.get(i))) return false;
            }
            return true;
        }

        Releve projeter() {
            var entrees = new HashMap<EmiRecipe, List<PlanRetrait.Stock<EmiStack>>>();
            piles.forEach(
                    (recette, objets) -> {
                        if (!objets.isEmpty())
                            entrees.put(
                                    recette,
                                    objets.stream()
                                            .map(
                                                    p ->
                                                            new PlanRetrait.Stock<>(
                                                                    ComparaisonEmi.pourInventaire(
                                                                            EmiStack.of(p)),
                                                                    p.getCount(),
                                                                    true))
                                            .toList());
                    });
            return new Releve(
                    Map.copyOf(entrees), Set.copyOf(piles.keySet()), Set.copyOf(inconnus));
        }
    }

    private record Contexte(
            UUID id,
            ResourceKey<Level> dimension,
            int conteneur,
            Map<BlockPos, EmiRecipe> visites,
            Set<BlockPos> reserves) {
        static Contexte du(AbstractContainerMenu menu, InstantaneArbreEmi arbre) {
            var mc = Minecraft.getInstance();
            return new Contexte(
                    mc.player.getUUID(),
                    mc.level.dimension(),
                    menu.containerId,
                    MachinesEmi.postesVisites(arbre),
                    MachinesEmi.lotsSuivis().stream()
                            .flatMap(l -> l.emplacement().stream())
                            .collect(java.util.stream.Collectors.toSet()));
        }

        ServerPlayer joueur(MinecraftServer serveur) throws ReflectiveOperationException {
            var p = serveur.getPlayerList().getPlayer(id);
            return p != null
                            && p.level().dimension() == dimension
                            && p.containerMenu.containerId == conteneur
                            && Class.forName("appeng.menu.me.common.MEStorageMenu")
                                    .isInstance(p.containerMenu)
                            && p.containerMenu.stillValid(p)
                    ? p
                    : null;
        }
    }

    void lire(
            AbstractContainerMenu menu,
            InstantaneArbreEmi arbre,
            Map<Object, Long> annonce,
            Consumer<Releve> retour) {
        if (recettes.isEmpty()) {
            retour.accept(VIDE);
            return;
        }
        var mc = Minecraft.getInstance();
        var serveur = mc.getSingleplayerServer();
        if (serveur == null) {
            // Le terminal distant synchronise son stock. Les entrées des machines distantes
            // ne sont pas transmises : calculer le besoin de l'arbre avec les stocks connus.
            retour.accept(VIDE);
            return;
        }
        var inconnu = new Releve(Map.of(), Set.of(), Set.copyOf(recettes));
        var joueur = mc.player;
        var monde = mc.level;
        var contexte = Contexte.du(menu, arbre);
        serveur.execute(
                () -> {
                    derniere = null;
                    try {
                        var p = contexte.joueur(serveur);
                        if (p != null) derniere = observer(p, contexte, annonce);
                    } catch (ReflectiveOperationException
                            | RuntimeException
                            | LinkageError indisponible) {
                        // La lecture incomplète ne permet aucune prise dépendant d’un poste.
                    }
                    var mesure = derniere;
                    mc.execute(
                            () -> {
                                if (mc.player == joueur
                                        && mc.level == monde
                                        && joueur.containerMenu == menu)
                                    retour.accept(mesure == null ? inconnu : mesure.projeter());
                            });
                });
    }

    private Mesure observer(ServerPlayer p, Contexte contexte, Map<Object, Long> annonce)
            throws ReflectiveOperationException {
        var mesure = mesurer(p, contexte.visites(), contexte.reserves());
        var cles = new LinkedHashSet<>(mouvements.niveaux.keySet());
        cles.addAll(annonce.keySet());
        var actuels = new HashMap<Object, Long>();
        if (!cles.isEmpty()) {
            var quantites = quantites(p.containerMenu);
            for (var cle : cles) actuels.put(cle, quantite(quantites, cle));
        }
        mouvements.relever(annonce, actuels, recettes, mesure.piles().keySet());
        var inconnus = new LinkedHashSet<>(mesure.inconnus());
        inconnus.addAll(mouvements.aVerifier);
        return new Mesure(mesure.piles(), inconnus);
    }

    private static Object quantites(AbstractContainerMenu menu)
            throws ReflectiveOperationException {
        var hote = GregTechMachines.appeler(menu, "getHost");
        var stockage =
                Class.forName("appeng.api.storage.ITerminalHost")
                        .getMethod("getInventory")
                        .invoke(hote);
        return Class.forName("appeng.api.storage.MEStorage")
                .getMethod("getAvailableStacks")
                .invoke(stockage);
    }

    private static long quantite(Object quantites, Object cle) throws ReflectiveOperationException {
        return (long)
                Class.forName("appeng.api.stacks.KeyCounter")
                        .getMethod("get", Class.forName("appeng.api.stacks.AEKey"))
                        .invoke(quantites, cle);
    }

    void retirer(
            AbstractContainerMenu menu,
            InstantaneArbreEmi arbre,
            long serial,
            Object cle,
            ItemStack variante,
            int poche,
            Object action,
            int repetitions,
            Method interaction,
            Consumer<Boolean> refus) {
        var mc = Minecraft.getInstance();
        var serveur = mc.getSingleplayerServer();
        var contexte = Contexte.du(menu, arbre);
        serveur.execute(
                () -> {
                    try {
                        var p = contexte.joueur(serveur);
                        boolean verifierPoste = !recettes.isEmpty();
                        if (p != null
                                && p.containerMenu.getCarried().isEmpty()
                                && (!verifierPoste
                                        || derniere != null && p.containerMenu == terminal.get())) {
                            if (verifierPoste) {
                                var grille = reseau.get();
                                var mesure = observer(p, contexte, Map.of());
                                if (!derniere.identique(mesure) || grille != reseau.get()) {
                                    mc.execute(() -> refus.accept(true));
                                    return;
                                }
                            }
                            var signature =
                                    interaction
                                            .getDeclaringClass()
                                            .getDeclaredMethod("getStackBySerial", long.class);
                            signature.setAccessible(true);
                            var inventaire = p.getInventory();
                            boolean destinationLibre =
                                    poche < 0
                                            || (inventaire.getSlotWithRemainingSpace(variante) < 0
                                                    && inventaire.getFreeSlot() == poche);
                            var avant = mouvements.niveaux.get(cle);
                            if ((!verifierPoste || avant != null)
                                    && destinationLibre
                                    && cle.equals(signature.invoke(p.containerMenu, serial))) {
                                // Chaque interaction conserve les droits et le coût AE2. Aucun
                                // import ou export d'un autre tick ne s'intercale dans le lot.
                                for (int i = 0; i < repetitions; i++) {
                                    interaction.invoke(p.containerMenu, serial, action);
                                    var curseur = p.containerMenu.getCarried();
                                    if (curseur.getCount() != i + 1
                                            || !ItemStack.isSameItemSameTags(variante, curseur))
                                        break;
                                }
                                var recu =
                                        poche < 0
                                                ? p.containerMenu.getCarried()
                                                : inventaire.getItem(poche);
                                if (avant != null && ItemStack.isSameItemSameTags(variante, recu))
                                    mouvements.niveaux.put(
                                            cle, Math.max(0L, avant - recu.getCount()));
                                return;
                            }
                        }
                    } catch (ReflectiveOperationException
                            | RuntimeException
                            | LinkageError indisponible) {
                        mouvements.aVerifier.addAll(recettes);
                    }
                    mc.execute(() -> refus.accept(false));
                });
    }

    private Mesure mesurer(ServerPlayer p, Map<BlockPos, EmiRecipe> visites, Set<BlockPos> reserves)
            throws ReflectiveOperationException {
        var positions = new LinkedHashSet<>(visites.keySet());
        Object grille = destinationsBus(p, positions);
        if (p.containerMenu != terminal.get() || grille != reseau.get() || grille == null) {
            if (p.containerMenu != terminal.get() || grille != reseau.get()) mouvements.oublier();
            destinations.clear();
            terminal = new WeakReference<>(p.containerMenu);
            reseau = new WeakReference<>(grille);
        }
        destinations
                .entrySet()
                .removeIf(
                        e -> {
                            var pos = e.getKey();
                            var bloc = e.getValue().get();
                            return bloc == null
                                    || bloc.isRemoved()
                                    || !p.level().hasChunkAt(pos)
                                    || pos.distSqr(p.blockPosition()) > 48 * 48
                                    || p.level().getBlockEntity(pos) != bloc;
                        });
        positions.addAll(destinations.keySet());
        var mesures = new HashMap<EmiRecipe, List<ItemStack>>();
        var identifiees = new HashMap<EmiRecipe, BlockEntity>();
        var inconnus = new LinkedHashSet<EmiRecipe>();
        for (var pos : positions) {
            if (pos.distSqr(p.blockPosition()) > 48 * 48
                    || !p.level().hasChunkAt(pos)
                    || reserves.contains(pos)) continue;
            var bloc = p.level().getBlockEntity(pos);
            var machine = GregTechMachines.machine(bloc);
            if (machine == null) continue;
            var affectee = visites.get(pos);
            var compatibles =
                    (affectee == null ? recettes : List.of(affectee))
                            .stream()
                                    .filter(r -> affectee != null || !visites.containsValue(r))
                                    .filter(r -> GregTechMachines.compatible(machine, r))
                                    .toList();
            if (compatibles.isEmpty()) continue;
            if (compatibles.size() != 1 || !autorise(p, pos, machine)) {
                inconnus.addAll(compatibles);
                continue;
            }
            var recette = compatibles.get(0);
            // Deux bains destinataires pour la même opération : l'arbre ne choisit pas leur
            // emplacement.
            if (mesures.containsKey(recette)) {
                inconnus.add(recette);
                continue;
            }
            var execution = GregTechMachines.execution(machine, recette);
            if (execution != null && execution.active() && execution.autreRecette()) continue;
            var champ = GregTechMachines.champPublic(machine.getClass(), "importItems");
            var handler = (IItemHandler) champ.get(machine);
            var piles = new ArrayList<ItemStack>();
            for (int i = 0; i < handler.getSlots(); i++) {
                var pile = handler.getStackInSlot(i);
                if (!pile.isEmpty()) piles.add(pile.copy());
            }
            mesures.put(recette, piles);
            if (grille != null && !visites.containsKey(pos)) identifiees.put(recette, bloc);
        }
        inconnus.forEach(mesures::remove);
        identifiees.forEach(
                (recette, bloc) -> {
                    if (mesures.containsKey(recette))
                        destinations.put(bloc.getBlockPos().immutable(), new WeakReference<>(bloc));
                });
        return new Mesure(mesures, inconnus);
    }

    private static Object destinationsBus(ServerPlayer p, Set<BlockPos> positions)
            throws ReflectiveOperationException {
        var node = GregTechMachines.appeler(p.containerMenu, "getNetworkNode");
        if (node == null) return null;
        var grid =
                Class.forName("appeng.api.networking.IGridNode").getMethod("getGrid").invoke(node);
        var bus = Class.forName("appeng.parts.automation.ExportBusPart");
        var typeGrille = Class.forName("appeng.api.networking.IGrid");
        var getMachines = typeGrille.getMethod("getMachines", Class.class);
        // AE2 indexe les parts par classe concrète, y compris les bus d’export des extensions.
        for (var type : (Iterable<?>) typeGrille.getMethod("getMachineClasses").invoke(grid)) {
            if (!bus.isAssignableFrom((Class<?>) type)) continue;
            for (var part : (Set<?>) getMachines.invoke(grid, type)) {
                BlockEntity hote = (BlockEntity) GregTechMachines.appeler(part, "getBlockEntity");
                if (hote.getLevel() == p.level()
                        && hote.getBlockPos().distSqr(p.blockPosition()) <= 49 * 49)
                    positions.add(
                            hote.getBlockPos()
                                    .relative(
                                            (Direction) GregTechMachines.appeler(part, "getSide")));
            }
        }
        return grid;
    }

    private static boolean autorise(ServerPlayer p, BlockPos pos, Object machine)
            throws ReflectiveOperationException {
        if (!p.level().mayInteract(p, pos)
                || !(boolean)
                        Class.forName("com.gregtechceu.gtceu.common.machine.owner.MachineOwner")
                                .getMethod(
                                        "canOpenOwnerMachine",
                                        Player.class,
                                        Class.forName(
                                                "com.gregtechceu.gtceu.api.machine.MetaMachine"))
                                .invoke(null, p, machine)) return false;
        if (!ModList.get().isLoaded("ftbchunks")) return true;
        var api =
                Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI")
                        .getMethod("api")
                        .invoke(null);
        var type = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API");
        if (!(boolean) type.getMethod("isManagerLoaded").invoke(api)) return false;
        var protection = Class.forName("dev.ftb.mods.ftbchunks.api.Protection");
        return !(boolean)
                Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunkManager")
                        .getMethod(
                                "shouldPreventInteraction",
                                Entity.class,
                                InteractionHand.class,
                                BlockPos.class,
                                protection,
                                Entity.class)
                        .invoke(
                                type.getMethod("getManager").invoke(api),
                                p,
                                InteractionHand.MAIN_HAND,
                                pos,
                                protection.getField("INTERACT_BLOCK").get(null),
                                null);
    }
}
