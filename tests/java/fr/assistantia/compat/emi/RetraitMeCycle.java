package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.ArbreEmi;
import fr.assistantia.PlanRetrait;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Même inventaire que le cycle machine. Le terminal simule uniquement les réponses du serveur. */
final class RetraitMeCycle {
    private record Vue(InstantaneArbreEmi arbre, List<Lot> retraits) {}

    private static final class Int2ObjectOpenHashMap<T> {}

    private record ServerboundContainerClickPacket(
            int menu,
            int etat,
            int caseId,
            int bouton,
            ClickType type,
            ItemStack curseur,
            Int2ObjectOpenHashMap<?> changements) {}

    private static final class InventaireArbreEmi {
        static void invalider() {}
    }

    public static final class Terminal extends AbstractContainerMenu {
        final ItemStack variante;
        final ArrayDeque<Runnable> reponses = new ArrayDeque<>();
        int stock;

        Terminal(List<Slot> poches, ItemStack variante, int stock) {
            slots.addAll(poches);
            this.variante = variante;
            this.stock = stock;
        }

        public boolean isPowered() {
            return true;
        }

        public void retirer(long serie, int quantite) {
            assert serie == 1 && stock >= quantite;
            reponses.add(
                    () -> {
                        int total = getCarried().getCount() + quantite;
                        stock -= quantite;
                        carried = variante.copy();
                        carried.setCount(total);
                    });
        }

        void deposer(Object packet) {
            var clic = (ServerboundContainerClickPacket) packet;
            assert clic.menu() == containerId && clic.type() == ClickType.PICKUP;
            reponses.add(
                    () -> {
                        var destination =
                                slots.stream()
                                        .filter(s -> s.index == clic.caseId())
                                        .findFirst()
                                        .orElseThrow();
                        int total = destination.getItem().getCount() + getCarried().getCount();
                        destination.item = variante.copy();
                        destination.item.setCount(total);
                        carried = ItemStack.EMPTY;
                    });
        }
    }

    private static int ticks;
    private static Vue vue;
    private static List<Lot> objets;
    private static Acces ACCES;
    private static Supplier<List<PlanRetrait.Stock<EmiStack>>> personnel;
    private static String message;

    // CHAMPS_TERMINAL_ME

    private static Terminal courant() {
        var actuel = Minecraft.getInstance().player.containerMenu;
        return actuel instanceof Terminal terminal ? terminal : null;
    }

    private static Object arbreCourant() {
        return ArbreEmi.actif();
    }

    private static boolean occupe() {
        return menu != null;
    }

    private static void actualiser(boolean complet) throws ReflectiveOperationException {
        if (courant() == null) {
            vue = null;
            return;
        }
        var terminal = courant();
        var arbre = InstantaneArbreEmi.lire(arbreCourant());
        var reseau = List.of(new PlanRetrait.Stock<>(terminal.variante, terminal.stock, false));
        var couverture = StockFabricationEmi.courant(personnel.get(), reseau);
        var lots =
                couverture.retraitsReseau(arbre.machines(), arbre.lots()).stream()
                        .map(p -> new Lot(1, terminal.variante, p.quantite()))
                        .toList();
        vue = new Vue(arbre, lots);
    }

    private static void message(String texte) {
        message = texte;
    }

    private static void signaler(Throwable erreur) {
        throw new AssertionError(erreur);
    }

    // ACTIONS_TERMINAL_ME

    static int retirer(
            List<Slot> poches,
            ItemStack variante,
            int stock,
            int quota,
            Supplier<List<PlanRetrait.Stock<EmiStack>>> possessions)
            throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        var ancienMenu = mc.player.containerMenu;
        var ancienEcran = mc.screen;
        var terminal = new Terminal(poches, variante, stock);
        var terminalEcran = new AbstractContainerScreen<>(terminal);
        mc.player.containerMenu = terminal;
        mc.screen = terminalEcran;
        mc.getConnection().sender = terminal::deposer;
        personnel = possessions;
        message = null;
        ACCES =
                new Acces(
                        null,
                        null,
                        null,
                        null,
                        Terminal.class.getMethod("isPowered"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Terminal.class.getMethod("retirer", long.class, int.class),
                        1,
                        64,
                        32,
                        null);
        try {
            lancer(terminalEcran, null);
            if (quota == 0) {
                assert !occupe() && vue.retraits().isEmpty() && terminal.reponses.isEmpty()
                        : "le lot en cours couvre le besoin à la réouverture du terminal";
                return terminal.stock;
            }
            assert occupe() && reste == quota : "le retrait doit prendre le complément calculé";
            for (int i = 0; i < 64 && occupe(); i++) {
                avancer();
                if (!terminal.reponses.isEmpty()) {
                    avancer();
                    assert terminal.reponses.size() == 1
                            : "attendre la réponse avant le clic suivant";
                    terminal.reponses.removeFirst().run();
                }
            }
            assert !occupe() && terminal.getCarried().isEmpty() : "fin du retrait confirmée";
            assert message.equals(quota + " objets récupérés dans le réseau ME.") : message;
            assert vue != null && vue.retraits().isEmpty() : "aucun retrait supplémentaire proposé";
            return terminal.stock;
        } finally {
            mc.player.containerMenu = ancienMenu;
            mc.screen = ancienEcran;
            mc.getConnection().sender = null;
        }
    }
}
