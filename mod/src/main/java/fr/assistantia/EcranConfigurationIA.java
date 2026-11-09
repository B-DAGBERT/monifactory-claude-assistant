package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Choix du modèle local de la discussion et des fiches : le moteur actif reste géré par
 * l'assistant. Les valeurs « plan » de la configuration sont renvoyées telles quelles.
 */
public final class EcranConfigurationIA extends Screen {
    private static final String NOTE = "Appliquer prend effet au prochain échange.";
    private final Screen parent;
    // Même après fermeture de Y, conserver l'accusé attendu et le brouillon local.
    private static final EtatConfigurationIA etat = new EtatConfigurationIA();
    private BoutonTheme question;
    private List<CatalogueModeles.Modele> modeles = List.of();
    private String sourceCatalogue = "";
    private BoutonTheme effortQuestion, appliquer, defauts, actualiser, retour;
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    /** Carte du modèle, texte d'état et ligne de note, fixés par {@link #init()}. */
    private int hautCarte, hautStatut, ligneNote;

    private boolean actualiserPlace;

    public EcranConfigurationIA(Screen parent) {
        super(Component.literal("Modèles IA"));
        this.parent = parent;
    }

    private BoutonTheme bouton(String titre, Button.OnPress action) {
        return addRenderableWidget(BoutonTheme.creer(titre, action));
    }

    @Override
    protected void init() {
        fenetre = Theme.Fenetre.centree(width, height, 500, 260);
        int x = fenetre.contenuX(), l = fenetre.contenuLargeur();
        hautCarte = fenetre.contenuY();
        // Dans la carte : le titre de la section puis la rangée modèle · réflexion.
        int rangee = hautCarte + 5 + Theme.LIGNE + 2, interieur = l - 10;
        int reflexion = Math.min(124, interieur / 2 - Theme.ECART);
        question =
                bouton("Choisir…", b -> choisirModele())
                        .zone(x + 5, rangee, interieur - reflexion - Theme.ECART);
        effortQuestion =
                bouton("Réflexion", b -> cycler())
                        .zone(x + 5 + interieur - reflexion, rangee, reflexion);
        hautStatut = rangee + Theme.BOUTON + 5 + 2 * Theme.ECART;
        ligneNote = fenetre.basBoutons() - Theme.ECART - Theme.LIGNE + 1;
        appliquer = bouton("Appliquer", b -> appliquer()).genre(Theme.Genre.PRINCIPAL);
        defauts =
                bouton(
                                "Défauts",
                                b -> {
                                    etat.defauts(System.currentTimeMillis());
                                    remplir();
                                })
                        .infobulle(
                                "Remettre les valeurs par défaut dans le brouillon. Appliquer reste nécessaire.");
        actualiser =
                bouton(
                                "Actualiser",
                                b -> {
                                    etat.actualiser(System.currentTimeMillis());
                                    remplir();
                                })
                        .infobulle(
                                "Relire la version de configuration courante sans remplacer ton brouillon.");
        actualiser.visible = false;
        retour = bouton("Retour", b -> onClose());
        actualiserPlace = !actualiser.visible;
        placerBoutons();
        rafraichir();
        remplir();
        actualiserBoutons();
    }

    /**
     * Rangée du bas : Appliquer, Défauts et, quand la configuration a changé ailleurs, Actualiser ;
     * Retour à droite.
     */
    private void placerBoutons() {
        if (actualiserPlace == actualiser.visible) return;
        actualiserPlace = actualiser.visible;
        int x = fenetre.contenuX(), droite = fenetre.contenuDroite(), y = fenetre.basBoutons();
        int lRetour = Theme.largeurBouton(font, "Retour", 120);
        retour.zone(droite - lRetour, y, lRetour);
        int place = droite - lRetour - Theme.MARGE - x;
        int lAppliquer =
                Math.max(
                        Theme.largeurBouton(font, "Appliquer", 120),
                        Theme.largeurBouton(font, "En cours…", 120));
        int lDefauts = Theme.largeurBouton(font, "Défauts", 120);
        int lActualiser = Theme.largeurBouton(font, "Actualiser", 120);
        int voulu =
                lAppliquer
                        + Theme.ECART
                        + lDefauts
                        + (actualiser.visible ? Theme.ECART + lActualiser : 0);
        if (voulu > place) { // fenêtre étroite : la rangée se partage la place restante
            int n = actualiser.visible ? 3 : 2;
            lAppliquer = lDefauts = lActualiser = (place - (n - 1) * Theme.ECART) / n;
        }
        appliquer.zone(x, y, lAppliquer);
        defauts.zone(x + lAppliquer + Theme.ECART, y, lDefauts);
        actualiser.zone(x + lAppliquer + lDefauts + 2 * Theme.ECART, y, lActualiser);
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
        // La flèche reste visible : seul le nom est coupé, complet dans l'infobulle.
        question.texte(Theme.couper(font, nom, question.getWidth() - 6 - font.width(" ▾")) + " ▾");
        question.infobulle("Choisir un modèle\n" + nom + "\n" + id);
        appliquer.active = etat.peutAppliquer(Pont.assistantActif(), maintenant);
        appliquer.texte(etat.attente() ? "En cours…" : "Appliquer");
        defauts.active = edition;
        actualiser.visible = etat.perimee();
        actualiser.active = edition && Pont.assistantActif() && etat.configuration().modifiable();
        placerBoutons();
        String effort = valeurs == null ? "" : valeurs.effortQuestion();
        String reflexion = EtatConfigurationIA.nomEffort(effort);
        effortQuestion.active = edition && !etat.configuration().efforts().isEmpty();
        effortQuestion.texte(reflexion);
        effortQuestion.infobulle(
                "Réflexion : " + reflexion + " (" + effort + ") · cliquer pour changer");
    }

    @Override
    public void tick() {
        rafraichir();
        actualiserBoutons();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        var conf = etat.configuration();
        fenetre.dessiner(
                g,
                font,
                title.getString(),
                "Moteur actif : " + (conf == null ? "indisponible" : conf.nom()),
                mx,
                my);
        int x = fenetre.contenuX(), l = fenetre.contenuLargeur();
        int hautRangee = question.getY();
        Theme.carte(g, x, hautCarte, l, hautRangee + Theme.BOUTON + 5 - hautCarte, false);
        int titreY = hautCarte + 6;
        Theme.texte(
                g,
                font,
                "Discussion et fiches",
                x + 5,
                titreY,
                effortQuestion.getX() - Theme.ECART - x - 5,
                Theme.TEXTE,
                mx,
                my);
        Theme.texte(
                g,
                font,
                "Réflexion",
                effortQuestion.getX(),
                titreY,
                effortQuestion.getWidth(),
                Theme.TEXTE_SECONDAIRE,
                mx,
                my);
        String statut = etat.message(Pont.assistantActif());
        int limite = (actualiser.visible ? fenetre.basBoutons() : ligneNote) - Theme.ECART;
        int lignes = Math.max(1, (limite - hautStatut) / Theme.LIGNE);
        Theme.paragraphe(
                g,
                font,
                statut,
                x,
                hautStatut,
                l,
                etat.attente() ? Theme.JAUNE : Theme.TEXTE_SECONDAIRE,
                lignes);
        if (Theme.lignes(font, statut, l) > lignes
                && mx >= x
                && mx < x + l
                && my >= hautStatut
                && my < hautStatut + lignes * Theme.LIGNE)
            Theme.infobulle(font, Component.literal(statut));
        if (!actualiser.visible)
            Theme.texte(g, font, NOTE, x, ligneNote, l, Theme.TEXTE_SECONDAIRE, mx, my);
        super.render(g, mx, my, delta);
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
