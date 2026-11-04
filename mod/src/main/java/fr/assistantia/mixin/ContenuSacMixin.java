package fr.assistantia.mixin;

import fr.assistantia.compat.backpacks.SacsPortes;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Le paquet natif permet aussi de confirmer un sac complètement vide. */
@Pseudo
@Mixin(
        targets = "net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackContentsMessage",
        remap = false)
public abstract class ContenuSacMixin implements SacsPortes.Reponse {
    @Accessor("backpackUuid")
    public abstract UUID assistantia$id();

    @Accessor("backpackContents")
    public abstract CompoundTag assistantia$contenu();

    @Inject(method = "handleMessage", at = @At("TAIL"), remap = false, require = 0)
    private static void assistantia$reception(@Coerce Object message, CallbackInfo ci) {
        var r = (SacsPortes.Reponse) message;
        SacsPortes.reception(r.assistantia$id(), r.assistantia$contenu());
    }
}
