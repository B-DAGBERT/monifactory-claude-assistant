package fr.assistantia.mixin;

import fr.assistantia.StockProduction;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Le paquet Sophisticated conserve les quantités supérieures à 127. */
@Pseudo
@Mixin(
        targets = "net.p3pp3rf1y.sophisticatedcore.network.SyncContainerStacksMessage",
        remap = false)
public abstract class ContenuStockSophistiqueMixin implements StockProduction.Contenu {
    @Unique private Connection assistantia$connexion;

    public Connection assistantia$connexion() {
        return assistantia$connexion;
    }

    public void assistantia$connexion(Connection connexion) {
        assistantia$connexion = connexion;
    }

    @Accessor("windowId")
    public abstract int assistantia$menu();

    @Accessor("stateId")
    public abstract int assistantia$etat();

    @Accessor("itemStacks")
    public abstract List<ItemStack> assistantia$piles();

    @Accessor("carriedStack")
    public abstract ItemStack assistantia$curseur();

    @Inject(method = "onMessage", at = @At("HEAD"), remap = false, require = 0)
    private static void assistantia$origine(
            @Coerce Object message, Supplier<NetworkEvent.Context> fournisseur, CallbackInfo ci) {
        var contexte = fournisseur.get();
        ((StockProduction.Reponse) message)
                .assistantia$connexion(contexte == null ? null : contexte.getNetworkManager());
    }

    @Inject(method = "handleMessage", at = @At("TAIL"), remap = false, require = 0)
    private static void assistantia$reception(@Coerce Object message, CallbackInfo ci) {
        var r = (StockProduction.Contenu) message;
        StockProduction.complet(
                r.assistantia$connexion(),
                r.assistantia$menu(),
                r.assistantia$etat(),
                r.assistantia$piles(),
                r.assistantia$curseur(),
                true);
    }
}
