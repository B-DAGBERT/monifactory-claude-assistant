package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Choix des modèles locaux : le moteur actif reste géré par l'assistant. */
public final class EcranConfigurationIA extends Screen {
    private final Screen parent;
    // Même après fermeture de Y, conserver l'accusé attendu et le brouillon local.
    private static final EtatConfigurationIA etat = new EtatConfigurationIA();
    private EditBox question, plan;
    private Button effortQuestion, effortPlan, appliquer, defauts, actualiser;
    private int gauche, largeur;
    private boolean remplit;

    public EcranConfigurationIA(Screen parent) {
        super(Component.literal("Modèles IA"));
        this.parent = parent;
    }

    private Button bouton(String titre, int x, int y, int l, Button.OnPress action) {
        return addRenderableWidget(
                Button.builder(Component.literal(titre), action).bounds(x, y, l, 20).build());
    }

    @Override
    protected void init() {
        largeur = Math.min(500, width - 24);
        gauche = (width - largeur) / 2;
        int reflexion = Math.min(124, largeur / 2 - 6);
        question = champ("Modèle pour les discussions et les fiches", 64, largeur - reflexion - 6);
        plan = champ("Modèle pour les plans et les objectifs", 116, largeur - reflexion - 6);
        effortQuestion =
                bouton(
                        "Réflexion",
                        gauche + largeur - reflexion,
                        64,
                        reflexion,
                        b -> cycler(false));
        effortPlan =
                bouton(
                        "Réflexion",
                        gauche + largeur - reflexion,
                        116,
                        reflexion,
                        b -> cycler(true));
        int tiers = (largeur - 12) / 3;
        appliquer = bouton("Appliquer", gauche, height - 28, tiers, b -> appliquer());
        defauts =
                bouton(
                        "Défauts",
                        gauche + tiers + 6,
                        height - 28,
                        tiers,
                        b -> {
                            etat.defauts(System.currentTimeMillis());
                            remplir();
                        });
        defauts.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Remettre les valeurs par défaut dans le brouillon. Appliquer reste nécessaire.")));
        bouton(
                "Retour",
                gauche + 2 * (tiers + 6),
                height - 28,
                largeur - 2 * (tiers + 6),
                b -> onClose());
        actualiser =
                bouton(
                        "Actualiser",
                        gauche + largeur - 94,
                        height - 54,
                        94,
                        b -> {
                            etat.actualiser(System.currentTimeMillis());
                            remplir();
                        });
        actualiser.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Relire la version de configuration courante sans remplacer ton brouillon.")));
        actualiser.visible = false;
        rafraichir();
        remplir();
        actualiserBoutons();
        setInitialFocus(question);
    }

    private EditBox champ(String titre, int y, int l) {
        EditBox champ =
                addRenderableWidget(new EditBox(font, gauche, y, l, 20, Component.literal(titre)));
        champ.setMaxLength(128);
        champ.setHint(Component.literal("Nom du modèle"));
        champ.setTooltip(Tooltip.create(Component.literal(titre)));
        champ.setResponder(
                s -> {
                    if (remplit || question == null || plan == null || etat.brouillon() == null)
                        return;
                    var v = etat.brouillon();
                    etat.modifier(
                            new EtatConfigurationIA.Valeurs(
                                    question.getValue(),
                                    v.effortQuestion(),
                                    plan.getValue(),
                                    v.effortPlan()));
                    actualiserBoutons();
                });
        return champ;
    }

    private static String texte(JsonObject objet, String cle) {
        return objet != null
                        && objet.has(cle)
                        && objet.get(cle).isJsonPrimitive()
                        && objet.getAsJsonPrimitive(cle).isString()
                ? objet.get(cle).getAsString()
                : "";
    }

    private static JsonObject objet(JsonObject parent, String cle) {
        return parent != null && parent.has(cle) && parent.get(cle).isJsonObject()
                ? parent.getAsJsonObject(cle)
                : new JsonObject();
    }

    private static EtatConfigurationIA.Valeurs valeurs(JsonObject j) {
        return new EtatConfigurationIA.Valeurs(
                texte(j, "model_question"),
                texte(j, "effort_question"),
                texte(j, "model_plan"),
                texte(j, "effort_plan"));
    }

    private void rafraichir() {
        Pont.rafraichir();
        JsonObject j = objet(Pont.partie("ecran"), "configuration_ia");
        EtatConfigurationIA.Configuration configuration = null;
        EtatConfigurationIA.Reponse reponse = null;
        try {
            String moteur = texte(j, "moteur"), revision = texte(j, "revision");
            if (!moteur.isEmpty() && !revision.isEmpty()) {
                List<String> efforts = new ArrayList<>();
                for (var effort : Pont.liste(j, "efforts"))
                    if (effort.isJsonPrimitive()
                            && effort.getAsJsonPrimitive().isString()
                            && List.of("low", "medium", "high", "xhigh", "max")
                                    .contains(effort.getAsString())
                            && !efforts.contains(effort.getAsString()))
                        efforts.add(effort.getAsString());
                configuration =
                        new EtatConfigurationIA.Configuration(
                                moteur,
                                texte(j, "nom"),
                                revision,
                                Pont.vrai(j, "modifiable"),
                                valeurs(objet(j, "valeurs")),
                                valeurs(objet(j, "defauts")),
                                efforts,
                                texte(j, "message"));
                JsonObject ack = objet(j, "reponse");
                if (ack.has("n")
                        && ack.get("n").isJsonPrimitive()
                        && ack.getAsJsonPrimitive("n").isNumber())
                    reponse =
                            new EtatConfigurationIA.Reponse(
                                    ack.get("n").getAsBigDecimal().longValueExact(),
                                    texte(ack, "etat"),
                                    texte(ack, "message"));
            }
        } catch (RuntimeException ignored) {
            configuration = null;
        }
        boolean initial = etat.brouillon() == null;
        etat.observer(configuration, reponse, System.currentTimeMillis());
        if (initial && etat.brouillon() != null) remplir();
    }

    private void remplir() {
        var valeurs = etat.brouillon();
        if (valeurs == null || question == null || plan == null) return;
        remplit = true;
        try {
            question.setValue(valeurs.modelQuestion());
            plan.setValue(valeurs.modelPlan());
        } finally {
            remplit = false;
        }
        actualiserBoutons();
    }

    private void cycler(boolean plans) {
        var conf = etat.configuration();
        if (conf == null
                || conf.efforts().isEmpty()
                || !etat.interaction(System.currentTimeMillis())) return;
        var v = etat.brouillon();
        String ancien = plans ? v.effortPlan() : v.effortQuestion();
        String suivant =
                conf.efforts().get((conf.efforts().indexOf(ancien) + 1) % conf.efforts().size());
        etat.modifier(
                new EtatConfigurationIA.Valeurs(
                        v.modelQuestion(),
                        plans ? v.effortQuestion() : suivant,
                        v.modelPlan(),
                        plans ? suivant : v.effortPlan()));
        actualiserBoutons();
    }

    private void appliquer() {
        long maintenant = System.currentTimeMillis();
        rafraichir();
        if (!etat.peutAppliquer(Pont.assistantActif(), maintenant)) return;
        var v = etat.normalisees();
        var conf = etat.chargee();
        JsonObject valeurs = new JsonObject();
        valeurs.addProperty("model_question", v.modelQuestion());
        valeurs.addProperty("effort_question", v.effortQuestion());
        valeurs.addProperty("model_plan", v.modelPlan());
        valeurs.addProperty("effort_plan", v.effortPlan());
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "configuration_ia");
        demande.addProperty("moteur", conf.moteur());
        demande.addProperty("revision", conf.revision());
        demande.add("valeurs", valeurs);
        if (Pont.envoyerConfigurationIa(demande))
            etat.envoye(demande.get("n").getAsLong(), maintenant);
        else etat.echecEnvoi(maintenant);
        actualiserBoutons();
    }

    private void actualiserBoutons() {
        if (appliquer == null) return;
        long maintenant = System.currentTimeMillis();
        boolean edition = etat.disponible() && !etat.attente();
        question.setEditable(edition);
        plan.setEditable(edition);
        question.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Modèle pour les discussions et les fiches\n"
                                        + question.getValue())));
        plan.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Modèle pour les plans et les objectifs\n" + plan.getValue())));
        appliquer.active = etat.peutAppliquer(Pont.assistantActif(), maintenant);
        appliquer.setMessage(Component.literal(etat.attente() ? "En cours…" : "Appliquer"));
        defauts.active = edition;
        actualiser.visible = etat.perimee();
        actualiser.active = edition && Pont.assistantActif() && etat.configuration().modifiable();
        var v = etat.brouillon();
        for (int i = 0; i < 2; i++) {
            Button bouton = i == 0 ? effortQuestion : effortPlan;
            String effort = v == null ? "" : i == 0 ? v.effortQuestion() : v.effortPlan();
            String nom = EtatConfigurationIA.nomEffort(effort);
            bouton.active = edition && !etat.configuration().efforts().isEmpty();
            bouton.setMessage(
                    Component.literal(font.plainSubstrByWidth(nom, bouton.getWidth() - 8)));
            bouton.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    "Réflexion : "
                                            + nom
                                            + " ("
                                            + effort
                                            + ") · cliquer pour changer")));
        }
    }

    @Override
    public void tick() {
        question.tick();
        plan.tick();
        rafraichir();
        actualiserBoutons();
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if ((touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER)
                && (question.isFocused() || plan.isFocused())) {
            appliquer();
            return true;
        }
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        var conf = etat.configuration();
        String moteur = "Moteur actif : " + (conf == null ? "indisponible" : conf.nom());
        g.drawCenteredString(
                font, font.plainSubstrByWidth(moteur, largeur), width / 2, 29, Hud.GRIS);
        String[] titres = {"Discussion et fiches", "Plans et objectifs"};
        for (int i = 0; i < 2; i++) {
            int y = 50 + i * 52;
            g.fill(gauche - 4, y - 5, gauche + largeur + 4, y + 40, 0xA0202730);
            g.drawString(font, titres[i], gauche, y, Hud.BLANC, false);
            g.drawString(font, "Réflexion", effortQuestion.getX(), y, Hud.GRIS, false);
        }
        String statut = etat.message(Pont.assistantActif());
        int y = 150, limite = height - 62;
        for (var ligne : font.split(Component.literal(statut), largeur)) {
            if (y + 10 > limite) break;
            g.drawString(font, ligne, gauche, y, etat.attente() ? Hud.JAUNE : Hud.GRIS, false);
            y += 11;
        }
        if (!actualiser.visible)
            g.drawString(
                    font,
                    font.plainSubstrByWidth("Appliquer prend effet au prochain échange.", largeur),
                    gauche,
                    height - 48,
                    Hud.GRIS,
                    false);
        super.render(g, mx, my, delta);
        if (mx >= gauche && mx < gauche + largeur && my >= 148 && my < limite)
            g.renderTooltip(
                    font, font.split(Component.literal(statut), Math.min(320, width - 24)), mx, my);
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
