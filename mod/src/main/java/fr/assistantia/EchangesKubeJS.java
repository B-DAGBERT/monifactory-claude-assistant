package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Points d'entrée du script KubeJS client : ses relevés sont écrits sur le fil d'écriture du mod,
 * et la progression des quêtes se compare par empreinte, sans relire toutes les quêtes. Le script
 * garde son ancien fonctionnement si cette classe est absente.
 */
public final class EchangesKubeJS {
    private static final Path DOSSIER =
            FMLPaths.GAMEDIR.get().resolve("kubejs").resolve("assistant");
    private static final Set<String> RELEVES = Set.of("etat.json", "quetes.json");

    private record Reception(String contexte, long date) {}

    private static volatile Reception recu = new Reception("", 0);
    private static volatile String erreur = "";

    private EchangesKubeJS() {}

    private static final Map<String, Releve> PUBLIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Un relevé déjà mis en texte par le script, remplacé atomiquement en arrière-plan. */
    public static boolean publier(String nom, String texte) {
        if (nom == null || texte == null || !RELEVES.contains(nom)) return false;
        var releve = new Releve(texte);
        PUBLIES.put(nom, releve);
        Ecritures.publier(DOSSIER.resolve(nom), releve);
        return true;
    }

    /**
     * Le dernier relevé publié sous ce nom n'a jamais été écrit et n'est plus en file (abandonné
     * après une erreur) : le script doit le republier, même sans changement.
     */
    public static boolean perdu(String nom) {
        Releve releve = nom == null ? null : PUBLIES.get(nom);
        // Dans cet ordre : un relevé est confirmé avant que sa tâche ne quitte la file.
        if (releve == null || Ecritures.enAttente(DOSSIER.resolve(nom)) != null) return false;
        return !releve.ecrit;
    }

    /** Texte du script, ses nombres entiers remis en entiers sur le fil d'écriture. */
    static final class Releve extends Ecritures.Contenu {
        private final String texte;
        volatile boolean ecrit;

        Releve(String texte) {
            this.texte = texte;
        }

        protected byte[] produire() {
            return entiers(texte).getBytes(StandardCharsets.UTF_8);
        }

        protected void ecrit() {
            ecrit = true;
        }
    }

    /**
     * Le JSON.stringify de ce Rhino écrit tous les nombres en double (13.0, 1.794193700105E12) :
     * les valeurs entières redeviennent des entiers, comme les écrivait JsonIO.write. Mise en page
     * KubeJS (tabulations) ; texte inchangé s'il n'est pas du JSON.
     */
    static String entiers(String texte) {
        try {
            return Ecritures.serialiser(arrondir(JsonParser.parseString(texte)), true);
        } catch (java.io.IOException | RuntimeException illisible) {
            return texte;
        }
    }

    private static JsonElement arrondir(JsonElement valeur) {
        if (valeur.isJsonObject()) {
            var objet = valeur.getAsJsonObject();
            for (String cle : new java.util.ArrayList<>(objet.keySet()))
                objet.add(cle, arrondir(objet.get(cle)));
        } else if (valeur.isJsonArray()) {
            var tableau = valeur.getAsJsonArray();
            for (int i = 0; i < tableau.size(); i++) tableau.set(i, arrondir(tableau.get(i)));
        } else if (valeur.isJsonPrimitive() && valeur.getAsJsonPrimitive().isNumber()) {
            double nombre = valeur.getAsDouble();
            if (nombre == Math.rint(nombre) && Math.abs(nombre) <= 9_007_199_254_740_992.0)
                return new com.google.gson.JsonPrimitive((long) nombre);
        }
        return valeur;
    }

    /**
     * Relevé du serveur déjà accepté par le script pour ce contexte : seules ses parties sont lues
     * ici ; assemblage, contrôle de dimension, annotation et écriture se font en arrière-plan.
     */
    public static boolean transmettreMonde(
            CompoundTag donnees, int parties, String monde, String session, String dimension) {
        if (donnees == null || parties < 1 || parties > 30) return false;
        String[] textes = new String[parties];
        for (int i = 0; i < parties; i++) textes[i] = donnees.getString("p" + i);
        Ecritures.publier(
                DOSSIER.resolve("monde.json"),
                new ReleveMonde(textes, texte(monde), texte(session), texte(dimension)));
        return true;
    }

    /** Date (ms) du dernier relevé du serveur écrit pour ce contexte, 0 sinon. */
    public static long recu(String monde, String session, String dimension) {
        Reception r = recu;
        return r.contexte().equals(cle(texte(monde), texte(session), texte(dimension)))
                ? r.date()
                : 0;
    }

    /** Erreur du dernier relevé du serveur illisible, rendue une seule fois (vide sinon). */
    public static String erreurMonde() {
        String e = erreur;
        if (!e.isEmpty()) erreur = "";
        return e;
    }

    private static String texte(String valeur) {
        return valeur == null ? "" : valeur;
    }

    private static String cle(String monde, String session, String dimension) {
        return monde + "|" + session + "|" + dimension;
    }

    /** Ce que faisait le script sur le thread client, désormais sur le fil d'écriture. */
    static final class ReleveMonde extends Ecritures.Contenu {
        private final String[] parties;
        private final String monde, session, dimension;

        ReleveMonde(String[] parties, String monde, String session, String dimension) {
            this.parties = parties;
            this.monde = monde;
            this.session = session;
            this.dimension = dimension;
        }

        protected byte[] produire() throws java.io.IOException {
            StringBuilder json = new StringBuilder();
            for (String partie : parties) json.append(partie);
            JsonObject trame;
            try {
                trame = JsonParser.parseString(json.toString()).getAsJsonObject();
            } catch (RuntimeException e) {
                erreur = "relevé illisible : " + e.getMessage();
                return null;
            }
            if (!monde.isEmpty()) {
                // Une trame d'une autre dimension (changement en cours) n'est jamais écrite.
                JsonElement valeur = trame.get("dimension");
                if (valeur == null
                        || !valeur.isJsonPrimitive()
                        || !valeur.getAsString().equals(dimension)) return null;
                trame.addProperty("monde_actif", monde);
                trame.addProperty("session_monde", session);
                trame.addProperty("dimension_monde", dimension);
            }
            return Ecritures.serialiser(trame, true).getBytes(StandardCharsets.UTF_8);
        }

        protected void ecrit() {
            recu = new Reception(cle(monde, session, dimension), System.currentTimeMillis());
        }
    }

    // ---------- Quêtes FTB : empreintes de TeamData ----------

    private static Class<?> classeEquipe;
    private static Field termines, commences, progres;

    /**
     * Empreinte de tout ce qui change la liste des quêtes (fichier, équipe, langue, objets terminés
     * ou commencés). Vide si FTB Quests a changé de structure : le script relit alors tout, comme
     * avant.
     */
    public static synchronized String empreinteQuetes(Object fichier, Object equipe) {
        if (fichier == null || equipe == null || !champs(equipe)) return "";
        try {
            var options = Minecraft.getInstance().options;
            return System.identityHashCode(fichier)
                    + "/"
                    + System.identityHashCode(equipe)
                    + "/"
                    + (options == null ? "" : options.languageCode)
                    + "/"
                    + carte(termines, equipe)
                    + "/"
                    + carte(commences, equipe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Empreinte de la progression des tâches (quantités en cours). Vide si illisible. */
    public static synchronized String progresQuetes(Object equipe) {
        if (equipe == null || !champs(equipe)) return "";
        try {
            return carte(progres, equipe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private static boolean champs(Object equipe) {
        Class<?> type = equipe.getClass();
        if (type != classeEquipe) {
            classeEquipe = type;
            termines = champ(type, "completed");
            commences = champ(type, "started");
            progres = champ(type, "taskProgress");
        }
        return termines != null && commences != null && progres != null;
    }

    private static Field champ(Class<?> type, String nom) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field champ = c.getDeclaredField(nom);
                if (!Map.class.isAssignableFrom(champ.getType())) return null;
                champ.setAccessible(true);
                return champ;
            } catch (NoSuchFieldException e) {
                // Chercher dans la classe parente.
            } catch (RuntimeException e) {
                return null; // module fermé ou accès refusé : repli du script
            }
        }
        return null;
    }

    /** Taille et somme des entrées : une seule valeur modifiée change l'empreinte. */
    private static String carte(Field champ, Object equipe) throws IllegalAccessException {
        Object valeur = champ.get(equipe);
        if (!(valeur instanceof Map<?, ?> carte)) throw new IllegalStateException("carte absente");
        return carte.size() + ":" + carte.hashCode();
    }
}
