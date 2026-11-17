package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
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
    private static final long DUREE_RELEVE = 60_000_000_000L;

    private record Bloc(BlockPos position, BlockEntity entite, BlockPos associe) {
        boolean identique(ClientLevel niveau) {
            return niveau.getBlockEntity(position) == entite
                    && Objects.equals(associe, autreMoitie(niveau, position));
        }
    }

    private record Cible(ClientPacketListener connexion, ClientLevel niveau, List<Bloc> blocs) {
        boolean valide(Minecraft mc) {
            return mc.player != null
                    && niveau == mc.level
                    && connexion == mc.getConnection()
                    && !modifie();
        }

        boolean contient(BlockPos position) {
            return blocs.stream().anyMatch(b -> b.position().equals(position));
        }

        boolean contient(String dimension, long x, long y, long z) {
            return niveau.dimension().location().toString().equals(dimension)
                    && blocs.stream()
                            .map(Bloc::position)
                            .anyMatch(p -> p.getX() == x && p.getY() == y && p.getZ() == z);
        }

        boolean charge() {
            return blocs.stream().allMatch(b -> niveau.hasChunkAt(b.position()));
        }

        boolean modifie() {
            return blocs.stream()
                    .anyMatch(b -> niveau.hasChunkAt(b.position()) && !b.identique(niveau));
        }

        boolean memeCoffre(Cible autre) {
            return niveau == autre.niveau
                    && connexion == autre.connexion
                    && blocs.size() == autre.blocs.size()
                    && blocs.containsAll(autre.blocs);
        }
    }

    private record Interaction(
            ClientPacketListener connexion, ClientLevel niveau, BlockPos position, long date) {}

    private record Ouverture(AbstractContainerMenu menu, Cible coffre) {}

    private static final class Suivi {
        private final Cible coffre;
        // null si aucun menu synchronisé ne confirme le contenu courant.
        private List<ItemStack> piles;
        private long observeLe;

        private Suivi(Cible coffre) {
            this.coffre = coffre;
        }

        private boolean actualiser(List<ItemStack> stock) {
            observeLe = System.nanoTime();
            if (piles != null && memesPiles(piles, stock)) return false;
            piles = stock.stream().map(ItemStack::copy).toList();
            return true;
        }

        private boolean disponible(Minecraft mc) {
            return piles != null && coffre.valide(mc) && coffre.charge();
        }
    }

    private static final List<Suivi> TAMPONS = new ArrayList<>();
    private static Ouverture ouverture;
    private static Interaction interaction;
    private static Suivi precedent;
    private static long revision;

    private CoffreTampon() {}

    public static void basculer(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            message(mc, "Vise un coffre ou un tonneau.");
            return;
        }
        nettoyer(mc);
        BlockPos pos = hit.getBlockPos();
        var tampon = TAMPONS.stream().filter(t -> t.coffre.contient(pos)).findFirst().orElse(null);
        if (tampon != null) {
            TAMPONS.remove(tampon);
            if (tampon.piles != null
                    && (precedent == null || tampon.observeLe - precedent.observeLe > 0))
                precedent = tampon;
            revision++;
            message(mc, "Coffre tampon désélectionné.");
            return;
        }
        var nouvelle = identifier(mc, pos);
        if (nouvelle == null) {
            message(mc, "Vise un coffre ou un tonneau compatible dont les blocs sont chargés.");
            return;
        }
        revision++;
        if (precedent != null
                && nouvelle.memeCoffre(precedent.coffre)
                && System.nanoTime() - precedent.observeLe <= DUREE_RELEVE) {
            TAMPONS.add(precedent);
            precedent = null;
            message(mc, "Coffre tampon sélectionné.");
        } else {
            TAMPONS.add(new Suivi(nouvelle));
            message(mc, "Coffre tampon sélectionné. Ouvre-le pour relever son contenu.");
        }
    }

    private static Cible identifier(Minecraft mc, BlockPos pos) {
        if (mc.level == null || mc.getConnection() == null || !mc.level.hasChunkAt(pos))
            return null;
        var bloc = mc.level.getBlockState(pos).getBlock();
        var id = ForgeRegistries.BLOCKS.getKey(bloc);
        var entite = mc.level.getBlockEntity(pos);
        if (entite == null
                || id == null
                || !(bloc instanceof ChestBlock
                        || id.toString().equals("minecraft:barrel")
                        || id.getNamespace().equals("sophisticatedstorage"))) return null;
        pos = pos.immutable();
        var voisin = autreMoitie(mc.level, pos);
        var blocs = List.of(new Bloc(pos, entite, voisin));
        if (voisin != null) {
            if (!mc.level.hasChunkAt(voisin)) return null;
            var autre = mc.level.getBlockEntity(voisin);
            if (autre == null
                    || mc.level.getBlockState(voisin).getBlock() != bloc
                    || !pos.equals(autreMoitie(mc.level, voisin))) return null;
            blocs = List.of(new Bloc(pos, entite, voisin), new Bloc(voisin, autre, pos));
        }
        return new Cible(mc.getConnection(), mc.level, blocs);
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
        if (event.getLevel().isClientSide() && event.getEntity() == mc.player)
            interaction =
                    new Interaction(
                            mc.getConnection(),
                            mc.level,
                            event.getPos().immutable(),
                            System.nanoTime());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void ouvrir(ScreenEvent.Init.Post event) {
        var mc = Minecraft.getInstance();
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran)
                || mc.player == null
                || mc.player.containerMenu != ecran.getMenu()
                || !RetraitCoffre.estCoffre(ecran.getMenu())
                || ouverture != null && ouverture.menu() == ecran.getMenu()) return;
        var entree = interaction;
        interaction = null;
        ouverture = null;
        BlockPos position = positionMenu(ecran.getMenu(), mc, entree);
        if (position == null) return;
        var coffre = identifier(mc, position);
        if (coffre != null) ouverture = new Ouverture(ecran.getMenu(), coffre);
    }

    private static BlockPos positionMenu(
            AbstractContainerMenu menu, Minecraft mc, Interaction entree) {
        if (menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)
            return entree != null
                            && entree.niveau() == mc.level
                            && entree.connexion() == mc.getConnection()
                            && System.nanoTime() - entree.date() <= 5_000_000_000L
                    ? entree.position()
                    : null;
        // Sophisticated synchronise la position du bloc avec son menu, y compris pour un double.
        try {
            var valeur = menu.getClass().getMethod("getBlockPosition").invoke(menu);
            return valeur instanceof Optional<?> pos && pos.orElse(null) instanceof BlockPos bloc
                    ? bloc
                    : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        nettoyer(mc);
        if (!menuCourant(mc)) {
            ouverture = null;
            return;
        }
        if (!RetraitCoffre.occupe()) releverMenu();
    }

    private static void nettoyer(Minecraft mc) {
        if (TAMPONS.removeIf(t -> !t.coffre.valide(mc))) revision++;
        for (var tampon : TAMPONS) if (!tampon.coffre.charge()) invalider(tampon);
        if (precedent != null && !precedent.disponible(mc)) precedent = null;
    }

    @SubscribeEvent
    public static void fermer(ScreenEvent.Closing event) {
        var mc = Minecraft.getInstance();
        if (ouverture == null
                || !(event.getScreen() instanceof AbstractContainerScreen<?> ecran)
                || ecran.getMenu() != ouverture.menu()
                || !ouverture.coffre().valide(mc)) return;
        // Échap peut avoir déjà rendu son menu d'inventaire au joueur.
        if (RetraitCoffre.occupe()) {
            var tampon = tampon(ouverture.coffre());
            if (tampon != null) invalider(tampon);
            if (precedent != null && ouverture.coffre().memeCoffre(precedent.coffre))
                precedent = null;
        } else if (menuCourant(mc)) releverMenu();
    }

    private static boolean menuCourant(Minecraft mc) {
        return ouverture != null
                && mc.player != null
                && mc.player.containerMenu == ouverture.menu()
                && ouverture.coffre().valide(mc)
                && ouverture.coffre().charge();
    }

    private static void releverMenu() {
        if (!StockProduction.menuInitialise(ouverture.menu())) return;
        var stock = RetraitCoffre.pilesTampon(ouverture.menu());
        if (stock == null) return;
        var coffre = ouverture.coffre();
        var tampon = tampon(coffre);
        if (tampon == null) {
            if (precedent == null || !coffre.memeCoffre(precedent.coffre))
                precedent = new Suivi(coffre);
            precedent.actualiser(stock);
            return;
        }
        boolean premier = tampon.piles == null;
        if (tampon.actualiser(stock)) revision++;
        if (premier) message(Minecraft.getInstance(), "Contenu du coffre tampon relevé.");
    }

    private static boolean memesPiles(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }

    private static void invalider(Suivi tampon) {
        if (tampon.piles == null) return;
        tampon.piles = null;
        revision++;
    }

    private static Suivi tampon(Cible coffre) {
        return TAMPONS.stream().filter(t -> t.coffre.memeCoffre(coffre)).findFirst().orElse(null);
    }

    private static Suivi tamponOuvert(Minecraft mc) {
        return menuCourant(mc) ? tampon(ouverture.coffre()) : null;
    }

    public static boolean menuObserve() {
        return tamponOuvert(Minecraft.getInstance()) != null;
    }

    public static long revision() {
        return revision;
    }

    public static List<ItemStack> piles() {
        return piles(Minecraft.getInstance(), null);
    }

    /** Relevés des autres tampons quand les cases du menu ouvert sont comptées en direct. */
    static List<ItemStack> pilesHorsMenu() {
        var mc = Minecraft.getInstance();
        return piles(mc, tamponOuvert(mc));
    }

    private static List<ItemStack> piles(Minecraft mc, Suivi exclu) {
        return TAMPONS.stream()
                .filter(t -> t != exclu && t.disponible(mc))
                .flatMap(t -> t.piles.stream())
                .toList();
    }

    public static boolean contient(String dimension, long x, long y, long z) {
        var mc = Minecraft.getInstance();
        return TAMPONS.stream()
                .anyMatch(t -> t.coffre.contient(dimension, x, y, z) && t.disponible(mc));
    }

    static boolean selectionActive() {
        var mc = Minecraft.getInstance();
        return TAMPONS.stream().anyMatch(t -> t.coffre.valide(mc));
    }

    static List<BlockPos> positionsRepere() {
        var mc = Minecraft.getInstance();
        return TAMPONS.stream()
                .filter(t -> t.coffre.charge() && t.coffre.valide(mc))
                .map(t -> t.coffre.blocs().get(0).position())
                .toList();
    }

    /** La position est exclue du repérage dès la sélection, avant le relevé du contenu. */
    public static boolean selectionne(String dimension, long x, long y, long z) {
        var mc = Minecraft.getInstance();
        return TAMPONS.stream()
                .anyMatch(t -> t.coffre.contient(dimension, x, y, z) && t.coffre.valide(mc));
    }

    private static void message(Minecraft mc, String texte) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(texte), true);
    }
}
