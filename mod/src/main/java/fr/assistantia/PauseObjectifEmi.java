package fr.assistantia;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Synchronise la pause du plan, même lorsqu'elle est demandée depuis la discussion. */
public final class PauseObjectifEmi {
    private static EtatPauseEmi etat = new EtatPauseEmi();

    private static String cle(JsonObject vue) {
        String projet =
                vue.has("projets") && vue.get("projets").isJsonObject()
                        ? Pont.texte(vue.getAsJsonObject("projets"), "actif")
                        : "";
        return projet + "|" + Pont.texte(vue, "objectif_version");
    }

    public static boolean enPause() {
        var vue = Pont.partie("ecran");
        return etat.enPause(
                cle(vue), Pont.vrai(vue, "objectif_en_pause"), System.currentTimeMillis());
    }

    public static void demander(JsonObject vue) {
        if (etat.demander(cle(vue), System.currentTimeMillis())) arreter();
    }

    private static void arreter() {
        RechercheEmi.arreter();
        RetraitCoffre.arreter();
        AideCoffre.vider();
        EmiObjectif.suspendre();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            etat = new EtatPauseEmi();
            return;
        }
        if (!Pont.assistantActif()) return;
        var vue = Pont.partie("ecran");
        if (!vue.has("objectif_en_pause")) return;
        if (etat.actualiser(
                cle(vue), Pont.vrai(vue, "objectif_en_pause"), System.currentTimeMillis()))
            arreter();
    }
}
