package fr.assistantia;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Un conteneur ne reçoit jamais le relâchement d'un clic dont il n'a pas reçu l'appui : il le
 * traiterait comme un clic ordinaire et poserait l'objet tenu au curseur, ou le jetterait au sol
 * hors de son interface. Deux cas sont retenus :
 *
 * <ul>
 *   <li>l'appui a été pris par un panneau ou un bouton de l'assistant posé sur l'écran (barre EMI,
 *       Collecte, machine, établi) : son relâchement l'est aussi, même hors du bouton ;
 *   <li>l'appui a eu lieu sur un écran qui n'est pas un conteneur (Suivi, Sac, Outils EMI,
 *       déclaration, recette EMI…) et qui a rendu la main au conteneur avant le relâchement.
 * </ul>
 *
 * Un appui dans le monde ou d'un conteneur à un autre garde le comportement du jeu. Un relâchement
 * n'est jamais retenu pour sa position : un glisser commencé dans le conteneur (répartition d'une
 * pile, barre du créatif, widget GT) s'y termine, même au-dessus d'un panneau ou de la barre EMI.
 */
public final class RelachementSouris {
    /** Écran et bouton du dernier appui ; pris : appui consommé par l'assistant. */
    private static Screen ecran;

    private static int bouton = -1;
    private static boolean pris;

    /** Appui en cours de distribution que l'assistant vient de consommer. */
    private static ScreenEvent.MouseButtonPressed.Pre consomme;

    private RelachementSouris() {}

    /**
     * Appui pris par l'assistant : ni l'écran ni EMI ne le reçoivent, ni son relâchement. À appeler
     * depuis un écouteur de priorité supérieure à {@link #appui}.
     */
    public static void consommer(ScreenEvent.MouseButtonPressed.Pre event) {
        event.setCanceled(true);
        consomme = event;
    }

    /** L'écran de chaque appui, même annulé par un autre écouteur, appelé après tous les autres. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void appui(ScreenEvent.MouseButtonPressed.Pre event) {
        ecran = event.getScreen();
        bouton = event.getButton();
        pris = event == consomme;
        consomme = null;
    }

    /** Le premier relâchement qui suit un appui le termine, où qu'il arrive. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void relache(ScreenEvent.MouseButtonReleased.Pre event) {
        Screen courant = event.getScreen();
        boolean suite = pris && courant == ecran && event.getButton() == bouton;
        boolean orphelin =
                courant instanceof AbstractContainerScreen<?>
                        && ecran != null
                        && ecran != courant
                        && !(ecran instanceof AbstractContainerScreen<?>);
        if (suite || orphelin) event.setCanceled(true);
        ecran = null;
        bouton = -1;
        pris = false;
    }
}
