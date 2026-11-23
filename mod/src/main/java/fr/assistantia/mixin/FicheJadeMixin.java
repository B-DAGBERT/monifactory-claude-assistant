package fr.assistantia.mixin;

import fr.assistantia.Hud;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Jade 11 anime ce rectangle avant de peindre la fiche du bloc ou de l'entité. */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.OverlayRenderer", remap = false)
public abstract class FicheJadeMixin {
    @Shadow private static Rect2i morphRect;
    @Shadow public static boolean shown;

    @Inject(method = "renderOverlay", at = @At("RETURN"), require = 0, remap = false)
    private static void assistantia$ficheDessinee(CallbackInfo ci) {
        if (shown) Hud.ficheDessinee(morphRect);
    }
}
