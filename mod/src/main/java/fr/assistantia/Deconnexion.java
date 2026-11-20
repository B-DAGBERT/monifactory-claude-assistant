package fr.assistantia;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Déconnexion : oublie les références à la partie quittée (connexion, niveau, joueur, écrans et
 * menus) que certains parcours ne remplaceraient qu'à leur prochain usage. Chaque parcours
 * reconstruit ensuite son état comme au premier usage ; fichiers, réglages et mémoire des filons
 * restent intacts.
 */
public final class Deconnexion {
    private Deconnexion() {}

    @SubscribeEvent
    public static void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        CoffreTampon.oublierMonde();
        Filons.oublierMonde();
        // Les classes de compatibilité EMI ne sont chargées qu'en sa présence.
        if (Emi.present()) {
            fr.assistantia.compat.emi.EnvoiMachine.oublierMonde();
            fr.assistantia.compat.emi.MachinesEmi.oublierMonde();
            fr.assistantia.compat.emi.SuiviEmiEcran.oublierMonde();
        }
    }
}
