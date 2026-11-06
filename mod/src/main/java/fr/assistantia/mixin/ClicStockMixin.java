package fr.assistantia.mixin;

import fr.assistantia.StockProduction;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MultiPlayerGameMode.class, remap = false)
public abstract class ClicStockMixin {
    @Inject(
            method = {"handleInventoryMouseClick", "m_171799_"},
            at = @At("HEAD"),
            remap = false,
            require = 1)
    private void assistantia$avantClic(
            int menu, int slot, int bouton, ClickType type, Player joueur, CallbackInfo ci) {
        StockProduction.clic();
    }
}
