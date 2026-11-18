package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoffresIdentiteTest {
    public static void main(String[] args) throws Exception {
        var cas = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        for (var entree : cas) {
            var test = entree.getAsJsonObject();
            var memoire = new JsonObject();
            memoire.add("conteneurs", test.get("contents"));
            var stocks = CoffresConnus.analyser(memoire).objets();
            var attendu = test.getAsJsonObject("expected");
            assert stocks.size() == attendu.size() : test.get("name") + " : " + stocks;
            for (var valeur : attendu.entrySet()) {
                assert stocks.getOrDefault(valeur.getKey(), 0L) == valeur.getValue().getAsLong()
                        : test.get("name") + " : " + stocks;
            }
        }
        System.out.println("PASS identite coffres connus");
    }
}
