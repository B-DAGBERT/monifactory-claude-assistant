package fr.assistantia.mixin;

import fr.assistantia.compat.backpacks.SacsPortes;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
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

    @Unique private Object assistantia$connexion;

    public Object assistantia$connexion() {
        return assistantia$connexion;
    }

    public void assistantia$connexion(Object connexion) {
        assistantia$connexion = connexion;
    }

    // Le contexte Forge est capturé avant enqueueWork, y compris pour un paquet fractionné.
    @Inject(method = "onMessage", at = @At("HEAD"), remap = false, require = 0)
    private static void assistantia$origine(
            @Coerce Object message, Supplier<NetworkEvent.Context> fournisseur, CallbackInfo ci) {
        var contexte = fournisseur.get();
        ((SacsPortes.Reponse) message)
                .assistantia$connexion(contexte == null ? null : contexte.getNetworkManager());
    }

    // Contrôler avant le corps natif : sinon son cache peut contaminer le repli de SacsPortes.
    @Inject(
            method = "handleMessage",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void assistantia$verifierOrigine(@Coerce Object message, CallbackInfo ci) {
        var origine = ((SacsPortes.Reponse) message).assistantia$connexion();
        var actuelle = Minecraft.getInstance().getConnection();
        if (!(origine instanceof Connection connexion)
                || !connexion.isConnected()
                || actuelle == null
                || actuelle.getConnection() != connexion) ci.cancel();
    }

    @Inject(method = "handleMessage", at = @At("TAIL"), remap = false, require = 0)
    private static void assistantia$reception(@Coerce Object message, CallbackInfo ci) {
        var r = (SacsPortes.Reponse) message;
        SacsPortes.reception(r.assistantia$id(), r.assistantia$contenu());
    }
}
