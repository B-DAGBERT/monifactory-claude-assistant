package fr.assistantia;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Stock de planification relevé dans un menu ouvert avec l'accès natif du joueur. */
public final class CoffreTampon {
    private record Bloc(BlockPos position, BlockEntity entite, BlockPos associe) {
        boolean identique(ClientLevel niveau) {
            return niveau.getBlockEntity(position) == entite
                    && java.util.Objects.equals(associe, autreMoitie(niveau, position));
        }
    }

    private record Cible(ClientPacketListener connexion, ClientLevel niveau, List<Bloc> blocs) {}

    private static Cible cible;
    private static AbstractContainerMenu menu;
    private static BlockPos interaction;
    private static long cliqueLe;
    private static boolean synchronise;
    private static boolean releve;
    private static List<ItemStack> piles = List.of();
    private static long revision;

    private CoffreTampon() {}

    public static void basculer(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            message(mc, "Vise un coffre ou un tonneau.");
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        var bloc = mc.level.getBlockState(pos).getBlock();
        var id = ForgeRegistries.BLOCKS.getKey(bloc);
        var entite = mc.level.getBlockEntity(pos);
        if (entite == null
                || id == null
                || !(bloc instanceof ChestBlock
                        || id.toString().equals("minecraft:barrel")
                        || id.getNamespace().equals("sophisticatedstorage"))) {
            message(mc, "Vise un coffre ou un tonneau compatible.");
            return;
        }
        if (memeMonde(mc) && vise(pos)) {
            effacer();
            message(mc, "Coffre tampon désélectionné.");
            return;
        }
        var voisin = autreMoitie(mc.level, pos);
        var blocs = List.of(new Bloc(pos, entite, voisin));
        if (voisin != null) {
            var autre = mc.level.getBlockEntity(voisin);
            if (autre == null
                    || mc.level.getBlockState(voisin).getBlock() != bloc
                    || !pos.equals(autreMoitie(mc.level, voisin))) {
                message(mc, "Les deux moitiés du coffre doivent être chargées.");
                return;
            }
            blocs = List.of(new Bloc(pos, entite, voisin), new Bloc(voisin, autre, pos));
        }
        effacer();
        cible = new Cible(mc.getConnection(), mc.level, blocs);
        message(mc, "Coffre tampon sélectionné. Ouvre-le pour relever son contenu.");
    }

    private static BlockPos autreMoitie(ClientLevel niveau, BlockPos pos) {
        var etat = niveau.getBlockState(pos);
        return etat.hasProperty(BlockStateProperties.CHEST_TYPE)
                        && etat.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
                ? pos.relative(ChestBlock.getConnectedDirection(etat))
                : null;
    }

    @SubscribeEvent
    public static void clic(PlayerInteractEvent.RightClickBlock event) {
        var mc = Minecraft.getInstance();
        if (!event.getLevel().isClientSide() || event.getEntity() != mc.player || !memeMonde(mc))
            return;
        interaction = event.getPos().immutable();
        cliqueLe = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void ouvrir(ScreenEvent.Init.Post event) {
        var screen = event.getScreen();
        var mc = Minecraft.getInstance();
        if (!(screen instanceof AbstractContainerScreen<?> ecran)
                || !memeMonde(mc)
                || mc.player == null
                || mc.player.containerMenu != ecran.getMenu()
                || !RetraitCoffre.estCoffre(ecran.getMenu())
                || ecran.getMenu() == menu) return;
        var pos = interaction;
        interaction = null;
        if (pos == null || !vise(pos) || System.nanoTime() - cliqueLe > 5_000_000_000L) return;
        menu = ecran.getMenu();
        synchronise = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || cible == null) return;
        var mc = Minecraft.getInstance();
        if (!memeMonde(mc)
                || mc.player == null
                || cible.blocs().stream()
                        .anyMatch(
                                b -> mc.level.hasChunkAt(b.position()) && !b.identique(mc.level))) {
            effacer();
            return;
        }
        if (!charge(mc)) {
            actualiser(List.of(), false);
            menu = null;
            return;
        }
        if (!menuObserve()) {
            menu = null;
            return;
        }
        if (!synchronise) {
            synchronise = StockProduction.menuDisponible();
            if (!synchronise) return;
        }
        if (!RetraitCoffre.occupe()) actualiser(RetraitCoffre.pilesTampon(menu), true);
    }

    @SubscribeEvent
    public static void fermer(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran)
                || ecran.getMenu() != menu
                || !menuObserve()
                || !synchronise) return;
        if (RetraitCoffre.occupe()) actualiser(List.of(), false);
        else actualiser(RetraitCoffre.pilesTampon(menu), true);
    }

    private static void actualiser(List<ItemStack> stock, boolean connu) {
        if (releve == connu && piles.size() == stock.size()) {
            boolean identique = true;
            for (int i = 0; i < piles.size() && identique; i++)
                identique = ItemStack.matches(piles.get(i), stock.get(i));
            if (identique) return;
        }
        boolean premier = !releve && connu;
        piles = stock.stream().map(ItemStack::copy).toList();
        releve = connu;
        revision++;
        if (premier) message(Minecraft.getInstance(), "Contenu du coffre tampon relevé.");
    }

    private static void effacer() {
        cible = null;
        interaction = null;
        menu = null;
        synchronise = false;
        actualiser(List.of(), false);
    }

    public static boolean menuObserve() {
        var mc = Minecraft.getInstance();
        return menu != null
                && mc.player != null
                && mc.player.containerMenu == menu
                && memeMonde(mc);
    }

    public static long revision() {
        return revision;
    }

    public static List<ItemStack> piles() {
        return disponible() ? piles : List.of();
    }

    public static boolean contient(String dimension, long x, long y, long z) {
        return disponible()
                && cible.niveau().dimension().location().toString().equals(dimension)
                && cible.blocs().stream()
                        .map(Bloc::position)
                        .anyMatch(p -> p.getX() == x && p.getY() == y && p.getZ() == z);
    }

    private static boolean disponible() {
        var mc = Minecraft.getInstance();
        return releve && memeMonde(mc) && charge(mc);
    }

    private static boolean charge(Minecraft mc) {
        return cible.blocs().stream().allMatch(b -> mc.level.hasChunkAt(b.position()));
    }

    private static boolean vise(BlockPos position) {
        return cible.blocs().stream().anyMatch(b -> b.position().equals(position));
    }

    private static boolean memeMonde(Minecraft mc) {
        return cible != null
                && cible.connexion() == mc.getConnection()
                && cible.niveau() == mc.level;
    }

    private static void message(Minecraft mc, String texte) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(texte), true);
    }
}
