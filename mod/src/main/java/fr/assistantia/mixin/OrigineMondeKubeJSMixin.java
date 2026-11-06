package fr.assistantia.mixin;

import fr.assistantia.IdentiteMonde;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seul le canal de relevé de l'assistant est protégé ; aucun autre événement KubeJS n'est changé.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.kubejs.net.SendDataFromServerMessage", remap = false)
public abstract class OrigineMondeKubeJSMixin {
    @Shadow @Final private String channel;
    @Shadow @Final private CompoundTag data;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void assistantia$origine(@Coerce Object contexte, CallbackInfo ci) {
        if ("assistant_ia_monde".equals(channel)
                && IdentiteMonde.isolation()
                && !IdentiteMonde.annoterPaquet(contexte, data)) ci.cancel();
    }
}
