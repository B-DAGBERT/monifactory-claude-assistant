package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.LotFabrication;
import fr.assistantia.PlanRetrait;
import java.util.*;
import net.minecraft.core.BlockPos;

public class LotMachineTest {
    static final EmiRecipe RECETTE = new EmiRecipe() {};

    static LotFabrication<EmiStack> suivi() {
        return new LotFabrication<>(
                List.of(new PlanRetrait.Stock<>(new EmiStack("plate"), 2, true)), Objects::equals);
    }

    public static void main(String[] args) {
        var pos = new BlockPos(10, 64, -2);
        var manuel = new LotMachine(1, RECETTE, 2, Optional.empty(), suivi());
        var envoye = new LotMachine(2, RECETTE, 2, Optional.of(pos), suivi());
        // Régression réelle : déclarer puis ouvrir une machine ne doit plus lever de NPE.
        for (var ouvert : List.of(pos, new BlockPos(11, 64, -2))) {
            assert !manuel.ici(ouvert);
            assert List.of(manuel, envoye).stream()
                            .filter(l -> l.ici(ouvert) && l.suivi().annulable())
                            .count()
                    == (ouvert.equals(pos) ? 1 : 0);
        }
        assert manuel.manuel() && !manuel.ici(null);
        assert manuel.lieu().equals("déclaré manuellement");
        assert !envoye.manuel() && envoye.ici(new BlockPos(10, 64, -2));
        assert envoye.lieu().equals("10, 64, -2");
        pos.x = 99;
        assert envoye.ici(new BlockPos(10, 64, -2)) : "position figée à la déclaration";
        manuel.suivi().recevoir(List.of(new PlanRetrait.Stock<>(new EmiStack("plate"), 1, true)));
        assert manuel.suivi().totalRecu() == 1 && manuel.suivi().annulable();
        manuel.suivi().annuler();
        assert manuel.suivi().restants().isEmpty();
    }
}
