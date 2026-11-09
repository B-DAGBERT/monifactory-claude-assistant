package fr.assistantia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copie immuable et bon marché de la mémoire des filons, prise sur le thread client (copies de
 * tableaux primitifs) puis sérialisée par le thread d'écriture. Sert aussi à reconstruire la
 * mémoire sans relire le fichier lors d'une reconnexion rapide.
 */
record InstantaneFilons(String monde, long date, String[] types, List<Gisement> gisements) {
    record Gisement(
            String id,
            String dimension,
            int x,
            int y,
            int z,
            String definition,
            String nom,
            boolean epuise,
            boolean epuiseManuel,
            String[] composition,
            String[] choix,
            long[] positions,
            int[] typesBlocs,
            int[] minesTypes,
            int[] minesNombres,
            long verification,
            int[] volume,
            long[] colonnesRestantes,
            long scanFait,
            long scanTotal,
            boolean scanComplet) {
        int restants() {
            return positions.length;
        }

        int mines() {
            int n = 0;
            for (int m : minesNombres) n += m;
            return n;
        }
    }

    static InstantaneFilons de(FilonMemoire memoire, String monde, long date) {
        String[] types = new String[memoire.typesConnus()];
        for (int t = 0; t < types.length; t++) types[t] = memoire.typeId(t);
        List<Gisement> gisements = new ArrayList<>(memoire.filons().size());
        for (var f : memoire.filons()) {
            String[] composition = f.composition.toArray(String[]::new);
            Arrays.sort(composition);
            var choisis = f.typesChoisis();
            String[] choix = choisis == null ? null : choisis.toArray(String[]::new);
            if (choix != null) Arrays.sort(choix);
            gisements.add(
                    new Gisement(
                            f.id,
                            f.ancre.dimension(),
                            f.ancre.x(),
                            f.ancre.y(),
                            f.ancre.z(),
                            f.definition,
                            f.nom,
                            f.epuise,
                            f.epuiseManuel(),
                            composition,
                            choix,
                            Arrays.copyOf(f.positions, f.presents),
                            Arrays.copyOf(f.typesBlocs, f.presents),
                            Arrays.copyOf(f.minesTypes, f.minesTypesNombre),
                            Arrays.copyOf(f.minesNombres, f.minesTypesNombre),
                            f.verification,
                            f.volume == null ? null : f.volume.valeurs(),
                            f.colonnesRestantes.clone(),
                            f.scanFait,
                            f.scanTotal,
                            f.scanComplet));
        }
        return new InstantaneFilons(monde, date, types, List.copyOf(gisements));
    }

    /** Reconstruit une mémoire indépendante (reconnexion avant la fin de l'écriture). */
    FilonMemoire memoire() {
        var m = new FilonMemoire();
        int[] correspondance = new int[types.length];
        for (int t = 0; t < types.length; t++) correspondance[t] = m.type(types[t]);
        for (var g : gisements) {
            var ancre = new FilonMemoire.Position(g.dimension(), g.x(), g.y(), g.z());
            m.gisement(
                    g.id(),
                    ancre,
                    g.definition(),
                    g.nom(),
                    java.util.Set.of(g.composition()),
                    g.epuise());
            var f = m.filon(g.id());
            f.marquerEpuise(g.epuiseManuel());
            if (g.choix() != null) f.choisirTypes(List.of(g.choix()));
            for (int i = 0; i < g.positions().length; i++)
                m.restaurer(f, g.positions()[i], correspondance[g.typesBlocs()[i]], false);
            for (int i = 0; i < g.minesTypes().length; i++)
                f.compterMine(correspondance[g.minesTypes()[i]], g.minesNombres()[i]);
            f.verification = g.verification();
            f.volume = VolumeFilon.lire(g.volume());
            f.colonnesRestantes = f.volume == null ? new long[0] : g.colonnesRestantes().clone();
            f.scanFait = g.scanFait();
            f.scanTotal = g.scanTotal();
            f.scanComplet = g.scanComplet();
        }
        return m;
    }
}
