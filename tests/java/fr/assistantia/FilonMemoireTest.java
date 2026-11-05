package fr.assistantia;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable headless regression tests: java -ea ... FilonMemoireTest. */
public final class FilonMemoireTest {
    static FilonMemoire.Position p(int x, int y, int z) {
        return new FilonMemoire.Position("minecraft:overworld", x, y, z);
    }

    static void verifier(boolean condition, String raison) {
        if (!condition) throw new AssertionError(raison);
    }

    public static void main(String[] args) {
        suivi();
        groupes();
        restauration();
        scan();
        securite();
        limite();
        importHistorique();
        pauseScan();
        scanPonctuel();
        fraicheur();
        arrivee();
        mondeActif();
        choixAffichage();
        gisementsGregtech();
        disposition();
        epuisementManuel();
        destinationProche();
        teleportationDirecte();
        rotationCache();
        System.out.println(
                "19 scénarios filons validés : suivi, groupes, restauration, scan, destination, limite.");
    }

    static void disposition() {
        for (int largeur : new int[] {300, 440, 460, 620, 1000})
            for (int hauteur : new int[] {164, 200, 300, 500})
                for (boolean outils : new boolean[] {false, true}) {
                    int x = 10, y = 36, bas = y + hauteur;
                    var d = DispositionFilons.calculer(x, y, largeur, bas, outils);
                    verifier(
                            d.lignes() >= 1,
                            "Une ligne reste accessible, même en petite fenêtre avec Outils ouvert");
                    verifier(
                            d.listeY() + d.lignes() * DispositionFilons.LIGNE <= d.listeBas(),
                            "Pas de ligne coupée ni de clic hors liste");
                    verifier(
                            d.detailX() >= x && d.detailX() + d.detailLargeur() <= x + largeur,
                            "Fiche dans la fenêtre");
                    verifier(
                            d.actionsY() + 18 < d.statutY() && d.statutY() + 9 <= bas,
                            "Actions et statut sans chevauchement");
                    verifier(
                            d.detailY() + 24 <= d.actionsY(),
                            "Titre et coordonnées toujours visibles");
                    if (d.colonnes())
                        verifier(x + d.listeLargeur() < d.detailX(), "Colonnes séparées");
                    else
                        verifier(
                                d.listeBas() < d.detailY(),
                                "Fiche sous la liste en petite fenêtre");
                }
    }

    static void gisementsGregtech() {
        var m = new FilonMemoire(true);
        var centre = p(0, 20, 0);
        verifier(
                !m.observer(centre, "minecraft:iron_ore", true, 1) && m.filons().isEmpty(),
                "Un bloc isolé ne crée jamais un faux gisement GT");
        verifier(
                m.gisement(
                        "gt:a",
                        centre,
                        "gtceu:iron_vein",
                        "Iron vein",
                        Set.of("minecraft:iron_ore"),
                        false),
                "Gisement natif ajouté");
        var f = m.filon("gt:a");
        verifier(
                f.visible() && f.restants() == 0 && f.types().containsKey("minecraft:iron_ore"),
                "Gisement visible sans scan, composition native disponible");
        verifier(
                f.typesReperes().isEmpty() && !f.quantite().contains("0 "),
                "Composition théorique sans faux compteurs à zéro");
        verifier(
                !m.gisement(
                        "gt:a",
                        centre,
                        "gtceu:iron_vein",
                        "Iron vein",
                        Set.of("minecraft:iron_ore"),
                        false),
                "Lecture répétée sans doublon ni écriture inutile");
        verifier(
                !m.observer(centre, "minecraft:gold_ore", true, 2),
                "Une composition incompatible n'est pas attribuée au gisement");
        verifier(
                !m.observer(p(100, 20, 0), "minecraft:iron_ore", true, 2),
                "Pas de relevé arbitrairement distant");
        verifier(
                m.observer(centre, "minecraft:iron_ore", true, 2), "Relevé compatible à proximité");
        verifier(
                f.typesReperes().equals(Map.of("minecraft:iron_ore", 1)),
                "Afficher seulement le minerai réellement observé");
        m.observer(centre, "minecraft:air", false, 3);
        verifier(
                f.typesReperes().isEmpty(),
                "Un minerai retiré ne reste pas sous forme de ligne à zéro");
        verifier(f.visible() && !f.epuise, "Zéro observé n'épuise pas le gisement GT");
        m.gisement(
                "gt:b",
                p(1, 20, 0),
                "gtceu:gold_vein",
                "Gold vein",
                Set.of("minecraft:gold_ore"),
                false);
        verifier(m.filons().size() == 2, "Deux gisements GT proches ne fusionnent jamais");
        f.choisirTypes(Set.of("minecraft:iron_ore"));
        verifier(
                m.viderReleves() == 1
                        && m.blocs().isEmpty()
                        && m.filons().size() == 2
                        && f.typeAffiche("minecraft:iron_ore"),
                "Flush du cache conserve gisements et choix");
        verifier(m.viderReleves() == 0, "Flush répété sans effet");
        m.gisement(
                "gt:a", centre, "gtceu:iron_vein", "Iron vein", Set.of("minecraft:iron_ore"), true);
        verifier(!f.visible(), "Seul l'indicateur GT peut masquer le gisement comme épuisé");
        m.restaurerReleve(centre, "minecraft:iron_ore", false, 100, "gt:a");
        verifier(
                m.blocs().iterator().next().filon.equals("gt:a"),
                "Restauration conserve l'identité native");
        m.restaurerReleve(p(2, 20, 0), "minecraft:iron_ore", false, 100, "absent");
        verifier(m.blocs().size() == 1, "Pas de restauration d'un relevé orphelin");
        var autre = new FilonMemoire(true);
        verifier(autre.filons().isEmpty(), "Aucun gisement hérité d'un autre monde");
    }

    static void epuisementManuel() {
        var m = new FilonMemoire(true);
        var centre = p(0, 20, 0);
        var types = Set.of("minecraft:iron_ore");
        m.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein", types, false);
        m.observer(centre, "minecraft:iron_ore", true, 1);
        var f = m.filon("gt:a");
        verifier(f.marquerEpuise(true), "Marquage personnel accepté");
        verifier(
                !f.visible() && f.estEpuise() && !f.epuise && f.epuiseManuel(),
                "Masquer sans altérer le journal GregTech");
        verifier(
                f.quantite().contains("personnel") && f.restants() == 1,
                "Source du marquage explicite, relevés conservés");
        verifier(!f.marquerEpuise(true), "Répéter le même marquage est sans effet");
        m.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein actualisé", types, false);
        verifier(
                !f.visible() && f.epuiseManuel(),
                "Le journal actualisé n'efface pas le marquage personnel");
        verifier(
                f.marquerEpuise(false) && f.visible() && f.restants() == 1,
                "Annuler retrouve le filon et ses relevés");
        f.marquerEpuise(true);
        m.viderReleves();
        verifier(f.epuiseManuel() && !f.visible(), "Vider les relevés conserve le marquage");
        var autreMonde = new FilonMemoire(true);
        autreMonde.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein", types, false);
        verifier(
                autreMonde.filon("gt:a").visible(),
                "Même identifiant dans un autre monde : aucun marquage hérité");
        autreMonde.filon("gt:a").marquerEpuise(f.epuiseManuel());
        verifier(
                !autreMonde.filon("gt:a").visible(),
                "Restaurer le champ sauvegardé rétablit le marquage");
        m.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein", types, true);
        f.marquerEpuise(false);
        verifier(
                !f.visible() && f.epuise && !f.epuiseManuel(),
                "Annuler le marquage n'efface jamais l'épuisement GregTech");
    }

    static void destinationProche() {
        var centre = p(0, 10, 0);
        var lateral = p(2, 11, 0);
        var surface = p(0, 80, 0);
        verifier(
                FilonMemoire.destinationProche(
                                centre, -64, 320, q -> q.equals(lateral) || q.equals(surface))
                        .equals(lateral),
                "Préférer un passage voisin à la surface lointaine");
        verifier(
                FilonMemoire.destinationProche(centre, -64, 320, q -> q.equals(surface))
                        .equals(surface),
                "Conserver la surface si aucun passage voisin n'est libre");
        var pres = p(-1, 12, -1);
        var loin = p(-7, 11, -7);
        verifier(
                FilonMemoire.destinationProche(
                                centre, -64, 320, q -> q.equals(pres) || q.equals(loin))
                        .equals(pres),
                "Comparer les distances réelles, pas seulement la première colonne visitée");
        verifier(
                FilonMemoire.destinationProche(
                                centre,
                                -64,
                                320,
                                q -> q.y() <= 10 || Math.abs(q.x()) > FilonMemoire.RAYON_TP)
                        == null,
                "Recherche bornée au voisinage, toujours au-dessus du filon");
        AtomicInteger appels = new AtomicInteger();
        verifier(
                FilonMemoire.destinationProche(
                                        p(0, -64, 0),
                                        -64,
                                        320,
                                        q -> {
                                            appels.incrementAndGet();
                                            return false;
                                        })
                                == null
                        && appels.get() < 120000,
                "Aucune case libre : recherche bornée avant repli direct");
        verifier(
                FilonMemoire.destinationProche(p(0, 318, 0), -64, 320, q -> true) == null,
                "Pas de point libre au-delà du plafond");
    }

    static void teleportationDirecte() {
        verifier(
                FilonMemoire.destinationDirecte(p(10000, 10, -20000), -64, 320)
                        .equals(p(10000, 11, -20000)),
                "Zone non chargée ou pleine : destination directe juste au-dessus, sans scan obligatoire");
        verifier(
                FilonMemoire.destinationDirecte(p(0, 400, 0), -64, 320).equals(p(0, 318, 0)),
                "Repli borné au plafond avec place pour la tête");
        verifier(
                FilonMemoire.destinationDirecte(p(0, -100, 0), -64, 320).equals(p(0, -63, 0)),
                "Repli borné au plancher");
        var nether = new FilonMemoire.Position("minecraft:the_nether", 5, 10, 7);
        verifier(
                FilonMemoire.destinationDirecte(nether, 0, 256)
                        .dimension()
                        .equals(nether.dimension()),
                "Dimension conservée");
        verifier(
                FilonMemoire.destinationDirecte(nether, 0, 2) == null,
                "Refuser un monde sans hauteur utilisable");
    }

    static void choixAffichage() {
        var m = new FilonMemoire();
        var origine = p(0, 20, 0);
        m.observer(origine, "minecraft:iron_ore", true, 100);
        m.observer(p(1, 20, 0), "minecraft:gold_ore", true, 100);
        var f = m.filons().iterator().next();
        verifier(
                f.contours(origine).size() == 2 && f.typesChoisis() == null,
                "Sans filtre enregistré, tous les minerais sont proposés");
        Set<String> brouillon = new HashSet<>(Set.of("minecraft:gold_ore"));
        f.choisirTypes(brouillon);
        brouillon.clear();
        verifier(
                f.contours(origine).size() == 1
                        && f.contours(origine).get(0).id.equals("minecraft:gold_ore"),
                "Seulement les blocs sélectionnés, copie indépendante du brouillon");
        m.observer(p(2, 20, 0), "minecraft:diamond_ore", true, 100);
        verifier(
                !f.typeAffiche("minecraft:diamond_ore"),
                "Une nouvelle découverte ne contourne pas la sélection validée");
        verifier(
                f.contours(new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0)).isEmpty()
                        && f.contours(p(1000, 20, 0)).isEmpty(),
                "Dimension et portée respectées après filtrage");
        var apres = new FilonMemoire();
        for (var b : m.blocs()) apres.restaurer(b.position, b.id, b.retire, b.vu, f.ancre);
        apres.filon(f.id).choisirTypes(f.typesChoisis());
        verifier(
                apres.filon(f.id).contours(origine).size() == 1,
                "Choix restauré avec la mémoire du filon");
        m.observer(p(1, 20, 0), "minecraft:air", false, 200);
        verifier(
                f.contours(origine).isEmpty(),
                "Les blocs sélectionnés mais minés ne sont plus affichés");
        f.choisirTypes(Set.of());
        verifier(f.contours(origine).isEmpty(), "Sélection vide : aucun contour");
        verifier(new FilonMemoire().filons().isEmpty(), "Aucun choix partagé avec un autre monde");
        var dense = new FilonMemoire();
        for (int i = 0; i < 800; i++)
            dense.restaurer(
                    p(i % 10, 20 + i / 100, i / 10 % 10),
                    "minecraft:iron_ore",
                    false,
                    100,
                    origine);
        dense.restaurer(p(15, 20, 0), "minecraft:gold_ore", false, 100, origine);
        var df = dense.filon(origine.cle());
        verifier(df.contours(origine).size() == 768, "Budget de rendu conservé");
        verifier(df.contours(origine, 64).size() == 64, "Budget personnalisé respecté");
        verifier(df.contours(origine, 0).size() == 1, "Budget nul borné à un bloc");
        verifier(
                df.contours(origine, 4096).size() == 801,
                "Tous les blocs disponibles dans le budget");
        var proches = df.contours(origine, 64);
        verifier(proches.get(0).position.distance2(origine) == 0, "Plus proches en premier");
        verifier(dense.blocs().size() == 801, "Le budget de rendu ne supprime aucun relevé");
        df.choisirTypes(Set.of("minecraft:gold_ore"));
        verifier(
                df.contours(origine).size() == 1,
                "Filtrer avant de limiter : les minerais masqués ne consomment pas le budget");
    }

    static void mondeActif() {
        var partieA = new FilonMemoire();
        var partieB = new FilonMemoire();
        var nether = new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0);
        partieA.observer(p(0, 20, 0), "minecraft:iron_ore", true, 100);
        partieA.observer(nether, "minecraft:nether_gold_ore", true, 100);
        verifier(
                partieA.filonsDans("minecraft:overworld").size() == 1
                        && partieA.filonsDans("minecraft:overworld")
                                .get(0)
                                .ancre
                                .equals(p(0, 20, 0)),
                "Overworld seul");
        verifier(
                partieA.filonsDans("minecraft:the_nether").size() == 1
                        && partieA.filonsDans("minecraft:the_nether").get(0).ancre.equals(nether),
                "Le portail affiche uniquement le Nether");
        verifier(
                partieA.filonsDans("minecraft:the_end").isEmpty(),
                "Une dimension non explorée reste vide");
        verifier(
                partieA.filonsDans("").isEmpty() && partieA.filonsDans(null).isEmpty(),
                "Hors partie : aucune donnée visible");
        verifier(
                partieB.filonsDans("minecraft:overworld").isEmpty(),
                "Autre partie, mêmes coordonnées : aucun filon hérité");
        verifier(
                partieA.filonsDans("minecraft:overworld").size() == 1
                        && partieA.filons().size() == 2,
                "Retour à la première dimension sans effacer les autres filons");
    }

    static void scanPonctuel() {
        var scan = new FilonMemoire.Scan();
        var centre = p(0, 20, 0);
        scan.lancerPonctuel(centre);
        Set<String> visites = new HashSet<>();
        for (int i = 0; i < FilonMemoire.Scan.TOTAL; i++) {
            var position = scan.suivant(p(1000, 20, 0));
            verifier(
                    position != null && Math.abs(position.x()) <= 16,
                    "Le scan ponctuel garde son point de départ malgré le déplacement");
            visites.add(position.cle());
        }
        verifier(
                visites.size() == FilonMemoire.Scan.TOTAL,
                "Un passage exact, sans doublon ni boucle");
        verifier(
                !scan.actif() && scan.termine() && scan.pourcentage() == 100,
                "Arrêt automatique à 100 %");
        verifier(scan.suivant(centre) == null, "Aucune lecture après la fin");
        scan.lancerPonctuel(p(100, -59, -86));
        verifier(
                scan.suivant(p(100, 42, -86)).equals(p(100, -59, -86)),
                "Un scan ciblé commence au filon à 101 blocs sous le joueur, pas à ses pieds");
        scan.lancerPonctuel(p(50, 20, 0));
        verifier(scan.suivant(centre).equals(p(50, 20, 0)), "Relancer commence au nouveau centre");
        scan.activer(false);
        verifier(
                !scan.termine() && scan.suivant(centre) == null,
                "Annuler ne prétend pas avoir terminé");
        scan.lancerPonctuel(centre);
        verifier(
                scan.suivant(new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0)) == null
                        && !scan.actif()
                        && !scan.termine(),
                "Changement de dimension : annulation sûre");
        scan.activer(true);
        verifier(
                scan.suivant(centre) != null && !scan.ponctuel(),
                "Reprise du mode automatique après annulation");
        scan.lancerPonctuel(centre);
        for (int i = 0; i < FilonMemoire.Scan.TOTAL; i++) scan.suivant(centre);
        scan.activer(true);
        verifier(
                scan.suivant(centre) != null && !scan.termine(),
                "Reprise automatique après 100 % sans dépassement du curseur");
    }

    static void fraicheur() {
        var memoire = new FilonMemoire();
        long maintenant = 3600000;
        memoire.observer(p(0, 20, 0), "minecraft:iron_ore", true, maintenant - 1200000);
        memoire.observer(p(1, 20, 0), "minecraft:iron_ore", true, maintenant);
        var filon = memoire.filons().iterator().next();
        verifier(
                filon.ageReleve(maintenant).equals("20 min") && filon.releveAncien(maintenant),
                "Un bloc récent ne rajeunit pas les autres blocs du compteur");
        memoire.observer(p(0, 20, 0), "minecraft:air", false, maintenant);
        verifier(
                filon.ageReleve(maintenant).equals("moins de 1 min")
                        && !filon.releveAncien(maintenant),
                "Un bloc retiré ne rend pas périmé le compteur restant");
        verifier(
                filon.ageReleve(1).equals("moins de 1 min"),
                "Décalage d'horloge : jamais d'âge négatif");
        memoire.observer(p(1, 20, 0), "minecraft:air", false, maintenant);
        verifier(
                filon.verificationAncienne() == maintenant,
                "Historique consultable même pour un filon vide");
        memoire.importer(p(2, 20, 0), "minecraft:iron_ore", false, 0);
        verifier(
                filon.ageReleve(maintenant).equals("date inconnue")
                        && filon.releveAncien(maintenant),
                "Date manquante signalée");
    }

    static void arrivee() {
        var memoire = new FilonMemoire();
        memoire.observer(p(0, 20, 0), "minecraft:iron_ore", true, 100);
        var filon = memoire.filons().iterator().next();
        verifier(filon.arrive(p(3, 20, 0)), "Arrivée à trois blocs du minerai");
        verifier(
                !filon.arrive(p(4, 20, 0)) && !filon.arrive(p(0, 24, 0)),
                "Pas d'arrivée trop loin ou à un autre étage");
        verifier(
                !filon.arrive(new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0)),
                "Pas d'arrivée dans une autre dimension");
        memoire.observer(p(0, 20, 0), "minecraft:air", false, 200);
        verifier(!filon.arrive(p(0, 20, 0)), "Pas d'arrivée sur un ancien bloc disparu");
        memoire.observer(p(20, 20, 0), "minecraft:iron_ore", true, 200);
        verifier(
                filon.arrive(p(20, 20, 0)),
                "L'arrivée vise le minerai restant, pas l'ancre historique");
    }

    static void importHistorique() {
        FilonMemoire m = new FilonMemoire();
        var ancien = p(0, 20, 0);
        var dejaMine = p(1, 20, 0);
        verifier(m.importer(ancien, "minecraft:iron_ore", false, 100), "Ancien minerai récupéré");
        verifier(
                m.importer(dejaMine, "minecraft:iron_ore", true, 100),
                "Ancien historique miné récupéré");
        verifier(m.filons().iterator().next().restants() == 1, "Anciennes quantités conservées");
        verifier(
                !m.importer(ancien, "minecraft:iron_ore", false, 100),
                "Import répété sans doublon");
        m.observer(ancien, "minecraft:air", false, 200);
        verifier(
                !m.importer(ancien, "minecraft:iron_ore", false, 100),
                "Ne pas ressusciter un minerai récemment cassé");
        verifier(
                m.filons().iterator().next().restants() == 0,
                "Le nouveau relevé reste prioritaire");
        verifier(
                m.importer(ancien, "minecraft:gold_ore", false, 300),
                "Une observation importée réellement plus récente prévaut");
        verifier(m.blocs().size() == 2, "Mêmes coordonnées, aucun nouveau bloc");
    }

    static void pauseScan() {
        FilonMemoire.Scan scan = new FilonMemoire.Scan();
        FilonMemoire.Scan reference = new FilonMemoire.Scan();
        var joueur = p(2, 30, 4);
        for (int i = 0; i < 1000; i++) {
            verifier(
                    scan.suivant(joueur).equals(reference.suivant(joueur)),
                    "Même parcours avant la pause");
        }
        int progression = scan.pourcentage();
        verifier(scan.activer(false), "Mise en pause");
        verifier(!scan.activer(false), "Commande répétée sans inversion de l'état");
        for (int i = 0; i < 1000; i++)
            verifier(scan.suivant(joueur) == null, "Aucune position lue en pause");
        verifier(scan.pourcentage() == progression, "Le curseur ne bouge pas en pause");
        verifier(scan.activer(true), "Reprise");
        verifier(
                scan.suivant(joueur).equals(reference.suivant(joueur)),
                "Reprendre à la position suivante");
        FilonMemoire.Scan recharge = new FilonMemoire.Scan();
        recharge.activer(false);
        verifier(
                recharge.suivant(joueur) == null,
                "Un scan chargé désactivé ne commence pas le parcours");
    }

    static void suivi() {
        FilonMemoire m = new FilonMemoire();
        var p = p(1, 20, 2);
        verifier(!m.observer(p, "minecraft:stone", false, 1), "Ne pas mémoriser toute la pierre");
        verifier(m.observer(p, "minecraft:iron_ore", true, 2), "Découverte");
        String id = m.filons().iterator().next().id;
        verifier(
                !m.observer(p, "minecraft:iron_ore", true, 3),
                "Observation répétée sans double compte");
        verifier(m.filon(id).restants() == 1, "Un bloc = une quantité");
        m.observer(p, "minecraft:air", false, 4);
        verifier(
                m.filon(id).restants() == 0 && m.filon(id).blocs.size() == 1,
                "Bloc cassé par n'importe quel joueur");
        verifier(m.filon(id).id.equals(id), "Filon vidé conserve son identité");
        m.observer(p, "minecraft:gold_ore", true, 5);
        verifier(
                m.filon(id).restants() == 1 && m.filon(id).types().get("minecraft:gold_ore") == 1,
                "Bloc remplacé réapparaît");
        verifier(m.filon(id).destination(p(0, 0, 0)).equals(p), "Destination = un bloc restant");
    }

    static void groupes() {
        FilonMemoire m = new FilonMemoire();
        m.observer(p(0, 20, 0), "gtceu:tin_ore", true, 1);
        m.observer(p(2, 20, 0), "gtceu:cassiterite_ore", true, 1);
        verifier(m.filons().size() == 1, "Plusieurs minerais voisins dans un filon");
        m.observer(p(80, 20, 0), "gtceu:tin_ore", true, 1);
        m.observer(
                new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0),
                "gtceu:tin_ore",
                true,
                1);
        verifier(m.filons().size() == 3, "Filons éloignés et dimensions séparés");
        verifier(
                Double.isInfinite(
                        p(0, 0, 0).distance2(new FilonMemoire.Position("autre", 0, 0, 0))),
                "Distance interdimension");
    }

    static void restauration() {
        FilonMemoire avant = new FilonMemoire();
        avant.observer(p(-5, 8, 2), "minecraft:diamond_ore", true, 100);
        avant.observer(p(-4, 8, 2), "minecraft:diamond_ore", true, 100);
        avant.observer(p(-5, 8, 2), "minecraft:air", false, 200);
        FilonMemoire apres = new FilonMemoire();
        for (var b : avant.blocs())
            apres.restaurer(b.position, b.id, b.retire, b.vu, avant.filon(b.filon).ancre);
        var f = apres.filons().iterator().next();
        verifier(
                f.restants() == 1 && f.blocs.size() == 2,
                "Quantités restaurées sans perdre l'historique");
        verifier(
                f.id.equals(avant.filons().iterator().next().id),
                "Identifiant stable après reprise");
        apres.restaurer(p(-4, 8, 2), "minecraft:diamond_ore", false, 100, f.ancre);
        verifier(f.restants() == 1, "Pas de doublon au chargement");
        apres.restaurer(
                p(1, 2, 3),
                "minecraft:iron_ore",
                false,
                1,
                new FilonMemoire.Position("autre", 1, 2, 3));
        verifier(apres.blocs().size() == 2, "Rejeter une ancre de mauvaise dimension");
    }

    static void scan() {
        verifier(
                new FilonMemoire.Scan().suivant(p(5, 8, 9)).equals(p(5, 8, 9)),
                "Scanner près du joueur en premier");
        FilonMemoire.Scan s = new FilonMemoire.Scan();
        var centre = p(-50, -10, 60);
        Set<String> vus = new HashSet<>();
        for (int i = 0; i < FilonMemoire.Scan.TOTAL; i++) {
            var q = s.suivant(centre);
            vus.add(q.cle());
            verifier(
                    Math.abs(q.x() - centre.x()) <= 16
                            && Math.abs(q.y() - centre.y()) <= 16
                            && Math.abs(q.z() - centre.z()) <= 16,
                    "Scan borné");
        }
        verifier(
                vus.size() == FilonMemoire.Scan.TOTAL,
                "Tous les blocs parcourus une fois par cycle");
        verifier(s.pourcentage() == 0, "Nouveau cycle");
        var loin = p(1000, 80, 1000);
        verifier(s.suivant(loin).distance2(loin) <= 3 * 16 * 16, "Déplacement recentre le scan");
        var autre = new FilonMemoire.Position("minecraft:the_end", 0, 40, 0);
        verifier(s.suivant(autre).dimension().equals(autre.dimension()), "Dimension recentrée");
    }

    static void securite() {
        var cible = p(0, 10, 0);
        AtomicInteger appels = new AtomicInteger();
        var aucun =
                FilonMemoire.destinationAuDessus(
                        cible,
                        -64,
                        320,
                        q -> {
                            appels.incrementAndGet();
                            return false;
                        });
        verifier(
                aucun == null && appels.get() == 308,
                "Refuser sans position sûre, recherche bornée par le plafond");
        var proche =
                FilonMemoire.destinationAuDessus(
                        cible, -64, 320, q -> q.equals(p(0, 12, 0)) || q.equals(p(0, 80, 0)));
        verifier(proche.equals(p(0, 12, 0)), "Première cavité sûre au-dessus, avant la surface");
        verifier(
                FilonMemoire.destinationAuDessus(cible, -64, 320, q -> q.equals(p(0, 80, 0)))
                        .equals(p(0, 80, 0)),
                "Atteindre la surface même à plus de huit blocs au-dessus");
        verifier(
                FilonMemoire.destinationAuDessus(
                                cible, -64, 320, q -> q.y() <= 10 || q.x() != 0 || q.z() != 0)
                        == null,
                "Ne jamais viser sous le filon, à sa hauteur ou sur le côté");
        verifier(
                FilonMemoire.destinationAuDessus(p(0, 317, 0), -64, 320, q -> true)
                        .equals(p(0, 318, 0)),
                "Dernière position avec assez de place pour la tête");
        verifier(
                FilonMemoire.destinationAuDessus(p(0, 318, 0), -64, 320, q -> true) == null,
                "Respecter le plafond");
        var nether = new FilonMemoire.Position("minecraft:the_nether", 5, -100, 7);
        var bas = FilonMemoire.destinationAuDessus(nether, 0, 256, q -> true);
        verifier(
                bas.equals(new FilonMemoire.Position("minecraft:the_nether", 5, 1, 7)),
                "Respecter le plancher, la dimension et les coordonnées du centre");
    }

    static void rotationCache() {
        var m = new FilonMemoire(true);
        var centre = p(0, 20, 0);
        var types = Set.of("gtceu:garnet_sand_ore");
        m.gisement("ancien", p(1000, 20, 0), "gtceu:garnet_tin_vein", "Ancien", types, false);
        m.gisement("nouveau", centre, "gtceu:garnet_tin_vein", "Nouveau", types, false);
        m.filon("ancien").marquerEpuise(true);
        m.filon("ancien").choisirTypes(types);
        m.restaurerReleve(centre, "gtceu:garnet_sand_ore", false, 1, "nouveau");
        for (int i = 0; i < FilonMemoire.MAX_BLOCS - 1; i++)
            m.restaurerReleve(
                    p(1000 + i, 20, 0),
                    "gtceu:garnet_sand_ore",
                    i == FilonMemoire.MAX_BLOCS - 2,
                    i + 2,
                    "ancien");
        verifier(
                m.plein() && m.blocs().size() == 100000, "Capacité réelle de 100 000 observations");
        m.protegerScan(centre);
        m.observer(p(1000, 20, 0), "gtceu:garnet_sand_ore", true, 1000000);
        long debut = System.nanoTime();
        verifier(
                m.observer(p(1, 20, 0), "gtceu:garnet_sand_ore", true, 1000001),
                "Cache saturé : le nouveau filon est quand même détecté");
        long ms = (System.nanoTime() - debut) / 1000000;
        verifier(
                m.remplaces() == FilonMemoire.LOT_ROTATION
                        && m.blocs().size()
                                == FilonMemoire.MAX_BLOCS - FilonMemoire.LOT_ROTATION + 1,
                "Rotation par lot sans dépasser le plafond");
        var restants = new HashSet<FilonMemoire.Position>();
        for (var b : m.blocs()) restants.add(b.position);
        verifier(
                restants.contains(centre) && restants.contains(p(1, 20, 0)),
                "Conserver les blocs encore présents dans la zone scannée");
        verifier(
                restants.contains(p(1000, 20, 0)) && !restants.contains(p(1001, 20, 0)),
                "Conserver le relevé revu récemment, retirer le plus ancien hors zone");
        verifier(
                !restants.contains(p(1000 + FilonMemoire.MAX_BLOCS - 2, 20, 0)),
                "Retirer en priorité le bloc miné, même récent");
        verifier(
                m.filon("ancien").epuiseManuel()
                        && m.filon("ancien").typesChoisis().equals(types)
                        && m.filons().size() == 2,
                "Conserver gisements, marquages et filtres");
        verifier(
                m.filons().stream().mapToInt(f -> f.blocs.size()).sum() == m.blocs().size(),
                "Aucune référence de bloc évincé dans les filons");
        var recharge = new FilonMemoire(true);
        recharge.gisement(
                "ancien", p(1000, 20, 0), "gtceu:garnet_tin_vein", "Ancien", types, false);
        recharge.gisement("nouveau", centre, "gtceu:garnet_tin_vein", "Nouveau", types, false);
        for (var b : m.blocs()) recharge.restaurerReleve(b.position, b.id, b.retire, b.vu, b.filon);
        verifier(
                recharge.blocs().size() == m.blocs().size() && recharge.blocs().size() > 20000,
                "Restaurer un cache dépassant l'ancienne limite sans le tronquer");
        long evinces = m.remplaces();
        verifier(
                m.observer(p(2, 20, 0), "gtceu:garnet_sand_ore", true, 1000002)
                        && m.remplaces() == evinces,
                "Pas de parcours de rotation tant qu'il reste de la place");
        System.out.println("Rotation d'un cache de 100 000 blocs : " + ms + " ms");
    }

    static void limite() {
        FilonMemoire m = new FilonMemoire();
        for (int i = 0; i < FilonMemoire.MAX_BLOCS + 20; i++)
            m.observer(p(i % 30, i / 900, i / 30 % 30), "minecraft:iron_ore", true, i);
        verifier(
                m.blocs().size() == FilonMemoire.MAX_BLOCS && m.plein(),
                "Mémoire bornée sans éviction silencieuse");
        m.observer(p(0, 0, 0), "minecraft:air", false, 30000);
        verifier(
                m.blocs().stream().anyMatch(b -> b.position.equals(p(0, 0, 0)) && b.retire),
                "Continuer à corriger les blocs connus quand la mémoire est pleine");
    }
}
