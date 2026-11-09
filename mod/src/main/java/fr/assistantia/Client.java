package fr.assistantia;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Branchement des événements côté client. */
public final class Client {
    private Client() {}

    /** Dernière étape lue et son adaptation, pour ne pas recréer l'étape du HUD à chaque image. */
    private static VueRecette.EtapeSuivante etapeLue;

    private static Hud.Etape etapeHud;

    /**
     * La prochaine étape du plan de l'onglet Recette, pour le panneau en haut à gauche. Lecture
     * d'une valeur déjà publiée par l'analyse EMI : sans calcul, utilisable à chaque image.
     */
    private static Hud.Etape etapeRecette() {
        var etape = VueRecette.etapeSuivante();
        if (etape != etapeLue) {
            etapeLue = etape;
            etapeHud =
                    etape == null
                            ? null
                            : new Hud.Etape(etape.icone(), etape.titre(), etape.detail());
        }
        return etapeHud;
    }

    public static void init() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(Touches::enregistrer);
        modBus.addListener(Hud::enregistrer);
        Hud.source(Client::etapeRecette);
        MinecraftForge.EVENT_BUS.register(InventaireJoueur.class);
        MinecraftForge.EVENT_BUS.register(Touches.class);
        MinecraftForge.EVENT_BUS.register(Surbrillance.class);
        MinecraftForge.EVENT_BUS.register(Guidage.class);
        MinecraftForge.EVENT_BUS.register(Reperage.class);
        MinecraftForge.EVENT_BUS.register(RechercheEmi.class);
        MinecraftForge.EVENT_BUS.register(Filons.class);
        MinecraftForge.EVENT_BUS.register(Fullbright.class);
        MinecraftForge.EVENT_BUS.register(Stats.class);
        MinecraftForge.EVENT_BUS.register(CatalogueProduction.class);
        MinecraftForge.EVENT_BUS.register(StockProduction.class);
        MinecraftForge.EVENT_BUS.register(AideEcran.class);
        MinecraftForge.EVENT_BUS.register(RetraitCoffre.class);
    }
}
