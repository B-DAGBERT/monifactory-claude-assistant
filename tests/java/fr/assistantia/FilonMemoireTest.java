package fr.assistantia;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Executable headless regression tests: java -ea ... FilonMemoireTest (ASCII output only). */
public final class FilonMemoireTest {
    static final String OW = "minecraft:overworld";

    static FilonMemoire.Position p(int x, int y, int z) {
        return new FilonMemoire.Position(OW, x, y, z);
    }

    static void verifier(boolean condition, String raison) {
        if (!condition) throw new AssertionError(raison);
    }

    /** Monde synthétique : blocs par position, chunks chargés à la demande, horloge contrôlée. */
    static final class MondeTest implements ScanFilon.Monde {
        final FilonMemoire memoire;
        final Map<Long, String> blocs = new HashMap<>();
        final Set<Long> decharges = new HashSet<>();
        int lectures;
        boolean palette = true;

        MondeTest(FilonMemoire memoire) {
            this.memoire = memoire;
        }

        void poser(int x, int y, int z, String id) {
            blocs.put(FilonMemoire.pos(x, y, z), id);
        }

        void retirer(int x, int y, int z) {
            blocs.remove(FilonMemoire.pos(x, y, z));
        }

        void decharger(int cx, int cz) {
            decharges.add(VolumeFilon.colonne(cx, cz));
        }

        void charger(int cx, int cz) {
            decharges.remove(VolumeFilon.colonne(cx, cz));
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 319;
        }

        @Override
        public boolean charge(int cx, int cz) {
            return !decharges.contains(VolumeFilon.colonne(cx, cz));
        }

        @Override
        public boolean sansMinerai(int cx, int sy, int cz, FilonMemoire.Filon filon) {
            if (!palette) return false;
            for (var e : blocs.entrySet()) {
                long q = e.getKey();
                if (FilonMemoire.x(q) >> 4 == cx
                        && FilonMemoire.y(q) >> 4 == sy
                        && FilonMemoire.z(q) >> 4 == cz
                        && filon.dansComposition(memoire.typeConnu(e.getValue()))) return false;
            }
            return true;
        }

        @Override
        public int type(int x, int y, int z) {
            lectures++;
            String id = blocs.get(FilonMemoire.pos(x, y, z));
            return id == null ? -1 : memoire.typeConnu(id);
        }
    }

    static int balayer(ScanFilon scan, MondeTest monde) {
        int tours = 0;
        while (!scan.termine() && tours < 10_000) {
            scan.avancer(monde, () -> 0L, Long.MAX_VALUE, 1000);
            tours++;
            if (scan.attente()) break;
        }
        return tours;
    }

    public static void main(String[] args) throws Exception {
        tableLong();
        positions();
        volumes();
        scanComplet();
        budgetEtReprise();
        colonnesNonChargees();
        proprieteVoisins();
        remplissageMarge();
        comptesDirects();
        presqueEpuise();
        sauvegardeCompacte();
        ancienFormat();
        couleurs();
        gisementsGregtech();
        epuisementManuel();
        choixAffichage();
        mondeActif();
        disposition();
        destinationProche();
        teleportationDirecte();
        securite();
        System.out.println(
                "21 scenarios filons valides : volume, remplissage, propriete, comptes, sauvegarde,"
                        + " ancien format, couleurs, destination.");
    }

    static void tableLong() {
        var t = new TableLong();
        var reference = new HashMap<Long, Integer>();
        var hasard = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            long cle =
                    FilonMemoire.pos(
                            hasard.nextInt(64) - 32, hasard.nextInt(64) - 32, hasard.nextInt(64));
            int op = hasard.nextInt(3);
            if (op == 0) {
                t.ecrire(cle, i);
                reference.put(cle, i);
            } else if (op == 1) {
                Integer attendu = reference.remove(cle);
                verifier(
                        t.retirer(cle) == (attendu == null ? TableLong.ABSENT : attendu),
                        "Retrait");
            } else
                verifier(
                        t.lire(cle) == reference.getOrDefault(cle, TableLong.ABSENT),
                        "Lecture après retraits par décalage");
        }
        verifier(t.taille() == reference.size(), "Taille exacte");
        AtomicInteger vus = new AtomicInteger();
        t.parcourir(
                (k, v) -> verifier(reference.get(k) == v && vus.incrementAndGet() > 0, "Parcours"));
        verifier(vus.get() == reference.size(), "Parcours complet");
        verifier(
                t.ajouter(1, 2) == 2 && t.ajouter(1, -2) == 0 && !t.contient(1),
                "Compteur à zéro retiré");
    }

    static void positions() {
        for (int[] c :
                new int[][] {
                    {0, 0, 0},
                    {-1, -64, -1},
                    {29_999_999, 2047, -29_999_999},
                    {-30_000_000, -2048, 30_000_000},
                    {123, -5, -456}
                }) {
            long q = FilonMemoire.pos(c[0], c[1], c[2]);
            verifier(
                    FilonMemoire.x(q) == c[0]
                            && FilonMemoire.y(q) == c[1]
                            && FilonMemoire.z(q) == c[2],
                    "Codage BlockPos.asLong réversible");
            verifier(q != TableLong.VIDE, "Jamais la clé vide");
        }
    }

    static void volumes() {
        // Couches GTCEu (cluster 44) : sphère de rayon 22 autour du centre, pas le cube de 16.
        var couches = VolumeFilon.couches(100, 40, -100, 44);
        verifier(
                couches.minX() == 78
                        && couches.maxX() == 122
                        && couches.minY() == 18
                        && couches.maxY() == 62,
                "Couches : centre ± ceil(taille/2)");
        verifier(
                VolumeFilon.couches(0, 0, 0, 45).maxX() == 23, "Taille impaire arrondie au-dessus");
        var veine = VolumeFilon.veine(0, 30, 0, 52);
        verifier(veine.minX() == -26 && veine.maxY() == 56, "Veine : cube ± 26");
        // Dyke (cluster 50) : colonne de rayon floor(sqrt(2*25)) = 7 dans le chunk du centre.
        var dyke = VolumeFilon.dyke(37, 40, 50, 25, 100);
        verifier(
                dyke.minX() == 32 - 7
                        && dyke.maxX() == 47 + 7
                        && dyke.minZ() == 32 - 7
                        && dyke.maxZ() == 47 + 7,
                "Dyke : chunk du centre ± rayon horizontal");
        verifier(dyke.minY() == 25 && dyke.maxY() == 100, "Dyke : hauteurs du générateur");
        verifier(VolumeFilon.dyke(-1, -17, 50, 0, 10).minX() == -16 - 7, "Dyke : chunk négatif");
        // Cuboïde de l'uraninite (bornes du commit 5bb14a1 conservées).
        var cuboide = VolumeFilon.cuboide(100, 40, -100, 30, 10, 40, 7);
        verifier(
                cuboide.positions() == 44L * 44 * 32,
                "Bornes du générateur cuboïde de l'uraninite");
        verifier(
                cuboide.contient(86, 9, -114) && cuboide.contient(129, 40, -71), "Bornes incluses");
        verifier(!cuboide.contient(130, 40, -71) && !cuboide.contient(86, 8, -114), "Bords exclus");
        var classique = VolumeFilon.classique(0, 0, 0, 25, 3);
        verifier(
                classique.maxX() == 12 && classique.maxY() == 3,
                "Classique : ± taille/2, ± yRadius");
        var standard = VolumeFilon.standard(0, 0, 0, 16);
        verifier(
                standard.maxX() == 2 + 2 && standard.maxY() == 4 && standard.minY() == -4,
                "Standard : boîte de doPlace");
        var geode = VolumeFilon.geode(0, 0, 0, -16, 16);
        verifier(geode.minX() == -17 && geode.maxX() == 17, "Géode : décalages plus une couche");
        var inconnu = VolumeFilon.inconnu(0, 0, 0, 16, 16);
        verifier(
                inconnu.bordMaxX() == 32
                        && inconnu.maxX() == 16
                        && inconnu.parcourt(31, 0, 0)
                        && !inconnu.contient(31, 0, 0),
                "Inconnu : noyau et marge");
        verifier(veine.colonnes().length == 4 * 4, "Colonnes de chunks couvertes");
        verifier(
                couches.profondeur(100, 40, -100) == 0
                        && Math.abs(couches.profondeur(122, 40, -100) - 1) < 1e-9,
                "Profondeur 0 au centre, 1 sur une face");
        verifier(
                !VolumeFilon.cuboide(0, 0, 0, 128, 0, 4095, 7).acceptable(),
                "Volume trop coûteux refusé");
        var relu = VolumeFilon.lire(couches.valeurs());
        verifier(
                couches.equals(relu) && VolumeFilon.lire(new int[] {99, 0, 0, 0, 1, 1, 1}) == null,
                "Volume sauvegardé relu");
    }

    static FilonMemoire.Filon gisement(
            FilonMemoire m, String id, int x, int y, int z, String... composition) {
        m.gisement(id, p(x, y, z), "gtceu:test_vein", "Test " + id, Set.of(composition), false);
        return m.filon(id);
    }

    static void scanComplet() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 8, 40, 8, "gtceu:coal_ore", "gtceu:deepslate_coal_ore");
        var monde = new MondeTest(m);
        // Filon de 22 de rayon : des minerais bien au-delà du cube historique de 16 blocs.
        int poses = 0;
        for (int x = -14; x <= 30; x++)
            for (int y = 18; y <= 62; y++)
                for (int z = -14; z <= 30; z++) {
                    double d =
                            Math.sqrt(
                                    Math.pow(x - 8, 2) + Math.pow(y - 40, 2) + Math.pow(z - 8, 2));
                    if (d <= 22 && (x + y + z) % 3 == 0) {
                        monde.poser(
                                x, y, z, y < 30 ? "gtceu:deepslate_coal_ore" : "gtceu:coal_ore");
                        poses++;
                    }
                }
        monde.poser(8, 70, 8, "gtceu:coal_ore"); // hors des bornes du générateur
        monde.poser(9, 41, 8, "minecraft:stone");
        m.type("minecraft:stone");
        var volume = VolumeFilon.couches(8, 40, 8, 44);
        var scan = ScanFilon.lancer(m, f, volume, m.filons(), -64, 319);
        balayer(scan, monde);
        verifier(scan.termine() && f.scanComplet() && !f.scanEnAttente(), "Scan terminé tout seul");
        verifier(
                f.restants() == poses && f.trouves() == poses,
                "Tout le volume : " + f.restants() + " / " + poses);
        verifier(f.avancementScan() == 100, "Avancement 100 %");
        verifier(!f.present(FilonMemoire.pos(8, 70, 8)), "Hors des bornes du générateur : ignoré");
        verifier(f.typesReperes().size() == 2, "Toutes les variantes de pierre");
        verifier(f.verification == 1000, "Date de vérification");
        // Rescan après minage : les minerais disparus deviennent des compteurs minés.
        monde.retirer(8, 40, 8 + 1);
        int mines = 0;
        for (int i = 0; i < f.nombre() && mines < 10; i++) {
            long q = f.position(i);
            if (monde.blocs.remove(q) != null) mines++;
        }
        scan = ScanFilon.lancer(m, f, volume, m.filons(), -64, 319);
        balayer(scan, monde);
        verifier(
                f.mines() == 11 && f.restants() == poses - 11 && f.trouves() == poses,
                "Total trouvé conservé, minés comptés (" + f.mines() + ")");
        // Sections sans minerai sautées : bien moins de lectures que de positions.
        var autre = new FilonMemoire();
        var g = gisement(autre, "b", 8, 40, 8, "gtceu:coal_ore");
        var vide = new MondeTest(autre);
        vide.poser(8, 40, 8, "gtceu:coal_ore");
        var s2 =
                ScanFilon.lancer(
                        autre, g, VolumeFilon.couches(8, 40, 8, 44), autre.filons(), -64, 319);
        balayer(s2, vide);
        verifier(
                g.restants() == 1 && vide.lectures <= 16 * 16 * 16,
                "Palette : sections vides sautées (" + vide.lectures + " lectures)");
    }

    static void budgetEtReprise() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 0, 0, 0, "gtceu:tin_ore");
        var monde = new MondeTest(m);
        monde.palette = false;
        for (int i = -10; i <= 10; i += 2) monde.poser(i, i, i, "gtceu:tin_ore");
        var scan = ScanFilon.lancer(m, f, VolumeFilon.veine(0, 0, 0, 22), m.filons(), -64, 319);
        // Horloge simulée : 1 unité par lecture, budget de 300 par tick.
        AtomicLong temps = new AtomicLong();
        int ticks = 0;
        while (!scan.termine() && ticks < 10_000) {
            long limite = temps.get() + 300;
            scan.avancer(monde, () -> temps.addAndGet(50), limite, ticks);
            ticks++;
        }
        long total = 23L * 23 * 23;
        verifier(ticks >= 10 && scan.termine(), "Scan réparti sur plusieurs ticks (" + ticks + ")");
        verifier(f.restants() == 11 && scan.lus() == total, "Chaque position lue une seule fois");
    }

    static void colonnesNonChargees() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 8, 10, 8, "gtceu:iron_ore");
        var monde = new MondeTest(m);
        monde.poser(8, 10, 8, "gtceu:iron_ore");
        monde.poser(-10, 10, 8, "gtceu:iron_ore"); // chunk -1, non chargé au départ
        monde.decharger(-1, 0);
        var scan = ScanFilon.lancer(m, f, VolumeFilon.veine(8, 10, 8, 40), m.filons(), -64, 319);
        balayer(scan, monde);
        verifier(
                !scan.termine() && f.scanEnAttente() && scan.attente(),
                "Colonne non chargée en attente");
        verifier(
                f.restants() == 1 && !f.presqueEpuise(),
                "Rien d'inventé ; pas de proposition pendant l'attente");
        verifier(f.avancementScan() < 100, "Avancement partiel");
        verifier(!scan.colonnePrete(monde, 5000), "Rien à faire tant que le chunk est absent");
        monde.charger(-1, 0);
        verifier(scan.colonnePrete(monde, 6000), "Chunk chargé : reprise");
        balayer(scan, monde);
        verifier(
                scan.termine() && f.restants() == 2 && f.scanComplet(),
                "Le scan finit vraiment après chargement");
        // Reprise après reconnexion : colonnes restantes persistées.
        var g = gisement(m, "b", 200, 10, 200, "gtceu:iron_ore");
        monde.decharger(12, 12);
        var s2 = ScanFilon.lancer(m, g, VolumeFilon.veine(200, 10, 200, 10), m.filons(), -64, 319);
        balayer(s2, monde);
        verifier(g.scanEnAttente(), "Colonnes restantes");
        var repris = ScanFilon.reprendre(m, g, m.filons());
        monde.charger(12, 12);
        balayer(repris, monde);
        verifier(repris.termine() && g.scanComplet(), "Reprise d'un scan sauvegardé");
    }

    static void proprieteVoisins() {
        var m = new FilonMemoire();
        var a = gisement(m, "a", 0, 40, 0, "gtceu:copper_ore");
        var b = gisement(m, "b", 30, 40, 0, "gtceu:copper_ore");
        var c = gisement(m, "c", 15, 40, 0, "gtceu:gold_ore");
        a.volume = VolumeFilon.veine(0, 40, 0, 52);
        b.volume = VolumeFilon.veine(30, 40, 0, 52);
        c.volume = VolumeFilon.veine(15, 40, 0, 52);
        var monde = new MondeTest(m);
        for (int x = -20; x <= 50; x++) monde.poser(x, 40, 0, "gtceu:copper_ore");
        monde.poser(15, 41, 0, "gtceu:gold_ore");
        // b a déjà relevé un minerai que la boîte de a contient aussi : jamais repris.
        m.ajouter(b, FilonMemoire.pos(5, 40, 0), m.typeConnu("gtceu:copper_ore"));
        balayer(ScanFilon.lancer(m, a, a.volume, m.filons(), -64, 319), monde);
        verifier(
                !a.present(FilonMemoire.pos(5, 40, 0)) && b.present(FilonMemoire.pos(5, 40, 0)),
                "Jamais repris à un autre gisement");
        verifier(
                a.present(FilonMemoire.pos(14, 40, 0)) && !a.present(FilonMemoire.pos(16, 40, 0)),
                "Chevauchement : au gisement le plus au centre");
        verifier(!a.present(FilonMemoire.pos(15, 41, 0)), "Autre composition jamais comptée");
        balayer(ScanFilon.lancer(m, b, b.volume, m.filons(), -64, 319), monde);
        verifier(
                b.present(FilonMemoire.pos(16, 40, 0)) && !b.present(FilonMemoire.pos(14, 40, 0)),
                "Partage symétrique");
        verifier(
                a.present(FilonMemoire.pos(15, 40, 0)) != b.present(FilonMemoire.pos(15, 40, 0)),
                "Égalité départagée une seule fois");
        int total = 0;
        for (int x = -20; x <= 50; x++) {
            long q = FilonMemoire.pos(x, 40, 0);
            int proprietaires = (a.present(q) ? 1 : 0) + (b.present(q) ? 1 : 0);
            verifier(proprietaires <= 1, "Un minerai, un seul gisement");
            total += proprietaires;
        }
        verifier(total == 71, "Aucun minerai perdu entre deux gisements voisins (" + total + ")");
    }

    static void remplissageMarge() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 0, 40, 0, "gtceu:salt_ore");
        var voisin = gisement(m, "v", 60, 40, 0, "gtceu:salt_ore");
        voisin.volume = VolumeFilon.veine(60, 40, 0, 20);
        var monde = new MondeTest(m);
        // Générateur inconnu : noyau ± 8, marge de 16 suivie de proche en proche.
        for (int x = 0; x <= 8; x++) monde.poser(x, 40, 0, "gtceu:salt_ore");
        for (int x = 11; x <= 20; x += 3) monde.poser(x, 40, 0, "gtceu:salt_ore"); // trous de 2
        monde.poser(24, 40, 0, "gtceu:salt_ore"); // trou de 3 : détaché
        monde.poser(0, 40, 14, "gtceu:salt_ore"); // marge, sans lien
        monde.poser(
                10,
                49,
                0,
                "gtceu:salt_ore"); // diagonale de Chebyshev 3 depuis (8,40,0)? non : 9 en y
        var volume = VolumeFilon.inconnu(0, 40, 0, 8, 16);
        balayer(ScanFilon.lancer(m, f, volume, m.filons(), -64, 319), monde);
        for (int x = 0; x <= 8; x++)
            verifier(f.present(FilonMemoire.pos(x, 40, 0)), "Noyau complet");
        for (int x = 11; x <= 20; x += 3)
            verifier(f.present(FilonMemoire.pos(x, 40, 0)), "Trous de 2 blocs tolérés : " + x);
        verifier(!f.present(FilonMemoire.pos(24, 40, 0)), "Trou de 3 blocs : pas relié");
        verifier(
                !f.present(FilonMemoire.pos(0, 40, 14)) && !f.present(FilonMemoire.pos(10, 49, 0)),
                "Minerais isolés de la marge ignorés");
        verifier(f.restants() == 9 + 4, "Filon relié uniquement (" + f.restants() + ")");
    }

    static void comptesDirects() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 0, 10, 0, "gtceu:redstone_ore", "gtceu:deepslate_redstone_ore");
        var g = gisement(m, "b", 100, 10, 0, "gtceu:redstone_ore");
        var monde = new MondeTest(m);
        int t = m.typeConnu("gtceu:redstone_ore"), td = m.typeConnu("gtceu:deepslate_redstone_ore");
        for (int i = 0; i < 20; i++) {
            m.ajouter(f, FilonMemoire.pos(i, 10, 0), t);
            monde.poser(i, 10, 0, "gtceu:redstone_ore");
        }
        m.ajouter(g, FilonMemoire.pos(100, 10, 0), t);
        monde.poser(100, 10, 0, "gtceu:redstone_ore");
        verifier(f.trouves() == 20 && f.restants() == 20, "Départ");
        // Le joueur mine trois blocs, un bloc change de pierre, un chunk se décharge.
        monde.retirer(0, 10, 0);
        monde.retirer(1, 10, 0);
        monde.retirer(2, 10, 0);
        monde.poser(3, 10, 0, "gtceu:deepslate_redstone_ore");
        monde.retirer(100, 10, 0);
        monde.decharger(6, 0);
        var v = new VerificationFilons();
        int mines = 0;
        for (int i = 0; i < 10; i++) mines += v.tour(m, OW, monde, 8, 5000);
        verifier(
                mines == 3 && f.restants() == 17 && f.mines() == 3,
                "Compte restant tenu sans rescan");
        verifier(f.trouves() == 20, "Total trouvé conservé pour le ratio");
        verifier(
                f.typesReperes().get("gtceu:deepslate_redstone_ore") == 1,
                "Type actualisé, pas miné");
        verifier(g.restants() == 1, "Chunk non chargé : jamais compté miné");
        verifier(f.verification == 5000, "Passe complète datée");
        // Filon surligné : les plus proches du joueur relus à chaque tick.
        monde.retirer(5, 10, 0);
        long[] proches = {
            FilonMemoire.pos(5, 10, 0), FilonMemoire.pos(4, 10, 0), FilonMemoire.pos(0, 10, 0)
        };
        verifier(
                VerificationFilons.proches(m, f, proches, 64, monde) == 1 && f.restants() == 16,
                "Minage du filon surligné vu au tick");
        verifier(VerificationFilons.proches(m, f, proches, 64, monde) == 0, "Pas de double compte");
        verifier(td >= 0, "Types de la composition déclarés");
    }

    static void presqueEpuise() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 0, 10, 0, "minecraft:redstone_ore");
        int t = m.typeConnu("minecraft:redstone_ore");
        for (int i = 0; i < 1500; i++)
            m.ajouter(f, FilonMemoire.pos(i % 40, 10 + i / 1600, i / 40), t);
        verifier(!f.presqueEpuise() && f.pourcentRestant() == 100, "Filon plein");
        for (int i = 0; i < 1300; i++) m.miner(f, f.position(0));
        verifier(f.restants() == 200 && !f.presqueEpuise(), "200 / 1500 (13 %) : pas encore");
        for (int i = 0; i < 100; i++) m.miner(f, f.position(0));
        verifier(
                f.restants() == 100 && f.trouves() == 1500 && f.presqueEpuise(),
                "1500 trouvés, 100 restants : proposer Épuisé");
        verifier(f.quantite().contains("100 restants sur 1500"), "Ratio affiché");
        verifier(f.visible() && !f.estEpuise(), "Jamais de marquage automatique");
        f.colonnesRestantes = new long[] {VolumeFilon.colonne(0, 0)};
        verifier(!f.presqueEpuise(), "Scan inachevé : pas de proposition");
        f.colonnesRestantes = new long[0];
        f.marquerEpuise(true);
        verifier(!f.presqueEpuise() && !f.visible(), "Déjà épuisé : plus de proposition");
        f.marquerEpuise(false);
        verifier(f.presqueEpuise(), "Rétablir garde la proposition");
        var petit = gisement(m, "b", 50, 10, 0, "minecraft:redstone_ore");
        for (int i = 0; i < 10; i++) m.ajouter(petit, FilonMemoire.pos(50 + i, 10, 0), t);
        for (int i = 0; i < 10; i++) m.miner(petit, petit.position(0));
        verifier(!petit.presqueEpuise(), "Trop peu trouvé pour conclure");
    }

    static FilonMemoire exemple() {
        var m = new FilonMemoire();
        var a = gisement(m, "gt|a", 631, 42, 106, "gtceu:coal_ore", "gtceu:deepslate_coal_ore");
        m.gisement(
                "gt|n",
                new FilonMemoire.Position("minecraft:the_nether", -40, 30, 25),
                "gtceu:sulfur_vein",
                "Sulfur Vein",
                Set.of("gtceu:netherrack_sulfur_ore"),
                false);
        var nether = m.filon("gt|n");
        int coal = m.typeConnu("gtceu:coal_ore"), deep = m.typeConnu("gtceu:deepslate_coal_ore");
        for (int i = 0; i < 500; i++)
            m.ajouter(
                    a,
                    FilonMemoire.pos(610 + i % 40, 30 + i / 400, 90 + i / 40 % 10),
                    i % 3 == 0 ? deep : coal);
        for (int i = 0; i < 120; i++) m.miner(a, a.position(i));
        a.choisirTypes(Set.of("gtceu:coal_ore"));
        a.marquerEpuise(true);
        a.volume = VolumeFilon.couches(631, 42, 106, 40);
        a.colonnesRestantes = new long[] {VolumeFilon.colonne(39, 6), VolumeFilon.colonne(-2, -3)};
        a.scanFait = 1234;
        a.scanTotal = 5678;
        a.verification = 1_794_000_000_000L;
        m.ajouter(
                nether, FilonMemoire.pos(-41, 29, 24), m.typeConnu("gtceu:netherrack_sulfur_ore"));
        verifier(nether.dimension().equals("minecraft:the_nether"), "Gisement du Nether");
        return m;
    }

    static void sauvegardeCompacte() throws Exception {
        var m = exemple();
        var s = InstantaneFilons.de(m, "6f74389bbf483840c8e47a9d", 42);
        String texte = FormatFilons.natif(s);
        var relu = FormatFilons.lire(texte, "6f74389bbf483840c8e47a9d");
        for (var f : m.filons()) {
            var g = relu.filon(f.id);
            verifier(
                    g != null
                            && g.ancre.equals(f.ancre)
                            && g.nom.equals(f.nom)
                            && g.definition.equals(f.definition),
                    "Gisement relu " + f.id);
            verifier(
                    g.restants() == f.restants()
                            && g.mines() == f.mines()
                            && g.trouves() == f.trouves(),
                    "Comptes relus");
            verifier(
                    g.typesReperes().equals(f.typesReperes())
                            && g.typesMines().equals(f.typesMines()),
                    "Types relus");
            for (int i = 0; i < f.nombre(); i++)
                verifier(g.present(f.position(i)), "Position relue");
            verifier(
                    Objects.equals(g.typesChoisis(), f.typesChoisis())
                            && g.epuiseManuel() == f.epuiseManuel(),
                    "Choix et marquage relus");
            verifier(
                    Objects.equals(g.volume, f.volume)
                            && Arrays.equals(g.colonnesRestantes, f.colonnesRestantes),
                    "Scan à reprendre relu");
            verifier(
                    g.scanFait == f.scanFait
                            && g.scanTotal == f.scanTotal
                            && g.verification == f.verification,
                    "Avancement relu");
            verifier(g.composition.equals(f.composition), "Composition relue");
        }
        verifier(
                texte.length() < 500 * 14 + 2000,
                "Format compact : " + texte.length() + " caractères pour 380 minerais");
        verifier(
                !texte.contains("\"cle\"") && !texte.contains("\"ancre\""),
                "Aucune chaîne répétée par bloc");
        // Instantané repris directement lors d'une reconnexion rapide.
        var direct = s.memoire();
        verifier(
                direct.filon("gt|a").restants() == m.filon("gt|a").restants()
                        && direct.filon("gt|a").mines() == 120,
                "Instantané sans relecture");
        // Résumé pour l'application : comptes, pas de positions.
        var contexte = new com.google.gson.JsonObject();
        contexte.addProperty("session_monde", "0123");
        String resume = FormatFilons.resume(s, contexte);
        var j = com.google.gson.JsonParser.parseString(resume).getAsJsonObject();
        verifier(
                j.get("v").getAsInt() == 2
                        && j.get("source").getAsString().equals("gregtech")
                        && j.get("session_monde").getAsString().equals("0123"),
                "Résumé versionné avec son contexte");
        var g = j.getAsJsonArray("gisements").get(0).getAsJsonObject();
        verifier(
                g.get("restants").getAsInt() == 380
                        && g.get("mines").getAsInt() == 120
                        && g.get("trouves").getAsInt() == 500,
                "Comptes du résumé");
        verifier(
                g.get("scan").getAsString().equals("partiel")
                        && g.get("epuise_manuel").getAsBoolean(),
                "État du résumé");
        verifier(
                g.getAsJsonObject("minerais").getAsJsonArray("gtceu:coal_ore").get(0).getAsInt()
                                + g.getAsJsonObject("minerais")
                                        .getAsJsonArray("gtceu:deepslate_coal_ore")
                                        .get(0)
                                        .getAsInt()
                        == 380,
                "Comptes par type");
        verifier(!resume.contains("\"blocs\""), "Aucun bloc dans le résumé");
        verifier(resume.length() < 4000, "Résumé léger : " + resume.length());
        boolean refuse = false;
        try {
            FormatFilons.lire(texte, "autre-monde");
        } catch (java.io.IOException e) {
            refuse = true;
        }
        verifier(refuse, "Sauvegarde d'un autre monde refusée");
    }

    static void ancienFormat() throws Exception {
        String ancre = "{\"dimension\":\"minecraft:overworld\",\"x\":10,\"y\":20,\"z\":30}";
        String v1 =
                "{\"v\":1,\"monde\":\"m\",\"scan_actif\":false,\"gisements\":[{\"dimension\":\"minecraft:overworld\",\"x\":10,\"y\":20,\"z\":30,"
                        + "\"id\":\"gt|a\",\"definition\":\"gtceu:iron_vein\",\"nom\":\"Iron Vein\",\"epuise\":false,\"epuise_manuel\":true,"
                        + "\"composition\":[\"gtceu:iron_ore\",\"gtceu:deepslate_iron_ore\"]}],\"source\":\"gregtech\",\"capacite_blocs\":100000,"
                        + "\"types_affiches_par_filon\":{\"gt|a\":[\"gtceu:iron_ore\"]},\"blocs\":["
                        + "{\"dimension\":\"minecraft:overworld\",\"x\":11,\"y\":20,\"z\":30,\"bloc\":\"gtceu:iron_ore\",\"filon\":\"gt|a\",\"mine\":false,\"t\":500,\"cle\":\"x\",\"ancre\":"
                        + ancre
                        + "},"
                        + "{\"dimension\":\"minecraft:overworld\",\"x\":12,\"y\":20,\"z\":30,\"bloc\":\"gtceu:iron_ore\",\"filon\":\"gt|a\",\"mine\":true,\"t\":900,\"cle\":\"y\",\"ancre\":"
                        + ancre
                        + "},"
                        + "{\"dimension\":\"minecraft:overworld\",\"x\":13,\"y\":20,\"z\":30,\"bloc\":\"gtceu:deepslate_iron_ore\",\"filon\":\"gt|a\",\"mine\":false,\"t\":700},"
                        + "{\"dimension\":\"minecraft:the_nether\",\"x\":13,\"y\":20,\"z\":30,\"bloc\":\"gtceu:iron_ore\",\"filon\":\"gt|a\",\"mine\":false,\"t\":700},"
                        + "{\"dimension\":\"minecraft:overworld\",\"x\":14,\"y\":20,\"z\":30,\"bloc\":\"gtceu:iron_ore\",\"filon\":\"absent\",\"mine\":false,\"t\":700}"
                        + "]}";
        var m = FormatFilons.lire(v1, "m");
        var f = m.filon("gt|a");
        verifier(
                f != null && f.restants() == 2 && f.mines() == 1 && f.trouves() == 3,
                "Ancien format : présents gardés, minés en compteurs");
        verifier(
                f.epuiseManuel() && f.typesChoisis().equals(Set.of("gtceu:iron_ore")),
                "Marquage et choix relus");
        verifier(f.verification == 500, "Date : plus ancien minerai présent");
        verifier(
                m.filons().size() == 1 && m.presents() == 2,
                "Relevés orphelins ou d'une autre dimension ignorés");
        // L'ordre des champs ne compte pas (blocs avant les gisements).
        String inverse =
                "{\"blocs\":[{\"dimension\":\"minecraft:overworld\",\"x\":11,\"y\":20,\"z\":30,\"bloc\":\"gtceu:iron_ore\",\"filon\":\"gt|a\",\"mine\":false,\"t\":5}],"
                        + "\"v\":1,\"monde\":\"m\",\"gisements\":[{\"dimension\":\"minecraft:overworld\",\"x\":10,\"y\":20,\"z\":30,\"id\":\"gt|a\",\"definition\":\"d\",\"nom\":\"n\",\"composition\":[\"gtceu:iron_ore\"]}]}";
        verifier(
                FormatFilons.lire(inverse, "m").filon("gt|a").restants() == 1,
                "Ordre des champs libre");
        for (String mauvais :
                new String[] {
                    v1.replace("\"x\":11", "\"x\":40000000"),
                    v1.replace("minecraft:overworld\",\"x\":11", "Mauvaise Dimension\",\"x\":11"),
                    "{broken",
                    v1.replace("\"v\":1", "\"v\":9")
                }) {
            boolean refuse = false;
            try {
                FormatFilons.lire(mauvais, "m");
            } catch (java.io.IOException e) {
                refuse = true;
            }
            verifier(
                    refuse,
                    "Relevé invalide refusé : "
                            + mauvais.substring(0, Math.min(30, mauvais.length())));
        }
    }

    static void couleurs() {
        String[] dimensions = {
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "ad_astra:moon"
        };
        var hasard = new Random(3);
        for (int i = 0; i < 400; i++) {
            int rgb = i < 20 ? (i * 0x0C0C0C) & 0xFFFFFF : hasard.nextInt(0x1000000);
            String cle = "mat" + i;
            for (String d : dimensions) {
                int c = CouleursFilons.couleur(cle, rgb, d) & 0xFFFFFF;
                double l = CouleursFilons.luminance(c);
                if (d.endsWith("the_end"))
                    verifier(
                            l <= CouleursFilons.LUMINANCE_MAX + 1e-9,
                            "Contraste sur la pierre de l'End");
                else
                    verifier(
                            l >= CouleursFilons.LUMINANCE_MIN - 0.01,
                            "Contraste sur pierre, deepslate, netherrack");
                verifier(CouleursFilons.hsv(c)[1] > 0.2 || l > 0.85, "Couleur franche, pas grise");
            }
        }
        // Une couleur par matériau : la pierre autour ne la change pas.
        verifier(
                CouleursFilons.cleBloc("gtceu:netherrack_sulfur_ore").equals("sulfur")
                        && CouleursFilons.cleBloc("gtceu:basalt_sulfur_ore").equals("sulfur"),
                "Clé par matériau");
        int deepslate = 0x4D4D50, pierre = 0x7D7D7D, netherrack = 0x6F3535, end = 0xDBDEA3;
        int soufre = CouleursFilons.couleur("sulfur", 0xC8C800, "minecraft:overworld") & 0xFFFFFF;
        verifier(
                CouleursFilons.contraste(soufre, deepslate) > 3
                        && CouleursFilons.contraste(soufre, pierre) > 1.8,
                "Soufre lisible sur pierre et deepslate");
        int soufreNether =
                CouleursFilons.couleur("sulfur", 0xC8C800, "minecraft:the_nether") & 0xFFFFFF;
        verifier(
                CouleursFilons.contraste(soufreNether, netherrack) > 3,
                "Soufre lisible sur netherrack");
        int endCouleur =
                CouleursFilons.couleur("magnesite", 0xFAFAB4, "minecraft:the_end") & 0xFFFFFF;
        verifier(CouleursFilons.contraste(endCouleur, end) > 2.5, "Lisible sur la pierre de l'End");
        verifier(
                CouleursFilons.couleur("iron", 0xC8C8C8, "minecraft:overworld")
                        == CouleursFilons.couleur("iron", 0xC8C8C8, "minecraft:overworld"),
                "Stable");
    }

    static void gisementsGregtech() {
        var m = new FilonMemoire();
        var centre = p(0, 20, 0);
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
                f.typesReperes().isEmpty() && f.quantite().contains("Pas encore scanné"),
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
                f.dansComposition(m.typeConnu("minecraft:iron_ore"))
                        && !f.dansComposition(m.type("minecraft:gold_ore")),
                "Composition par type");
        m.gisement(
                "gt:b",
                p(1, 20, 0),
                "gtceu:gold_vein",
                "Gold vein",
                Set.of("minecraft:gold_ore"),
                false);
        verifier(m.filons().size() == 2, "Deux gisements GT proches ne fusionnent jamais");
        m.gisement(
                "gt:a", centre, "gtceu:iron_vein", "Iron vein", Set.of("minecraft:iron_ore"), true);
        verifier(
                !f.visible() && f.quantite().contains("GregTech"),
                "Seul l'indicateur GT peut masquer le gisement comme épuisé");
        verifier(new FilonMemoire().filons().isEmpty(), "Aucun gisement hérité d'un autre monde");
    }

    static void epuisementManuel() {
        var m = new FilonMemoire();
        var centre = p(0, 20, 0);
        var types = Set.of("minecraft:iron_ore");
        m.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein", types, false);
        var f = m.filon("gt:a");
        m.ajouter(f, centre.longue(), m.typeConnu("minecraft:iron_ore"));
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
        m.gisement("gt:a", centre, "gtceu:iron_vein", "Iron vein", types, true);
        f.marquerEpuise(false);
        verifier(
                !f.visible() && f.epuise && !f.epuiseManuel(),
                "Annuler le marquage n'efface jamais l'épuisement GregTech");
    }

    static void choixAffichage() {
        var m = new FilonMemoire();
        var f = gisement(m, "a", 0, 20, 0, "minecraft:iron_ore", "minecraft:gold_ore");
        int fer = m.typeConnu("minecraft:iron_ore"), or = m.typeConnu("minecraft:gold_ore");
        m.ajouter(f, FilonMemoire.pos(0, 20, 0), fer);
        m.ajouter(f, FilonMemoire.pos(1, 20, 0), or);
        verifier(
                f.contours(0, 20, 0, 96, 768).length == 2 && f.typesChoisis() == null,
                "Sans filtre enregistré, tous les minerais sont proposés");
        Set<String> brouillon = new HashSet<>(Set.of("minecraft:gold_ore"));
        f.choisirTypes(brouillon);
        brouillon.clear();
        int[] c = f.contours(0, 20, 0, 96, 768);
        verifier(
                c.length == 1 && f.typeBloc(c[0]) == or,
                "Seulement les blocs sélectionnés, copie indépendante du brouillon");
        verifier(f.contours(1000, 20, 0, 96, 768).length == 0, "Portée respectée");
        m.miner(f, FilonMemoire.pos(1, 20, 0));
        verifier(
                f.contours(0, 20, 0, 96, 768).length == 0,
                "Les blocs sélectionnés mais minés ne sont plus affichés");
        var dense = new FilonMemoire();
        var d = gisement(dense, "d", 0, 20, 0, "minecraft:iron_ore", "minecraft:gold_ore");
        int t = dense.typeConnu("minecraft:iron_ore");
        for (int i = 0; i < 800; i++)
            dense.ajouter(d, FilonMemoire.pos(i % 10, 20 + i / 100, i / 10 % 10), t);
        dense.ajouter(d, FilonMemoire.pos(15, 20, 0), dense.typeConnu("minecraft:gold_ore"));
        verifier(d.contours(0, 20, 0, 96, 768).length == 768, "Budget de rendu conservé");
        verifier(d.contours(0, 20, 0, 96, 64).length == 64, "Budget personnalisé respecté");
        verifier(d.contours(0, 20, 0, 96, 0).length == 1, "Budget nul borné à un bloc");
        verifier(
                d.contours(0, 20, 0, 96, 4096).length == 801,
                "Tous les blocs disponibles dans le budget");
        int[] proches = d.contours(0, 20, 0, 96, 64);
        verifier(
                FilonMemoire.distance2(d.position(proches[0]), 0, 20, 0) == 0,
                "Plus proches en premier");
        for (int i = 1; i < proches.length; i++)
            verifier(
                    FilonMemoire.distance2(d.position(proches[i - 1]), 0, 20, 0)
                            <= FilonMemoire.distance2(d.position(proches[i]), 0, 20, 0),
                    "Tri par distance");
        verifier(d.restants() == 801, "Le budget de rendu ne supprime aucun relevé");
        d.choisirTypes(Set.of("minecraft:gold_ore"));
        verifier(
                d.contours(0, 20, 0, 96, 768).length == 1,
                "Filtrer avant de limiter : les minerais masqués ne consomment pas le budget");
    }

    static void mondeActif() {
        var m = new FilonMemoire();
        m.gisement("o", p(0, 20, 0), "d", "o", Set.of("minecraft:iron_ore"), false);
        m.gisement(
                "n",
                new FilonMemoire.Position("minecraft:the_nether", 0, 20, 0),
                "d",
                "n",
                Set.of("minecraft:nether_gold_ore"),
                false);
        verifier(
                m.filonsDans(OW).size() == 1 && m.filonsDans(OW).get(0).id.equals("o"),
                "Overworld seul");
        verifier(
                m.filonsDans("minecraft:the_nether").size() == 1,
                "Le portail affiche uniquement le Nether");
        verifier(
                m.filonsDans("minecraft:the_end").isEmpty()
                        && m.filonsDans("").isEmpty()
                        && m.filonsDans(null).isEmpty(),
                "Dimension vide ou hors partie");
        verifier(
                Double.isInfinite(
                        p(0, 0, 0).distance2(new FilonMemoire.Position("autre", 0, 0, 0))),
                "Distance interdimension");
    }

    static void disposition() {
        for (int largeur : new int[] {300, 440, 460, 620, 1000})
            for (int hauteur : new int[] {130, 166, 200, 300, 500}) {
                int x = 10, y = 36, bas = y + hauteur;
                var d = DispositionFilons.calculer(x, y, largeur, bas);
                String cas = largeur + "x" + hauteur;
                verifier(d.lignes() >= 1, "Une ligne de liste toujours visible " + cas);
                verifier(
                        d.listeY() >= d.outils2Y() + DispositionFilons.BOUTON,
                        "Liste sous les outils " + cas);
                verifier(
                        d.detailX() >= x && d.detailX() + d.detailLargeur() <= x + largeur,
                        "Fiche dans la fenêtre " + cas);
                verifier(
                        d.actionsY() + DispositionFilons.BOUTON < d.statutY()
                                && d.statutY() + 9 <= bas,
                        "Actions et statut sans chevauchement " + cas);
                verifier(
                        d.detailY() + 2 * DispositionFilons.TEXTE <= d.actionsY(),
                        "Titre et compte de la fiche visibles " + cas);
                verifier(d.detailBas() <= d.actionsY(), "Fiche au-dessus des actions " + cas);
                if (d.colonnes())
                    verifier(x + d.listeLargeur() < d.detailX(), "Colonnes séparées " + cas);
                else
                    verifier(
                            d.listeBas() <= d.detailY(),
                            "Fiche sous la liste en petite fenêtre " + cas);
            }
        var petit = DispositionFilons.calculer(10, 36, 300, 202);
        verifier(
                petit.lignes() >= 2 && !petit.colonnes(),
                "320 x 240 : deux lignes de liste et la fiche");
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
                "Comparer les distances réelles");
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
                "Destination directe juste au-dessus");
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
        verifier(
                FilonMemoire.destinationAuDessus(
                                cible,
                                -64,
                                320,
                                q -> q.equals(p(0, 12, 0)) || q.equals(p(0, 80, 0)))
                        .equals(p(0, 12, 0)),
                "Première cavité sûre au-dessus, avant la surface");
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
        verifier(
                FilonMemoire.destinationAuDessus(nether, 0, 256, q -> true)
                        .equals(new FilonMemoire.Position("minecraft:the_nether", 5, 1, 7)),
                "Respecter le plancher, la dimension et les coordonnées du centre");
    }
}
