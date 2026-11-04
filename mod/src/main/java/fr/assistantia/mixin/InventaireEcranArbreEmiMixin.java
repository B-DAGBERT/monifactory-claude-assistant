package fr.assistantia.mixin;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import fr.assistantia.compat.emi.InventaireArbreEmi;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.emi.emi.screen.BoMScreen", remap = false)
public abstract class InventaireEcranArbreEmiMixin {
    @Redirect(
            method = "recalculateTree",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/emi/emi/api/recipe/EmiPlayerInventory;of(Lnet/minecraft/world/entity/player/Player;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;"),
            remap = false,
            require = 1)
    private EmiPlayerInventory assistantia$inventaire(Player player) {
        return InventaireArbreEmi.completer(EmiPlayerInventory.of(player));
    }
}
