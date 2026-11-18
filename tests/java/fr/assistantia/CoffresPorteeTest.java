package fr.assistantia;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import net.minecraft.client.Minecraft;

public final class CoffresPorteeTest {
    private static final Map<String, Long> CHARBON = Map.of("minecraft:coal", 7L);
    private static final Map<String, Long> PRESSE = Map.of("ae2:silicon_press", 1L);

    public static void main(String[] args) throws Exception {
        var mc = Minecraft.getInstance();
        mc.player = new Minecraft.Joueur();
        mc.level = new Minecraft.Niveau();
        var position = mc.player.pos;

        Path dossier = Path.of(args[0]);
        Path memoire = dossier.resolve("stockage.json");
        ecrire(memoire, 11);
        CoffresConnus.lire(dossier, CoffresConnus.etat());

        position.x = 10;
        assert CoffresConnus.proches().isEmpty() : "coffre à 257 blocs";
        assert CoffresConnus.enMachines().equals(PRESSE) : "inscriber à 256 blocs";
        long avant = CoffresConnus.revisionProches();
        position.x = 11;
        assert CoffresConnus.proches().equals(CHARBON) : "coffre à 256 blocs, même secteur";
        assert CoffresConnus.enMachines().isEmpty() : "inscriber à 257 blocs, même secteur";
        long auBord = CoffresConnus.revisionProches();
        assert auBord > avant : "le passage de la limite invalide le plan";

        position.z = 1;
        assert CoffresConnus.proches().isEmpty() : "la portée est circulaire";
        position.z = 0;
        assert CoffresConnus.proches().equals(CHARBON);
        long dedans = CoffresConnus.revisionProches();
        position.x = 12;
        assert CoffresConnus.revisionProches() == dedans : "même stock malgré le déplacement";
        assert CoffresConnus.proches().equals(CHARBON);
        position.x = 63;
        assert CoffresConnus.proches().equals(CHARBON);
        position.x = 64;
        assert CoffresConnus.revisionProches() == dedans
                : "passer un secteur garde des totaux identiques";
        position.x = 12;

        long revisionFichiers = CoffresConnus.etat().revision();
        ecrire(memoire, 21);
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        assert CoffresConnus.etat().revision() > revisionFichiers;
        assert CoffresConnus.revisionProches() == dedans
                : "un changement distant ne refait pas le plan";

        CoffreTampon.tamponX = 267L;
        CoffreTampon.revision++;
        assert CoffresConnus.proches().isEmpty() : "un tampon n'est pas un coffre ordinaire";
        assert CoffresConnus.revisionProches() > dedans;
        CoffreTampon.tamponX = null;
        CoffreTampon.excluX = 267L;
        CoffreTampon.revision++;
        assert CoffresConnus.proches().isEmpty() : "un coffre exclu sort du calcul";
        CoffreTampon.excluX = null;
        CoffreTampon.revision++;
        assert CoffresConnus.proches().equals(CHARBON);

        mc.level.cle.id = "minecraft:the_nether";
        assert CoffresConnus.proches().equals(Map.of("minecraft:gold_ingot", 2L));
        assert CoffresConnus.enMachines().isEmpty();

        mc.level.cle.id = "minecraft:overworld";
        position.x = -30;
        assert CoffresConnus.proches().equals(Map.of("minecraft:redstone", 3L));
        position.x = -29;
        assert CoffresConnus.proches().isEmpty() : "257 blocs à l'ouest du joueur";
        assert CoffresConnus.enMachines().equals(PRESSE);

        mc.player = null;
        assert CoffresConnus.proches().isEmpty();
        assert CoffresConnus.enMachines().isEmpty();
        System.out.println("PASS portee exacte");
    }

    private static void ecrire(Path memoire, int distant) throws Exception {
        String releve =
                """
                {"conteneurs":[
                  {"v":2,"bloc":"minecraft:barrel","dimension":"minecraft:overworld","x":267,"y":64,"z":0,"t":1,
                   "objets":[{"item":"minecraft:coal","n":7}]},
                  {"v":2,"bloc":"lootr:lootr_chest","dimension":"minecraft:overworld","x":267,"y":63,"z":0,"t":1,
                   "objets":[{"item":"minecraft:coal","n":8}]},
                  {"v":2,"bloc":"minecraft:barrel","dimension":"minecraft:the_nether","x":267,"y":64,"z":0,"t":1,
                   "objets":[{"item":"minecraft:gold_ingot","n":2}]},
                  {"v":2,"bloc":"ae2:inscriber","dimension":"minecraft:overworld","x":-246,"y":64,"z":0,"t":1,
                   "objets":[{"item":"ae2:silicon_press","n":1}]},
                  {"v":2,"bloc":"minecraft:barrel","dimension":"minecraft:overworld","x":-286,"y":64,"z":0,"t":1,
                   "objets":[{"item":"minecraft:redstone","n":3}]},
                  {"v":2,"bloc":"minecraft:barrel","dimension":"minecraft:overworld","x":555,"y":64,"z":0,"t":1,
                   "objets":[{"item":"minecraft:coal","n":%d}]}
                ]}
                """
                        .formatted(distant);
        Files.writeString(memoire, releve);
        Files.setLastModifiedTime(memoire, FileTime.fromMillis(1_700_000_000_000L + distant));
    }
}
