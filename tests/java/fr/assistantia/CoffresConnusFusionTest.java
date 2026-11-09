package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;

/**
 * Executable headless regression tests: java -ea ... CoffresConnusFusionTest (ASCII output only).
 * Client chest memory (stockage.json) merged with the assistant's server-aware file
 * (coffres_connus.json).
 */
public final class CoffresConnusFusionTest {
    static final String OW = "minecraft:overworld";

    static void verifier(boolean condition, String raison) {
        if (!condition) throw new AssertionError(raison);
    }

    static JsonObject json(String texte) {
        return JsonParser.parseString(texte.replace('\'', '"')).getAsJsonObject();
    }

    /** One container entry; nom null omits the field, as in server observations. */
    static String c(String bloc, String nom, int x, int z, long t, String objets) {
        return "{'v':2,'bloc':'"
                + bloc
                + "',"
                + (nom == null ? "" : "'nom':'" + nom + "',")
                + "'dimension':'"
                + OW
                + "','x':"
                + x
                + ",'y':64,'z':"
                + z
                + ",'t':"
                + t
                + ",'objets':["
                + objets
                + "]}";
    }

    static String o(String item, long n) {
        return "{'item':'" + item + "','n':" + n + "}";
    }

    static String fichier(String machines, String... conteneurs) {
        return "{'v':2,'conteneurs':["
                + String.join(",", conteneurs)
                + "],'machines':["
                + machines
                + "]}";
    }

    static String machine(String bloc) {
        return "{'v':2,'bloc':'" + bloc + "','dimension':'" + OW + "','x':0,'y':0,'z':0,'t':1}";
    }

    static Map<String, Long> objets(String memoire, String fusion) {
        return CoffresConnus.analyser(
                        memoire == null ? null : json(memoire),
                        fusion == null ? null : json(fusion))
                .objets();
    }

    public static void main(String[] args) throws Exception {
        newestObservationWinsPerPosition();
        equalDatePrefersAssistantFile();
        newerEmptyObservationHidesOldContent();
        largeChestTwinsAreSymmetric();
        machinesAreTheUnionOfBothFiles();
        missingSecondFileKeepsLegacyReading();
        filesAreReadMergedAndKeptWhenUnreadable(Files.createTempDirectory("coffres-connus"));
        planCountsOnlyNearbyChestsOfTheSameDimension();
        System.out.println("PASS fusion coffres connus");
    }

    /**
     * Player report: 9 Wrought Iron Ingot were "to fetch" from chests while the player had none.
     * The 28 + 6 ingots were in a Lootr chest and two explored chests of the overworld, the base
     * being in javd:void. Totals stay global (Introuvable), the plan counts only nearby chests.
     */
    static void planCountsOnlyNearbyChestsOfTheSameDimension() {
        String vide = "javd:void", lingot = "gtceu:wrought_iron_ingot";
        var lu =
                CoffresConnus.analyser(
                        json(
                                fichier(
                                        "",
                                        c(
                                                "lootr:lootr_chest",
                                                "Chest",
                                                549,
                                                371,
                                                10,
                                                o(lingot, 28)),
                                        c("minecraft:chest", "Chest", 270, 140, 10, o(lingot, 3))
                                                .replace(OW, vide))),
                        json(
                                fichier(
                                        "",
                                        c("minecraft:chest", null, 533, 387, 20, o(lingot, 2)),
                                        c("minecraft:barrel", null, 600, 900, 20, o(lingot, 50))
                                                .replace(OW, vide),
                                        c("minecraft:barrel", null, 258, 155, 20, o(lingot, 5))
                                                .replace(OW, vide))));
        verifier(lu.objets().get(lingot) == 88L, "global totals unchanged: " + lu.objets());
        verifier(lu.conteneurs().size() == 5, "positions kept: " + lu.conteneurs());
        var base = CoffresConnus.proches(lu.conteneurs(), vide, 265, 150, CoffresConnus.RAYON_PLAN);
        verifier(base.equals(Map.of(lingot, 8L)), "base only (same dimension, in range): " + base);
        var overworld = CoffresConnus.proches(lu.conteneurs(), OW, 540, 380, 256);
        verifier(overworld.equals(Map.of(lingot, 2L)), "never a Lootr chest: " + overworld);
        verifier(
                CoffresConnus.proches(lu.conteneurs(), "minecraft:the_nether", 0, 0, 256).isEmpty(),
                "other dimension");
        // Player report: a press already in an AE2 inscriber must not be fetched like from a chest.
        String presse = "ae2:silicon_press";
        var avecInscriber =
                CoffresConnus.analyser(
                        json(
                                fichier(
                                        "",
                                        c("minecraft:chest", "Chest", 260, 150, 10, o(lingot, 4))
                                                .replace(OW, vide))),
                        json(
                                fichier(
                                        "",
                                        c("ae2:inscriber", null, 262, 150, 20, o(presse, 1))
                                                .replace(OW, vide))));
        var coffres = CoffresConnus.proches(avecInscriber.conteneurs(), vide, 265, 150, 256, false);
        verifier(coffres.equals(Map.of(lingot, 4L)), "an inscriber is never a chest: " + coffres);
        var installes =
                CoffresConnus.proches(avecInscriber.conteneurs(), vide, 265, 150, 256, true);
        verifier(installes.equals(Map.of(presse, 1L)), "press installed: " + installes);
        verifier(
                avecInscriber.objets().get(presse) == 1L,
                "still known for possessions: " + avecInscriber.objets());
    }

    static void newestObservationWinsPerPosition() {
        String drawer = "functionalstorage:oak_1";
        // Server saw the drawer after the player's last visit: 379 sand, not 10.
        var serveurPlusRecent =
                objets(
                        fichier(
                                "",
                                c(drawer, "Oak Drawer", 270, 132, 100, o("minecraft:sand", 10))),
                        fichier("", c(drawer, "Oak 1", 270, 132, 200, o("minecraft:sand", 379))));
        verifier(serveurPlusRecent.equals(Map.of("minecraft:sand", 379L)), "" + serveurPlusRecent);
        // The player opened it after the last server observation: the client memory wins.
        var clientPlusRecent =
                objets(
                        fichier("", c(drawer, "Oak Drawer", 270, 132, 300, o("minecraft:sand", 5))),
                        fichier("", c(drawer, "Oak 1", 270, 132, 200, o("minecraft:sand", 379))));
        verifier(clientPlusRecent.equals(Map.of("minecraft:sand", 5L)), "" + clientPlusRecent);
        // A drawer only the server saw counts (the reported bug), next to a remembered barrel.
        var serveurSeul =
                objets(
                        fichier(
                                "",
                                c("minecraft:barrel", "Barrel", 0, 0, 10, o("minecraft:sand", 2))),
                        fichier("", c(drawer, null, 271, 132, 20, o("minecraft:sand", 379))));
        verifier(serveurSeul.equals(Map.of("minecraft:sand", 381L)), "" + serveurSeul);
    }

    static void equalDatePrefersAssistantFile() {
        var egal =
                objets(
                        fichier(
                                "",
                                c(
                                        "minecraft:barrel",
                                        "Barrel",
                                        5,
                                        5,
                                        50,
                                        o("minecraft:iron_ingot", 3))),
                        fichier(
                                "",
                                c(
                                        "minecraft:barrel",
                                        "Barrel",
                                        5,
                                        5,
                                        50,
                                        o("minecraft:iron_ingot", 9))));
        verifier(
                egal.equals(Map.of("minecraft:iron_ingot", 9L)), "equal t -> merged file: " + egal);
    }

    static void newerEmptyObservationHidesOldContent() {
        var vide =
                objets(
                        fichier(
                                "",
                                c(
                                        "minecraft:barrel",
                                        "Barrel",
                                        7,
                                        7,
                                        10,
                                        o("minecraft:gold_ingot", 4))),
                        fichier("", c("minecraft:barrel", null, 7, 7, 20, "")));
        verifier(vide.isEmpty(), "newer empty observation wins: " + vide);
    }

    static void largeChestTwinsAreSymmetric() {
        String chest = "minecraft:chest";
        // Newer server half (no Large name, no nom at all) hides the older client "Large Chest".
        var serveurRecent =
                objets(
                        fichier(
                                "",
                                c(chest, "Large Chest", 11, 0, 400, o("minecraft:redstone", 2))),
                        fichier("", c(chest, null, 10, 0, 500, o("minecraft:redstone", 6))));
        verifier(
                serveurRecent.equals(Map.of("minecraft:redstone", 6L)),
                "twin with unnamed server half: " + serveurRecent);
        // Newer client "Large Chest" hides the older server half, whichever file comes first.
        var clientRecent =
                objets(
                        fichier("", c(chest, "Large Chest", 21, 0, 900, o("minecraft:coal", 8))),
                        fichier("", c(chest, "Chest", 20, 0, 100, o("minecraft:coal", 1))));
        verifier(
                clientRecent.equals(Map.of("minecraft:coal", 8L)),
                "symmetric twin: " + clientRecent);
        var memeFichier =
                objets(
                        null,
                        fichier(
                                "",
                                c(chest, "Chest", 30, 0, 100, o("minecraft:coal", 1)),
                                c(chest, "Large Chest", 31, 0, 900, o("minecraft:coal", 8))));
        verifier(
                memeFichier.equals(Map.of("minecraft:coal", 8L)),
                "order independent: " + memeFichier);
        // Two adjacent single chests (no Large name) and adjacent drawers stay independent.
        var independants =
                objets(
                        null,
                        fichier(
                                "",
                                c(chest, "Chest", 40, 0, 1, o("minecraft:coal", 1)),
                                c(chest, "Chest", 41, 0, 2, o("minecraft:coal", 2)),
                                c(
                                        "functionalstorage:oak_1",
                                        null,
                                        50,
                                        0,
                                        1,
                                        o("minecraft:sand", 1)),
                                c(
                                        "functionalstorage:oak_1",
                                        null,
                                        51,
                                        0,
                                        2,
                                        o("minecraft:sand", 2))));
        verifier(
                independants.equals(Map.of("minecraft:coal", 3L, "minecraft:sand", 3L)),
                "independent neighbours: " + independants);
        // A "Large" neighbour of another block is not a twin.
        var autreBloc =
                objets(
                        fichier("", c(chest, "Large Chest", 60, 0, 9, o("minecraft:coal", 4))),
                        fichier(
                                "",
                                c(
                                        "minecraft:trapped_chest",
                                        null,
                                        61,
                                        0,
                                        1,
                                        o("minecraft:coal", 5))));
        verifier(autreBloc.equals(Map.of("minecraft:coal", 9L)), "other block: " + autreBloc);
    }

    static void machinesAreTheUnionOfBothFiles() {
        var lu =
                CoffresConnus.analyser(
                        json(
                                fichier(
                                        machine("gtceu:lv_alloy_smelter")
                                                + ","
                                                + "{'v':1,'bloc':'gtceu:old'}")),
                        json(
                                fichier(
                                        machine("gtceu:steam_solid_boiler")
                                                + ","
                                                + machine("gtceu:lv_alloy_smelter"))));
        verifier(
                lu.machines().equals(Set.of("gtceu:lv_alloy_smelter", "gtceu:steam_solid_boiler")),
                "machines union: " + lu.machines());
    }

    static void missingSecondFileKeepsLegacyReading() {
        String memoire =
                fichier(
                        machine("gtceu:lv_bender"),
                        c("minecraft:chest", "Large Chest", 1, 0, 10, o("minecraft:redstone", 5)),
                        c("minecraft:chest", "Large Chest", 2, 0, 20, o("minecraft:redstone", 7)));
        var seul = CoffresConnus.analyser(json(memoire));
        var avecNull = CoffresConnus.analyser(json(memoire), null);
        verifier(seul.equals(avecNull), "analyser(json) == analyser(json, null)");
        verifier(seul.objets().equals(Map.of("minecraft:redstone", 7L)), "" + seul.objets());
        verifier(seul.machines().equals(Set.of("gtceu:lv_bender")), "" + seul.machines());
        var fusionSeule = CoffresConnus.analyser(null, json(memoire));
        verifier(fusionSeule.equals(seul), "merged file alone");
        var rien = CoffresConnus.analyser(null, null);
        verifier(rien.objets().isEmpty() && rien.machines().isEmpty(), "nothing");
    }

    static long horloge = 1_700_000_000_000L;

    static void ecrire(Path fichier, String texte) throws Exception {
        Files.writeString(fichier, texte.replace('\'', '"'), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(fichier, FileTime.fromMillis(horloge += 10_000));
    }

    static void filesAreReadMergedAndKeptWhenUnreadable(Path dossier) throws Exception {
        Path memoire = dossier.resolve("stockage.json"),
                fusion = dossier.resolve("coffres_connus.json");
        String drawer = "functionalstorage:oak_1";

        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var vide = CoffresConnus.etat();
        verifier(vide.pret() && vide.objets().isEmpty(), "no file yet: ready and empty");
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        verifier(CoffresConnus.etat() == vide, "no new revision while nothing exists");

        ecrire(
                memoire,
                fichier("", c("minecraft:barrel", "Barrel", 0, 0, 10, o("minecraft:sand", 2))));
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var client = CoffresConnus.etat();
        verifier(
                client.objets().equals(Map.of("minecraft:sand", 2L)),
                "client only: " + client.objets());
        verifier(client.revision() > vide.revision(), "revision after a change");

        ecrire(
                fusion,
                fichier(
                        machine("gtceu:steam_solid_boiler"),
                        c(drawer, null, 270, 132, 20, o("minecraft:sand", 379))));
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var fusionne = CoffresConnus.etat();
        verifier(
                fusionne.objets().equals(Map.of("minecraft:sand", 381L)),
                "merged: " + fusionne.objets());
        verifier(
                fusionne.machines().equals(Set.of("gtceu:steam_solid_boiler")),
                "" + fusionne.machines());
        verifier(fusionne.revision() > client.revision(), "revision after the merged file appears");

        // Only observation dates move: same totals, same revision (no replanning every frame).
        ecrire(
                fusion,
                fichier(
                        machine("gtceu:steam_solid_boiler"),
                        c(drawer, null, 270, 132, 30, o("minecraft:sand", 379))));
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var rafraichi = CoffresConnus.etat();
        verifier(rafraichi.revision() == fusionne.revision(), "same totals keep the revision");
        verifier(!rafraichi.fusion().equals(fusionne.fusion()), "new stamp remembered");

        // A file being written (unreadable) keeps the last good state entirely.
        ecrire(fusion, "{'v':2,'conteneurs':[");
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        verifier(CoffresConnus.etat() == rafraichi, "unreadable file keeps the last state");

        ecrire(fusion, fichier("", c(drawer, null, 270, 132, 40, o("minecraft:sand", 300))));
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var change = CoffresConnus.etat();
        verifier(change.objets().equals(Map.of("minecraft:sand", 302L)), "" + change.objets());
        verifier(change.revision() > rafraichi.revision(), "revision after a real change");

        // Assistant stopped and its file removed: back to the client memory alone.
        Files.delete(fusion);
        CoffresConnus.lire(dossier, CoffresConnus.etat());
        var retour = CoffresConnus.etat();
        verifier(retour.objets().equals(Map.of("minecraft:sand", 2L)), "" + retour.objets());
        verifier(
                retour.machines().isEmpty() && retour.revision() > change.revision(),
                "back to memory");
        Files.delete(memoire);
        Files.delete(dossier);
    }
}
