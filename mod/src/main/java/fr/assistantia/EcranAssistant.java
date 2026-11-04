package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le tableau de bord de l'assistant (touche Y), en onglets : - Objectif : l'objectif en cours, son
 * avancement, ses cibles et les machines nécessaires (clic : recette dans EMI, clic droit : fiche «
 * à quoi ça sert ») ; - Plan : l'objectif principal et la liste de ses objectifs (faits, en cours,
 * à venir) ; - Base : les machines vues par le serveur ; Discussion ; Réglages : chaque option avec
 * son état. Une question s'écrit en bas, quel que soit l'onglet.
 */
public class EcranAssistant extends Screen {
    private static final int FOND = 0xD00D1013, MARGE = 10, LIGNE = 10;
    // Toujours ajouter les nouveaux onglets avant Réglages, qui reste tout à droite.
    private static final String[] ONGLETS = {
        "Objectif", "Plan", "Base", "Discussion", "Filons", "Réglages"
    };
    private static final int OBJECTIF = 0,
            PLAN = 1,
            BASE = 2,
            DISCUSSION = 3,
            FILONS = 4,
            REGLAGES = 5;
    private static int onglet = OBJECTIF; // gardé d'une ouverture à l'autre

    /**
     * Zone cliquable : une icône d'objet (recette), une étape (la cocher) ou un réglage (le
     * changer).
     */
    private record Zone(int x, int y, int l, int h, String item, int etape, String reglage) {}

    private final List<Zone> zones = new ArrayList<>();
    private final List<Button> boutonsOnglets = new ArrayList<>();

    /**
     * Réglages changés à l'instant, affichés tout de suite sans attendre la réponse de l'assistant.
     */
    private static final Map<String, Boolean> REGLAGES_EN_ATTENTE = new HashMap<>();

    private final List<Button> actionsObjectif = new ArrayList<>();
    private OngletFilons filons;
    private OngletBase base;
    private Button projets, objectifEmi, masquerMaj;
    private static final AvisMiseAJour AVIS_MAJ =
            new AvisMiseAJour(
                    net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR
                            .get()
                            .resolve("assistantia-avis-masque.txt"));
    private String messageEmi = "";
    private EditBox saisie;
    private Button envoyer;
    private static final EtatDiscussion ETAT_DISCUSSION = new EtatDiscussion();
    private final OngletDiscussion discussion = new OngletDiscussion(ETAT_DISCUSSION);
    private Button pauseObjectif;
    private static Boolean pauseDemandee;
    private static String pauseVersion;
    private static long pauseEnvoyee;
    private Button relancer; // visible seulement quand l'assistant ne tourne pas
    private boolean lanceurInconnu;
    private int defilement = 0;

    public EcranAssistant() {
        super(Component.literal("Assistant IA"));
    }

    @Override
    protected void init() {
        int x = MARGE;
        boutonsOnglets.clear();
        actionsObjectif.clear();
        int largeurTotale =
                java.util.Arrays.stream(ONGLETS).mapToInt(t -> this.font.width(t) + 20).sum();
        for (int i = 0; i < ONGLETS.length; i++) {
            int numero = i;
            int largeur =
                    Math.min(
                            this.font.width(ONGLETS[i]) + 16,
                            Math.max(
                                    30,
                                    (this.font.width(ONGLETS[i]) + 20)
                                                    * (this.width - 2 * MARGE)
                                                    / largeurTotale
                                            - 4));
            boutonsOnglets.add(
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(
                                                    this.font.plainSubstrByWidth(
                                                            ONGLETS[i], largeur - 8)),
                                            b -> {
                                                onglet = numero;
                                                defilement = 0;
                                            })
                                    .bounds(x, MARGE, largeur, 18)
                                    .build()));
            boutonsOnglets
                    .get(i)
                    .setTooltip(
                            net.minecraft.client.gui.components.Tooltip.create(
                                    Component.literal(ONGLETS[i])));
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
        String[][] actions = {
            {"Objectif suivant", "objectif_suivant"},
            {"Refaire l'objectif", "refaire_objectif"}
        };
        int largeur = (this.width - 2 * MARGE - actions.length * 6 - 104) / actions.length;
        for (int i = 0; i < actions.length; i++) {
            String code = actions[i][1];
            actionsObjectif.add(
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(actions[i][0]),
                                            b -> envoyerAction(code))
                                    .bounds(MARGE + i * (largeur + 6), bas - 26, largeur, 20)
                                    .build()));
        }
        objectifEmi =
                addRenderableWidget(
                        Button.builder(Component.literal("Objectif EMI"), b -> ouvrirArbre())
                                .bounds(this.width - MARGE - 104, bas - 26, 104, 20)
                                .build());
        actionsObjectif.add(objectifEmi);
        relancer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Relancer l'assistant"),
                                        b -> lanceurInconnu = !Pont.relancer())
                                .bounds(this.width - MARGE - 120, MARGE, 120, 18)
                                .build());
        pauseObjectif =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Mettre en pause"),
                                        b -> {
                                            JsonObject vue = ecran();
                                            pauseDemandee = !Pont.vrai(vue, "objectif_en_pause");
                                            pauseVersion = Pont.texte(vue, "objectif_version");
                                            pauseEnvoyee = System.currentTimeMillis();
                                            JsonObject demande = new JsonObject();
                                            demande.addProperty("type", "action");
                                            demande.addProperty(
                                                    "action",
                                                    pauseDemandee
                                                            ? "pause_objectif"
                                                            : "reprendre_objectif");
                                            demande.addProperty("version", pauseVersion);
                                            demande.addProperty(
                                                    "projet",
                                                    Pont.texte(partie(vue, "projets"), "actif"));
                                            Pont.envoyer(demande);
                                            b.active = false;
                                        })
                                .bounds(this.width - MARGE - 120, MARGE + 26, 120, 18)
                                .build());
        projets =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Mes projets"),
                                        b -> this.minecraft.setScreen(new EcranProjets(this)))
                                .bounds(MARGE, MARGE + 26, 120, 18)
                                .build());
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
        onglet = DISCUSSION;
    }

    private void envoyerAction(String code) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "action");
        demande.addProperty("action", code);
        demande.addProperty("projet", Pont.texte(partie(ecran(), "projets"), "actif"));
        Pont.envoyer(demande);
    }

    private void cocherEtape(int index) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "action");
        demande.addProperty("action", "etape");
        demande.addProperty("projet", Pont.texte(partie(ecran(), "projets"), "actif"));
        demande.addProperty("index", index);
        Pont.envoyer(demande);
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

    /** Met toutes les cibles et les recettes du plan dans EMI, uniquement à la demande. */
    private void ouvrirArbre() {
        if (this.minecraft == null || this.minecraft.player == null) return;
        EmiObjectif.Resultat resultat = EmiObjectif.preparer(partie(ecran(), "objectif_emi"));
        messageEmi = resultat.message();
        if (!resultat.succes()) return;
        Screen parent = this;
        this.minecraft.setScreen(
                new InventoryScreen(this.minecraft.player) {
                    @Override
                    public void onClose() {
                        this.minecraft.setScreen(parent);
                    }
                });
        if (!EmiObjectif.afficher()) {
            messageEmi =
                    "L’arbre EMI n’a pas pu s’ouvrir. Réessaie après le chargement des recettes.";
            this.minecraft.setScreen(this);
        } else if (!messageEmi.isEmpty()) {
            this.minecraft.player.displayClientMessage(Component.literal(messageEmi), false);
        }
    }

    private static JsonObject ecran() {
        return Pont.partie("ecran");
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if ((touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER)
                && saisie.isFocused()) {
            envoyerQuestion();
            return true;
        }
        if (onglet == DISCUSSION && discussion.touche(touche)) return true;
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        if (onglet == DISCUSSION && discussion.cliquer(x, y, bouton)) return true;
        if (onglet == FILONS && filons.cliquer(x, y, bouton)) return true;
        for (Zone z : zones) {
            if (x < z.x()
                    || x >= z.x() + z.l()
                    || y < z.y()
                    || y >= z.y() + z.h()
                    || this.minecraft == null
                    || this.minecraft.player == null) {
                continue;
            }
            if (z.reglage() != null) {
                if (bouton != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
                changerReglage(z.reglage(), !valeurReglage(z.reglage(), false));
            } else if (z.etape() >= 0) {
                cocherEtape(z.etape());
            } else if (bouton == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                EcranInfo.ouvrir(Pont.objet(z.item()), "plan", null);
            } else {
                this.minecraft.setScreen(
                        new InventoryScreen(
                                this.minecraft.player)); // EMI s'ouvre depuis un inventaire
                Emi.afficherRecettes(Pont.objet(z.item()));
            }
            return true;
        }
        return super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (onglet == DISCUSSION) return discussion.defiler(x, y, delta);
        if (onglet == FILONS) return filons.defiler(x, y, delta);
        if (onglet == BASE) return base.defiler(x, y, delta);
        // Molette vers le haut : on remonte dans les autres onglets.
        int pas = (delta > 0 ? 3 : -3) * (onglet == DISCUSSION ? 1 : -1);
        defilement = Math.max(0, defilement + pas);
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        if (onglet == DISCUSSION && discussion.glisser(y)) return true;
        return super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        discussion.relacher();
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
        filons.visible(onglet == FILONS);
        base.visible(onglet == BASE);
        actionsObjectif.forEach(b -> b.visible = onglet == OBJECTIF || onglet == PLAN);
        for (int i = 0; i < boutonsOnglets.size(); i++) {
            boutonsOnglets.get(i).active = i != onglet; // l'onglet ouvert est grisé
        }
        JsonObject ecran = ecran();
        boolean ciblesEmi = !Pont.liste(partie(ecran, "objectif_emi"), "cibles").isEmpty();
        boolean emiPret = EmiObjectif.disponible();
        objectifEmi.active = ciblesEmi && emiPret && this.minecraft.player != null;
        objectifEmi.setTooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(
                                !ciblesEmi
                                        ? "Pas de craft défini pour cet objectif. Si besoin, relance l’assistant mis à jour."
                                        : !emiPret
                                                ? "EMI charge ses recettes ou n’est pas installé."
                                                : "Mettre toutes les cibles, quantités et recettes de l’objectif dans l’arbre EMI. Inventaire suivi par EMI ; coffres suivis par l’assistant.")));
        discussion.visible(onglet == DISCUSSION);
        discussion.actualiser(ecran);
        if (!saisie.getValue().equals(ETAT_DISCUSSION.brouillon()))
            saisie.setValue(ETAT_DISCUSSION.brouillon());
        saisie.setEditable(!ETAT_DISCUSSION.enAttente());
        envoyer.active =
                ecran.has("question_recue")
                        && ETAT_DISCUSSION.peutEnvoyer(
                                Pont.assistantActif(), Pont.vrai(ecran, "occupe"));
        envoyer.setMessage(Component.literal(ETAT_DISCUSSION.enAttente() ? "Envoi…" : "Envoyer"));
        int haut = MARGE + 26,
                bas = this.height - MARGE - 18 - (onglet == OBJECTIF || onglet == PLAN ? 32 : 6);
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
        relancer.visible = !actif && !Pont.relanceEnCours() && onglet != FILONS;
        relancer.setY(onglet == DISCUSSION ? haut + 12 : MARGE);
        if (onglet != FILONS && onglet != DISCUSSION && actif) {
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
            droite = this.font.plainSubstrByWidth(droite, this.width - MARGE - debut);
            g.drawString(
                    this.font,
                    droite,
                    this.width - MARGE - this.font.width(droite),
                    MARGE + 5,
                    Hud.GRIS,
                    false);
        } else if (onglet != DISCUSSION
                && onglet != FILONS) { // l'assistant ne tourne pas : rien ne répondra tant qu'il
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
                    arret,
                    fin - this.font.width(arret),
                    MARGE + 5,
                    Pont.relanceEnCours() ? Hud.GRIS : Hud.ROUGE,
                    false);
        }
        projets.visible = onglet == OBJECTIF || onglet == PLAN;
        if (projets.visible) {
            String nomProjet = Pont.texte(partie(ecran, "projets"), "nom");
            projets.setX(MARGE);
            projets.setY(haut);
            projets.setWidth(this.width - 2 * MARGE);
            projets.setMessage(
                    Component.literal(
                            this.font.plainSubstrByWidth(
                                    "Projet : "
                                            + (nomProjet.isEmpty() ? "Mes projets" : nomProjet)
                                            + "  ▾",
                                    this.width - 2 * MARGE - 16)));
            projets.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal(
                                    "Choisir, créer ou renommer un projet. Les autres objectifs et plans sont conservés.")));
            haut += 24;
        }
        boolean enPause = Pont.vrai(ecran, "objectif_en_pause");
        if (pauseDemandee != null
                && (pauseDemandee == enPause
                        || !Pont.texte(ecran, "objectif_version").equals(pauseVersion)
                        || System.currentTimeMillis() - pauseEnvoyee > 5000)) {
            pauseDemandee = null;
        }
        pauseObjectif.visible =
                (onglet == OBJECTIF || onglet == PLAN) && !Pont.texte(ecran, "objectif").isEmpty();
        pauseObjectif.active =
                actif
                        && !Pont.vrai(ecran, "occupe")
                        && pauseDemandee == null
                        && System.currentTimeMillis() - pauseEnvoyee > 650;
        pauseObjectif.setMessage(
                Component.literal(
                        pauseDemandee != null
                                ? "En cours…"
                                : enPause ? "Reprendre" : "Mettre en pause"));
        if (pauseObjectif.visible) {
            pauseObjectif.setY(haut);
            g.drawString(
                    this.font,
                    this.font.plainSubstrByWidth(
                            enPause
                                    ? "Objectif en pause · progression conservée"
                                    : "Objectif actif",
                            this.width - 2 * MARGE - 128),
                    MARGE,
                    haut + 5,
                    enPause ? Hud.JAUNE : Hud.GRIS,
                    false);
            haut += 24;
        }
        int largeur = this.width - 2 * MARGE;
        if (!messageEmi.isEmpty() && (onglet == OBJECTIF || onglet == PLAN)) {
            haut = ecrire(g, messageEmi, MARGE, haut, largeur, Hud.JAUNE) + 4;
        }
        switch (onglet) {
            case OBJECTIF -> objectif(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case PLAN -> plan(g, ecran, MARGE, haut, largeur, bas - 4);
            case FILONS -> filons.render(g, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case BASE -> base.render(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case REGLAGES -> reglages(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            default -> discussion.render(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
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

    // ---------- Onglet Objectif ----------

    private void objectif(
            GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas, int sx, int sy) {
        JsonObject plan = partie(ecran, "plan");
        String objectif = Pont.texte(ecran, "objectif");
        if (objectif.isEmpty()) {
            ecrire(
                    g,
                    "Pas d'objectif en cours : « Objectif suivant » prend le prochain objectif de ton plan, "
                            + "le sélecteur « Projet » permet d’en créer un autre.",
                    x,
                    y,
                    largeur,
                    Hud.GRIS);
            return;
        }
        String titrePlan = Pont.texte(plan, "titre");
        if (!titrePlan.isEmpty()) {
            g.drawString(
                    this.font,
                    this.font.plainSubstrByWidth("Plan : " + titrePlan, largeur),
                    x,
                    y,
                    Hud.GRIS,
                    false);
            y += 11;
        }
        y = ecrire(g, "✦ " + objectif, x, y, largeur, Hud.OR) + 4;
        double progres = Mth.clamp(Pont.nombre(ecran, "progres", 0), 0, 1);
        g.fill(x, y, x + largeur, y + 4, 0x30FFFFFF);
        g.fill(x, y, x + (int) (largeur * progres), y + 4, 0xFF6BE36B);
        y += 9;
        g.drawString(
                this.font,
                "Avancement : " + Math.round(progres * 100) + " %",
                x,
                y,
                Hud.GRIS,
                false);
        y += 18;
        int haut = y, ligne = y - defilement * LIGNE;
        // Les recettes détaillées restent dans EMI : ici uniquement les cibles et les machines.
        for (JsonElement element : Pont.liste(ecran, "sections")) {
            JsonObject section = element.getAsJsonObject();
            String titre = Pont.texte(section, "titre");
            if (!titre.equals("Objectif") && !titre.equals("Machines nécessaires")) continue;
            if (ligne >= haut && ligne < bas - 12)
                g.drawString(
                        this.font,
                        titre,
                        x,
                        ligne,
                        Hud.couleur(Pont.texte(section, "couleur")),
                        false);
            ligne += 15;
            for (JsonElement entry : Pont.liste(section, "objets")) {
                JsonObject objet = entry.getAsJsonObject();
                String item = Pont.texte(objet, "item");
                String texte = Pont.texte(objet, "nom") + " · " + Pont.texte(objet, "texte");
                int hauteur =
                        Math.max(
                                18,
                                this.font
                                                        .split(
                                                                Component.literal(texte),
                                                                Math.max(1, largeur - 24))
                                                        .size()
                                                * LIGNE
                                        + 4);
                if (ligne >= haut && ligne + hauteur <= bas - 14) {
                    g.renderItem(Pont.objet(item), x, ligne);
                    boolean survol =
                            sx >= x && sx < x + largeur && sy >= ligne && sy < ligne + hauteur;
                    ecrire(
                            g,
                            texte,
                            x + 22,
                            ligne + 3,
                            largeur - 24,
                            survol ? Hud.BLEU : Hud.BLANC);
                    zones.add(new Zone(x, ligne, largeur, hauteur, item, -1, null));
                }
                ligne += hauteur;
            }
            ligne += 8;
        }
        int depassement = ligne + defilement * LIGNE - (bas - 14);
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
        g.drawString(
                this.font,
                this.font.plainSubstrByWidth(
                        "Détails des crafts : Objectif EMI · Molette : défiler", largeur),
                x,
                bas - 10,
                Hud.GRIS,
                false);
    }

    // ---------- Onglet Plan ----------

    private void plan(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas) {
        JsonObject plan = partie(ecran, "plan");
        JsonArray objectifs = Pont.liste(plan, "objectifs");
        if (objectifs.isEmpty()) {
            ecrire(
                    g,
                    "Pas encore de plan. Sélecteur « Projet » → Créer : l'assistant choisit ton prochain grand objectif "
                            + "dans un projet séparé, sans perdre tes autres objectifs.",
                    x,
                    y,
                    largeur,
                    Hud.GRIS);
            return;
        }
        String titre = Pont.texte(plan, "titre");
        g.drawString(
                this.font,
                "✦ Plan : " + (titre.isEmpty() ? "en cours" : titre),
                x,
                y,
                Hud.OR,
                true);
        y += 12;
        int faits = 0;
        for (JsonElement e : objectifs) {
            faits += "fait".equals(Pont.texte(e.getAsJsonObject(), "etat")) ? 1 : 0;
        }
        g.fill(x, y, x + largeur, y + 4, 0x30FFFFFF);
        g.fill(x, y, x + largeur * faits / objectifs.size(), y + 4, 0xFF6BE36B);
        y += 9;
        String pourquoi = Pont.texte(plan, "pourquoi");
        if (!pourquoi.isEmpty()) {
            y = ecrire(g, "Pourquoi : " + pourquoi, x, y, largeur, Hud.GRIS) + 4;
        }
        y =
                ecrire(
                                g,
                                faits
                                        + " objectif"
                                        + (faits > 1 ? "s" : "")
                                        + " fait"
                                        + (faits > 1 ? "s" : "")
                                        + " sur "
                                        + objectifs.size(),
                                x,
                                y,
                                largeur,
                                Hud.GRIS)
                        + 4;
        int numero = 1, debut = y;
        y -= defilement * LIGNE;
        for (JsonElement e : objectifs) {
            JsonObject o = e.getAsJsonObject();
            String etat = Pont.texte(o, "etat");
            String marque =
                    "en_pause".equals(etat)
                            ? "Ⅱ "
                            : "fait".equals(etat)
                                    ? "✔ "
                                    : "en_cours".equals(etat) ? "▶ " : numero + ". ";
            int couleur =
                    "fait".equals(etat)
                            ? Hud.VERT
                            : "en_cours".equals(etat) ? Hud.JAUNE : Hud.BLANC;
            String texte =
                    marque
                            + Pont.texte(o, "titre")
                            + ("en_pause".equals(etat)
                                    ? "  (en pause)"
                                    : "en_cours".equals(etat) ? "  (en cours)" : "");
            if (y >= debut && y < bas - 12) {
                ecrire(g, texte, x, y, largeur, couleur);
            }
            y += Math.max(1, this.font.split(Component.literal(texte), largeur).size()) * LIGNE + 3;
            numero++;
        }
        int depassement = y + defilement * LIGNE - (bas - 12);
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
        g.drawString(
                this.font,
                this.font.plainSubstrByWidth(
                        "« Objectif suivant » : passer au prochain objectif "
                                + "du plan · « Mes projets » : en choisir un autre",
                        largeur),
                x,
                bas - 10,
                Hud.GRIS,
                false);
    }

    // ---------- Onglet Base ----------

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
        JsonArray reglages = new JsonArray();
        JsonObject lumiere = new JsonObject();
        lumiere.addProperty("cle", Fullbright.CLE);
        lumiere.addProperty("nom", "Luminosité maximale (Fullbright)");
        lumiere.addProperty("description", Fullbright.description());
        reglages.add(lumiere);
        for (JsonElement reglage : Pont.liste(ecran, "reglages")) reglages.add(reglage);
        int bouton = 96, texte = largeur - bouton - 12, debut = y;
        y -= defilement * LIGNE;
        for (JsonElement e : reglages) {
            JsonObject r = e.getAsJsonObject();
            String cle = Pont.texte(r, "cle");
            boolean active = valeurReglage(cle, false);
            List<FormattedCharSequence> description =
                    this.font.split(Component.literal(Pont.texte(r, "description")), texte);
            int hauteur = Math.max(22, 12 + description.size() * LIGNE) + 4;
            if (y >= debut - 2 && y + hauteur <= bas) {
                g.drawString(this.font, Pont.texte(r, "nom"), x, y, Hud.BLANC, false);
                int yd = y + 11;
                for (FormattedCharSequence ligne : description) {
                    g.drawString(this.font, ligne, x, yd, Hud.GRIS, false);
                    yd += LIGNE;
                }
                // L'interrupteur dit l'état : « ✔ Activé » en vert, « ✘ Désactivé » en gris
                int bx = x + largeur - bouton, by = y + (hauteur - 4 - 18) / 2;
                boolean survol = sx >= bx && sx < bx + bouton && sy >= by && sy < by + 18;
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
                zones.add(new Zone(bx, by, bouton, 18, null, -1, cle));
                g.fill(x, y + hauteur - 3, x + largeur, y + hauteur - 2, 0x18FFFFFF);
            }
            y += hauteur;
        }
        int depassement = y + defilement * LIGNE - bas;
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
    }

    private static JsonObject partie(JsonObject objet, String cle) {
        return objet.has(cle) && objet.get(cle).isJsonObject()
                ? objet.getAsJsonObject(cle)
                : new JsonObject();
    }

    private int ecrire(GuiGraphics g, String texte, int x, int y, int largeur, int couleur) {
        Font font = this.font;
        for (FormattedCharSequence l : font.split(Component.literal(texte), largeur)) {
            g.drawString(font, l, x, y, couleur, false);
            y += LIGNE;
        }
        return y;
    }
}
