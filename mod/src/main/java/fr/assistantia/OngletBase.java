package fr.assistantia;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/** Machines lisibles, recherche locale et défilement sans dépendance à une requête IA. */
public final class OngletBase {
    private final Minecraft mc = Minecraft.getInstance();
    private EditBox recherche;
    private Button filtre;
    private static String texte = "";
    private static int categorie;
    private int decalage, x, y, largeur, bas, lignes, total;
    private long dernierClic;
    private boolean actif;
    private JsonArray source;
    private static final Set<String> GROUPES_OUVERTS = new HashSet<>();
    private final GroupesMachines<JsonObject> groupes = new GroupesMachines<>(GROUPES_OUVERTS);
    private final List<ZoneGroupe> zones = new ArrayList<>();

    private record ZoneGroupe(String type, int y) {}

    private String filtreTexte = "";
    private int filtreCategorie = -1, alertes;

    public void init(Consumer<AbstractWidget> ajout) {
        recherche = new EditBox(mc.font, 0, 0, 100, 18, Component.literal("Chercher une machine"));
        recherche.setHint(Component.literal("Chercher une machine…"));
        recherche.setValue(texte);
        recherche.setResponder(
                s -> {
                    texte = s;
                    decalage = 0;
                });
        ajout.accept(recherche);
        filtre =
                Button.builder(
                                Component.literal("Toutes"),
                                b -> {
                                    if (System.currentTimeMillis() - dernierClic < 650) return;
                                    dernierClic = System.currentTimeMillis();
                                    categorie = (categorie + 1) % 3;
                                    decalage = 0;
                                })
                        .bounds(0, 0, 100, 18)
                        .build();
        ajout.accept(filtre);
        visible(false);
    }

    public void visible(boolean valeur) {
        actif = valeur;
        recherche.visible = filtre.visible = valeur;
        if (!valeur) recherche.setFocused(false);
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
        this.y = y;
        this.largeur = largeur;
        this.bas = bas;
        int l = Math.min(120, largeur / 3);
        recherche.setX(x);
        recherche.setY(y);
        recherche.setWidth(largeur - l - 6);
        filtre.setX(x + largeur - l);
        filtre.setY(y);
        filtre.setWidth(l);
        filtre.setMessage(
                Component.literal(new String[] {"Toutes", "À vérifier", "En marche"}[categorie]));
        filtre.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Filtrer : toutes les machines, alertes / attente ou machines en marche.")));
        actualiser(vue);
        zones.clear();
        String resume =
                Pont.liste(vue, "machines").size()
                        + " machines · "
                        + (int) Pont.nombre(vue, "coffres", 0)
                        + " coffres"
                        + (alertes > 0 ? " · " + alertes + " à vérifier" : "");
        g.drawString(
                mc.font, mc.font.plainSubstrByWidth(resume, largeur), x, y + 25, Hud.GRIS, false);
        int debut = y + 40;
        lignes = Math.max(0, (bas - 15 - debut) / 30);
        total = groupes.lignes().size();
        decalage = Math.min(decalage, Math.max(0, total - lignes));
        for (int i = 0; i < lignes && decalage + i < total; i++) {
            var ligne = groupes.lignes().get(decalage + i);
            int ly = debut + i * 30;
            boolean survol = mx >= x && mx < x + largeur && my >= ly && my < ly + 28;
            if (ligne.entete()) {
                dessinerGroupe(g, ligne.groupe(), ly, survol, mx, my);
            } else {
                dessinerMachine(
                        g,
                        ligne.machine().valeur(),
                        ligne.groupe().machines().size() > 1,
                        ly,
                        survol,
                        mx,
                        my);
            }
        }
        if (total == 0)
            g.drawString(
                    mc.font,
                    mc.font.plainSubstrByWidth(
                            Pont.liste(vue, "machines").isEmpty()
                                    ? "Aucune machine repérée. Approche-toi de ta base."
                                    : "Aucune machine pour ce filtre.",
                            largeur),
                    x,
                    debut + 4,
                    Hud.GRIS,
                    false);
        String statut =
                (total == 0 || lignes == 0
                                ? "0"
                                : (decalage + 1) + "–" + Math.min(total, decalage + lignes))
                        + " / "
                        + total
                        + " lignes · "
                        + (Pont.vrai(vue, "serveur")
                                ? "liaison serveur active"
                                : "derniers relevés conservés");
        g.drawString(
                mc.font, mc.font.plainSubstrByWidth(statut, largeur), x, bas - 10, Hud.GRIS, false);
        if (my >= bas - 14 && my < bas && mx >= x && mx < x + largeur) {
            var joueurs = Pont.liste(vue, "joueurs");
            String detail =
                    statut
                            + (joueurs.isEmpty()
                                    ? ""
                                    : (Pont.vrai(vue, "serveur")
                                                    ? "\nJoueurs :\n"
                                                    : "\nDernières positions connues :\n")
                                            + String.join(
                                                    "\n",
                                                    java.util.stream.StreamSupport.stream(
                                                                    joueurs.spliterator(), false)
                                                            .map(JsonElement::getAsString)
                                                            .toList()));
            g.renderTooltip(
                    mc.font,
                    mc.font.split(Component.literal(detail), Math.min(largeur, 320)),
                    mx,
                    my);
        }
    }

    private static String position(JsonObject m) {
        return "X "
                + (int) Pont.nombre(m, "x", 0)
                + " · Y "
                + (int) Pont.nombre(m, "y", 0)
                + " · Z "
                + (int) Pont.nombre(m, "z", 0);
    }

    private void dessinerGroupe(
            GuiGraphics g,
            GroupesMachines.Groupe<JsonObject> groupe,
            int ly,
            boolean survol,
            int mx,
            int my) {
        zones.add(new ZoneGroupe(groupe.type(), ly));
        var representant = groupe.machines().get(0).valeur();
        boolean ouvert = groupes.ouvert(groupe.type());
        g.fill(x, ly, x + largeur, ly + 28, survol ? 0x80404A53 : 0x50303840);
        g.fill(
                x,
                ly,
                x + 2,
                ly + 28,
                0xFF000000 | Hud.couleur(Pont.texte(representant, "couleur")));
        g.renderItem(Pont.objet(Pont.texte(representant, "item")), x + 6, ly + 6);
        String quantite = "× " + groupe.machines().size();
        int lQuantite = mc.font.width(quantite);
        g.drawString(mc.font, quantite, x + largeur - lQuantite - 6, ly + 3, Hud.OR, false);
        g.drawString(
                mc.font,
                mc.font.plainSubstrByWidth(
                        (ouvert ? "− " : "+ ") + groupe.nom(), largeur - lQuantite - 40),
                x + 28,
                ly + 3,
                Hud.BLANC,
                false);
        int marche = groupe.enMarche(), aVerifier = groupe.alertes();
        List<String> etats = new ArrayList<>();
        if (marche > 0) etats.add(marche + " en marche");
        if (aVerifier > 0) etats.add(aVerifier + " à vérifier");
        String resume =
                etats.isEmpty()
                        ? groupe.machines().size() + " emplacements"
                        : String.join(" · ", etats);
        g.drawString(
                mc.font,
                mc.font.plainSubstrByWidth(resume, largeur - 32),
                x + 28,
                ly + 16,
                Hud.GRIS,
                false);
        if (survol) {
            String detail =
                    groupe.nom()
                            + " · "
                            + quantite
                            + "\n"
                            + resume
                            + (ouvert
                                    ? "\nCliquer pour replier les emplacements."
                                    : "\nCliquer pour voir chaque emplacement et son état.");
            g.renderTooltip(
                    mc.font,
                    mc.font.split(Component.literal(detail), Math.min(largeur, 320)),
                    mx,
                    my);
        }
    }

    private void dessinerMachine(
            GuiGraphics g, JsonObject m, boolean enfant, int ly, boolean survol, int mx, int my) {
        int gauche = x + (enfant ? 10 : 0), espace = largeur - (enfant ? 10 : 0);
        g.fill(gauche, ly, x + largeur, ly + 28, survol ? 0x60404A53 : 0x30303840);
        g.fill(gauche, ly, gauche + 2, ly + 28, 0xFF000000 | Hud.couleur(Pont.texte(m, "couleur")));
        g.renderItem(Pont.objet(Pont.texte(m, "item")), gauche + 6, ly + 6);
        g.drawString(
                mc.font,
                mc.font.plainSubstrByWidth(
                        enfant ? position(m) : Pont.texte(m, "nom"), espace - 32),
                gauche + 28,
                ly + 3,
                Hud.BLANC,
                false);
        String etat = Pont.texte(m, "etat");
        long vu = (long) Pont.nombre(m, "vu", 0);
        long age = System.currentTimeMillis() - vu;
        boolean ancien =
                m.has("actuel")
                        ? !Pont.vrai(m, "actuel") || age < 0 || age >= 15000
                        : vu == 0 || age > 30000;
        g.drawString(
                mc.font,
                mc.font.plainSubstrByWidth((ancien ? "Dernier relevé : " : "") + etat, espace - 32),
                gauche + 28,
                ly + 16,
                ancien ? Hud.GRIS : Hud.couleur(Pont.texte(m, "couleur")),
                false);
        if (survol) {
            String detail =
                    Pont.texte(m, "nom")
                            + "\n"
                            + etat
                            + "\n"
                            + position(m)
                            + "\n"
                            + Pont.texte(m, "dimension")
                            + (ancien
                                    ? "\nRelevé ancien : approche-toi pour actualiser."
                                    : "\nObservation récente.");
            g.renderTooltip(
                    mc.font,
                    mc.font.split(Component.literal(detail), Math.min(largeur, 320)),
                    mx,
                    my);
        }
    }

    public boolean clic(double mx, double my, int bouton) {
        if (!actif || bouton != 0 || mx < x || mx >= x + largeur) return false;
        for (var zone : zones)
            if (my >= zone.y() && my < zone.y() + 28) {
                if (groupes.basculer(zone.type(), System.currentTimeMillis()))
                    recherche.setFocused(false);
                return true;
            }
        return false;
    }

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0 || mx < x || mx >= x + largeur || my < y + 40 || my >= bas - 15)
            return false;
        decalage =
                Math.max(0, Math.min(Math.max(0, total - lignes), decalage + (delta < 0 ? 1 : -1)));
        return true;
    }
}
