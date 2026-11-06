package fr.assistantia.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.LotFabrication;
import fr.assistantia.PlanRetrait;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Projection des seuls lots du propriétaire natif ; aucune réconciliation ou action au clic. */
final class ProjectionLotsEmi {
    private static final int MAX_LOTS = 256, MAX_ENTREES = 4096, MAX_TOTAL_ENTREES = 16384;
    private static final long MAX_QUANTITE = 1_000_000_000L;

    record Portee(
            Object arbre,
            Object connexion,
            Object recettes,
            String monde,
            String session,
            String dimension,
            String projet,
            String revisionProjet,
            long revisionArbre,
            long lotsArbre) {
        boolean accepte(JsonObject contexte) {
            return arbre != null
                    && connexion != null
                    && recettes != null
                    && monde.matches("m1_[0-9a-f]{64}")
                    && session.matches("[0-9a-f]{32}")
                    && monde.equals(texte(contexte, "monde_actif"))
                    && session.equals(texte(contexte, "session_monde"))
                    && dimension.equals(texte(contexte, "dimension_monde"))
                    && projet.equals(texte(contexte, "projet"))
                    && !revisionProjet.isBlank()
                    && revisionProjet.equals(texte(contexte, "revision_projet"))
                    && revisionArbre >= 0
                    && lotsArbre > 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Portee p
                    && arbre == p.arbre
                    && connexion == p.connexion
                    && recettes == p.recettes
                    && monde.equals(p.monde)
                    && session.equals(p.session)
                    && dimension.equals(p.dimension)
                    && projet.equals(p.projet)
                    && revisionProjet.equals(p.revisionProjet)
                    && revisionArbre == p.revisionArbre
                    && lotsArbre == p.lotsArbre;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    System.identityHashCode(arbre),
                    System.identityHashCode(connexion),
                    System.identityHashCode(recettes),
                    monde,
                    session,
                    dimension,
                    projet,
                    revisionProjet,
                    revisionArbre,
                    lotsArbre);
        }
    }

    private record Quantite(JsonObject resource, long n) {}

    private ProjectionLotsEmi() {}

    static String texte(JsonObject objet, String cle) {
        var valeur = objet == null ? null : objet.get(cle);
        return valeur != null && valeur.isJsonPrimitive() && valeur.getAsJsonPrimitive().isString()
                ? valeur.getAsString()
                : "";
    }

    static JsonObject lire(
            SuiviLotsEmi suivi,
            Portee portee,
            List<PlanRetrait.Stock<EmiStack>> personnels,
            boolean synchronise,
            JsonObject contexte,
            JsonObject catalogue) {
        JsonObject result = new JsonObject();
        result.addProperty("v", 3);
        result.addProperty("revision", suivi.revision());
        JsonArray lots = new JsonArray();
        TreeSet<String> limites = new TreeSet<>();
        int entrees = 0;
        boolean disponible = portee != null && portee.accepte(contexte) && synchronise;
        if (!disponible) limites.add("Suivi des lots indisponible dans ce contexte natif.");
        try {
            if (suivi.actifs().size() > MAX_LOTS)
                throw new IllegalArgumentException("Trop de lots natifs ; aucune troncature.");
            var stock = quantites(personnels);
            if (!suivi.actifs().isEmpty()
                    && (suivi.inventaireAssimile() == null
                            || !stock.equals(quantites(suivi.inventaireAssimile()))
                            || suivi.receptionEnAttente())) {
                disponible = false;
                limites.add("Réceptions ou inventaire encore en cours de rapprochement.");
            }
            result.addProperty("stock_empreinte", sha(montants(stock).toString()));
            if (disponible)
                for (var lot : suivi.actifs()) {
                    if (!portee.equals(suivi.origine(lot.id()))) {
                        limites.add(
                                "Lots d'un autre arbre, projet ou contexte non attribué exclus.");
                        continue;
                    }
                    if (lot.suivi().receptionNonAttribuee()) {
                        limites.add(
                                "Lots avec réception sans preuve de transfert exact exclus du calcul (corrélation locale seulement).");
                        continue;
                    }
                    JsonObject valeur;
                    try {
                        if (!(portee.recettes()
                                        instanceof dev.emi.emi.api.recipe.EmiRecipeManager manager)
                                || lot.recette().getId() == null
                                || manager.getRecipe(lot.recette().getId()) != lot.recette())
                            throw new IllegalArgumentException("ancienne génération de recette");
                        valeur = lot(lot, portee);
                    } catch (IllegalArgumentException | ArithmeticException error) {
                        limites.add("Lots non représentables exclus : " + error.getMessage());
                        continue;
                    }
                    for (String cle : List.of("total", "recus", "restants", "annules"))
                        entrees += valeur.getAsJsonArray(cle).size();
                    if (entrees > MAX_TOTAL_ENTREES)
                        throw new IllegalArgumentException(
                                "Trop de quantités de lots ; aucune troncature.");
                    lots.add(valeur);
                }
        } catch (IllegalArgumentException | ArithmeticException error) {
            disponible = false;
            limites.add(error.getMessage());
        }
        result.addProperty("disponible", disponible);
        result.add("lots", disponible ? lots : new JsonArray());
        JsonArray messages = new JsonArray();
        limites.forEach(messages::add);
        result.add("limites", messages);
        // La révision métier seule n'inclut pas À récupérer ni une preuve en vol.
        JsonObject preuve = new JsonObject();
        preuve.add("suivi", result.deepCopy());
        JsonObject contexteExact = new JsonObject();
        for (String cle :
                List.of(
                        "monde_actif",
                        "session_monde",
                        "dimension_monde",
                        "projet",
                        "revision_projet"))
            if (contexte != null && contexte.has(cle)) contexteExact.add(cle, contexte.get(cle));
        preuve.add("contexte", contexteExact);
        JsonObject selection = new JsonObject();
        for (String cle : List.of("source", "generation", "sha256"))
            if (catalogue != null && catalogue.has(cle)) selection.add(cle, catalogue.get(cle));
        preuve.add("catalogue", selection);
        result.addProperty("empreinte", sha(preuve.toString()));
        return result;
    }

    private static JsonObject lot(LotMachine lot, Portee portee) {
        if (lot.id() <= 0 || lot.recette().getId() == null)
            throw new IllegalArgumentException("identité de lot ou recette absente");
        var total = quantites(lot.suivi().attendus());
        var recette = new java.util.ArrayList<PlanRetrait.Stock<EmiStack>>();
        for (var sortie : lot.recette().getOutputs()) {
            if (sortie.isEmpty()
                    || sortie.getChance() < 1
                    || lot.manuel() && sortie.getItemStack().isEmpty()) continue;
            long n = Math.multiplyExact(sortie.getAmount(), lot.fois());
            if (n <= 0 || n > 1_000_000L)
                throw new IllegalArgumentException("total natif plafonné ou invalide");
            recette.add(new PlanRetrait.Stock<>(sortie, (int) n, true));
        }
        if (!total.equals(quantites(recette)))
            throw new IllegalArgumentException("total promis non représentable exactement");
        var recus = quantites(lot.suivi().obtenus());
        var restant = quantites(lot.suivi().restants());
        if (total.isEmpty()) throw new IllegalArgumentException("aucune sortie garantie");
        TreeMap<String, Quantite> difference = new TreeMap<>();
        for (var entree : total.entrySet()) {
            long recu = recus.containsKey(entree.getKey()) ? recus.get(entree.getKey()).n() : 0;
            long n = entree.getValue().n() - recu;
            if (n < 0) throw new IllegalArgumentException("réception supérieure au total promis");
            if (n > 0)
                difference.put(entree.getKey(), new Quantite(entree.getValue().resource(), n));
        }
        if (!total.keySet().containsAll(recus.keySet()))
            throw new IllegalArgumentException("réception hors promesse");
        var etat = lot.suivi().etat();
        boolean annule = etat == LotFabrication.Etat.ANNULE;
        if ((annule && !restant.isEmpty()) || (!annule && !restant.equals(difference)))
            throw new IllegalArgumentException("quantités de suivi incohérentes");
        if ((etat == LotFabrication.Etat.TERMINE) != (!annule && difference.isEmpty()))
            throw new IllegalArgumentException("état de réception incohérent");
        JsonObject result = new JsonObject();
        result.addProperty("id", "emi-lot/" + portee.session() + "/" + lot.id());
        result.addProperty("recette", lot.recette().getId().toString());
        result.addProperty("origine", lot.manuel() ? "declaration_joueur" : "envoi_machine");
        result.addProperty(
                "etat",
                switch (etat) {
                    case EN_MACHINE -> "en_cours";
                    case A_RECUPERER -> "a_recuperer";
                    case TERMINE -> "termine";
                    case ANNULE -> "annule";
                });
        result.add("total", montants(total));
        result.add("recus", montants(recus));
        result.add("restants", montants(restant));
        result.add("annules", annule ? montants(difference) : new JsonArray());
        lot.emplacement()
                .ifPresent(
                        p ->
                                result.addProperty(
                                        "machine_key",
                                        portee.dimension()
                                                + "|"
                                                + p.getX()
                                                + "|"
                                                + p.getY()
                                                + "|"
                                                + p.getZ()));
        return result;
    }

    private static TreeMap<String, Quantite> quantites(List<PlanRetrait.Stock<EmiStack>> piles) {
        if (piles.size() > MAX_ENTREES)
            throw new IllegalArgumentException("Trop de ressources natives ; aucune troncature.");
        TreeMap<String, Quantite> result = new TreeMap<>();
        for (var pile : piles) {
            if (pile.quantite() < 0) throw new IllegalArgumentException("quantité négative");
            if (pile.quantite() == 0) continue;
            JsonObject resource = RecetteProduction.resource(pile.objet());
            String cle = resource.toString();
            long n =
                    Math.addExact(
                            pile.quantite(), result.containsKey(cle) ? result.get(cle).n() : 0);
            if (n > MAX_QUANTITE) throw new IllegalArgumentException("quantité hors limite exacte");
            result.put(cle, new Quantite(resource, n));
        }
        return result;
    }

    private static JsonArray montants(TreeMap<String, Quantite> quantites) {
        JsonArray result = new JsonArray();
        for (var entree : quantites.values()) {
            JsonObject amount = new JsonObject();
            amount.add("resource", entree.resource().deepCopy());
            amount.addProperty("quantity", entree.n());
            result.add(amount);
        }
        return result;
    }

    private static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
