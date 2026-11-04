package fr.assistantia.mixin;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import fr.assistantia.compat.emi.InventaireArbreEmi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
}
