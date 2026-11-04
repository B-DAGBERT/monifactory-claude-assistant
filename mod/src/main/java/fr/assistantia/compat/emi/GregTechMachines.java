package fr.assistantia.compat.emi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

/** Accès optionnel aux vraies entrées GregTech, sans dépendance serveur supplémentaire. */
public final class GregTechMachines {
    public record Cuve(Object widget, IFluidHandler handler, int index) {
        public FluidStack contenu() {
            try {
                return ((FluidStack) appeler(widget, "getFluid")).copy();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cuve non synchronisée", e);
            }
        }

        public int capacite() {
            try {
                return champ(widget.getClass(), "lastTankCapacity").getInt(widget);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Capacité inconnue", e);
            }
        }

        public FluidTank simulation() {
            FluidTank copie = new FluidTank(capacite(), f -> handler.isFluidValid(index, f));
            copie.setFluid(contenu());
            return copie;
        }

        public void viderContenant() throws ReflectiveOperationException {
            // Même action que le clic gauche natif, un seul contenant, jamais Maj-clic.
            Class<?> base = Class.forName("com.lowdragmc.lowdraglib.gui.widget.Widget");
            Method action = base.getDeclaredMethod("writeClientAction", int.class, Consumer.class);
            action.setAccessible(true);
            action.invoke(widget, 1, (Consumer<FriendlyByteBuf>) b -> b.writeBoolean(false));
        }

        public boolean peutRemplirContenant() throws ReflectiveOperationException {
            Field f = champ(widget.getClass(), "allowClickFilled");
            return f.getBoolean(widget);
        }
    }

    public record Interface(
            Object machine,
            BlockPos position,
            List<Slot> entrees,
            List<Cuve> cuves,
            Object circuit) {}

    public static Object appeler(Object cible, String methode) throws ReflectiveOperationException {
        return cible.getClass().getMethod(methode).invoke(cible);
    }

    public static Field champ(Class<?> type, String nom) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(nom);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(nom);
    }

    public static boolean estMachine(AbstractContainerMenu menu) {
        try {
            return ouvrir(menu) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static Object machine(BlockEntity be) {
        if (be == null || !be.getClass().getName().startsWith("com.gregtechceu.")) return null;
        try {
            return appeler(be, "getMetaMachine");
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static Interface ouvrir(AbstractContainerMenu menu) throws ReflectiveOperationException {
        if (!menu.getClass()
                .getName()
                .equals("com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer")) return null;
        Object ui = appeler(menu, "getModularUI");
        Object holder = ui.getClass().getField("holder").get(ui);
        Object machine;
        try {
            machine = appeler(holder, "getMetaMachine");
        } catch (NoSuchMethodException e) {
            machine = holder;
        }
        if (!machine.getClass().getName().startsWith("com.gregtechceu.")) return null;
        BlockPos pos = (BlockPos) appeler(machine, "getPos");
        Map<?, ?> slots = (Map<?, ?>) appeler(ui, "getSlotMap");
        List<Slot> entrees = new ArrayList<>();
        Object circuit = null;
        for (var e : slots.entrySet()) {
            Object w = e.getValue();
            if (w.getClass().getName().endsWith("GhostCircuitSlotWidget")) {
                circuit = w;
                continue;
            }
            if (w.getClass().getName().contains("Phantom")) continue;
            if ("INPUT".equals(String.valueOf(appeler(w, "getIngredientIO"))))
                entrees.add((Slot) e.getKey());
        }
        entrees.sort(Comparator.comparingInt(s -> s.index));
        List<Cuve> cuves = new ArrayList<>();
        for (Object w : (List<?>) appeler(ui, "getFlatVisibleWidgetCollection")) {
            if (!w.getClass().getName().equals("com.gregtechceu.gtceu.api.gui.widget.TankWidget"))
                continue;
            if (!"INPUT".equals(String.valueOf(appeler(w, "getIngredientIO")))
                    || !champ(w.getClass(), "allowClickDrained").getBoolean(w)) continue;
            IFluidHandler h = (IFluidHandler) appeler(w, "getFluidTank");
            int index = (int) appeler(w, "getTank");
            if (h != null
                    && h.getTanks() == 1
                    && cuves.stream().noneMatch(c -> c.handler() == h && c.index() == index))
                cuves.add(new Cuve(w, h, index));
        }
        return new Interface(machine, pos, List.copyOf(entrees), List.copyOf(cuves), circuit);
    }

    public static Object recette(EmiRecipe r) throws ReflectiveOperationException {
        if (!r.getClass()
                .getName()
                .equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe")) return null;
        return champ(r.getClass(), "recipe").get(r);
    }

    public static boolean compatible(Object machine, EmiRecipe r) {
        try {
            Object recette = recette(r);
            if (recette == null) return false;
            // Comparer le type effectivement sélectionné, pas seulement le nom du bloc.
            if (appeler(machine, "getRecipeType")
                    != recette.getClass().getField("recipeType").get(recette)) return false;
            Object energy = appeler(recette, "getInputEUt");
            long tension = ((Number) appeler(energy, "voltage")).longValue();
            Object definition = appeler(machine, "getDefinition");
            int tier = ((Number) appeler(definition, "getTier")).intValue();
            long capacite = 8L << (2 * Math.min(14, Math.max(0, tier)));
            if (machine.getClass().getName().toLowerCase(Locale.ROOT).contains("steam"))
                capacite = 32;
            return tension <= capacite;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static boolean occupe(Object machine) {
        try {
            Object logique = appeler(machine, "getRecipeLogic");
            return ((Number) appeler(logique, "getProgress")).intValue() > 0
                    || "WORKING".equals(String.valueOf(appeler(logique, "getStatus")));
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    public static int circuit(ItemStack item) throws ReflectiveOperationException {
        return (int)
                Class.forName("com.gregtechceu.gtceu.common.item.IntCircuitBehaviour")
                        .getMethod("getCircuitConfiguration", ItemStack.class)
                        .invoke(null, item);
    }

    public static void reglerCircuit(Object widget, int valeur)
            throws ReflectiveOperationException {
        widget.getClass().getMethod("setCircuitValue", int.class).invoke(widget, valeur);
    }
}
