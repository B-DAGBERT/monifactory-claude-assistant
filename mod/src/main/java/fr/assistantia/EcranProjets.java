package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Une sélection locale, des actions explicites et une confirmation avant toute suppression. */
public final class EcranProjets extends Screen {
    private final Screen parent;
    private final List<Button> lignes = new ArrayList<>();
    private final List<JsonObject> visibles = new ArrayList<>();
    // Une fermeture ou un redimensionnement ne doit pas permettre de doubler une demande.
    private static JsonObject demande;
    private static long envoye;
    private EditBox nom;
    private Button ouvrir, renommer, supprimer, creer, precedent, suivant, valider, mode, retry;
    private String selection = "",
            edition = "",
            cible = "",
            nomCible = "",
            brouillon = "",
            erreur = "";
    private boolean complet = true;
    private int page, parPage, gauche, largeur;
    private long ouvert, dernierClic;

    public EcranProjets(Screen parent) {
        super(Component.literal("Mes projets"));
        this.parent = parent;
    }

    private JsonObject vue() {
        return Pont.partie("ecran");
    }

    private JsonObject projets() {
        JsonObject e = vue();
        return e.has("projets") && e.get("projets").isJsonObject()
                ? e.getAsJsonObject("projets")
                : new JsonObject();
    }

    private List<JsonObject> liste() {
        List<JsonObject> result = new ArrayList<>();
        for (var item : Pont.liste(projets(), "liste"))
            if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return result;
    }

    private JsonObject selectionnee() {
        return liste().stream()
                .filter(p -> Pont.texte(p, "id").equals(selection))
                .findFirst()
                .orElse(new JsonObject());
    }

    private Button bouton(String t, int x, int y, int l, Button.OnPress action) {
        return addRenderableWidget(
                Button.builder(Component.literal(t), action).bounds(x, y, l, 20).build());
    }

    @Override
    protected void init() {
        ouvert = System.currentTimeMillis();
        largeur = Math.min(520, width - 24);
        gauche = (width - largeur) / 2;
        parPage = Math.max(1, (height - 158) / 34);
        lignes.clear();
        nom = null;
        if (selectionnee().size() == 0) selection = Pont.texte(projets(), "actif");
        if (edition.isEmpty()) {
            for (int i = 0; i < parPage; i++) {
                final int ligne = i;
                lignes.add(
                        bouton(
                                "",
                                gauche,
                                48 + i * 34,
                                largeur,
                                b -> {
                                    if (demande == null && ligne < visibles.size()) {
                                        selection = Pont.texte(visibles.get(ligne), "id");
                                        actualiser();
                                    }
                                }));
            }
            precedent =
                    bouton(
                            "Précédent",
                            gauche,
                            height - 102,
                            90,
                            b -> {
                                page--;
                                actualiser();
                            });
            suivant =
                    bouton(
                            "Suivant",
                            gauche + largeur - 90,
                            height - 102,
                            90,
                            b -> {
                                page++;
                                actualiser();
                            });
            int tiers = (largeur - 12) / 3;
            ouvrir =
                    bouton(
                            "Ouvrir",
                            gauche,
                            height - 76,
                            tiers,
                            b -> envoyer("choisir", selection));
            renommer =
                    bouton(
                            "Renommer",
                            gauche + tiers + 6,
                            height - 76,
                            tiers,
                            b -> editer("renommer"));
            supprimer =
                    bouton(
                            "Supprimer…",
                            gauche + 2 * (tiers + 6),
                            height - 76,
                            largeur - 2 * (tiers + 6),
                            b -> editer("supprimer"));
            creer =
                    bouton(
                            "Nouveau projet",
                            gauche,
                            height - 28,
                            (largeur - 6) / 2,
                            b -> editer("creer"));
        } else {
            if (!edition.equals("supprimer")) {
                nom =
                        addRenderableWidget(
                                new EditBox(
                                        font,
                                        gauche,
                                        66,
                                        largeur,
                                        20,
                                        Component.literal("Nom du projet")));
                nom.setMaxLength(100);
                nom.setHint(Component.literal("Nom / but : passer en LV, préparer une ferme…"));
                nom.setValue(brouillon);
                nom.setResponder(
                        s -> {
                            brouillon = s;
                            actualiser();
                        });
                setInitialFocus(nom);
            }
            if (edition.equals("creer"))
                mode =
                        bouton(
                                "",
                                gauche,
                                94,
                                largeur,
                                b -> {
                                    complet = !complet;
                                    actualiser();
                                });
            valider =
                    bouton(
                            edition.equals("supprimer")
                                    ? "Supprimer"
                                    : edition.equals("creer") ? "Créer" : "Enregistrer",
                            gauche,
                            height - 28,
                            (largeur - 6) / 2,
                            b -> envoyer(edition, cible));
        }
        retry =
                bouton(
                        "Réessayer",
                        gauche,
                        height - 52,
                        largeur,
                        b -> {
                            if (demande != null
                                    && System.currentTimeMillis() - envoye > 12000
                                    && Pont.assistantActif()) expedier();
                        });
        bouton(
                edition.isEmpty() ? "Retour" : "Annuler",
                gauche + (largeur + 6) / 2,
                height - 28,
                (largeur - 6) / 2,
                b -> onClose());
        actualiser();
    }

    private boolean disponible() {
        return demande == null
                && Pont.assistantActif()
                && !Pont.vrai(vue(), "occupe")
                && !Pont.texte(projets(), "actif").isEmpty()
                && System.currentTimeMillis() - ouvert >= 350
                && System.currentTimeMillis() - dernierClic >= 650;
    }

    private void editer(String action) {
        if (!disponible()) return;
        JsonObject p = selectionnee();
        edition = action;
        cible = action.equals("creer") ? UUID.randomUUID().toString() : Pont.texte(p, "id");
        nomCible = action.equals("creer") ? "" : Pont.texte(p, "nom");
        brouillon = nomCible;
        erreur = "";
        init(minecraft, width, height);
    }

    private void envoyer(String action, String id) {
        if (!disponible()) return;
        if ((action.equals("creer") || action.equals("renommer")) && brouillon.trim().isEmpty())
            return;
        demande = new JsonObject();
        demande.addProperty("type", "projet");
        demande.addProperty("action", action);
        demande.addProperty("token", UUID.randomUUID().toString());
        demande.addProperty("id", id);
        demande.addProperty("origine", Pont.texte(projets(), "actif"));
        demande.addProperty("nom", brouillon.trim());
        demande.addProperty("mode", complet ? "plan" : "objectif");
        if (action.equals("supprimer")) demande.addProperty("confirmation", nomCible);
        dernierClic = System.currentTimeMillis();
        expedier();
        actualiser();
    }

    private void expedier() {
        envoye = System.currentTimeMillis();
        if (!Pont.envoyer(demande)) {
            erreur = "Envoi impossible. Vérifie l’assistant puis réessaie.";
            demande = null;
        } else erreur = "";
    }

    private void actualiser() {
        boolean libre = disponible();
        if (edition.isEmpty()) {
            var projets = liste();
            int pages = Math.max(1, (projets.size() + parPage - 1) / parPage);
            page = Math.max(0, Math.min(page, pages - 1));
            if (selectionnee().size() == 0) selection = Pont.texte(projets(), "actif");
            visibles.clear();
            for (int i = 0; i < lignes.size(); i++) {
                Button b = lignes.get(i);
                int index = page * parPage + i;
                b.visible = index < projets.size();
                b.active = demande == null;
                if (!b.visible) continue;
                var p = projets.get(index);
                visibles.add(p);
                String nom =
                        (selection.equals(Pont.texte(p, "id")) ? "▶ " : "") + Pont.texte(p, "nom");
                b.setMessage(Component.literal(font.plainSubstrByWidth(nom, largeur - 12)));
                b.setTooltip(
                        Tooltip.create(
                                Component.literal(
                                        Pont.texte(p, "nom")
                                                + "\n"
                                                + Pont.texte(p, "objectif")
                                                + "\n"
                                                + statut(p))));
            }
            precedent.active = demande == null && page > 0;
            suivant.active = demande == null && page < pages - 1;
            boolean existe = selectionnee().size() > 0;
            boolean actif = Pont.vrai(selectionnee(), "actif");
            ouvrir.setMessage(Component.literal(actif ? "Ouvert" : "Ouvrir"));
            ouvrir.active = libre && existe && !actif;
            renommer.active = supprimer.active = libre && existe;
            creer.active = libre;
        } else {
            valider.active = libre && (edition.equals("supprimer") || !brouillon.trim().isEmpty());
            if (nom != null) nom.setEditable(demande == null);
            if (edition.equals("creer")) {
                mode.setMessage(
                        Component.literal(complet ? "Plusieurs objectifs" : "Un seul objectif"));
                mode.active = demande == null;
            }
        }
        retry.visible = demande != null && System.currentTimeMillis() - envoye > 12000;
        retry.active = retry.visible && Pont.assistantActif();
    }

    private String statut(JsonObject p) {
        String progres =
                p.has("total") && p.has("faits") && p.get("total").getAsInt() > 0
                        ? " · "
                                + p.get("faits").getAsInt()
                                + "/"
                                + p.get("total").getAsInt()
                                + " objectifs"
                        : "";
        return (Pont.vrai(p, "pause") ? "EN PAUSE" : Pont.vrai(p, "actif") ? "Actif" : "Sauvegardé")
                + progres;
    }

    @Override
    public void tick() {
        if (nom != null) nom.tick();
        Pont.rafraichir();
        var p = projets();
        if (demande != null && p.has("retour") && p.get("retour").isJsonObject()) {
            var retour = p.getAsJsonObject("retour");
            if (Pont.texte(demande, "token").equals(Pont.texte(retour, "token"))) {
                String action = Pont.texte(demande, "action");
                demande = null;
                if (Pont.vrai(retour, "ok")) {
                    if (action.equals("choisir") || action.equals("creer")) {
                        minecraft.setScreen(parent);
                        return;
                    }
                    edition = "";
                    erreur = action.equals("supprimer") ? "Projet supprimé." : "Projet renommé.";
                    init(minecraft, width, height);
                } else erreur = Pont.texte(retour, "message");
            }
        }
        actualiser();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        String titre =
                edition.equals("supprimer")
                        ? "Supprimer ce projet ?"
                        : edition.equals("renommer")
                                ? "Renommer le projet"
                                : edition.equals("creer") ? "Nouveau projet" : "Mes projets";
        g.drawCenteredString(font, titre, width / 2, 12, Hud.OR);
        String message =
                demande != null
                        ? "Enregistrement…"
                        : !erreur.isEmpty()
                                ? erreur
                                : !Pont.assistantActif()
                                        ? "Lance l’assistant pour gérer tes projets."
                                        : Pont.vrai(vue(), "occupe")
                                                ? "L’assistant travaille…"
                                                : "";
        g.drawCenteredString(
                font, font.plainSubstrByWidth(message, largeur), width / 2, 30, Hud.GRIS);
        if (edition.isEmpty()) {
            for (int i = 0; i < visibles.size(); i++) {
                var p = visibles.get(i);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(statut(p), largeur - 8),
                        gauche + 4,
                        70 + i * 34,
                        Pont.vrai(p, "pause") ? Hud.JAUNE : Hud.GRIS,
                        false);
            }
            g.drawCenteredString(
                    font,
                    (page + 1) + " / " + Math.max(1, (liste().size() + parPage - 1) / parPage),
                    width / 2,
                    height - 96,
                    Hud.GRIS);
            if (!retry.visible)
                g.drawString(
                        font,
                        font.plainSubstrByWidth(Pont.texte(selectionnee(), "objectif"), largeur),
                        gauche,
                        height - 48,
                        Hud.GRIS,
                        false);
        } else if (edition.equals("supprimer")) {
            g.drawString(
                    font, font.plainSubstrByWidth(nomCible, largeur), gauche, 58, Hud.JAUNE, false);
            String suite = "Le projet et sa progression seront supprimés.";
            if (cible.equals(Pont.texte(projets(), "actif"))) {
                var autre =
                        liste().stream()
                                .filter(p -> !cible.equals(Pont.texte(p, "id")))
                                .findFirst();
                suite +=
                        autre.isPresent()
                                ? " Projet ouvert ensuite : "
                                        + Pont.texte(autre.get(), "nom")
                                        + " ("
                                        + statut(autre.get())
                                        + ")."
                                : " Un projet vide sera créé, sans appel IA.";
            }
            int y = 80;
            for (var ligne : font.split(Component.literal(suite), largeur)) {
                if (y > height - 65) break;
                g.drawString(font, ligne, gauche, y, Hud.GRIS, false);
                y += 12;
            }
        } else {
            g.drawString(font, "Nom / but du projet", gauche, 50, Hud.BLANC, false);
            g.drawString(
                    font,
                    font.plainSubstrByWidth(
                            edition.equals("creer")
                                    ? "L’assistant préparera ce nouveau projet."
                                    : "La progression et la pause sont conservées.",
                            largeur),
                    gauche,
                    125,
                    Hud.GRIS,
                    false);
        }
        super.render(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        if (minecraft == null) return;
        if (!edition.isEmpty()) {
            edition = "";
            init(minecraft, width, height);
        } else minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
