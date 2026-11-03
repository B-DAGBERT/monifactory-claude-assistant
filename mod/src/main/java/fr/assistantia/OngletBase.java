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
        var machines = new ArrayList<JsonObject>();
        int alertes = 0;
        for (var element : Pont.liste(vue, "machines")) {
            var m = element.getAsJsonObject();
            String couleur = Pont.texte(m, "couleur");
            boolean alerte = couleur.equals("rouge") || couleur.equals("jaune");
            if (alerte) alertes++;
            if ((categorie == 1 && !alerte) || (categorie == 2 && !couleur.equals("vert")))
                continue;
            if (normaliser(Pont.texte(m, "nom")).contains(normaliser(texte))) machines.add(m);
        }
        machines.sort(
                Comparator.<JsonObject>comparingInt(
                                m ->
                                        switch (Pont.texte(m, "couleur")) {
                                            case "rouge" -> 0;
                                            case "jaune" -> 1;
                                            case "vert" -> 2;
                                            default -> 3;
                                        })
                        .thenComparing(m -> Pont.texte(m, "nom")));
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
        total = machines.size();
        decalage = Math.min(decalage, Math.max(0, total - lignes));
        for (int i = 0; i < lignes && decalage + i < total; i++) {
            var m = machines.get(decalage + i);
            int ly = debut + i * 30;
            boolean survol = mx >= x && mx < x + largeur && my >= ly && my < ly + 28;
            g.fill(x, ly, x + largeur, ly + 28, survol ? 0x60404A53 : 0x30303840);
            g.fill(x, ly, x + 2, ly + 28, 0xFF000000 | Hud.couleur(Pont.texte(m, "couleur")));
            g.renderItem(Pont.objet(Pont.texte(m, "item")), x + 6, ly + 6);
            g.drawString(
                    mc.font,
                    mc.font.plainSubstrByWidth(Pont.texte(m, "nom"), largeur - 32),
                    x + 28,
                    ly + 3,
                    Hud.BLANC,
                    false);
            String etat = Pont.texte(m, "etat");
            long vu = (long) Pont.nombre(m, "vu", 0);
            boolean ancien = vu == 0 || System.currentTimeMillis() - vu > 30000;
            g.drawString(
                    mc.font,
                    mc.font.plainSubstrByWidth(
                            (ancien ? "Dernier relevé : " : "") + etat, largeur - 32),
                    x + 28,
                    ly + 16,
                    ancien ? Hud.GRIS : Hud.couleur(Pont.texte(m, "couleur")),
                    false);
            if (survol) {
                String detail =
                        Pont.texte(m, "nom")
                                + "\n"
                                + etat
                                + "\nX "
                                + (int) Pont.nombre(m, "x", 0)
                                + " · Y "
                                + (int) Pont.nombre(m, "y", 0)
                                + " · Z "
                                + (int) Pont.nombre(m, "z", 0)
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
                (total == 0 ? "0" : (decalage + 1) + "–" + Math.min(total, decalage + lignes))
                        + " / "
                        + total
                        + " · "
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
                                    : "\nJoueurs :\n"
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

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0 || mx < x || mx >= x + largeur || my < y + 40 || my >= bas - 15)
            return false;
        decalage =
                Math.max(0, Math.min(Math.max(0, total - lignes), decalage + (delta < 0 ? 1 : -1)));
        return true;
    }
}
