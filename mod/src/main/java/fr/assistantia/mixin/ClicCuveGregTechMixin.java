package fr.assistantia.mixin;

import fr.assistantia.Emi;
import fr.assistantia.compat.emi.MachinesEmi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.gregtechceu.gtceu.api.gui.widget.TankWidget", remap = false)
public abstract class ClicCuveGregTechMixin {
    @Inject(method = "mouseClicked", at = @At("RETURN"), remap = false, require = 0)
    private void assistantia$observerRemplissage(
            double x, double y, int bouton, CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValueZ() && Emi.present()) MachinesEmi.clicSortieFluide(this);
    }
}
