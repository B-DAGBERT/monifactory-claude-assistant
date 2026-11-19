package fr.assistantia.mixin;

import fr.assistantia.Emi;
import fr.assistantia.RetraitCoffre;
import fr.assistantia.StockProduction;
import fr.assistantia.compat.emi.ReglageCircuitMachine;
import fr.assistantia.compat.emi.TerminalMe;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation seule, après application native sur le thread client. */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class PaquetsStockMixin {
    private Connection assistantia$connexion() {
        // ForgeGradle remappe cet appel ordinaire, contrairement aux sélecteurs Mixin.
        return ((ClientPacketListener) (Object) this).getConnection();
    }

    @Inject(
            method = {"handleContainerContent", "m_6837_"},
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void assistantia$complet(ClientboundContainerSetContentPacket paquet, CallbackInfo ci) {
        var connexion = assistantia$connexion();
        StockProduction.complet(
                connexion,
                paquet.getContainerId(),
                paquet.getStateId(),
                paquet.getItems(),
                paquet.getCarriedItem(),
                false);
        ReglageCircuitMachine.confirmer(connexion, paquet.getContainerId(), paquet.getItems());
        RetraitCoffre.observerCorrection(connexion, paquet.getContainerId(), paquet.getItems());
        if (Emi.present())
            TerminalMe.confirmer(connexion, paquet.getContainerId(), paquet.getItems());
    }

    @Inject(
            method = {"handleContainerSetSlot", "m_5735_"},
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void assistantia$variation(ClientboundContainerSetSlotPacket paquet, CallbackInfo ci) {
        var connexion = assistantia$connexion();
        StockProduction.variation(connexion);
        ReglageCircuitMachine.confirmer(
                connexion, paquet.getContainerId(), paquet.getSlot(), paquet.getItem());
        RetraitCoffre.observerCorrection(
                connexion, paquet.getContainerId(), paquet.getSlot(), paquet.getItem());
        if (Emi.present())
            TerminalMe.confirmer(
                    connexion, paquet.getContainerId(), paquet.getSlot(), paquet.getItem());
    }

    @Inject(
            method = {"handleOpenScreen", "m_5980_"},
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void assistantia$ouverture(ClientboundOpenScreenPacket paquet, CallbackInfo ci) {
        StockProduction.variation(assistantia$connexion());
    }

    @Inject(
            method = {"handleContainerClose", "m_7776_"},
            at = @At("TAIL"),
            remap = false,
            require = 1)
    private void assistantia$fermeture(ClientboundContainerClosePacket paquet, CallbackInfo ci) {
        StockProduction.variation(assistantia$connexion());
    }
}
