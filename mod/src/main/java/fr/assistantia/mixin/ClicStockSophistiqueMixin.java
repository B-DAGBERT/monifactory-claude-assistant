package fr.assistantia.mixin;

import fr.assistantia.StockProduction;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase", remap = false)
public abstract class ClicStockSophistiqueMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), remap = false, require = 0)
    private void assistantia$avantClic(int slot, int bouton, ClickType type, CallbackInfo ci) {
        StockProduction.clic();
    }
}
