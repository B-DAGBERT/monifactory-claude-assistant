package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Instantané natif borné au clic de lecture ; aucun retrait, scan ni acquisition de backpack. */
final class InventaireProduction {
    private record Capture(
            String id,
            EtatProduction.Contexte contexte,
            long inventaire,
            JsonObject stock,
            JsonObject demande,
            JsonObject catalogue,
            long revisionLots,
            String lots) {}

    private static Capture derniere;
    private static String empreinteCapture = "";

    private InventaireProduction() {}

    static CompletableFuture<String> exporter(
            JsonObject demande,
            EtatProduction.Contexte contexte,
            JsonObject catalogue,
            boolean depuisCoffre) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !courant(contexte))
            return CompletableFuture.completedFuture("");
        long maintenant = System.currentTimeMillis();
        String capture = Pont.texte(demande, "capture");
        var json = new JsonObject();
        json.addProperty("v", 2);
        json.addProperty("t", maintenant);
        json.addProperty("capture", capture);
        json.addProperty("dimension", contexte.dimension());
        json.addProperty("actif", true);
        for (String cle :
                java.util.List.of(
                        "projet",
                        "revision_projet",
                        "catalogue_source",
                        "catalogue_generation",
                        "catalogue_sha256")) json.add(cle, demande.get(cle));
        JsonObject stock = depuisCoffre ? StockProduction.capturer(maintenant) : null;
        if (stock != null) json.add("stock", stock);
        json.addProperty("coffre_demande", depuisCoffre);
        boolean synchronise = InventaireJoueur.disponible();
        json.addProperty("synchronise", synchronise);
        json.addProperty("complet", false);
        json.add(
                "inventaire",
                sansVariantes(
                        stock == null && synchronise
                                ? InventaireJoueur.piles()
                                : java.util.List.of()));
        JsonObject lots = lots(demande, catalogue);
        json.add("suivi", lots);
        annoter(json, contexte);
        String empreinte = Pont.vrai(lots, "disponible") ? Pont.texte(lots, "empreinte") : "";
        long revisionLots = lots.has("revision") ? lots.get("revision").getAsLong() : -1;
        derniere =
                new Capture(
                        capture,
                        contexte,
                        InventaireJoueur.revision(),
                        stock,
                        demande.deepCopy(),
                        catalogue.deepCopy(),
                        revisionLots,
                        empreinte);
        empreinteCapture = "";
        return CatalogueProduction.publierInventaire(json);
    }

    private static JsonObject lots(JsonObject demande, JsonObject catalogue) {
        try {
            return (JsonObject)
                    Class.forName("fr.assistantia.compat.emi.MachinesEmi")
                            .getMethod("lotsProduction", JsonObject.class, JsonObject.class)
                            .invoke(null, demande, catalogue);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return new JsonObject();
        }
    }

    static boolean confirmerCapture(String id, String empreinte) {
        if (derniere == null || !derniere.id.equals(id) || !empreinte.matches("[0-9a-f]{64}"))
            return false;
        empreinteCapture = empreinte;
        return captureCourante(id, empreinte);
    }

    static boolean captureCourante(String id, String empreinte) {
        var capture = derniere;
        if (capture == null
                || !capture.id.equals(id)
                || !courant(capture.contexte)
                || empreinte.isEmpty()
                || !empreinteCapture.equals(empreinte)
                || capture.inventaire != InventaireJoueur.revision()
                || (capture.stock != null && !StockProduction.valide(capture.stock))) return false;
        var ecran = Pont.partie("ecran");
        var production = ecran.get("production_v2");
        if (production == null || !production.isJsonObject()) return false;
        var courant = production.getAsJsonObject().get("contexte");
        if (courant == null
                || !courant.isJsonObject()
                || !capture.contexte
                        .projet()
                        .equals(Pont.texte(courant.getAsJsonObject(), "projet"))
                || !capture.contexte
                        .revision()
                        .equals(Pont.texte(courant.getAsJsonObject(), "revision_projet")))
            return false;
        if (capture.lots.isEmpty()) return true;
        try {
            return capture.revisionLots
                    == (long)
                            Class.forName("fr.assistantia.compat.emi.MachinesEmi")
                                    .getMethod("revisionLotsProduction")
                                    .invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }

    static boolean valideCapture(String id, String empreinte) {
        if (!captureCourante(id, empreinte)) return false;
        var capture = derniere;
        if (capture.lots.isEmpty()) return true;
        JsonObject lots = lots(capture.demande, capture.catalogue);
        return Pont.vrai(lots, "disponible") && capture.lots.equals(Pont.texte(lots, "empreinte"));
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
