package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Catalogue en lecture seule ; les identifiants configurés restent sélectionnables hors ligne. */
public final class CatalogueModeles {
    public record Modele(String id, String nom, String origine, List<String> efforts) {}

    private CatalogueModeles() {}

    public static List<Modele> lire(JsonObject vue) {
        List<Modele> result = new ArrayList<>();
        for (var entry : Pont.liste(vue, "modeles")) {
            if (!entry.isJsonObject()) continue;
            JsonObject j = entry.getAsJsonObject();
            String id = Pont.texte(j, "id"), nom = Pont.texte(j, "nom");
            if (id.isEmpty() || result.stream().anyMatch(m -> m.id().equals(id))) continue;
            List<String> efforts = new ArrayList<>();
            for (var effort : Pont.liste(j, "efforts")) {
                if (effort.isJsonPrimitive()
                        && effort.getAsJsonPrimitive().isString()
                        && List.of("low", "medium", "high", "xhigh", "max")
                                .contains(effort.getAsString())
                        && !efforts.contains(effort.getAsString()))
                    efforts.add(effort.getAsString());
            }
            result.add(
                    new Modele(
                            id,
                            nom.isEmpty() ? id : nom,
                            Pont.texte(j, "origine"),
                            List.copyOf(efforts)));
        }
        return List.copyOf(result);
    }

    public static List<String> efforts(List<Modele> modeles, String id, List<String> secours) {
        return modeles.stream()
                .filter(m -> m.id().equals(id) && !m.efforts().isEmpty())
                .map(Modele::efforts)
                .findFirst()
                .orElse(secours);
    }

    public static String nom(List<Modele> modeles, String id) {
        return modeles.stream()
                .filter(m -> m.id().equals(id))
                .map(Modele::nom)
                .findFirst()
                .orElse(id);
    }
}
