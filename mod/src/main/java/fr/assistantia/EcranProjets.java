package fr.assistantia;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Choix local d'un projet ; seule sa création demande une génération à l'assistant. */
public final class EcranProjets extends Screen {
    private final Screen parent;
    private EditBox nom;
    private Button mode, creer, renommer, precedent, suivant, retry;
    private final List<Button> choix = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private boolean plan = true;
    private int page, parPage, gauche, largeur;
    private long ouvert, envoye, dernierClic;
    private JsonObject demande;
    private String erreur = "", brouillon = "";

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

    private Button bouton(String t, int x, int y, int l, Button.OnPress action) {
        return addRenderableWidget(
                Button.builder(Component.literal(t), action).bounds(x, y, l, 20).build());
    }

    @Override
    protected void init() {
        ouvert = System.currentTimeMillis();
        largeur = Math.min(520, width - 20);
        gauche = (width - largeur) / 2;
        parPage = Math.max(1, (height - 156) / 32);
        choix.clear();
        for (int i = 0; i < parPage; i++) {
            final int ligne = i;
            choix.add(
                    bouton(
                            "Choisir",
                            gauche + largeur - 70,
                            48 + i * 32 + 3,
                            70,
                            b -> {
                                if (ligne < ids.size()) envoyer("choisir", ids.get(ligne));
                            }));
        }
        precedent = bouton("Précédent", gauche, height - 100, 90, b -> tourner(-1));
        suivant = bouton("Suivant", gauche + largeur - 90, height - 100, 90, b -> tourner(1));
        nom =
                addRenderableWidget(
                        new EditBox(
                                font,
                                gauche,
                                height - 76,
                                largeur,
                                20,
                                Component.literal("Nom ou but du projet")));
        nom.setMaxLength(100);
        nom.setHint(Component.literal("Nom / but : passer en LV, préparer une ferme…"));
        nom.setValue(brouillon);
        nom.setResponder(s -> brouillon = s);
        int tiers = (largeur - 12) / 3;
        mode =
                bouton(
                        "Plan complet",
                        gauche,
                        height - 52,
                        tiers,
                        b -> {
                            if (!clicDisponible()) return;
                            plan = !plan;
                            dernierClic = System.currentTimeMillis();
                        });
        creer =
                bouton(
                        "Créer",
                        gauche + tiers + 6,
                        height - 52,
                        tiers,
                        b -> envoyer("creer", UUID.randomUUID().toString()));
        renommer =
                bouton(
                        "Renommer",
                        gauche + 2 * (tiers + 6),
                        height - 52,
                        largeur - 2 * (tiers + 6),
                        b -> envoyer("renommer", Pont.texte(projets(), "actif")));
        creer.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Créer un projet distinct et faire préparer son objectif ou son plan par ton assistant. Le projet actuel est conservé.")));
        renommer.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Renommer le projet actif avec le texte saisi, sans appel IA.")));
        retry =
                bouton(
                        "Réessayer",
                        gauche,
                        height - 28,
                        (largeur - 6) / 2,
                        b -> {
                            if (demande != null && System.currentTimeMillis() - envoye > 12000) {
                                Pont.envoyer(demande);
                                envoye = System.currentTimeMillis();
                            }
                        });
        bouton(
                "Retour",
                gauche + (largeur + 6) / 2,
                height - 28,
                (largeur - 6) / 2,
                b -> onClose());
    }

    private boolean clicDisponible() {
        return demande == null
                && System.currentTimeMillis() - ouvert >= 350
                && System.currentTimeMillis() - dernierClic >= 650;
    }

    private void tourner(int delta) {
        if (clicDisponible()) {
            page = Math.max(0, page + delta);
            dernierClic = System.currentTimeMillis();
        }
    }

    private void envoyer(String action, String id) {
        if (!clicDisponible() || !Pont.assistantActif() || Pont.vrai(vue(), "occupe")) return;
        if (!action.equals("choisir") && nom.getValue().trim().isEmpty()) {
            erreur = "Saisis un nom ou un but.";
            return;
        }
        JsonObject p = projets();
        if (Pont.texte(p, "actif").isEmpty()) {
            erreur = "Redémarre l'assistant pour charger les projets.";
            return;
        }
        demande = new JsonObject();
        demande.addProperty("type", "projet");
        demande.addProperty("action", action);
        demande.addProperty("token", UUID.randomUUID().toString());
        demande.addProperty("id", id);
        demande.addProperty("origine", Pont.texte(p, "actif"));
        demande.addProperty("nom", nom.getValue().trim());
        demande.addProperty("mode", plan ? "plan" : "objectif");
        envoye = dernierClic = System.currentTimeMillis();
        erreur = "";
        Pont.envoyer(demande);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        JsonObject p = projets();
        if (demande != null && p.has("retour") && p.get("retour").isJsonObject()) {
            JsonObject retour = p.getAsJsonObject("retour");
            if (Pont.texte(demande, "token").equals(Pont.texte(retour, "token"))) {
                if (Pont.vrai(retour, "ok")) {
                    onClose();
                    return;
                }
                erreur = Pont.texte(retour, "message");
                demande = null;
            }
        }
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        String etat =
                demande != null
                        ? "Enregistrement…"
                        : !erreur.isEmpty()
                                ? erreur
                                : !Pont.assistantActif()
                                        ? "Lance l'assistant pour gérer tes projets."
                                        : Pont.vrai(vue(), "occupe")
                                                ? "L’assistant travaille ; sélection disponible ensuite."
                                                : "Un projet actif · les autres restent sauvegardés";
        g.drawCenteredString(font, font.plainSubstrByWidth(etat, largeur), width / 2, 30, Hud.GRIS);
        var liste = Pont.liste(p, "liste");
        int pages = Math.max(1, (liste.size() + parPage - 1) / parPage);
        page = Math.min(page, pages - 1);
        boolean disponible =
                clicDisponible()
                        && Pont.assistantActif()
                        && !Pont.vrai(vue(), "occupe")
                        && !Pont.texte(p, "actif").isEmpty();
        ids.clear();
        for (int i = 0; i < choix.size(); i++) {
            int index = page * parPage + i;
            var b = choix.get(i);
            b.visible = index < liste.size();
            if (!b.visible) continue;
            var projet = liste.get(index).getAsJsonObject();
            ids.add(Pont.texte(projet, "id"));
            int y = 48 + i * 32;
            boolean actif = Pont.vrai(projet, "actif");
            g.fill(gauche, y, gauche + largeur, y + 30, actif ? 0x80445E76 : 0x40303840);
            g.drawString(
                    font,
                    font.plainSubstrByWidth(Pont.texte(projet, "nom"), largeur - 80),
                    gauche + 4,
                    y + 3,
                    actif ? Hud.OR : Hud.BLANC,
                    false);
            String sous =
                    (Pont.vrai(projet, "pause") ? "En pause · " : "")
                            + Pont.texte(projet, "objectif");
            g.drawString(
                    font,
                    font.plainSubstrByWidth(sous, largeur - 80),
                    gauche + 4,
                    y + 17,
                    Hud.GRIS,
                    false);
            b.setMessage(Component.literal(actif ? "Actif" : "Choisir"));
            b.active = disponible && !actif;
            b.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    Pont.texte(projet, "nom")
                                            + "\n"
                                            + sous
                                            + "\n"
                                            + (int) Pont.nombre(projet, "faits", 0)
                                            + " / "
                                            + (int) Pont.nombre(projet, "total", 0)
                                            + " objectifs terminés")));
        }
        mode.setMessage(Component.literal(plan ? "Plan complet" : "Objectif seul"));
        mode.active = disponible;
        creer.active = renommer.active = disponible && !nom.getValue().trim().isEmpty();
        nom.setEditable(demande == null);
        precedent.active = disponible && page > 0;
        suivant.active = disponible && page + 1 < pages;
        retry.visible = demande != null && System.currentTimeMillis() - envoye > 12000;
        g.drawCenteredString(font, (page + 1) + " / " + pages, width / 2, height - 94, Hud.GRIS);
        super.render(g, mx, my, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
