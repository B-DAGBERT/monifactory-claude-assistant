package fr.assistantia.compat.emi;

import fr.assistantia.OrdreMachines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Numérotation du plan à partir de l'ordre d'{@link OrdreMachines} : rang 1 à faire d'abord. Les
 * lots déjà lancés s'insèrent entre les étapes à préparer et les étapes suivantes, comme dans le
 * suivi. Calcul pur : aucun nom, aucune lecture du jeu.
 */
public final class RangsRecette {
    private RangsRecette() {}

    public enum Statut {
        PRETE,
        A_PREPARER,
        A_RECUPERER,
        EN_MACHINE,
        ATTEND,
        MANQUE,
        POSTE_MANQUANT,
        BLOQUEE,
        INCONNUE;

        /** Une action est possible tout de suite. */
        public boolean actionnable() {
            return this == PRETE || this == A_PREPARER || this == A_RECUPERER;
        }
    }

    /** Un lot du suivi : déjà envoyé ou déclaré, ses sorties restent attendues. */
    public record Lot<R>(int id, R recette, int fois, boolean recuperable) {}

    /**
     * Une étape numérotée. {@code attend} est le rang de l'étape attendue (0 sinon), {@code lot}
     * l'identifiant du lot (0 pour une fabrication restante).
     */
    public record Etape<R>(
            int rang, R recette, int fois, int lot, Statut statut, String raison, int attend) {
        public Etape {
            raison = raison == null ? "" : raison;
        }
    }

    /** Repère d'une machine dans le monde : rang, étoile de la première à utiliser, grisé. */
    public record Repere(int rang, boolean etoile, boolean attenue, String texte) {}

    /**
     * @param lotAttendu identifiant du lot dont la sortie manque à cette recette, 0 sinon
     */
    public static <R> List<Etape<R>> numeroter(
            List<OrdreMachines.Ligne<R>> lignes,
            List<Lot<R>> lots,
            Function<R, Integer> lotAttendu) {
        // Ordre du suivi : prêtes, à préparer, lots en cours, puis étapes suivantes.
        List<Object> ordre = new ArrayList<>();
        for (var groupe : OrdreMachines.Groupe.values()) {
            if (groupe == OrdreMachines.Groupe.ETAPES) ordre.addAll(lots);
            for (var ligne : lignes) if (ligne.groupe() == groupe) ordre.add(ligne);
        }
        Map<R, Integer> rangs = new HashMap<>();
        Map<Integer, Integer> rangsLots = new HashMap<>();
        for (int i = 0; i < ordre.size(); i++) {
            if (ordre.get(i) instanceof OrdreMachines.Ligne<?> ligne) {
                @SuppressWarnings("unchecked")
                R recette = (R) ligne.operation().recette();
                rangs.putIfAbsent(recette, i + 1);
            } else if (ordre.get(i) instanceof Lot<?> lot) rangsLots.putIfAbsent(lot.id(), i + 1);
        }
        List<Etape<R>> etapes = new ArrayList<>();
        for (int i = 0; i < ordre.size(); i++) {
            int rang = i + 1;
            if (ordre.get(i) instanceof Lot<?> brut) {
                @SuppressWarnings("unchecked")
                Lot<R> lot = (Lot<R>) brut;
                etapes.add(
                        new Etape<>(
                                rang,
                                lot.recette(),
                                lot.fois(),
                                lot.id(),
                                lot.recuperable() ? Statut.A_RECUPERER : Statut.EN_MACHINE,
                                lot.recuperable() ? "Sorties à récupérer" : "En machine",
                                0));
                continue;
            }
            @SuppressWarnings("unchecked")
            var ligne = (OrdreMachines.Ligne<R>) ordre.get(i);
            R recette = ligne.operation().recette();
            int fois = ligne.operation().fois();
            String raison = ligne.raison();
            Statut statut;
            int attend = 0;
            switch (ligne.mode()) {
                case PRET -> statut = Statut.PRETE;
                case PREPARER -> statut = Statut.A_PREPARER;
                case EN_COURS -> statut = Statut.EN_MACHINE;
                case INCONNU -> statut = Statut.INCONNUE;
                default -> {
                    if (ligne.cycle()) statut = Statut.BLOQUEE;
                    else if (raison.startsWith("Machine manquante")
                            || raison.startsWith("Poste manquant")) statut = Statut.POSTE_MANQUANT;
                    else if (raison.startsWith("Attend") || ligne.conflit()) {
                        statut = Statut.ATTEND;
                        attend = premier(ligne.dependances(), rangs, rang);
                    } else if (raison.startsWith("Manque")) statut = Statut.MANQUE;
                    else statut = Statut.BLOQUEE;
                }
            }
            if (statut == Statut.MANQUE || statut == Statut.ATTEND && attend == 0) {
                Integer lot = lotAttendu == null ? null : lotAttendu.apply(recette);
                Integer rangLot = lot == null || lot == 0 ? null : rangsLots.get(lot);
                if (rangLot != null) {
                    statut = Statut.ATTEND;
                    attend = rangLot;
                }
            }
            etapes.add(new Etape<>(rang, recette, fois, 0, statut, raison, attend));
        }
        return List.copyOf(etapes);
    }

    private static <R> int premier(List<R> dependances, Map<R, Integer> rangs, int rang) {
        int meilleur = 0;
        for (R dependance : dependances) {
            Integer r = rangs.get(dependance);
            if (r != null && r != rang && (meilleur == 0 || r < meilleur)) meilleur = r;
        }
        return meilleur;
    }

    /** L'étape à mettre en avant (HUD, étoile) : la première action possible, sinon la première. */
    public static <R> Etape<R> suivante(List<Etape<R>> etapes) {
        for (var etape : etapes) if (etape.statut().actionnable()) return etape;
        return etapes.isEmpty() ? null : etapes.get(0);
    }

    /**
     * Repères des machines surlignées, au plus {@code max}, dans l'ordre fourni (les plus proches
     * d'abord). Une machine reçoit le rang de la première étape qu'elle sait faire ; l'étoile va à
     * la plus proche machine de la première étape machine actionnable. Un lot envoyé dans une
     * machine y reste affiché tant que ses sorties sont attendues.
     */
    public static <R, P> Map<P, Repere> reperes(
            List<Etape<R>> etapes,
            List<P> positions,
            BiPredicate<P, R> compatible,
            Map<P, Integer> lotsParPosition,
            int max) {
        Map<P, Repere> resultat = new LinkedHashMap<>();
        Map<P, Etape<R>> choisies = new LinkedHashMap<>();
        for (P position : positions) {
            if (choisies.size() >= max) break;
            Integer lot = lotsParPosition == null ? null : lotsParPosition.get(position);
            Etape<R> retenue = null;
            if (lot != null && lot != 0)
                for (var etape : etapes)
                    if (etape.lot() == lot) {
                        retenue = etape;
                        break;
                    }
            if (retenue == null)
                for (var etape : etapes)
                    if (etape.lot() == 0 && compatible.test(position, etape.recette())) {
                        retenue = etape;
                        break;
                    }
            if (retenue != null) choisies.put(position, retenue);
        }
        Etape<R> etoile = null;
        for (var etape : etapes)
            if (etape.lot() == 0
                    && etape.statut().actionnable()
                    && choisies.values().stream().anyMatch(e -> e == etape)) {
                etoile = etape;
                break;
            }
        boolean placee = false;
        for (var entree : choisies.entrySet()) {
            var etape = entree.getValue();
            boolean star = !placee && etape == etoile;
            placee |= star;
            String texte =
                    etape.lot() != 0
                            ? etape.rang()
                                    + (etape.statut() == Statut.A_RECUPERER
                                            ? " · prêt"
                                            : " · en cours")
                            : (star ? "★ " : "") + etape.rang();
            resultat.put(
                    entree.getKey(),
                    new Repere(
                            etape.rang(),
                            star,
                            etape.lot() == 0 && !etape.statut().actionnable(),
                            texte));
        }
        return resultat;
    }

    /** Texte court d'un statut, avec l'étape attendue. */
    public static String texte(Etape<?> etape, Function<String, String> nettoyer) {
        Objects.requireNonNull(etape);
        return switch (etape.statut()) {
            case PRETE -> "Prête";
            case A_PREPARER -> etape.raison().isBlank()
                    ? "À préparer"
                    : nettoyer.apply(etape.raison());
            case A_RECUPERER -> "À récupérer";
            case EN_MACHINE -> "En machine";
            case ATTEND -> etape.attend() > 0
                    ? "Attend l’étape " + etape.attend()
                    : "Stock pris par une autre étape";
            case MANQUE -> nettoyer.apply(etape.raison());
            case POSTE_MANQUANT -> nettoyer.apply(etape.raison());
            case BLOQUEE -> etape.raison().isBlank() ? "Bloquée" : nettoyer.apply(etape.raison());
            case INCONNUE -> etape.raison().isBlank()
                    ? "À vérifier"
                    : nettoyer.apply(etape.raison());
        };
    }
}
