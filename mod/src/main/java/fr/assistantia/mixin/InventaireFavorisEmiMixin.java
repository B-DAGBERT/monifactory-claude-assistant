package fr.assistantia.mixin;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import fr.assistantia.compat.emi.InventaireArbreEmi;
import fr.assistantia.compat.emi.MachinesEmi;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ne touche ni au stock vide de référence, ni aux recettes remplissables de l'établi. */
@Pseudo
@Mixin(targets = "dev.emi.emi.runtime.EmiFavorites", remap = false)
public abstract class InventaireFavorisEmiMixin {
    @ModifyVariable(
            method = "updateSynthetic",
            at = @At("HEAD"),
            argsOnly = true,
            remap = false,
            require = 1)
    private static EmiPlayerInventory assistantia$inventaire(EmiPlayerInventory stock) {
        return InventaireArbreEmi.completer(stock);
    }

    @Inject(
            method = "updateSynthetic",
            at =
                    @At(
                            value = "FIELD",
                            target = "Ldev/emi/emi/bom/BoM;craftingMode:Z",
                            opcode = Opcodes.PUTSTATIC),
            cancellable = true,
            remap = false,
            require = 1)
    private static void assistantia$conserverSuivi(EmiPlayerInventory stock, CallbackInfo ci) {
        // Les sorties attendues satisfont le plan mais doivent encore être réceptionnées.
        if (MachinesEmi.sortieFinaleAttendue()) ci.cancel();
    }
}
