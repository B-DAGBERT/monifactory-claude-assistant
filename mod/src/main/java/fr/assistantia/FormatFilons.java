package fr.assistantia;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Formats des filons, lus et écrits en flux (aucun arbre JSON) hors du thread client.
 *
 * <p>Sauvegarde native v2 : une table des types de blocs, puis par gisement ses métadonnées, ses
 * compteurs de minerais minés et ses minerais présents en entiers relatifs à l'ancre {@code [dx,
 * dy, dz, type, …]}. L'ancien format v1 (un objet par bloc) reste lisible.
 *
 * <p>Résumé {@code filons_mod.json} pour l'application Python : gisements et compteurs seulement,
 * sans aucune position de bloc.
 */
final class FormatFilons {
    static final int VERSION = 2;
    private static final Pattern RESSOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private FormatFilons() {}

    // ---------- Écriture ----------

    static String natif(InstantaneFilons s) {
        var texte = new StringWriter(64 + s.gisements().size() * 512);
        try (var j = new JsonWriter(texte)) {
            j.beginObject();
            j.name("v").value(VERSION);
            j.name("monde").value(s.monde());
            j.name("source").value("gregtech");
            j.name("t").value(s.date());
            j.name("types").beginArray();
            for (String t : s.types()) j.value(t);
            j.endArray();
            j.name("gisements").beginArray();
            Map<String, Integer> indexTypes = new HashMap<>();
            for (int t = 0; t < s.types().length; t++) indexTypes.put(s.types()[t], t);
            for (var g : s.gisements()) {
                j.beginObject();
                j.name("id").value(g.id());
                j.name("dimension").value(g.dimension());
                j.name("x").value(g.x());
                j.name("y").value(g.y());
                j.name("z").value(g.z());
                j.name("definition").value(g.definition());
                j.name("nom").value(g.nom());
                j.name("epuise").value(g.epuise());
                j.name("epuise_manuel").value(g.epuiseManuel());
                j.name("composition").beginArray();
                for (String c : g.composition()) j.value(indexTypes.get(c));
                j.endArray();
                if (g.choix() != null) {
                    j.name("choix").beginArray();
                    for (String c : g.choix()) j.value(c);
                    j.endArray();
                }
                if (g.verification() > 0) j.name("verification").value(g.verification());
                if (g.volume() != null) {
                    j.name("volume").beginArray();
                    for (int v : g.volume()) j.value(v);
                    j.endArray();
                    j.name("scan").beginArray();
                    j.value(g.scanFait()).value(g.scanTotal()).value(g.scanComplet() ? 1 : 0);
                    j.endArray();
                    if (g.colonnesRestantes().length > 0) {
                        j.name("attente").beginArray();
                        for (long c : g.colonnesRestantes())
                            j.value(VolumeFilon.colonneX(c)).value(VolumeFilon.colonneZ(c));
                        j.endArray();
                    }
                }
                if (g.minesTypes().length > 0) {
                    j.name("mines").beginArray();
                    for (int i = 0; i < g.minesTypes().length; i++)
                        j.value(g.minesTypes()[i]).value(g.minesNombres()[i]);
                    j.endArray();
                }
                j.name("blocs").beginArray();
                for (int i = 0; i < g.positions().length; i++) {
                    long p = g.positions()[i];
                    j.value(FilonMemoire.x(p) - g.x())
                            .value(FilonMemoire.y(p) - g.y())
                            .value(FilonMemoire.z(p) - g.z())
                            .value(g.typesBlocs()[i]);
                }
                j.endArray();
                j.endObject();
            }
            j.endArray();
            j.endObject();
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringWriter : impossible
        }
        return texte.toString();
    }

    /**
     * Résumé compact pour l'application : par gisement, restants, minés et trouvés par type de
     * bloc. Les champs de contexte (monde, session, dimension) sont recopiés tels quels.
     */
    static String resume(InstantaneFilons s, JsonObject contexte) {
        var texte = new StringWriter(64 + s.gisements().size() * 1024);
        try (var j = new JsonWriter(texte)) {
            j.beginObject();
            j.name("v").value(VERSION);
            j.name("monde").value(s.monde());
            j.name("source").value("gregtech");
            j.name("resume").value(true);
            j.name("t").value(s.date());
            if (contexte != null)
                for (var e : contexte.entrySet())
                    if (e.getValue().isJsonPrimitive())
                        j.name(e.getKey()).value(e.getValue().getAsString());
            j.name("gisements").beginArray();
            for (var g : s.gisements()) {
                int[] presents = new int[s.types().length], mines = new int[s.types().length];
                for (int t : g.typesBlocs()) presents[t]++;
                for (int i = 0; i < g.minesTypes().length; i++)
                    mines[g.minesTypes()[i]] += g.minesNombres()[i];
                int restants = g.restants(), mine = g.mines(), trouves = restants + mine;
                j.beginObject();
                j.name("id").value(g.id());
                j.name("nom").value(g.nom());
                j.name("definition").value(g.definition());
                j.name("dimension").value(g.dimension());
                j.name("x").value(g.x());
                j.name("y").value(g.y());
                j.name("z").value(g.z());
                j.name("epuise").value(g.epuise());
                j.name("epuise_manuel").value(g.epuiseManuel());
                j.name("composition").beginArray();
                for (String c : g.composition()) j.value(c);
                j.endArray();
                j.name("restants").value(restants);
                j.name("mines").value(mine);
                j.name("trouves").value(trouves);
                j.name("scan")
                        .value(
                                g.colonnesRestantes().length > 0
                                        ? "partiel"
                                        : g.scanComplet()
                                                ? "complet"
                                                : trouves > 0 ? "zone" : "aucun");
                j.name("presque_epuise")
                        .value(
                                !g.epuise()
                                        && !g.epuiseManuel()
                                        && g.colonnesRestantes().length == 0
                                        && trouves >= FilonMemoire.MINIMUM_PRESQUE_EPUISE
                                        && restants * 100L
                                                <= (long) trouves
                                                        * FilonMemoire.POURCENT_PRESQUE_EPUISE);
                if (g.verification() > 0) j.name("verification").value(g.verification());
                j.name("minerais").beginObject();
                for (int t = 0; t < s.types().length; t++)
                    if (presents[t] > 0 || mines[t] > 0) {
                        j.name(s.types()[t]).beginArray();
                        j.value(presents[t]).value(mines[t]);
                        j.endArray();
                    }
                j.endObject();
                j.endObject();
            }
            j.endArray();
            j.endObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return texte.toString();
    }

    // ---------- Lecture ----------

    /** Lit une sauvegarde v1 ou v2 du monde {@code monde}. */
    static FilonMemoire lire(String texte, String monde) throws IOException {
        try (var j = new JsonReader(new StringReader(texte))) {
            var lecture = new Lecture();
            j.beginObject();
            while (j.hasNext()) {
                String nom = j.nextName();
                switch (nom) {
                    case "v" -> lecture.version = j.nextInt();
                    case "monde" -> lecture.monde = j.nextString();
                    case "types" -> {
                        j.beginArray();
                        while (j.hasNext()) lecture.types.add(ressource(j.nextString()));
                        j.endArray();
                    }
                    case "gisements" -> {
                        j.beginArray();
                        while (j.hasNext()) lecture.gisements.add(gisement(j));
                        j.endArray();
                    }
                    case "blocs" -> lecture.blocsV1(j);
                    case "types_affiches_par_filon" -> lecture.choixV1(j);
                    default -> j.skipValue();
                }
            }
            j.endObject();
            if (!monde.equals(lecture.monde)) throw new IOException("Mauvais monde");
            if (lecture.version != 1 && lecture.version != VERSION)
                throw new IOException("Version de filons inconnue : " + lecture.version);
            return lecture.memoire();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Sauvegarde des filons illisible", e);
        }
    }

    private static String ressource(String id) throws IOException {
        if (id == null || !RESSOURCE.matcher(id).matches())
            throw new IOException("Identifiant invalide : " + id);
        return id;
    }

    private static int coordonnee(int v, int limite) throws IOException {
        if (Math.abs((long) v) > limite) throw new IOException("Coordonnées invalides");
        return v;
    }

    /** Un gisement v1 ou v2 (composition en noms ou en indices de types). */
    private static Map<String, Object> gisement(JsonReader j) throws IOException {
        Map<String, Object> g = new HashMap<>();
        j.beginObject();
        while (j.hasNext()) {
            String nom = j.nextName();
            switch (nom) {
                case "id", "definition", "nom", "dimension" -> g.put(nom, j.nextString());
                case "x", "y", "z" -> g.put(nom, j.nextInt());
                case "epuise", "epuise_manuel" -> g.put(nom, j.nextBoolean());
                case "verification" -> g.put(nom, j.nextLong());
                case "composition", "choix" -> {
                    List<Object> valeurs = new ArrayList<>();
                    j.beginArray();
                    while (j.hasNext())
                        valeurs.add(
                                j.peek() == JsonToken.NUMBER
                                        ? (Object) j.nextInt()
                                        : j.nextString());
                    j.endArray();
                    g.put(nom, valeurs);
                }
                case "volume", "mines", "blocs", "attente" -> g.put(nom, entiers(j));
                case "scan" -> {
                    long[] scan = new long[3];
                    int n = 0;
                    j.beginArray();
                    while (j.hasNext()) {
                        long v = j.nextLong();
                        if (n < 3) scan[n++] = v;
                    }
                    j.endArray();
                    g.put(nom, scan);
                }
                default -> j.skipValue();
            }
        }
        j.endObject();
        return g;
    }

    private static int[] entiers(JsonReader j) throws IOException {
        int[] valeurs = new int[64];
        int n = 0;
        j.beginArray();
        while (j.hasNext()) {
            if (n == valeurs.length) valeurs = Arrays.copyOf(valeurs, n * 2);
            valeurs[n++] = j.nextInt();
        }
        j.endArray();
        return Arrays.copyOf(valeurs, n);
    }

    /** Lecture tolérante à l'ordre des champs : les blocs v1 sont rattachés à la fin. */
    private static final class Lecture {
        int version = -1;
        String monde = "";
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> gisements = new ArrayList<>();
        final Map<String, List<String>> choixV1 = new HashMap<>();
        // Blocs v1, en tableaux primitifs.
        final Map<String, Integer> filonsV1 = new HashMap<>(), typesV1 = new HashMap<>();
        final List<String> nomsFilonsV1 = new ArrayList<>(), nomsTypesV1 = new ArrayList<>();
        final List<String> dimensionsV1 = new ArrayList<>();
        final Map<String, Integer> indexDimensionsV1 = new HashMap<>();
        int[] filonV1 = new int[1024], typeV1 = new int[1024], dimensionV1 = new int[1024];
        int[] xV1 = new int[1024], yV1 = new int[1024], zV1 = new int[1024];
        long[] tV1 = new long[1024];
        boolean[] mineV1 = new boolean[1024];
        int blocs;

        void blocsV1(JsonReader j) throws IOException {
            j.beginArray();
            while (j.hasNext()) {
                String dimension = null, bloc = null, filon = null;
                Integer x = null, y = null, z = null;
                boolean mine = false;
                long t = 0;
                j.beginObject();
                while (j.hasNext()) {
                    switch (j.nextName()) {
                        case "dimension" -> dimension = j.nextString();
                        case "bloc" -> bloc = j.nextString();
                        case "filon" -> filon = j.nextString();
                        case "x" -> x = j.nextInt();
                        case "y" -> y = j.nextInt();
                        case "z" -> z = j.nextInt();
                        case "mine" -> mine = j.nextBoolean();
                        case "t" -> t = (long) j.nextDouble();
                        default -> j.skipValue();
                    }
                }
                j.endObject();
                if (dimension == null
                        || bloc == null
                        || filon == null
                        || x == null
                        || y == null
                        || z == null) throw new IOException("Bloc de filon incomplet");
                ressource(dimension);
                ressource(bloc);
                coordonnee(x, 30_000_000);
                coordonnee(z, 30_000_000);
                coordonnee(y, 2047);
                if (blocs == filonV1.length) agrandir();
                filonV1[blocs] = indice(filonsV1, nomsFilonsV1, filon);
                typeV1[blocs] = indice(typesV1, nomsTypesV1, bloc);
                dimensionV1[blocs] = indice(indexDimensionsV1, dimensionsV1, dimension);
                xV1[blocs] = x;
                yV1[blocs] = y;
                zV1[blocs] = z;
                tV1[blocs] = t;
                mineV1[blocs++] = mine;
            }
            j.endArray();
        }

        void choixV1(JsonReader j) throws IOException {
            j.beginObject();
            while (j.hasNext()) {
                String filon = j.nextName();
                if (j.peek() != JsonToken.BEGIN_ARRAY) {
                    j.skipValue();
                    continue;
                }
                List<String> choisis = new ArrayList<>();
                j.beginArray();
                while (j.hasNext()) {
                    if (j.peek() == JsonToken.STRING) {
                        String type = j.nextString();
                        if (RESSOURCE.matcher(type).matches()) choisis.add(type);
                    } else j.skipValue();
                }
                j.endArray();
                choixV1.put(filon, choisis);
            }
            j.endObject();
        }

        private static int indice(Map<String, Integer> index, List<String> noms, String nom) {
            Integer i = index.get(nom);
            if (i != null) return i;
            noms.add(nom);
            index.put(nom, noms.size() - 1);
            return noms.size() - 1;
        }

        private void agrandir() {
            int n = blocs * 2;
            filonV1 = Arrays.copyOf(filonV1, n);
            typeV1 = Arrays.copyOf(typeV1, n);
            dimensionV1 = Arrays.copyOf(dimensionV1, n);
            xV1 = Arrays.copyOf(xV1, n);
            yV1 = Arrays.copyOf(yV1, n);
            zV1 = Arrays.copyOf(zV1, n);
            tV1 = Arrays.copyOf(tV1, n);
            mineV1 = Arrays.copyOf(mineV1, n);
        }

        @SuppressWarnings("unchecked")
        FilonMemoire memoire() throws IOException {
            var m = new FilonMemoire();
            int[] types2 = new int[types.size()];
            for (int t = 0; t < types2.length; t++) types2[t] = m.type(types.get(t));
            for (var g : gisements) {
                String id = (String) g.get("id"), dimension = (String) g.get("dimension");
                if (id == null || id.isEmpty() || dimension == null)
                    throw new IOException("Gisement incomplet");
                ressource(dimension);
                int x = coordonnee((Integer) g.getOrDefault("x", 0), 30_000_000),
                        y = coordonnee((Integer) g.getOrDefault("y", 0), 2047),
                        z = coordonnee((Integer) g.getOrDefault("z", 0), 30_000_000);
                Set<String> composition = new TreeSet<>();
                for (Object c : (List<Object>) g.getOrDefault("composition", List.of()))
                    composition.add(c instanceof Integer i ? type(i) : ressource((String) c));
                m.gisement(
                        id,
                        new FilonMemoire.Position(dimension, x, y, z),
                        (String) g.getOrDefault("definition", ""),
                        (String) g.getOrDefault("nom", ""),
                        composition,
                        (Boolean) g.getOrDefault("epuise", false));
                var f = m.filon(id);
                f.marquerEpuise((Boolean) g.getOrDefault("epuise_manuel", false));
                List<String> choix = new ArrayList<>();
                if (g.get("choix") instanceof List<?> liste) {
                    for (Object c : liste) choix.add(ressource((String) c));
                    f.choisirTypes(choix);
                } else if (choixV1.containsKey(id)) f.choisirTypes(choixV1.get(id));
                f.verification = (Long) g.getOrDefault("verification", 0L);
                f.volume = VolumeFilon.lire((int[]) g.get("volume"));
                if (f.volume != null && g.get("scan") instanceof long[] scan) {
                    f.scanFait = scan[0];
                    f.scanTotal = scan[1];
                    f.scanComplet = scan[2] != 0;
                }
                if (f.volume != null && g.get("attente") instanceof int[] attente) {
                    long[] colonnes = new long[attente.length / 2];
                    for (int i = 0; i < colonnes.length; i++)
                        colonnes[i] = VolumeFilon.colonne(attente[2 * i], attente[2 * i + 1]);
                    f.colonnesRestantes = colonnes;
                }
                if (g.get("mines") instanceof int[] mines)
                    for (int i = 0; i + 1 < mines.length; i += 2)
                        f.compterMine(m.type(type(mines[i])), Math.max(0, mines[i + 1]));
                if (g.get("blocs") instanceof int[] blocsV2)
                    for (int i = 0; i + 3 < blocsV2.length; i += 4)
                        m.restaurer(
                                f,
                                FilonMemoire.pos(
                                        coordonnee(x + blocsV2[i], 30_000_000),
                                        coordonnee(y + blocsV2[i + 1], 2047),
                                        coordonnee(z + blocsV2[i + 2], 30_000_000)),
                                m.type(type(blocsV2[i + 3])),
                                false);
            }
            rattacherV1(m);
            return m;
        }

        private String type(int t) throws IOException {
            if (t < 0 || t >= types.size()) throw new IOException("Type de bloc inconnu");
            return types.get(t);
        }

        /**
         * Blocs v1 : présents gardés, minés convertis en compteurs ; date = plus ancien présent.
         */
        private void rattacherV1(FilonMemoire m) {
            if (blocs == 0) return;
            FilonMemoire.Filon[] filons = new FilonMemoire.Filon[nomsFilonsV1.size()];
            for (int i = 0; i < filons.length; i++) filons[i] = m.filon(nomsFilonsV1.get(i));
            int[] types = new int[nomsTypesV1.size()];
            for (int i = 0; i < types.length; i++) types[i] = m.type(nomsTypesV1.get(i));
            Map<FilonMemoire.Filon, long[]> dates = new HashMap<>();
            for (int i = 0; i < blocs; i++) {
                var f = filons[filonV1[i]];
                // Pas de relevé orphelin ni d'une autre dimension que son gisement.
                if (f == null || !f.ancre.dimension().equals(dimensionsV1.get(dimensionV1[i])))
                    continue;
                m.restaurer(
                        f, FilonMemoire.pos(xV1[i], yV1[i], zV1[i]), types[typeV1[i]], mineV1[i]);
                long[] d = dates.computeIfAbsent(f, k -> new long[] {Long.MAX_VALUE, 0});
                if (!mineV1[i] && tV1[i] > 0) d[0] = Math.min(d[0], tV1[i]);
                d[1] = Math.max(d[1], tV1[i]);
            }
            dates.forEach(
                    (f, d) -> {
                        if (f.verification <= 0)
                            f.verification = d[0] != Long.MAX_VALUE ? d[0] : d[1];
                    });
        }
    }
}
