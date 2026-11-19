package fr.assistantia.compat.storage;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/** Lit le stockage synchronisé de Functional Storage, y compris ses variantes de tiroirs. */
public final class CapaciteDrawers {
    public enum Etat {
        NON_PLEIN,
        PLEIN,
        PLEIN_VOID
    }

    private static final String PREFIXE = "com.buuz135.functionalstorage.";
    private static final Api API = charger();

    private record Api(
            Class<?> tiroirObjet,
            Class<?> tiroirFluide,
            Class<?> compactage,
            Method stockageObjets,
            Method stockageFluides,
            Method sansLimite,
            Method voidActif,
            Method quantiteCompactee,
            Method formesCompactees,
            Method objetCompacte,
            Method unitesParObjet) {}

    private CapaciteDrawers() {}

    private static Class<?> classe(String suffixe) throws ClassNotFoundException {
        return Class.forName(PREFIXE + suffixe, false, CapaciteDrawers.class.getClassLoader());
    }

    private static Api charger() {
        try {
            var commun = classe("block.tile.ControllableDrawerTile");
            var objet = classe("block.tile.ItemControllableDrawerTile");
            var fluide = classe("block.tile.FluidDrawerTile");
            var compactage = classe("inventory.CompactingInventoryHandler");
            var forme = classe("util.CompactingUtil$Result");
            return new Api(
                    objet,
                    fluide,
                    compactage,
                    objet.getMethod("getStorage"),
                    fluide.getMethod("getFluidHandler"),
                    commun.getMethod("isCreative"),
                    commun.getMethod("isVoid"),
                    compactage.getMethod("getAmount"),
                    compactage.getMethod("getResultList"),
                    forme.getMethod("getResult"),
                    forme.getMethod("getNeeded"));
        } catch (ReflectiveOperationException | LinkageError absence) {
            return null;
        }
    }

    public static boolean disponible() {
        return API != null;
    }

    public static Etat etat(BlockEntity bloc) {
        if (API == null) return Etat.NON_PLEIN;
        boolean objets = API.tiroirObjet().isInstance(bloc);
        if (!objets && !API.tiroirFluide().isInstance(bloc)) return Etat.NON_PLEIN;
        try {
            if ((boolean) API.sansLimite().invoke(bloc)) return Etat.NON_PLEIN;
            boolean rendVoid = (boolean) API.voidActif().invoke(bloc);
            boolean plein;
            if (objets) {
                var stockage = (IItemHandler) API.stockageObjets().invoke(bloc);
                plein =
                        stockage != null
                                && (API.compactage().isInstance(stockage)
                                        ? compactagePlein(stockage)
                                        : casesPleines(stockage, rendVoid));
            } else {
                var cuves = (IFluidHandler) API.stockageFluides().invoke(bloc);
                plein = cuves != null && cuvesPleines(cuves);
            }
            if (!plein) return Etat.NON_PLEIN;
            return rendVoid ? Etat.PLEIN_VOID : Etat.PLEIN;
        } catch (ReflectiveOperationException | RuntimeException indisponible) {
            return Etat.NON_PLEIN;
        }
    }

    private static boolean casesPleines(IItemHandler stockage, boolean rendVoid) {
        // Functional Storage ajoute une case virtuelle de destruction à la fin.
        int cases = stockage.getSlots() - (rendVoid ? 1 : 0);
        for (int i = 0; i < cases; i++)
            if (!sature(stockage.getStackInSlot(i).getCount(), stockage.getSlotLimit(i)))
                return false;
        return cases > 0;
    }

    private static boolean cuvesPleines(IFluidHandler cuves) {
        int nombre = cuves.getTanks();
        for (int i = 0; i < nombre; i++)
            if (!sature(cuves.getFluidInTank(i).getAmount(), cuves.getTankCapacity(i)))
                return false;
        return nombre > 0;
    }

    private static boolean compactagePlein(IItemHandler stockage)
            throws ReflectiveOperationException {
        int quantite = (int) API.quantiteCompactee().invoke(stockage);
        if (quantite <= 0) return false;
        var formes = (List<?>) API.formesCompactees().invoke(stockage);
        long capacite = 0;
        for (int i = 0; i < formes.size(); i++) {
            var forme = formes.get(i);
            if (((ItemStack) API.objetCompacte().invoke(forme)).isEmpty()) continue;
            // Les formes partagent un stock. La forme la plus fine évite l'arrondi au bloc.
            long limite =
                    (long) stockage.getSlotLimit(i) * (int) API.unitesParObjet().invoke(forme);
            capacite = Math.max(capacite, limite);
        }
        return sature(quantite, capacite);
    }

    private static boolean sature(int quantite, long capacite) {
        return capacite > 0 && quantite >= capacite;
    }
}
