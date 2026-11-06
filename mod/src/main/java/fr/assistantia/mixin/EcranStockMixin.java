package fr.assistantia.mixin;

import fr.assistantia.StockProduction;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Préserve le menu serveur uniquement lors du passage explicite à Production. */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public abstract class EcranStockMixin {
    @Inject(
            method = {"removed", "m_7861_"},
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1)
    private void assistantia$transition(CallbackInfo ci) {
        if (StockProduction.garderMenu((Screen) (Object) this)) ci.cancel();
    }
}
