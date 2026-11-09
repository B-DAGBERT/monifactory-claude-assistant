package fr.assistantia;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Ce que l'onglet Recette, le panneau du HUD et les repères affichent, calculé hors du rendu par
 * l'analyse de l'arbre EMI et publié en bloc. Cette classe ne dépend pas d'EMI : l'écran de
 * l'assistant reste utilisable sans lui.
 */
public record VueRecette(
        Etat etat,
        String message,
        Objet cible,
        List<Etape> etapes,
        List<Alternative> alternatives,
        List<Introuvable> introuvables,
        List<MachineAFabriquer> machines,
        String annulation,
        String avancement,
        String remarque,
        String plan,
        long revision,
        EtapeSuivante suivante) {

    /**
     * {@code plan} : raison pour laquelle le plan n'est pas encore calculé (sac en lecture, calcul
     * en attente, arbre trop grand…), vide une fois le plan calculé. Un plan vide et calculé
     * signifie au contraire que tout est déjà en stock.
     */
    public VueRecette {
        message = texte(message);
        etapes = List.copyOf(etapes);
        alternatives = List.copyOf(alternatives);
        introuvables = List.copyOf(introuvables);
        machines = List.copyOf(machines);
        annulation = texte(annulation);
        avancement = texte(avancement);
        remarque = texte(remarque);
        plan = texte(plan);
    }

    public enum Etat {
        /** Pas de monde chargé. */
        SANS_MONDE,
        /** EMI charge encore ses recettes. */
        CHARGEMENT,
        /** Aucun objet choisi dans Recipe Tree. */
        SANS_ARBRE,
        /** Plan disponible (éventuellement vide). */
        PRET,
        /** Arbre illisible ou trop grand : {@link #message} explique. */
        ERREUR
    }

    /** Couleur d'un état d'étape, choisie par l'onglet dans le thème commun. */
    public enum Ton {
        PRET,
        PREPARER,
        EN_COURS,
        ATTENTE,
        PROBLEME
    }

    /** Un objet affiché : icône, nom, quantité lisible et détail au survol. */
    public record Objet(ItemStack icone, String nom, String quantite, String detail) {
        public Objet {
            icone = icone == null ? ItemStack.EMPTY : icone;
            nom = texte(nom);
            quantite = texte(quantite);
            detail = texte(detail);
        }
    }

    /** Une étape numérotée du plan, rang 1 à faire d'abord. */
    public record Etape(
            int rang,
            boolean etoile,
            Objet produit,
            String poste,
            ItemStack iconePoste,
            String circuit,
            String entrees,
            String etat,
            Ton ton,
            String detail) {
        public Etape {
            poste = texte(poste);
            iconePoste = iconePoste == null ? ItemStack.EMPTY : iconePoste;
            circuit = texte(circuit);
            entrees = texte(entrees);
            etat = texte(etat);
            detail = texte(detail);
        }
    }

    /** Une meilleure façon de faire un produit de l'arbre, appliquable dans l'arbre EMI. */
    public record Alternative(
            String id,
            Objet produit,
            String poste,
            ItemStack iconePoste,
            String gain,
            String detail) {
        public Alternative {
            id = texte(id);
            poste = texte(poste);
            iconePoste = iconePoste == null ? ItemStack.EMPTY : iconePoste;
            gain = texte(gain);
            detail = texte(detail);
        }
    }

    /** Une matière de base demandée par l'arbre que le joueur n'a nulle part. */
    public record Introuvable(Objet objet, String detail) {
        public Introuvable {
            detail = texte(detail);
        }
    }

    /** Une machine manquante, facile à faire, qui permettrait un meilleur craft. */
    public record MachineAFabriquer(Objet machine, String facilite, String gain, String detail) {
        public MachineAFabriquer {
            facilite = texte(facilite);
            gain = texte(gain);
            detail = texte(detail);
        }
    }

    /** La prochaine étape pour le panneau en haut à gauche, ex. « ★ Basic Bender ». */
    public record EtapeSuivante(ItemStack icone, String titre, String detail) {
        public EtapeSuivante {
            icone = icone == null ? ItemStack.EMPTY : icone;
            titre = texte(titre);
            detail = texte(detail);
        }
    }

    /** Actions de l'analyse EMI, branchées seulement quand EMI est chargé. */
    public interface Actions {
        /** Applique l'alternative affichée ; retourne le message à montrer au joueur. */
        String appliquer(String id);

        /** Rétablit le dernier choix remplacé par « Appliquer ». */
        String annuler();
    }

    private static volatile VueRecette courante;
    private static volatile Actions actions;

    public static VueRecette vide(Etat etat, String message) {
        return new VueRecette(
                etat, message, null, List.of(), List.of(), List.of(), List.of(), "", "", "", "", 0,
                null);
    }

    /** La dernière vue publiée, ou null si l'analyse EMI n'a encore rien publié. */
    public static VueRecette courante() {
        return courante;
    }

    public static void publier(VueRecette vue) {
        courante = vue;
    }

    public static void brancher(Actions branchees) {
        actions = branchees;
    }

    public static String appliquer(String id) {
        var a = actions;
        return a == null ? "EMI indisponible." : a.appliquer(id);
    }

    public static String annuler() {
        var a = actions;
        return a == null ? "EMI indisponible." : a.annuler();
    }

    /**
     * Prochaine étape du plan pour le HUD, ou null quand il n'y a rien à faire. Lecture d'un champ
     * déjà calculé : utilisable à chaque image.
     */
    public static EtapeSuivante etapeSuivante() {
        var vue = courante;
        return vue == null || vue.etat() != Etat.PRET ? null : vue.suivante();
    }

    private static String texte(String valeur) {
        return valeur == null ? "" : valeur;
    }
}
