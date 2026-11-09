package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le tableau de bord de l'assistant (touche Y), en onglets : Recette (le plan de l'arbre choisi
 * dans EMI), Base (les machines vues par le serveur et la lecture Production de l'arbre EMI),
 * Discussion, Filons et Réglages (chaque option avec son état). Une question s'écrit en bas, quel
 * que soit l'onglet ; au-dessus, une ligne d'état dit si l'assistant est prêt, occupé ou arrêté.
 */
public class EcranAssistant extends Screen {
    /** Largeur au plus du tableau de bord, centré au-delà : les lignes restent lisibles. */
    private static final int LARGEUR_MAX = 720;

    /** Indice du champ de question, selon que l'assistant tourne ou non. */
    private static final Component
            INDICE = Component.literal("Une question pour l’assistant ? Écris-la puis Entrée"),
            INDICE_ARRETE =
                    Component.literal(
                            "L’assistant est arrêté : relance-le pour poser une question");

    private static final String ACTIVE = "✔ Activé", DESACTIVE = "✘ Désactivé";

    /**
     * Les onglets, dans l'ordre d'affichage : un onglet ajouté ici (même en premier) ne décale
     * aucun indice ailleurs. Réglages reste le dernier, tout à droite.
     */
    private enum Onglet {
        RECETTE("Recette"),
        BASE("Base"),
        DISCUSSION("Discussion"),
        FILONS("Filons"),
        REGLAGES("Réglages");

        final String titre;

        Onglet(String titre) {
            this.titre = titre;
        }

        /** Calculé dans le jeu, sans l'assistant : ni ligne d'état ni bouton Relancer. */
        boolean local() {
            return this == RECETTE || this == FILONS;
        }
    }

    private static final Onglet[] ONGLETS = Onglet.values();
    private static Onglet onglet = Onglet.RECETTE; // gardé d'une ouverture à l'autre

    /** Ce que dit la ligne d'état : un point coloré puis le texte. */
    private record Statut(String texte, int point, int couleur) {}

    /** Un interrupteur de réglage dessiné à cette image, cliquable dans la liste visible. */
    private record Interrupteur(BoutonTheme bouton, String cle) {}

    private final List<BoutonTheme> boutonsOnglets = new ArrayList<>();

    /**
     * Réglages changés à l'instant, affichés tout de suite sans attendre la réponse de l'assistant.
     */
    private static final Map<String, Boolean> REGLAGES_EN_ATTENTE = new HashMap<>();

    /** Les interrupteurs des réglages : dessinés dans la liste qui défile, hors des widgets. */
    private final Map<String, BoutonTheme> interrupteurs = new HashMap<>();

    private final List<Interrupteur> interrupteursVisibles = new ArrayList<>();
    private final Map<String, List<FormattedCharSequence>> descriptions = new HashMap<>();
    private int largeurDescriptions = -1;

    private OngletRecette recette;
    private OngletFilons filons;
    private OngletBase base;
    private BoutonTheme masquerMaj, modelesIA, diagnostic;
    private EcranConfigurationIA configurationIA;
    private EcranProduction productionEcran;
    private static final AvisMiseAJour AVIS_MAJ =
            new AvisMiseAJour(
                    net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR
                            .get()
                            .resolve("assistantia-avis-masque.txt"));
    private ChampTheme saisie;
    private BoutonTheme envoyer;
    private static final EtatDiscussion ETAT_DISCUSSION = new EtatDiscussion();
    private final OngletDiscussion discussion = new OngletDiscussion(ETAT_DISCUSSION);
    private BoutonTheme relancer; // visible seulement quand l'assistant ne tourne pas
    private boolean lanceurInconnu;
    private final BarreDefilement defilementReglages = new BarreDefilement();

    /** Cadre du tableau de bord et colonne du contenu, fixés par {@link #init()}. */
    private int gauche, haut, droite, bas, contenuX, contenuDroite, ligneQuestion, ligneStatut;

    private int largeurInterrupteur, hautReglages, basReglages;

    public EcranAssistant() {
        super(Component.literal("Assistant IA"));
    }

    @Override
    protected void init() {
        int largeur = Math.min(LARGEUR_MAX, this.width - 2 * Theme.MARGE_ECRAN);
        gauche = (this.width - largeur) / 2;
        droite = gauche + largeur;
        haut = Theme.MARGE_ECRAN;
        bas = Math.max(haut + 120, this.height - Theme.MARGE_ECRAN);
        contenuX = gauche + Theme.BARRE + Theme.MARGE;
        contenuDroite = droite - Theme.MARGE;
        int place = contenuDroite - contenuX;
        ligneQuestion = bas - Theme.MARGE - Theme.BOUTON;
        ligneStatut = ligneQuestion - Theme.ECART - Theme.BOUTON;

        boutonsOnglets.clear();
        List<String> titres = new ArrayList<>();
        for (Onglet choix : ONGLETS) titres.add(choix.titre);
        int[] largeurs = Theme.largeursRangee(this.font, titres, place, Theme.ECART);
        int x = contenuX;
        for (int i = 0; i < ONGLETS.length; i++) {
            Onglet choix = ONGLETS[i];
            boutonsOnglets.add(
                    addRenderableWidget(
                            BoutonTheme.creer(choix.titre, b -> onglet = choix)
                                    .genre(Theme.Genre.ONGLET)
                                    .zone(x, haut + Theme.ECART, largeurs[i])));
            x += largeurs[i] + Theme.ECART;
        }

        int lMasquer = Theme.largeurBouton(this.font, "Masquer", 80);
        masquerMaj =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Masquer",
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
                                .infobulle(
                                        "Masquer cet avis ; la prochaine mise à jour s’affichera.")
                                .zone(contenuDroite - lMasquer, haut + Theme.TITRE, lMasquer));
        masquerMaj.visible = false;

        int lEnvoyer =
                Math.max(
                        Theme.largeurBouton(this.font, "Envoyer", 100),
                        Theme.largeurBouton(this.font, "Envoi…", 100));
        saisie =
                ChampTheme.dans(
                        this.font,
                        contenuX,
                        ligneQuestion,
                        place - lEnvoyer - Theme.ECART,
                        Component.literal("Question"));
        saisie.setMaxLength(2000);
        saisie.setValue(ETAT_DISCUSSION.brouillon());
        saisie.setResponder(ETAT_DISCUSSION::brouillon);
        saisie.setHint(INDICE);
        addRenderableWidget(saisie);
        envoyer =
                addRenderableWidget(
                        BoutonTheme.creer("Envoyer", b -> envoyerQuestion())
                                .genre(Theme.Genre.PRINCIPAL)
                                .zone(contenuDroite - lEnvoyer, ligneQuestion, lEnvoyer));
        int lRelancer = Theme.largeurBouton(this.font, "Relancer l’assistant", place / 2);
        relancer =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Relancer l’assistant",
                                        b -> lanceurInconnu = !Pont.relancer())
                                .genre(Theme.Genre.PRINCIPAL)
                                .infobulle(
                                        "Démarre l’assistant avec son lanceur, sans quitter le jeu.")
                                .zone(contenuDroite - lRelancer, ligneStatut, lRelancer));
        relancer.visible = false;

        modelesIA =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Modèles IA…",
                                        b -> {
                                            if (configurationIA == null)
                                                configurationIA = new EcranConfigurationIA(this);
                                            this.minecraft.setScreen(configurationIA);
                                        })
                                .infobulle(
                                        "Choisir le modèle et la réflexion pour la discussion et les fiches."));
        modelesIA.visible = false;
        diagnostic =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Diagnostic…",
                                        b -> this.minecraft.setScreen(new EcranDiagnostic(this)))
                                .infobulle(
                                        "L’état local du mod et de l’assistant, sans appel IA."));
        diagnostic.visible = false;
        largeurInterrupteur =
                Math.max(
                        Theme.largeurBouton(this.font, ACTIVE, 120),
                        Theme.largeurBouton(this.font, DESACTIVE, 120));

        recette = new OngletRecette();
        recette.init(w -> addRenderableWidget(w));
        base = new OngletBase();
        base.init(w -> addRenderableWidget(w), this::ouvrirProduction);
        filons = new OngletFilons();
        filons.init(w -> addRenderableWidget(w), this.height);
        discussion.init(this.font, w -> addRenderableWidget(w));
        setInitialFocus(saisie);
    }

    private void ouvrirProduction() {
        if (productionEcran == null) productionEcran = new EcranProduction(this);
        this.minecraft.setScreen(productionEcran);
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
        base.tick();
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
        if (onglet == Onglet.RECETTE && recette.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.REGLAGES && cliquerReglages(x, y, bouton)) return true;
        if (onglet == Onglet.DISCUSSION && discussion.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.FILONS && filons.cliquer(x, y, bouton)) return true;
        if (onglet == Onglet.BASE && base.cliquer(x, y, bouton)) return true;
        return super.mouseClicked(x, y, bouton);
    }

    private boolean cliquerReglages(double x, double y, int bouton) {
        if (defilementReglages.cliquer(x, y, bouton)) return true;
        if (y < hautReglages || y >= basReglages) return false;
        for (Interrupteur i : interrupteursVisibles)
            if (i.bouton().mouseClicked(x, y, bouton)) return true;
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return switch (onglet) {
            case RECETTE -> recette.defiler(x, y, delta);
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
        if (onglet == Onglet.RECETTE && recette.glisser(y)) return true;
        if (onglet == Onglet.REGLAGES && defilementReglages.glisser(y)) return true;
        if (onglet == Onglet.DISCUSSION && discussion.glisser(y)) return true;
        if (onglet == Onglet.BASE && base.glisser(y)) return true;
        if (onglet == Onglet.FILONS && filons.glisser(y)) return true;
        return super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        recette.relacher();
        discussion.relacher();
        base.relacher();
        filons.relacher();
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
        interrupteursVisibles.clear();
        JsonObject ecran = ecran();
        boolean actif = Pont.assistantActif();
        if (actif) lanceurInconnu = false;
        for (int i = 0; i < boutonsOnglets.size(); i++)
            boutonsOnglets.get(i).choisi(ONGLETS[i] == onglet); // ouvert : souligné, cliquable
        modelesIA.visible = diagnostic.visible = onglet == Onglet.REGLAGES;
        recette.visible(onglet == Onglet.RECETTE);
        filons.visible(onglet == Onglet.FILONS);
        base.visible(onglet == Onglet.BASE);
        discussion.visible(onglet == Onglet.DISCUSSION);
        discussion.actualiser(ecran);
        if (!saisie.getValue().equals(ETAT_DISCUSSION.brouillon()))
            saisie.setValue(ETAT_DISCUSSION.brouillon());
        saisie.setEditable(!ETAT_DISCUSSION.enAttente());
        saisie.setHint(actif ? INDICE : INDICE_ARRETE);
        envoyer.active =
                ecran.has("question_recue")
                        && ETAT_DISCUSSION.peutEnvoyer(actif, Pont.vrai(ecran, "occupe"));
        envoyer.texte(ETAT_DISCUSSION.enAttente() ? "Envoi…" : "Envoyer");

        Theme.panneau(g, gauche, haut, droite - gauche, bas - haut);
        // Sous les onglets, leur trait de base continue sur toute la largeur du panneau.
        Theme.separateur(
                g,
                gauche + Theme.BARRE,
                haut + Theme.ECART + Theme.BOUTON - 1,
                droite - gauche - Theme.BARRE);
        int y = haut + Theme.TITRE;
        String miseAJour = Pont.texte(ecran, "mise_a_jour");
        masquerMaj.visible = AVIS_MAJ.visible(Pont.texte(ecran, "mise_a_jour_id"), miseAJour);
        if (masquerMaj.visible) {
            int fin = masquerMaj.getX() - Theme.ECART;
            Theme.carteChoisie(g, contenuX, y, fin - contenuX, Theme.BOUTON);
            Theme.texte(
                    g,
                    this.font,
                    miseAJour,
                    contenuX + 5,
                    y + 4,
                    fin - contenuX - 10,
                    Theme.JAUNE,
                    sourisX,
                    sourisY);
            y += Theme.BOUTON + Theme.ECART;
        }

        boolean ligneEtat = !onglet.local();
        relancer.visible = ligneEtat && !actif && !Pont.relanceEnCours();
        if (ligneEtat) dessinerStatut(g, statut(ecran, actif), sourisX, sourisY);
        int basContenu = (ligneEtat ? ligneStatut : ligneQuestion) - Theme.ECART;
        int largeur = contenuDroite - contenuX;
        switch (onglet) {
            case RECETTE -> recette.render(g, contenuX, y, largeur, basContenu, sourisX, sourisY);
            case FILONS -> filons.render(g, contenuX, y, largeur, basContenu, sourisX, sourisY);
            case BASE -> base.render(g, ecran, contenuX, y, largeur, basContenu, sourisX, sourisY);
            case REGLAGES -> reglages(
                    g, ecran, contenuX, y, largeur, basContenu, sourisX, sourisY, partialTick);
            case DISCUSSION -> discussion.render(
                    g, ecran, contenuX, y, largeur, basContenu, sourisX, sourisY);
        }
        super.render(g, sourisX, sourisY, partialTick);
    }

    // ---------- Ligne d'état ----------

    /** État de l'assistant pour les onglets qui dépendent de lui (Base, Discussion, Réglages). */
    private Statut statut(JsonObject ecran, boolean actif) {
        if (!actif) {
            if (Pont.relanceEnCours())
                return new Statut("L’assistant démarre…", Theme.JAUNE, Theme.JAUNE);
            if (lanceurInconnu)
                return new Statut(
                        "Lance le lanceur de l’assistant (Mac.command ou .bat)",
                        Theme.ROUGE,
                        Theme.ROUGE);
            return new Statut(
                    "Assistant arrêté"
                            + (ETAT_DISCUSSION.brouillon().isBlank()
                                    ? ""
                                    : " · ton brouillon est conservé"),
                    Theme.ROUGE,
                    Theme.ROUGE);
        }
        String moteur = Pont.texte(Pont.vue(), "moteur");
        if (moteur.isEmpty()) moteur = "Assistant";
        if (!ecran.has("question_recue"))
            return new Statut(
                    "Relance l’assistant pour activer la discussion", Theme.JAUNE, Theme.JAUNE);
        String statut = Pont.texte(ecran, "statut").replaceFirst("^●\\s*", "");
        if (ETAT_DISCUSSION.enAttente())
            return new Statut(anime("Transmission de ta question"), Theme.JAUNE, Theme.JAUNE);
        if (Pont.vrai(ecran, "occupe"))
            return new Statut(
                    anime(statut.isEmpty() ? moteur + " prépare la réponse" : statut),
                    Theme.JAUNE,
                    Theme.JAUNE);
        if (!statut.isEmpty()) return new Statut(statut, Theme.JAUNE, Theme.JAUNE);
        return new Statut(
                moteur
                        + " · prêt · une question à la fois"
                        + (Pont.vrai(ecran, "serveur") ? " · serveur ✔" : ""),
                Theme.VERT,
                Theme.TEXTE_SECONDAIRE);
    }

    /** Le texte suivi de points qui s'animent (sans doubler des points de suspension). */
    private static String anime(String texte) {
        String debut = texte.replaceFirst("(…|\\.+)\\s*$", "");
        return debut + ".".repeat((int) (System.currentTimeMillis() / 450 % 4));
    }

    private void dessinerStatut(GuiGraphics g, Statut statut, int sourisX, int sourisY) {
        int ty = ligneStatut + (Theme.BOUTON - 9) / 2 + 1;
        int fin = relancer.visible ? relancer.getX() - Theme.ECART : contenuDroite;
        g.drawString(this.font, "●", contenuX, ty, statut.point(), false);
        int tx = contenuX + this.font.width("● ");
        Theme.texte(
                g, this.font, statut.texte(), tx, ty, fin - tx, statut.couleur(), sourisX, sourisY);
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

    private BoutonTheme interrupteur(String cle) {
        return interrupteurs.computeIfAbsent(
                cle,
                c ->
                        BoutonTheme.creer(
                                DESACTIVE,
                                b -> {
                                    if (this.minecraft != null && this.minecraft.player != null)
                                        changerReglage(c, !valeurReglage(c, false));
                                }));
    }

    /** La description d'un réglage en lignes, recoupée seulement quand la largeur change. */
    private List<FormattedCharSequence> description(String texte, int largeur) {
        if (largeur != largeurDescriptions) {
            descriptions.clear();
            largeurDescriptions = largeur;
        }
        if (descriptions.size() > 64) descriptions.clear();
        return descriptions.computeIfAbsent(
                texte, t -> this.font.split(Component.literal(t), largeur));
    }

    private void reglages(
            GuiGraphics g,
            JsonObject ecran,
            int x,
            int y,
            int largeur,
            int bas,
            int sx,
            int sy,
            float partialTick) {
        int moitie = (largeur - Theme.ECART) / 2;
        modelesIA.zone(x, y, moitie);
        diagnostic.zone(x + largeur - moitie, y, moitie);
        hautReglages = y + Theme.BOUTON + Theme.ECART + 2;
        basReglages = Math.max(hautReglages + 1, bas);
        JsonArray reglages = new JsonArray();
        JsonObject lumiere = new JsonObject();
        lumiere.addProperty("cle", Fullbright.CLE);
        lumiere.addProperty("nom", "Luminosité maximale (Fullbright)");
        lumiere.addProperty("description", Fullbright.description());
        reglages.add(lumiere);
        for (JsonElement reglage : Pont.liste(ecran, "reglages")) reglages.add(reglage);
        // Les cartes s'arrêtent avant la colonne de la barre ; 5 px de marge dans chaque carte.
        int fin = x + largeur - Theme.DEFILEMENT - Theme.ECART, marge = 5;
        int texte = Math.max(30, fin - x - 2 * marge - largeurInterrupteur - Theme.ECART);
        int contenu = 0;
        for (JsonElement e : reglages)
            contenu +=
                    hauteurReglage(
                                    description(
                                            Pont.texte(e.getAsJsonObject(), "description"), texte))
                            + Theme.ECART;
        defilementReglages.regler(
                x + largeur - Theme.DEFILEMENT,
                hautReglages,
                basReglages - hautReglages,
                Math.max(0, contenu - Theme.ECART));
        boolean dedans = sx >= x && sx < fin && sy >= hautReglages && sy < basReglages;
        int mx = dedans ? sx : Integer.MIN_VALUE, my = dedans ? sy : Integer.MIN_VALUE;
        int cy = hautReglages - defilementReglages.position();
        g.enableScissor(x, hautReglages, x + largeur, basReglages);
        try {
            for (JsonElement e : reglages) {
                JsonObject r = e.getAsJsonObject();
                List<FormattedCharSequence> lignes =
                        description(Pont.texte(r, "description"), texte);
                int hauteur = hauteurReglage(lignes);
                if (cy + hauteur > hautReglages && cy < basReglages) {
                    String cle = Pont.texte(r, "cle");
                    boolean active = valeurReglage(cle, false);
                    Theme.carte(
                            g, x, cy, fin - x, hauteur, mx >= x && my >= cy && my < cy + hauteur);
                    Theme.texte(
                            g,
                            this.font,
                            Pont.texte(r, "nom"),
                            x + marge,
                            cy + marge + 1,
                            texte,
                            Theme.TEXTE,
                            mx,
                            my);
                    int yd = cy + marge + 1 + Theme.LIGNE;
                    for (FormattedCharSequence ligne : lignes) {
                        g.drawString(
                                this.font, ligne, x + marge, yd, Theme.TEXTE_SECONDAIRE, false);
                        yd += Theme.LIGNE;
                    }
                    BoutonTheme bouton =
                            interrupteur(cle)
                                    .texte(active ? ACTIVE : DESACTIVE)
                                    .choisi(active)
                                    .zone(
                                            fin - marge - largeurInterrupteur,
                                            cy + (hauteur - Theme.BOUTON) / 2,
                                            largeurInterrupteur);
                    bouton.render(g, mx, my, partialTick);
                    interrupteursVisibles.add(new Interrupteur(bouton, cle));
                }
                cy += hauteur + Theme.ECART;
                if (cy >= basReglages) break;
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, defilementReglages, sx, sy);
    }

    /** Carte d'un réglage : son nom, sa description, l'interrupteur centré à droite. */
    private static int hauteurReglage(List<FormattedCharSequence> description) {
        return Math.max(Theme.BOUTON + 10, 10 + Theme.LIGNE * (1 + description.size()));
    }
}
