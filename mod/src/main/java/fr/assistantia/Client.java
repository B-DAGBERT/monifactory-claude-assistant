package fr.assistantia;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Branchement des événements côté client. */
public final class Client {
    private Client() {
    }

    public static void init() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(Touches::enregistrer);
        modBus.addListener(Hud::enregistrer);
        MinecraftForge.EVENT_BUS.register(Touches.class);
        MinecraftForge.EVENT_BUS.register(Surbrillance.class);
        MinecraftForge.EVENT_BUS.register(Guidage.class);
        MinecraftForge.EVENT_BUS.register(Reperage.class);
        MinecraftForge.EVENT_BUS.register(AideEcran.class);
    }
}
