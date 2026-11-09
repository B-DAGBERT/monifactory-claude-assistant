package fr.assistantia;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/**
 * Onglet Base : les machines vues par le serveur, groupées par type, avec une recherche locale, un
 * filtre et la lecture Production de l'arbre EMI. Aucune requête IA.
 */
public final class OngletBase {
    /** Hauteur d'une ligne de la liste et de sa carte (icône de 16 et deux lignes de texte). */
    private static final int PAS = 28, CARTE = 26, RETRAIT = 10;

    private static final String[] FILTRES = {"Toutes", "À vérifier", "En marche"};
    private final Minecraft mc = Minecraft.getInstance();
    private final BarreDefilement barre = new BarreDefilement();
    private ChampTheme recherche;
    private BoutonTheme filtre, production;
    private static String texte = "";
    private static int categorie;
    private int x, largeur, bas, haut;
    private long dernierClic;
    private boolean actif;
    private JsonArray source;
    private static final Set<String> GROUPES_OUVERTS = new HashSet<>();
    private final GroupesMachines<JsonObject> groupes = new GroupesMachines<>(GROUPES_OUVERTS);
    private final List<ZoneGroupe> zones = new ArrayList<>();

    /** Partie visible et cliquable d'un en-tête de groupe. */
    private record ZoneGroupe(String type, int y, int h) {}

    private String filtreTexte = "";
    private int filtreCategorie = -1, alertes;

    public void init(Consumer<AbstractWidget> ajout, Runnable ouvrirProduction) {
        recherche = ChampTheme.dans(mc.font, 0, 0, 100, Component.literal("Chercher une machine"));
        recherche.setMaxLength(100);
        recherche.setHint(Component.literal("Chercher une machine…"));
        recherche.setValue(texte);
        recherche.setResponder(
                s -> {
                    texte = s;
                    barre.aller(0);
                });
        ajout.accept(recherche);
        filtre =
                BoutonTheme.creer(
                                FILTRES[categorie],
                                b -> {
                                    if (System.currentTimeMillis() - dernierClic < 650) return;
                                    dernierClic = System.currentTimeMillis();
                                    categorie = (categorie + 1) % FILTRES.length;
                                    barre.aller(0);
                                })
                        .infobulle(
                                "Filtrer : toutes les machines, alertes et attentes, ou machines en marche.");
        ajout.accept(filtre);
        production =
                BoutonTheme.creer("Production…", b -> ouvrirProduction.run())
                        .infobulle(
                                "Lire le plan de l’arbre EMI ou ses diagnostics, sans rien modifier dans le jeu.");
        ajout.accept(production);
        visible(false);
    }

    public void visible(boolean valeur) {
        actif = valeur;
        recherche.visible = filtre.visible = production.visible = valeur;
        if (!valeur) {
            recherche.setFocused(false);
            barre.relacher();
        }
    }

    public void tick() {
        if (actif) recherche.tick();
    }

    private static String normaliser(String t) {
        return Normalizer.normalize(t, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private void actualiser(JsonObject vue) {
        JsonArray liste = Pont.liste(vue, "machines");
        if (liste.equals(source) && filtreTexte.equals(texte) && filtreCategorie == categorie) {
            source = liste;
            return;
        }
        // Pont publie un nouveau relevé ; aucune normalisation ni aucun tri à refaire par image.
        source = liste;
        filtreTexte = texte;
        filtreCategorie = categorie;
        List<GroupesMachines.Machine<JsonObject>> machines = new ArrayList<>();
        alertes = 0;
        String requete = normaliser(texte);
        for (var element : liste) {
            var m = element.getAsJsonObject();
            String couleur = Pont.texte(m, "couleur");
            boolean alerte = couleur.equals("rouge") || couleur.equals("jaune");
            if (alerte) alertes++;
            if ((categorie == 1 && !alerte) || (categorie == 2 && !couleur.equals("vert")))
                continue;
            String nom = Pont.texte(m, "nom");
            if (!normaliser(nom).contains(requete)) continue;
            String type = Pont.texte(m, "item");
            if (type.isBlank()) type = "nom:" + normaliser(nom);
            int priorite =
                    switch (couleur) {
                        case "rouge" -> 0;
                        case "jaune" -> 1;
                        case "vert" -> 2;
                        default -> 3;
                    };
            machines.add(
                    new GroupesMachines.Machine<>(
                            type,
                            nom,
                            Pont.texte(m, "dimension") + "|" + position(m),
                            priorite,
                            m));
        }
        groupes.actualiser(machines);
    }

    public void render(
            GuiGraphics g, JsonObject vue, int x, int y, int largeur, int bas, int mx, int my) {
        visible(true);
        this.x = x;
        this.largeur = largeur;
        this.bas = bas;
        Font font = mc.font;
        int lFiltre = 0;
        for (String nom : FILTRES) lFiltre = Math.max(lFiltre, Theme.largeurBouton(font, nom, 120));
        int lProduction = Theme.largeurBouton(font, "Production…", 120);
        int lRecherche = Math.max(40, largeur - lFiltre - lProduction - 2 * Theme.ECART);
        recherche.zone(x, y, lRecherche);
        filtre.zone(x + lRecherche + Theme.ECART, y, lFiltre).texte(FILTRES[categorie]);
        production.zone(x + largeur - lProduction, y, lProduction);
        actualiser(vue);
        zones.clear();

        int ligneY = y + Theme.BOUTON + Theme.ECART + 2;
        boolean serveur = Pont.vrai(vue, "serveur");
        String resume =
                Pont.liste(vue, "machines").size()
                        + " machines · "
                        + (int) Pont.nombre(vue, "coffres", 0)
                        + " coffres"
                        + (alertes > 0 ? " · " + alertes + " à vérifier" : "")
                        + (serveur ? " · liaison serveur active" : " · derniers relevés conservés");
        Theme.texte(g, font, resume, x, ligneY, largeur, Theme.TEXTE_SECONDAIRE);
        if (my >= ligneY - 2 && my < ligneY + Theme.LIGNE && mx >= x && mx < x + largeur)
            Theme.infobulle(font, Component.literal(detailBase(vue, resume)));

        haut = ligneY + Theme.LIGNE + Theme.ECART;
        var lignes = groupes.lignes();
        barre.regler(
                x + largeur - Theme.DEFILEMENT, haut, Math.max(0, bas - haut), lignes.size() * PAS);
        // La barre garde sa colonne : les cartes s'arrêtent avant elle.
        int fin = x + largeur - Theme.DEFILEMENT - Theme.ECART;
        boolean dedans = mx >= x && mx < fin && my >= haut && my < bas;
        g.enableScissor(x, haut, x + largeur, bas);
        try {
            int premier = barre.position() / PAS;
            for (int i = premier; i < lignes.size(); i++) {
                int ly = haut + i * PAS - barre.position();
                if (ly >= bas) break;
                var ligne = lignes.get(i);
                boolean survol = dedans && my >= ly && my < ly + CARTE;
                if (ligne.entete()) dessinerGroupe(g, font, ligne.groupe(), ly, fin, survol);
                else
                    dessinerMachine(
                            g,
                            font,
                            ligne.machine().valeur(),
                            ligne.groupe().machines().size() > 1,
                            ly,
                            fin,
                            survol);
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, mx, my);
        if (lignes.isEmpty())
            Theme.paragraphe(
                    g,
                    font,
                    Pont.liste(vue, "machines").isEmpty()
                            ? "Aucune machine repérée. Approche-toi de ta base."
                            : "Aucune machine pour ce filtre.",
                    x,
                    haut + Theme.ECART,
                    largeur,
                    Theme.TEXTE_SECONDAIRE,
                    Math.max(1, (bas - haut) / Theme.LIGNE));
    }

    /** Le résumé complet et les joueurs connus, au survol de la ligne de résumé. */
    private static String detailBase(JsonObject vue, String resume) {
        var joueurs = Pont.liste(vue, "joueurs");
        if (joueurs.isEmpty()) return resume;
        StringBuilder detail =
                new StringBuilder(resume)
                        .append(
                                Pont.vrai(vue, "serveur")
                                        ? "\nJoueurs :"
                                        : "\nDernières positions connues :");
        for (JsonElement joueur : joueurs) detail.append('\n').append(joueur.getAsString());
        return detail.toString();
    }

    private static String position(JsonObject m) {
        return "X "
                + (int) Pont.nombre(m, "x", 0)
                + " · Y "
                + (int) Pont.nombre(m, "y", 0)
                + " · Z "
                + (int) Pont.nombre(m, "z", 0);
    }

    /** Carte d'une ligne : fond du thème, barre de la couleur d'état à gauche. */
    private static void carte(
            GuiGraphics g, int gauche, int ly, int fin, boolean survol, int etat) {
        Theme.carte(g, gauche, ly, fin - gauche, CARTE, survol);
        g.fill(gauche, ly, gauche + 2, ly + CARTE, 0xFF000000 | etat);
    }

    private void dessinerGroupe(
            GuiGraphics g,
            Font font,
            GroupesMachines.Groupe<JsonObject> groupe,
            int ly,
            int fin,
            boolean survol) {
        int debut = Math.max(ly, haut), finY = Math.min(ly + CARTE, bas);
        if (finY > debut) zones.add(new ZoneGroupe(groupe.type(), debut, finY - debut));
        var representant = groupe.machines().get(0).valeur();
        boolean ouvert = groupes.ouvert(groupe.type());
        carte(g, x, ly, fin, survol, Hud.couleur(Pont.texte(representant, "couleur")));
        g.renderItem(Pont.objet(Pont.texte(representant, "item")), x + 6, ly + 5);
        String quantite = "× " + groupe.machines().size();
        int lQuantite = font.width(quantite), tx = x + 28;
        Theme.texte(g, font, quantite, fin - 6 - lQuantite, ly + 4, lQuantite, Theme.ACCENT);
        Theme.texte(
                g,
                font,
                (ouvert ? "− " : "+ ") + groupe.nom(),
                tx,
                ly + 4,
                fin - 6 - lQuantite - Theme.ECART - tx,
                Theme.TEXTE);
        int marche = groupe.enMarche(), aVerifier = groupe.alertes();
        List<String> etats = new ArrayList<>();
        if (marche > 0) etats.add(marche + " en marche");
        if (aVerifier > 0) etats.add(aVerifier + " à vérifier");
        String resume =
                etats.isEmpty()
                        ? groupe.machines().size() + " emplacements"
                        : String.join(" · ", etats);
        Theme.texte(g, font, resume, tx, ly + 15, fin - 6 - tx, Theme.TEXTE_SECONDAIRE);
        if (survol)
            Theme.infobulle(
                    font,
                    Component.literal(
                            groupe.nom()
                                    + " · "
                                    + quantite
                                    + "\n"
                                    + resume
                                    + (ouvert
                                            ? "\nCliquer pour replier les emplacements."
                                            : "\nCliquer pour voir chaque emplacement et son état.")));
    }

    private void dessinerMachine(
            GuiGraphics g,
            Font font,
            JsonObject m,
            boolean enfant,
            int ly,
            int fin,
            boolean survol) {
        int gauche = x + (enfant ? RETRAIT : 0), tx = gauche + 28;
        carte(g, gauche, ly, fin, survol, Hud.couleur(Pont.texte(m, "couleur")));
        g.renderItem(Pont.objet(Pont.texte(m, "item")), gauche + 6, ly + 5);
        Theme.texte(
                g,
                font,
                enfant ? position(m) : Pont.texte(m, "nom"),
                tx,
                ly + 4,
                fin - 6 - tx,
                Theme.TEXTE);
        String etat = Pont.texte(m, "etat");
        long vu = (long) Pont.nombre(m, "vu", 0);
        long age = System.currentTimeMillis() - vu;
        boolean ancien =
                m.has("actuel")
                        ? !Pont.vrai(m, "actuel") || age < 0 || age >= 15000
                        : vu == 0 || age > 30000;
        Theme.texte(
                g,
                font,
                (ancien ? "Dernier relevé : " : "") + etat,
                tx,
                ly + 15,
                fin - 6 - tx,
                ancien
                        ? Theme.TEXTE_SECONDAIRE
                        : 0xFF000000 | Hud.couleur(Pont.texte(m, "couleur")));
        if (survol)
            Theme.infobulle(
                    font,
                    Component.literal(
                            Pont.texte(m, "nom")
                                    + "\n"
                                    + etat
                                    + "\n"
                                    + position(m)
                                    + "\n"
                                    + Pont.texte(m, "dimension")
                                    + (ancien
                                            ? "\nRelevé ancien : approche-toi pour actualiser."
                                            : "\nObservation récente.")));
    }

    public boolean cliquer(double mx, double my, int bouton) {
        if (!actif) return false;
        if (barre.cliquer(mx, my, bouton)) return true;
        if (bouton != 0 || mx < x || mx >= x + largeur) return false;
        for (var zone : zones)
            if (my >= zone.y() && my < zone.y() + zone.h()) {
                if (groupes.basculer(zone.type(), System.currentTimeMillis()))
                    recherche.setFocused(false);
                return true;
            }
        return false;
    }

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0 || mx < x || mx >= x + largeur || my < haut || my >= bas)
            return false;
        barre.defiler(delta);
        return true;
    }

    public boolean glisser(double my) {
        return actif && barre.glisser(my);
    }

    public void relacher() {
        barre.relacher();
    }
}
