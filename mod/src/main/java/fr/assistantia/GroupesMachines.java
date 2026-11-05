package fr.assistantia;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Regroupement d'affichage : chaque emplacement garde son relevé propre. */
public final class GroupesMachines<T> {
    public record Machine<T>(String type, String nom, String position, int priorite, T valeur) {}

    public record Groupe<T>(
            String type,
            String nom,
            List<Machine<T>> machines,
            int priorite,
            int enMarche,
            int alertes) {
        public Groupe {
            machines = List.copyOf(machines);
        }
    }

    public record Ligne<T>(Groupe<T> groupe, Machine<T> machine) {
        public boolean entete() {
            return machine == null;
        }
    }

    private final Set<String> ouverts;
    private List<Groupe<T>> groupes = List.of();
    private List<Ligne<T>> lignes = List.of();
    private long dernierClic = -650;

    public GroupesMachines() {
        this(new HashSet<>());
    }

    public GroupesMachines(Set<String> ouverts) {
        this.ouverts = ouverts;
    }

    public void actualiser(List<Machine<T>> machines) {
        var parType = new LinkedHashMap<String, List<Machine<T>>>();
        for (var machine : machines)
            parType.computeIfAbsent(machine.type(), cle -> new ArrayList<>()).add(machine);
        List<Groupe<T>> resultat = new ArrayList<>();
        for (var entree : parType.entrySet()) {
            var membres = entree.getValue();
            membres.sort(
                    Comparator.comparingInt(Machine<T>::priorite)
                            .thenComparing(Machine<T>::position)
                            .thenComparing(Machine<T>::nom));
            var premier = membres.get(0);
            int marche = 0, alertes = 0;
            for (var membre : membres) {
                if (membre.priorite() == 2) marche++;
                else if (membre.priorite() < 2) alertes++;
            }
            resultat.add(
                    new Groupe<>(
                            entree.getKey(),
                            premier.nom(),
                            membres,
                            premier.priorite(),
                            marche,
                            alertes));
        }
        resultat.sort(
                Comparator.comparingInt(Groupe<T>::priorite)
                        .thenComparing(Groupe<T>::nom, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Groupe<T>::type));
        groupes = List.copyOf(resultat);
        reconstruire();
    }

    public List<Groupe<T>> groupes() {
        return groupes;
    }

    public List<Ligne<T>> lignes() {
        return lignes;
    }

    public boolean ouvert(String type) {
        return ouverts.contains(type);
    }

    public boolean basculer(String type, long maintenant) {
        if (maintenant - dernierClic < 650
                || groupes.stream()
                        .noneMatch(g -> g.type().equals(type) && g.machines().size() > 1))
            return false;
        dernierClic = maintenant;
        if (!ouverts.add(type)) ouverts.remove(type);
        reconstruire();
        return true;
    }

    private void reconstruire() {
        List<Ligne<T>> resultat = new ArrayList<>();
        for (var groupe : groupes) {
            if (groupe.machines().size() == 1) {
                resultat.add(new Ligne<>(groupe, groupe.machines().get(0)));
            } else {
                resultat.add(new Ligne<>(groupe, null));
                if (ouvert(groupe.type()))
                    for (var machine : groupe.machines())
                        resultat.add(new Ligne<>(groupe, machine));
            }
        }
        lignes = List.copyOf(resultat);
    }
}
