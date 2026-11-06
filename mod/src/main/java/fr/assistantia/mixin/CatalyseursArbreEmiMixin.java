package fr.assistantia.mixin;

import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.compat.emi.CatalyseursArbreEmi;
import fr.assistantia.compat.emi.InstantaneArbreEmi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EMI 1.1 construit normalement les enfants depuis getInputs uniquement. */
@Pseudo
@Mixin(targets = "dev.emi.emi.bom.MaterialNode", remap = false)
public abstract class CatalyseursArbreEmiMixin {
    @Unique private static boolean assistantia$erreurCatalyseurs;

    @Inject(method = "defineRecipe", at = @At("TAIL"), remap = false, require = 1)
    private void assistantia$catalyseurs(EmiRecipe recipe, CallbackInfo ci) {
        try {
            CatalyseursArbreEmi.completer(this, recipe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!assistantia$erreurCatalyseurs) {
                com.mojang.logging.LogUtils.getLogger()
                        .warn("Assistant : catalyseurs de l'arbre EMI indisponibles", e);
                assistantia$erreurCatalyseurs = true;
            }
        } finally {
            InstantaneArbreEmi.arbreModifie();
            fr.assistantia.CatalogueProduction.rechargement();
        }
    }
}
