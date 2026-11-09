package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Choix du modèle local de la discussion et des fiches : le moteur actif reste géré par
 * l'assistant. Les valeurs « plan » de la configuration sont renvoyées telles quelles.
 */
public final class EcranConfigurationIA extends Screen {
    private final Screen parent;
    // Même après fermeture de Y, conserver l'accusé attendu et le brouillon local.
    private static final EtatConfigurationIA etat = new EtatConfigurationIA();
    private Button question;
    private List<CatalogueModeles.Modele> modeles = List.of();
    private String sourceCatalogue = "";
    private Button effortQuestion, appliquer, defauts, actualiser;
    private int gauche, largeur;

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
        question = bouton("Choisir…", gauche, 64, largeur - reflexion - 6, b -> choisirModele());
        effortQuestion =
                bouton("Réflexion", gauche + largeur - reflexion, 64, reflexion, b -> cycler());
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
    }

    private void choisirModele() {
        if (!etat.disponible() || !etat.interaction(System.currentTimeMillis())) return;
        var v = etat.brouillon();
        String actuel = v.modelQuestion();
        List<CatalogueModeles.Modele> choix = new ArrayList<>(modeles);
        if (choix.stream().noneMatch(m -> m.id().equals(actuel)))
            choix.add(
                    new CatalogueModeles.Modele(
                            actuel,
                            actuel,
                            "Configuration actuelle",
                            etat.configuration().efforts()));
        minecraft.setScreen(
                new EcranChoixModele(
                        this,
                        "Modèle · Discussion",
                        choix,
                        actuel,
                        sourceCatalogue,
                        m -> {
                            // Le catalogue peut évoluer sans modifier la configuration ; conserver
                            // le brouillon.
                            var courant = etat.brouillon();
                            String effort = courant.effortQuestion();
                            if (!m.efforts().isEmpty() && !m.efforts().contains(effort))
                                effort = m.efforts().get(0);
                            etat.modifier(
                                    new EtatConfigurationIA.Valeurs(
                                            m.id(),
                                            effort,
                                            courant.modelPlan(),
                                            courant.effortPlan()));
                        }));
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
        modeles = CatalogueModeles.lire(j);
        sourceCatalogue = texte(j, "catalogue");
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
        actualiserBoutons();
    }

    private void cycler() {
        var conf = etat.configuration();
        if (conf == null
                || conf.efforts().isEmpty()
                || !etat.interaction(System.currentTimeMillis())) return;
        var v = etat.brouillon();
        var efforts = CatalogueModeles.efforts(modeles, v.modelQuestion(), conf.efforts());
        String suivant = efforts.get((efforts.indexOf(v.effortQuestion()) + 1) % efforts.size());
        etat.modifier(
                new EtatConfigurationIA.Valeurs(
                        v.modelQuestion(), suivant, v.modelPlan(), v.effortPlan()));
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
        question.active = edition;
        var valeurs = etat.brouillon();
        String id = valeurs == null ? "" : valeurs.modelQuestion();
        String nom = CatalogueModeles.nom(modeles, id);
        question.setMessage(
                Component.literal(font.plainSubstrByWidth(nom, question.getWidth() - 20) + " ▾"));
        question.setTooltip(
                Tooltip.create(Component.literal("Choisir un modèle\n" + nom + "\n" + id)));
        appliquer.active = etat.peutAppliquer(Pont.assistantActif(), maintenant);
        appliquer.setMessage(Component.literal(etat.attente() ? "En cours…" : "Appliquer"));
        defauts.active = edition;
        actualiser.visible = etat.perimee();
        actualiser.active = edition && Pont.assistantActif() && etat.configuration().modifiable();
        String effort = valeurs == null ? "" : valeurs.effortQuestion();
        String reflexion = EtatConfigurationIA.nomEffort(effort);
        effortQuestion.active = edition && !etat.configuration().efforts().isEmpty();
        effortQuestion.setMessage(
                Component.literal(
                        font.plainSubstrByWidth(reflexion, effortQuestion.getWidth() - 8)));
        effortQuestion.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Réflexion : "
                                        + reflexion
                                        + " ("
                                        + effort
                                        + ") · cliquer pour changer")));
    }

    @Override
    public void tick() {
        rafraichir();
        actualiserBoutons();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        var conf = etat.configuration();
        String moteur = "Moteur actif : " + (conf == null ? "indisponible" : conf.nom());
        g.drawCenteredString(
                font, font.plainSubstrByWidth(moteur, largeur), width / 2, 29, Hud.GRIS);
        g.fill(gauche - 4, 45, gauche + largeur + 4, 90, 0xA0202730);
        g.drawString(font, "Discussion et fiches", gauche, 50, Hud.BLANC, false);
        g.drawString(font, "Réflexion", effortQuestion.getX(), 50, Hud.GRIS, false);
        String statut = etat.message(Pont.assistantActif());
        int y = 98, limite = height - 62;
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
        if (mx >= gauche && mx < gauche + largeur && my >= 96 && my < limite)
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
