package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.LotFabrication;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Un lot déclaré par le joueur n'est associé à aucune machine précise. */
record LotMachine(
        int id,
        EmiRecipe recette,
        int fois,
        Optional<BlockPos> emplacement,
        LotFabrication<EmiStack> suivi) {
    LotMachine {
        emplacement = Objects.requireNonNull(emplacement).map(BlockPos::immutable);
    }

    boolean manuel() {
        return emplacement.isEmpty();
    }

    boolean ici(BlockPos position) {
        return emplacement.filter(p -> p.equals(position)).isPresent();
    }

    String lieu() {
        return emplacement.map(BlockPos::toShortString).orElse("déclaré manuellement");
    }
}
