package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Instantané natif borné au clic de lecture ; aucun retrait, scan ni acquisition de backpack. */
final class InventaireProduction {
    private InventaireProduction() {}

    static boolean exporter(String capture, EtatProduction.Contexte contexte) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !courant(contexte)) return false;
        var json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("t", System.currentTimeMillis());
        json.addProperty("capture", capture);
        json.addProperty("joueur", mc.player.getName().getString());
        json.addProperty("dimension", contexte.dimension());
        json.addProperty("actif", true);
        boolean synchronise = InventaireJoueur.disponible();
        json.addProperty("synchronise", synchronise);
        json.addProperty("complet", false);
        json.add(
                "inventaire",
                sansVariantes(synchronise ? InventaireJoueur.piles() : java.util.List.of()));
        annoter(json, contexte);
        return Pont.envoyerInventaireProduction(json);
    }

    static JsonObject sansVariantes(Iterable<ItemStack> piles) {
        Map<String, Long> comptes = new LinkedHashMap<>();
        for (var pile : piles) {
            if (pile.isEmpty() || pile.hasTag() && !pile.getTag().isEmpty()) continue;
            var id = ForgeRegistries.ITEMS.getKey(pile.getItem());
            if (id != null)
                comptes.merge(
                        id.toString(),
                        (long) pile.getCount(),
                        (a, b) -> Math.min(1_000_000_000L, a + b));
        }
        var json = new JsonObject();
        comptes.forEach(json::addProperty);
        return json;
    }

    static boolean courant(EtatProduction.Contexte contexte) {
        var mc = Minecraft.getInstance();
        return contexte != null
                && contexte.valide()
                && mc.level != null
                && contexte.monde().equals(IdentiteMonde.monde())
                && contexte.session().equals(IdentiteMonde.session())
                && contexte.dimension().equals(mc.level.dimension().location().toString());
    }

    static void annoter(JsonObject json, EtatProduction.Contexte contexte) {
        json.addProperty("monde_actif", contexte.monde());
        json.addProperty("session_monde", contexte.session());
        json.addProperty("dimension_monde", contexte.dimension());
    }
}
