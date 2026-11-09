package fr.assistantia;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Branchement des événements côté client. */
public final class Client {
    private Client() {}

    /** Dernière vue lue et son adaptation, pour ne pas recréer l'étape du HUD à chaque image. */
    private static VueRecette vueLue;

    private static Hud.Etape etapeHud;

    /**
     * L'étape en cours du plan de l'onglet Recette, puis les autres étapes prêtes, pour le panneau
     * en haut à gauche. Lecture d'une vue déjà publiée par l'analyse EMI : refaite seulement quand
     * elle change, utilisable à chaque image.
     */
    private static Hud.Etape etapeRecette() {
        var vue = VueRecette.courante();
        if (vue != vueLue) {
            vueLue = vue;
            etapeHud = etapeHud(vue);
        }
        return etapeHud;
    }

    static Hud.Etape etapeHud(VueRecette vue) {
        if (vue == null || vue.etat() != VueRecette.Etat.PRET || vue.suivante() == null)
            return null;
        var etape = vue.suivante();
        return new Hud.Etape(etape.icone(), etape.titre(), etape.detail(), pretes(vue));
    }

    /** « 2. 8 × Iron Plate · Basic Bender » pour chaque étape prête, sauf celle en cours (★). */
    static java.util.List<String> pretes(VueRecette vue) {
        java.util.List<String> lignes = new java.util.ArrayList<>();
        for (var e : vue.etapes()) {
            if (e.etoile() || e.ton() != VueRecette.Ton.PRET) continue;
            var produit = e.produit();
            lignes.add(
                    e.rang()
                            + ". "
                            + (produit.quantite().isEmpty() ? "" : produit.quantite() + " × ")
                            + produit.nom()
                            + (e.poste().isEmpty() ? "" : " · " + e.poste()));
        }
        return lignes;
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
        MinecraftForge.EVENT_BUS.register(RelachementSouris.class);
        MinecraftForge.EVENT_BUS.register(RetraitCoffre.class);
    }
}
