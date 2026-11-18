package fr.assistantia;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

public final class CoffresConnusContexteTest {
    public static void main(String[] args) throws Exception {
        FMLPaths.racine = Path.of(args[0]);
        Path mondeA = monde('a'), mondeB = monde('b'), mondeC = monde('c');
        Files.writeString(mondeA.resolve("stockage.json"), stock("minecraft:redstone"));
        Files.writeString(mondeB.resolve("stockage.json"), stock("minecraft:gold_ingot"));
        Files.writeString(mondeC.resolve("stockage.json"), stock("minecraft:iron_ingot"));

        var champ = CoffresConnus.class.getDeclaredField("LECTEUR");
        champ.setAccessible(true);
        var lecteur = (ExecutorService) champ.get(null);
        var mc = Minecraft.getInstance();
        var pause = suspendre(lecteur);
        try {
            connecter(mc, mondeA);
            CoffresConnus.tick();
            mc.level = null;
            mc.player = null;
            CoffresConnus.tick();
            connecter(mc, mondeB);
            CoffresConnus.tick();
        } finally {
            pause.countDown();
        }
        attendre(lecteur);
        assert !CoffresConnus.etat().pret();
        assert CoffresConnus.proches().isEmpty();
        CoffresConnus.tick();
        attendre(lecteur);
        assert CoffresConnus.proches().equals(Map.of("minecraft:gold_ingot", 5L));

        // Le dossier peut changer alors que l'objet connexion reste le même.
        pause = suspendre(lecteur);
        try {
            IdentiteMonde.actif = mondeA.getFileName().toString();
            CoffresConnus.tick();
            IdentiteMonde.actif = mondeC.getFileName().toString();
            CoffresConnus.tick();
        } finally {
            pause.countDown();
        }
        attendre(lecteur);
        assert CoffresConnus.proches().isEmpty();
        CoffresConnus.tick();
        attendre(lecteur);
        assert CoffresConnus.proches().equals(Map.of("minecraft:iron_ingot", 5L));

        Path memoire = mondeC.resolve("stockage.json");
        var identite =
                Files.readAttributes(memoire, java.nio.file.attribute.BasicFileAttributes.class);
        if (identite.fileKey() != null) {
            Path remplacement = mondeC.resolve("remplacement.json");
            Files.writeString(remplacement, stock("minecraft:gold_ingot"));
            Files.setLastModifiedTime(remplacement, identite.lastModifiedTime());
            assert Files.size(remplacement) == identite.size();
            Files.move(remplacement, memoire, StandardCopyOption.REPLACE_EXISTING);
            CoffresConnus.lire(mondeC, CoffresConnus.etat());
            assert CoffresConnus.proches().equals(Map.of("minecraft:gold_ingot", 5L));
        }
        System.out.println("PASS contexte coffres connus");
    }

    private static void connecter(Minecraft mc, Path monde) {
        mc.level = new Minecraft.Niveau();
        mc.player = new Minecraft.Joueur();
        mc.connexion = new Object();
        IdentiteMonde.actif = monde.getFileName().toString();
    }

    private static Path monde(char nom) throws Exception {
        return Files.createDirectories(
                FMLPaths.racine.resolve(
                        "kubejs/assistant/mondes/m1_" + String.valueOf(nom).repeat(64)));
    }

    private static CountDownLatch suspendre(ExecutorService lecteur) {
        var pause = new CountDownLatch(1);
        lecteur.submit(() -> pause.await(5, TimeUnit.SECONDS));
        return pause;
    }

    private static void attendre(ExecutorService lecteur) throws Exception {
        lecteur.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static String stock(String item) {
        return """
                {"conteneurs":[{"v":2,"bloc":"minecraft:chest","dimension":"minecraft:overworld",
                "x":0,"y":64,"z":0,"t":100,"objets":[{"item":"%s","n":5}]}]}
                """
                .formatted(item);
    }
}
