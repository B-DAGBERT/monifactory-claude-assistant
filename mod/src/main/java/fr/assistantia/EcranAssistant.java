package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le tableau de bord de l'assistant (touche Y), en onglets : Base (les machines vues par le serveur
 * et la lecture Production de l'arbre EMI), Discussion, Filons et Réglages (chaque option avec son
 * état). Une question s'écrit en bas, quel que soit l'onglet.
 */
public class EcranAssistant extends Screen {
    private static final int FOND = 0xD00D1013, MARGE = 10, LIGNE = 10;

    /**
     * Les onglets, dans l'ordre d'affichage : un onglet ajouté ici (même en premier) ne décale
     * aucun indice ailleurs. Réglages reste le dernier, tout à droite.
     */
    private enum Onglet {
        BASE("Base"),
        DISCUSSION("Discussion"),
        FILONS("Filons"),
        REGLAGES("Réglages");

        final String titre;

        Onglet(String titre) {
            this.titre = titre;
        }
    }

    private static final Onglet[] ONGLETS = Onglet.values();
    private static Onglet onglet = Onglet.DISCUSSION; // gardé d'une ouverture à l'autre

    /** Zone cliquable d'un réglage (le changer). */
    private record Zone(int x, int y, int l, int h, String reglage) {}

    private final List<Zone> zones = new ArrayList<>();
    private final List<Button> boutonsOnglets = new ArrayList<>();

    /**
     * Réglages changés à l'instant, affichés tout de suite sans attendre la réponse de l'assistant.
     */
    private static final Map<String, Boolean> REGLAGES_EN_ATTENTE = new HashMap<>();

    private OngletFilons filons;
    private OngletBase base;
    private Button masquerMaj, modelesIA, diagnostic, production;
    private EcranConfigurationIA configurationIA;
    private EcranProduction productionEcran;
    private static final AvisMiseAJour AVIS_MAJ =
            new AvisMiseAJour(
                    net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR
                            .get()
                            .resolve("assistantia-avis-masque.txt"));
    private EditBox saisie;
    private Button envoyer;
    private static final EtatDiscussion ETAT_DISCUSSION = new EtatDiscussion();
    private final OngletDiscussion discussion = new OngletDiscussion(ETAT_DISCUSSION);
    private Button relancer; // visible seulement quand l'assistant ne tourne pas
    private boolean lanceurInconnu;
    private final BarreDefilement defilementReglages = new BarreDefilement();

    public EcranAssistant() {
        super(Component.literal("Assistant IA"));
    }

    @Override
    protected void init() {
        int x = MARGE;
        boutonsOnglets.clear();
        int largeurTotale =
                java.util.Arrays.stream(ONGLETS).mapToInt(t -> this.font.width(t.titre) + 20).sum();
        for (Onglet choix : ONGLETS) {
            int largeur =
                    Math.min(
                            this.font.width(choix.titre) + 16,
                            Math.max(
                                    30,
                                    (this.font.width(choix.titre) + 20)
                                                    * (this.width - 2 * MARGE)
                                                    / largeurTotale
                                            - 4));
            Button bouton =
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(
                                                    this.font.plainSubstrByWidth(
                                                            choix.titre, largeur - 8)),
                                            b -> onglet = choix)
                                    .bounds(x, MARGE, largeur, 18)
                                    .build());
            bouton.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal(choix.titre)));
            boutonsOnglets.add(bouton);
            x += largeur + 4;
        }
        masquerMaj =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Masquer"),
                                        b -> {
                                            JsonObject vue = Pont.partie("ecran");
                                            try {
                                                AVIS_MAJ.masquer(
                                                        Pont.texte(vue, "mise_a_jour_id"),
                                                        Pont.texte(vue, "mise_a_jour"));
                                            } catch (java.io.IOException e) {
                                                com.mojang.logging.LogUtils.getLogger()
                                                        .warn(
                                                                "Avis masqué pour cette session seulement",
                                                                e);
                                            }
                                            masquerMaj.visible = false;
                                        })
                                .bounds(this.width - MARGE - 54, MARGE + 22, 54, 16)
                                .build());
        masquerMaj.visible = false;
        int bas = this.height - MARGE - 18;
        saisie =
                new EditBox(
                        this.font,
                        MARGE,
                        bas,
                        this.width - 2 * MARGE - 70,
                        18,
                        Component.literal("Question"));
        saisie.setMaxLength(2000);
        saisie.setValue(ETAT_DISCUSSION.brouillon());
        saisie.setResponder(ETAT_DISCUSSION::brouillon);
        saisie.setHint(Component.literal("Une question pour l’assistant ? Écris-la puis Entrée"));
        addRenderableWidget(saisie);
        envoyer =
                addRenderableWidget(
                        Button.builder(Component.literal("Envoyer"), b -> envoyerQuestion())
                                .bounds(this.width - MARGE - 64, bas - 1, 64, 20)
                                .build());
        relancer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Relancer l'assistant"),
                                        b -> lanceurInconnu = !Pont.relancer())
                                .bounds(this.width - MARGE - 120, MARGE, 120, 18)
                                .build());
        modelesIA =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Modèles IA…"),
                                        b -> {
                                            if (configurationIA == null)
                                                configurationIA = new EcranConfigurationIA(this);
                                            this.minecraft.setScreen(configurationIA);
                                        })
                                .bounds(MARGE, MARGE + 26, this.width - 2 * MARGE, 20)
                                .build());
        diagnostic =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Diagnostic…"),
                                        b -> this.minecraft.setScreen(new EcranDiagnostic(this)))
                                .bounds(MARGE, MARGE + 26, 100, 20)
                                .build());
        diagnostic.visible = false;
        production =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Production…"),
                                        b -> {
                                            if (productionEcran == null)
                                                productionEcran = new EcranProduction(this);
                                            this.minecraft.setScreen(productionEcran);
                                        })
                                .bounds(MARGE, MARGE + 26, 104, 18)
                                .build());
        production.visible = false;
        production.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(
                                "Lire le plan de l’arbre EMI ou ses diagnostics, sans rien modifier dans le jeu.")));
        modelesIA.visible = false;
        modelesIA.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(
                                "Choisir le modèle et la réflexion pour la discussion et les fiches.")));
        base = new OngletBase();
        base.init(w -> addRenderableWidget(w));
        filons = new OngletFilons();
        filons.init(w -> addRenderableWidget(w), this.height);
        discussion.init(this.font, w -> addRenderableWidget(w));
        setInitialFocus(saisie);
    }

    private void envoyerQuestion() {
        JsonObject vue = ecran();
        if (!vue.has("question_recue")
                || !ETAT_DISCUSSION.peutEnvoyer(Pont.assistantActif(), Pont.vrai(vue, "occupe")))
            return;
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "question");
        demande.addProperty("texte", saisie.getValue().trim());
        if (!Pont.envoyer(demande)) {
            ETAT_DISCUSSION.echecEnvoi();
            return;
        }
        ETAT_DISCUSSION.envoye(demande.get("n").getAsLong());
        onglet = Onglet.DISCUSSION;
    }

    private void changerReglage(String cle, boolean valeur) {
        if (Fullbright.CLE.equals(cle)) {
            Fullbright.regler(valeur);
            return;
        }
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "reglage");
        demande.addProperty("cle", cle);
        demande.addProperty("valeur", valeur);
        Pont.envoyer(demande);
        REGLAGES_EN_ATTENTE.put(cle, valeur);
    }

    private static JsonObject ecran() {
        return Pont.partie("ecran");
    }

    @Override
    public void tick() {
        saisie.tick();
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if ((touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER)
                && saisie.isFocused()) {
            envoyerQuestion();
            return true;
        }
        if (onglet == Onglet.DISCUSSION && discussion.touche(touche)) return true;
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        if (onglet == Onglet.REGLAGES && defilementReglages.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.DISCUSSION && discussion.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.FILONS && filons.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.BASE && base.clic(x, y, bouton)) return true;
        for (Zone z : zones) {
            if (x < z.x()
                    || x >= z.x() + z.l()
                    || y < z.y()
                    || y >= z.y() + z.h()
                    || this.minecraft == null
                    || this.minecraft.player == null) {
                continue;
            }
            if (bouton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
                changerReglage(z.reglage(), !valeurReglage(z.reglage(), false));
            return true;
        }
        return super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return switch (onglet) {
            case REGLAGES -> {
                defilementReglages.defiler(delta);
                yield true;
            }
            case DISCUSSION -> discussion.defiler(x, y, delta);
            case FILONS -> filons.defiler(x, y, delta);
            case BASE -> base.defiler(x, y, delta);
        };
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        if (onglet == Onglet.REGLAGES && defilementReglages.glisser(y)) return true;
        if (onglet == Onglet.DISCUSSION && discussion.glisser(y)) return true;
        return super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        discussion.relacher();
        defilementReglages.relacher();
        return super.mouseReleased(x, y, bouton);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int sourisX, int sourisY, float partialTick) {
        renderBackground(g);
        zones.clear();
        modelesIA.visible = onglet == Onglet.REGLAGES;
        diagnostic.visible = onglet == Onglet.REGLAGES;
        filons.visible(onglet == Onglet.FILONS);
        base.visible(onglet == Onglet.BASE);
        for (int i = 0; i < boutonsOnglets.size(); i++) {
            boutonsOnglets.get(i).active = ONGLETS[i] != onglet; // l'onglet ouvert est grisé
        }
        JsonObject ecran = ecran();
        discussion.visible(onglet == Onglet.DISCUSSION);
        discussion.actualiser(ecran);
        if (!saisie.getValue().equals(ETAT_DISCUSSION.brouillon()))
            saisie.setValue(ETAT_DISCUSSION.brouillon());
        saisie.setEditable(!ETAT_DISCUSSION.enAttente());
        envoyer.active =
                ecran.has("question_recue")
                        && ETAT_DISCUSSION.peutEnvoyer(
                                Pont.assistantActif(), Pont.vrai(ecran, "occupe"));
        envoyer.setMessage(Component.literal(ETAT_DISCUSSION.enAttente() ? "Envoi…" : "Envoyer"));
        int haut = MARGE + 26, bas = this.height - MARGE - 24;
        int hautMiseAJour = haut;
        String miseAJour = Pont.texte(ecran, "mise_a_jour");
        masquerMaj.visible = AVIS_MAJ.visible(Pont.texte(ecran, "mise_a_jour_id"), miseAJour);
        if (masquerMaj.visible) {
            g.fill(MARGE - 4, haut - 4, this.width - MARGE + 4, haut + 12, 0xD0353020);
            g.drawString(
                    this.font,
                    this.font.plainSubstrByWidth(miseAJour, this.width - 2 * MARGE - 62),
                    MARGE,
                    haut,
                    Hud.JAUNE,
                    false);
            haut += 18;
        }
        g.fill(MARGE - 4, haut - 4, this.width - MARGE + 4, bas, FOND);
        boolean actif = Pont.assistantActif();
        relancer.visible = !actif && !Pont.relanceEnCours() && onglet != Onglet.FILONS;
        relancer.setY(onglet == Onglet.DISCUSSION ? haut + 12 : haut);
        if (onglet != Onglet.FILONS && onglet != Onglet.DISCUSSION && actif) {
            lanceurInconnu = false;
            String statut = Pont.texte(ecran, "statut");
            String droite =
                    !statut.isEmpty() ? statut : Pont.vrai(ecran, "serveur") ? "serveur ✔" : "";
            int debut =
                    boutonsOnglets.isEmpty()
                            ? MARGE
                            : boutonsOnglets.get(boutonsOnglets.size() - 1).getX()
                                    + boutonsOnglets.get(boutonsOnglets.size() - 1).getWidth()
                                    + 8;
            droite = this.font.plainSubstrByWidth(droite, Math.max(0, this.width - MARGE - debut));
            g.drawString(
                    this.font,
                    droite,
                    this.width - MARGE - this.font.width(droite),
                    MARGE + 5,
                    Hud.GRIS,
                    false);
        } else if (onglet != Onglet.DISCUSSION
                && onglet != Onglet.FILONS) { // l'assistant ne tourne pas : rien ne répondra tant
            // qu'il
            // n'est pas
            // relancé
            String arret =
                    Pont.relanceEnCours()
                            ? "L'assistant démarre…"
                            : lanceurInconnu
                                    ? "Lance le lanceur de l’assistant (Mac.command ou .bat)"
                                    : "Assistant arrêté";
            int fin = relancer.visible ? relancer.getX() - 6 : this.width - MARGE;
            g.drawString(
                    this.font,
                    this.font.plainSubstrByWidth(arret, Math.max(0, fin - MARGE)),
                    MARGE,
                    haut + 5,
                    Pont.relanceEnCours() ? Hud.GRIS : Hud.ROUGE,
                    false);
            haut += 24;
        }
        production.visible = onglet == Onglet.BASE;
        if (production.visible) {
            production.setX(MARGE);
            production.setY(haut);
            production.setWidth(this.width - 2 * MARGE);
            haut += 24;
        }
        int largeur = this.width - 2 * MARGE;
        switch (onglet) {
            case FILONS -> filons.render(g, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case BASE -> base.render(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case REGLAGES -> reglages(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case DISCUSSION -> discussion.render(
                    g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
        }
        super.render(g, sourisX, sourisY, partialTick);
        if (masquerMaj.visible
                && sourisX < masquerMaj.getX() - 2
                && sourisY >= hautMiseAJour - 4
                && sourisY < hautMiseAJour + 12)
            g.renderTooltip(
                    this.font,
                    this.font.split(Component.literal(miseAJour), Math.min(320, this.width - 30)),
                    sourisX,
                    sourisY);
    }

    // ---------- Onglet Réglages ----------

    /**
     * La valeur affichée d'un réglage : celle qu'on vient de choisir, en attendant que l'assistant
     * la confirme.
     */
    private boolean valeurReglage(String cle, boolean parDefaut) {
        if (Fullbright.CLE.equals(cle)) return Fullbright.actif();
        for (JsonElement e : Pont.liste(ecran(), "reglages")) {
            JsonObject r = e.getAsJsonObject();
            if (cle.equals(Pont.texte(r, "cle"))) {
                boolean valeur = Pont.vrai(r, "valeur");
                Boolean choisi = REGLAGES_EN_ATTENTE.get(cle);
                if (choisi != null && choisi == valeur) {
                    REGLAGES_EN_ATTENTE.remove(cle); // confirmé
                }
                return choisi != null ? choisi : valeur;
            }
        }
        return parDefaut;
    }

    private void reglages(
            GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas, int sx, int sy) {
        modelesIA.setX(x);
        modelesIA.setY(y);
        modelesIA.setWidth((largeur - 6) / 2);
        diagnostic.setX(x + (largeur + 6) / 2);
        diagnostic.setY(y);
        diagnostic.setWidth((largeur - 6) / 2);
        y += 28;
        JsonArray reglages = new JsonArray();
        JsonObject lumiere = new JsonObject();
        lumiere.addProperty("cle", Fullbright.CLE);
        lumiere.addProperty("nom", "Luminosité maximale (Fullbright)");
        lumiere.addProperty("description", Fullbright.description());
        reglages.add(lumiere);
        for (JsonElement reglage : Pont.liste(ecran, "reglages")) reglages.add(reglage);
        int bouton = 96,
                droite = x + largeur - 12,
                texte = Math.max(30, largeur - bouton - 24),
                debut = y;
        int contenu = 0;
        for (JsonElement e : reglages) {
            int lignes =
                    this.font
                            .split(
                                    Component.literal(
                                            Pont.texte(e.getAsJsonObject(), "description")),
                                    texte)
                            .size();
            contenu += Math.max(22, 12 + lignes * LIGNE) + 4;
        }
        defilementReglages.regler(x + largeur - 5, debut, bas - debut, contenu);
        y -= defilementReglages.position();
        g.enableScissor(x, debut, x + largeur, bas);
        for (JsonElement e : reglages) {
            JsonObject r = e.getAsJsonObject();
            String cle = Pont.texte(r, "cle");
            boolean active = valeurReglage(cle, false);
            List<FormattedCharSequence> description =
                    this.font.split(Component.literal(Pont.texte(r, "description")), texte);
            int hauteur = Math.max(22, 12 + description.size() * LIGNE) + 4;
            if (y + hauteur > debut && y < bas) {
                g.drawString(
                        this.font,
                        this.font.plainSubstrByWidth(Pont.texte(r, "nom"), texte),
                        x,
                        y,
                        Hud.BLANC,
                        false);
                int yd = y + 11;
                for (FormattedCharSequence ligne : description) {
                    g.drawString(this.font, ligne, x, yd, Hud.GRIS, false);
                    yd += LIGNE;
                }
                int bx = droite - bouton, by = y + (hauteur - 4 - 18) / 2;
                boolean survol =
                        sx >= bx
                                && sx < bx + bouton
                                && sy >= Math.max(debut, by)
                                && sy < Math.min(bas, by + 18);
                g.fill(
                        bx,
                        by,
                        bx + bouton,
                        by + 18,
                        active
                                ? (survol ? 0xFF3FA845 : 0xFF2E8B36)
                                : (survol ? 0xFF5A5A5A : 0xFF3C3C3C));
                g.renderOutline(bx, by, bouton, 18, survol ? 0xFFFFFFFF : 0x60FFFFFF);
                String etat = active ? "✔ Activé" : "✘ Désactivé";
                g.drawString(
                        this.font,
                        etat,
                        bx + (bouton - this.font.width(etat)) / 2,
                        by + 5,
                        active ? 0xFFFFFF : 0xBBBBBB,
                        false);
                int clicY = Math.max(debut, by), clicBas = Math.min(bas, by + 18);
                if (clicBas > clicY) zones.add(new Zone(bx, clicY, bouton, clicBas - clicY, cle));
                g.fill(x, y + hauteur - 3, droite, y + hauteur - 2, 0x18FFFFFF);
            }
            y += hauteur;
        }
        g.disableScissor();
        if (defilementReglages.visible()) {
            int bx = defilementReglages.x(), by = defilementReglages.curseur();
            g.fill(bx, debut, bx + 4, bas, 0x50333333);
            g.fill(bx, by, bx + 4, by + defilementReglages.taille(), 0xBBAAAAAA);
        }
    }
}
