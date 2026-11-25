package fr.assistantia.compat.emi;

import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

/** Un seul contenant est confié au remplissage natif d'AE2, puis remis en poche. */
final class RemplissageMe {
    @FunctionalInterface
    interface Remplir {
        void envoyer() throws ReflectiveOperationException;
    }

    private enum Etape {
        PRISE,
        ISOLER,
        RANGER_PILE,
        REPRENDRE,
        REMPLIR,
        DEPOSER
    }

    private final Slot source, depot;
    private final ItemStack pile, un;
    private final FluidStack liquide;
    private final int volumeAvant, capacite;
    private final boolean fiole;
    private Etape etape = Etape.PRISE;
    private int gain;
    private long echeance;

    private RemplissageMe(
            Slot source,
            Slot depot,
            ItemStack pile,
            ItemStack un,
            FluidStack liquide,
            int volumeAvant,
            int capacite,
            boolean fiole) {
        this.source = source;
        this.depot = depot;
        this.pile = pile;
        this.un = un;
        this.liquide = liquide;
        this.volumeAvant = volumeAvant;
        this.capacite = capacite;
        this.fiole = fiole;
    }

    static RemplissageMe choisir(
            List<Slot> slots, Inventory inventaire, FluidStack liquide, int besoin) {
        RemplissageMe choix = null;
        for (Slot slot : slots) {
            if (slot.container != inventaire
                    || !slot.isActive()
                    || !slot.mayPickup(inventaire.player)) continue;
            var pile = slot.getItem();
            if (pile.isEmpty() || !slot.mayPlace(pile)) continue;
            var id = BuiltInRegistries.ITEM.getKey(pile.getItem());
            if (!id.getNamespace().equals("gtceu")) continue;
            boolean fiole = id.getPath().equals("glass_vial");
            if (fiole ? besoin >= 1_000 : !id.getPath().endsWith("_drum")) continue;
            var un = pile.copy();
            un.setCount(1);
            var handler = FluidUtil.getFluidHandler(un).orElse(null);
            if (handler == null) continue;
            int avant = volume(un, liquide);
            if (avant < 0) continue;
            int place = handler.fill(liquide, FluidAction.SIMULATE);
            if (place <= 0) continue;
            if (choix != null) {
                if (choix.fiole && !fiole) continue;
                if (choix.fiole == fiole
                        && (choix.capacite >= besoin
                                ? place < besoin || place >= choix.capacite
                                : place <= choix.capacite)) continue;
            }
            Slot depot = slot;
            if (pile.getCount() > 1) {
                depot =
                        slots.stream()
                                .filter(
                                        s ->
                                                s.container == inventaire
                                                        && s.isActive()
                                                        && s.getItem().isEmpty()
                                                        && s.mayPlace(un)
                                                        && s.mayPickup(inventaire.player))
                                .findFirst()
                                .orElse(null);
                if (depot == null) continue;
            }
            choix = new RemplissageMe(slot, depot, pile.copy(), un, liquide, avant, place, fiole);
        }
        return choix;
    }

    void prendre(BiConsumer<Slot, Integer> clic) {
        clic.accept(source, 0);
        attendre(Etape.PRISE);
    }

    /** Zéro pendant l'attente, -1 à l'expiration, gain confirmé après le dépôt. */
    int avancer(AbstractContainerMenu menu, BiConsumer<Slot, Integer> clic, Remplir remplir)
            throws ReflectiveOperationException {
        ItemStack curseur = menu.getCarried();
        switch (etape) {
            case PRISE -> {
                if (source.getItem().isEmpty() && ItemStack.matches(curseur, pile)) {
                    if (source == depot) {
                        remplir.envoyer();
                        attendre(Etape.REMPLIR);
                    } else if (depot.getItem().isEmpty()) {
                        clic.accept(depot, 1);
                        attendre(Etape.ISOLER);
                    }
                }
            }
            case ISOLER -> {
                if (source.getItem().isEmpty()
                        && ItemStack.matches(depot.getItem(), un)
                        && curseur.getCount() == pile.getCount() - 1
                        && ItemStack.isSameItemSameTags(curseur, pile)) {
                    clic.accept(source, 0);
                    attendre(Etape.RANGER_PILE);
                }
            }
            case RANGER_PILE -> {
                if (curseur.isEmpty()
                        && source.getItem().getCount() == pile.getCount() - 1
                        && ItemStack.isSameItemSameTags(source.getItem(), pile)
                        && ItemStack.matches(depot.getItem(), un)) {
                    clic.accept(depot, 0);
                    attendre(Etape.REPRENDRE);
                }
            }
            case REPRENDRE -> {
                if (depot.getItem().isEmpty() && ItemStack.matches(curseur, un)) {
                    remplir.envoyer();
                    attendre(Etape.REMPLIR);
                }
            }
            case REMPLIR -> {
                int delta = gain(curseur);
                if (delta > 0 && depot.getItem().isEmpty() && depot.mayPlace(curseur)) {
                    gain = delta;
                    clic.accept(depot, 0);
                    attendre(Etape.DEPOSER);
                }
            }
            case DEPOSER -> {
                if (curseur.isEmpty() && gain(depot.getItem()) == gain) return gain;
            }
        }
        return System.nanoTime() >= echeance ? -1 : 0;
    }

    private void attendre(Etape suite) {
        etape = suite;
        echeance = System.nanoTime() + 3_000_000_000L;
    }

    private static int volume(ItemStack pile, FluidStack liquide) {
        var contenu = FluidUtil.getFluidContained(pile).orElse(FluidStack.EMPTY);
        if (contenu.isEmpty()) return 0;
        return contenu.isFluidEqual(liquide) ? contenu.getAmount() : -1;
    }

    private int gain(ItemStack pile) {
        if (pile.getCount() != 1 || pile.getItem() != un.getItem()) return -1;
        int delta = volume(pile, liquide) - volumeAvant;
        return delta > 0 && delta <= capacite ? delta : -1;
    }
}
