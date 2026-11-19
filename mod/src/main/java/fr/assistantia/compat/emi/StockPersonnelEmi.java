package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.PlanMachines;
import fr.assistantia.PlanRetrait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;

/** Projection des piles fournies en stock EMI personnel. */
final class StockPersonnelEmi {
    private StockPersonnelEmi() {}

    static List<PlanRetrait.Stock<EmiStack>> convertir(List<ItemStack> piles) {
        List<PlanRetrait.Stock<EmiStack>> stock = new ArrayList<>();
        for (ItemStack pile : piles) {
            if (pile.isEmpty()) continue;
            stock.add(new PlanRetrait.Stock<>(EmiStack.of(pile), pile.getCount(), true));
            ItemStack un = pile.copy();
            un.setCount(1);
            var fluide = FluidUtil.getFluidContained(un).orElse(null);
            if (fluide != null && !fluide.isEmpty())
                stock.add(
                        new PlanRetrait.Stock<>(
                                EmiStack.of(fluide.getFluid(), fluide.getTag(), fluide.getAmount()),
                                PlanMachines.produit(fluide.getAmount(), pile.getCount()),
                                true));
        }
        return stock;
    }
}
