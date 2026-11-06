package fr.assistantia.mixin;

import fr.assistantia.IdentiteMonde;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GTCEu fournit une identité persistée dans la sauvegarde, indépendante du cache du joueur. */
@Pseudo
@Mixin(targets = "com.gregtechceu.gtceu.common.network.packets.SPacketSendWorldID", remap = false)
public abstract class IdentiteGregTechMixin {
    @Shadow private String worldId;

    @Inject(method = "execute", at = @At("HEAD"), remap = false, require = 0)
    private void assistantia$identite(NetworkEvent.Context contexte, CallbackInfo ci) {
        if (contexte != null) IdentiteMonde.recevoirGregTech(contexte.getNetworkManager(), worldId);
    }
}
