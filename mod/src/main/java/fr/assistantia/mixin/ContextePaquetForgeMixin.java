package fr.assistantia.mixin;

import fr.assistantia.IdentiteMonde;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Architectury 9.2.14 Forge : origine réseau conservée avant la mise en file sur le client. */
@Pseudo
@Mixin(targets = "dev.architectury.networking.forge.NetworkManagerImpl$1", remap = false)
public abstract class ContextePaquetForgeMixin implements IdentiteMonde.OrigineForge {
    @Accessor("val$context")
    public abstract NetworkEvent.Context assistantia$contexte();
}
