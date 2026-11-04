package fr.assistantia;

import java.nio.file.Files;

public final class AvisMiseAJourTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("avis-maj-test");
        var path = directory.resolve("avis.txt");
        try {
            var avis = new AvisMiseAJour(path);
            assert !avis.visible("v1", "");
            assert avis.visible("v1", "Mise à jour disponible");
            avis.masquer("v1", "Mise à jour disponible");
            assert !avis.visible("v1", "Mise à jour disponible");
            assert !avis.visible("v1", "Mise à jour prête") : "même version";
            var reopened = new AvisMiseAJour(path);
            assert !reopened.visible("v1", "Mise à jour disponible") : "après redémarrage";
            assert reopened.visible("v2", "Mise à jour disponible")
                    : "nouvelle version, même texte";
            reopened.masquer("", "Ancien assistant");
            assert !new AvisMiseAJour(path).visible("", "Ancien assistant");
            assert new AvisMiseAJour(path).visible("", "Autre avis");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
        System.out.println("Avis masqué persistant et nouvelle mise à jour visible : OK");
    }
}
