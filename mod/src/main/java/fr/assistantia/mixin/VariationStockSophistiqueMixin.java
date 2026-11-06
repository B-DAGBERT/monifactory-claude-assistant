package fr.assistantia.mixin;

import fr.assistantia.StockProduction;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = {
            "net.p3pp3rf1y.sophisticatedcore.network.SyncSlotStackMessage",
            "net.p3pp3rf1y.sophisticatedcore.network.SyncAdditionalSlotInfoMessage"
        },
        remap = false)
public abstract class VariationStockSophistiqueMixin implements StockProduction.Reponse {
    @Unique private Connection assistantia$connexion;

    public Connection assistantia$connexion() {
        return assistantia$connexion;
    }

    public void assistantia$connexion(Connection connexion) {
        assistantia$connexion = connexion;
    }

    @Inject(method = "onMessage", at = @At("HEAD"), remap = false, require = 0)
    private static void assistantia$origine(
            @Coerce Object message, Supplier<NetworkEvent.Context> fournisseur, CallbackInfo ci) {
        var contexte = fournisseur.get();
        ((StockProduction.Reponse) message)
                .assistantia$connexion(contexte == null ? null : contexte.getNetworkManager());
    }

    @Inject(method = "handleMessage", at = @At("TAIL"), remap = false, require = 0)
    private static void assistantia$reception(@Coerce Object message, CallbackInfo ci) {
        var r = (StockProduction.Reponse) message;
        if (message.getClass().getName().endsWith(".SyncAdditionalSlotInfoMessage"))
            StockProduction.informations(r.assistantia$connexion());
        else StockProduction.variation(r.assistantia$connexion());
    }
}
