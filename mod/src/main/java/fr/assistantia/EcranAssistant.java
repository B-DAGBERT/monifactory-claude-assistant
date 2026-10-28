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
 * Le tableau de bord de l'assistant (touche Y), en onglets :
 * - Objectif : l'objectif en cours, son pourquoi, ses étapes (clic : cocher ou décocher) et tous les objets avec leur
 *   icône (clic : recette dans EMI, clic droit : fiche « à quoi ça sert ») ;
 * - Plan : l'objectif principal et la liste de ses objectifs (faits, en cours, à venir) ;
 * - Base : les machines vues par le serveur ; Discussion ; Réglages : chaque option avec son état.
 * Une question s'écrit en bas, quel que soit l'onglet.
 */
public class EcranAssistant extends Screen {
    private static final int FOND = 0xD00D1013, MARGE = 10, LIGNE = 10;
    private static final String[] ONGLETS = {"Objectif", "Plan", "Base", "Discussion", "Réglages"};
    private static final int OBJECTIF = 0, PLAN = 1, BASE = 2, DISCUSSION = 3, REGLAGES = 4;
    private static int onglet = OBJECTIF; // gardé d'une ouverture à l'autre

    /** Zone cliquable : une icône d'objet (recette), une étape (la cocher) ou un réglage (le changer). */
    private record Zone(int x, int y, int l, int h, String item, int etape, String reglage) {
    }

    private final List<Zone> zones = new ArrayList<>();
    private final List<Button> boutonsOnglets = new ArrayList<>();
    /** Réglages changés à l'instant, affichés tout de suite sans attendre la réponse de l'assistant. */
    private static final Map<String, Boolean> REGLAGES_EN_ATTENTE = new HashMap<>();
    private EditBox saisie;
    private Button relancer;          // visible seulement quand l'assistant ne tourne pas
    private boolean lanceurInconnu;
    private int defilement = 0;
    private String enAttente = null;

    public EcranAssistant() {
        super(Component.literal("Assistant IA"));
    }

    @Override
    protected void init() {
        int x = MARGE;
        boutonsOnglets.clear();
        for (int i = 0; i < ONGLETS.length; i++) {
            int numero = i;
            int largeur = this.font.width(ONGLETS[i]) + 16;
            boutonsOnglets.add(addRenderableWidget(Button.builder(Component.literal(ONGLETS[i]), b -> {
                onglet = numero;
                defilement = 0;
            }).bounds(x, MARGE, largeur, 18).build()));
            x += largeur + 4;
        }
        int bas = this.height - MARGE - 18;
        saisie = new EditBox(this.font, MARGE, bas, this.width - 2 * MARGE - 70, 18, Component.literal("Question"));
        saisie.setMaxLength(400);
        saisie.setHint(Component.literal("Une question pour Codex ? Écris-la puis Entrée"));
        addRenderableWidget(saisie);
        addRenderableWidget(Button.builder(Component.literal("Envoyer"), b -> envoyerQuestion())
                .bounds(this.width - MARGE - 64, bas - 1, 64, 20).build());
        String[][] actions = {{"Objectif suivant", "objectif_suivant"}, {"Refaire l'objectif", "refaire_objectif"},
                {"Valider l'étape", "valider_etape"}, {"Nouveau plan", "nouveau_plan"}};
        int largeur = (this.width - 2 * MARGE - actions.length * 6 - 104) / actions.length;
        for (int i = 0; i < actions.length; i++) {
            String code = actions[i][1];
            addRenderableWidget(Button.builder(Component.literal(actions[i][0]), b -> envoyerAction(code))
                    .bounds(MARGE + i * (largeur + 6), bas - 26, largeur, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Arbre EMI"), b -> ouvrirArbre())
                .bounds(this.width - MARGE - 104, bas - 26, 104, 20).build());
        relancer = addRenderableWidget(Button.builder(Component.literal("Relancer l'assistant"),
                b -> lanceurInconnu = !Pont.relancer()).bounds(this.width - MARGE - 120, MARGE, 120, 18).build());
        setInitialFocus(saisie);
    }

    private void envoyerQuestion() {
        String texte = saisie.getValue().trim();
        if (texte.isEmpty()) {
            return;
        }
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "question");
        demande.addProperty("texte", texte);
        Pont.envoyer(demande);
        enAttente = texte;
        saisie.setValue("");
        onglet = DISCUSSION;
        defilement = 0;
    }

    private void envoyerAction(String code) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "action");
        demande.addProperty("action", code);
        Pont.envoyer(demande);
    }

    private void cocherEtape(int index) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "action");
        demande.addProperty("action", "etape");
        demande.addProperty("index", index);
        Pont.envoyer(demande);
    }

    private void changerReglage(String cle, boolean valeur) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "reglage");
        demande.addProperty("cle", cle);
        demande.addProperty("valeur", valeur);
        Pont.envoyer(demande);
        REGLAGES_EN_ATTENTE.put(cle, valeur);
    }

    /** L'arbre de fabrication d'EMI, avec l'objectif en cours : EMI y suit ce que tu as déjà. */
    private void ouvrirArbre() {
        JsonObject arbre = ecran().has("arbre") && ecran().get("arbre").isJsonObject()
                ? ecran().getAsJsonObject("arbre") : new JsonObject();
        String item = Pont.texte(arbre, "item");
        if (item.isEmpty() || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        this.minecraft.setScreen(new InventoryScreen(this.minecraft.player)); // EMI ouvre son arbre depuis un inventaire
        Emi.ouvrirArbre(Pont.texte(arbre, "id"), Pont.objet(item));
    }

    private static JsonObject ecran() {
        return Pont.partie("ecran");
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if ((touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER) && saisie.isFocused()) {
            envoyerQuestion();
            return true;
        }
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        for (Zone z : zones) {
            if (x < z.x() || x >= z.x() + z.l() || y < z.y() || y >= z.y() + z.h() || this.minecraft == null
                    || this.minecraft.player == null) {
                continue;
            }
            if (z.reglage() != null) {
                changerReglage(z.reglage(), !valeurReglage(z.reglage(), false));
            } else if (z.etape() >= 0) {
                cocherEtape(z.etape());
            } else if (bouton == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                EcranInfo.ouvrir(Pont.objet(z.item()), "plan", null);
            } else {
                this.minecraft.setScreen(new InventoryScreen(this.minecraft.player)); // EMI s'ouvre depuis un inventaire
                Emi.afficherRecettes(Pont.objet(z.item()));
            }
            return true;
        }
        return super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        // Molette vers le haut : on remonte. Les onglets comptent depuis le haut, la discussion depuis le bas.
        int pas = (delta > 0 ? 3 : -3) * (onglet == DISCUSSION ? 1 : -1);
        defilement = Math.max(0, defilement + pas);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int sourisX, int sourisY, float partialTick) {
        renderBackground(g);
        zones.clear();
        for (int i = 0; i < boutonsOnglets.size(); i++) {
            boutonsOnglets.get(i).active = i != onglet; // l'onglet ouvert est grisé
        }
        JsonObject ecran = ecran();
        int haut = MARGE + 26, bas = this.height - MARGE - 18 - 32;
        g.fill(MARGE - 4, haut - 4, this.width - MARGE + 4, bas, FOND);
        boolean actif = Pont.assistantActif();
        relancer.visible = !actif && !Pont.relanceEnCours();
        if (actif) {
            lanceurInconnu = false;
            String statut = Pont.texte(ecran, "statut");
            String droite = !statut.isEmpty() ? statut : Pont.vrai(ecran, "serveur") ? "serveur ✔" : "";
            int debut = boutonsOnglets.isEmpty() ? MARGE : boutonsOnglets.get(boutonsOnglets.size() - 1).getX()
                    + boutonsOnglets.get(boutonsOnglets.size() - 1).getWidth() + 8;
            droite = this.font.plainSubstrByWidth(droite, this.width - MARGE - debut);
            g.drawString(this.font, droite, this.width - MARGE - this.font.width(droite), MARGE + 5, Hud.GRIS, false);
        } else { // l'assistant ne tourne pas : rien ne répondra tant qu'il n'est pas relancé
            String arret = Pont.relanceEnCours() ? "L'assistant démarre…" : lanceurInconnu
                    ? "Lance le lanceur de l’assistant (Mac.command ou .bat)" : "Assistant arrêté";
            int fin = relancer.visible ? relancer.getX() - 6 : this.width - MARGE;
            g.drawString(this.font, arret, fin - this.font.width(arret), MARGE + 5,
                    Pont.relanceEnCours() ? Hud.GRIS : Hud.ROUGE, false);
        }
        int largeur = this.width - 2 * MARGE;
        switch (onglet) {
            case OBJECTIF -> objectif(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case PLAN -> plan(g, ecran, MARGE, haut, largeur, bas - 4);
            case BASE -> base(g, ecran, MARGE, haut, largeur, bas - 4);
            case REGLAGES -> reglages(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            default -> conversation(g, ecran, MARGE, haut, largeur, bas - 4);
        }
        super.render(g, sourisX, sourisY, partialTick);
    }

    // ---------- Onglet Objectif ----------

    private void objectif(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas, int sx, int sy) {
        JsonObject plan = partie(ecran, "plan");
        String objectif = Pont.texte(ecran, "objectif");
        if (objectif.isEmpty()) {
            ecrire(g, "Pas d'objectif en cours : « Objectif suivant » prend le prochain objectif de ton plan, "
                    + "« Nouveau plan » en prépare un nouveau.", x, y, largeur, Hud.GRIS);
            return;
        }
        String titrePlan = Pont.texte(plan, "titre");
        if (!titrePlan.isEmpty()) {
            g.drawString(this.font, this.font.plainSubstrByWidth("Plan : " + titrePlan, largeur), x, y, Hud.GRIS, false);
            y += 11;
        }
        g.drawString(this.font, "✦ " + objectif, x, y, Hud.OR, true);
        y += 12;
        double progres = Mth.clamp(Pont.nombre(ecran, "progres", 0), 0, 1);
        g.fill(x, y, x + largeur, y + 4, 0x30FFFFFF);
        g.fill(x, y, x + (int) (largeur * progres), y + 4, 0xFF6BE36B);
        y += 9;
        String pourquoi = Pont.texte(ecran, "pourquoi");
        if (!pourquoi.isEmpty()) {
            y = ecrire(g, "Pourquoi : " + pourquoi, x, y, largeur, Hud.GRIS) + 4;
        }
        int colonne = largeur / 2 - 6;
        // Colonne de gauche : les étapes, à cocher ou décocher d'un clic (elles défilent avec la molette, elles aussi)
        int yEtapes = y - defilement * LIGNE, numero = 0;
        for (JsonElement e : Pont.liste(ecran, "etapes")) {
            JsonObject etape = e.getAsJsonObject();
            boolean fait = Pont.vrai(etape, "fait"), courant = Pont.vrai(etape, "courant");
            String texte = (fait ? "☑ " : "☐ ") + Pont.texte(etape, "texte");
            int hauteur = Math.max(1, this.font.split(Component.literal(texte), colonne).size()) * LIGNE;
            if (yEtapes >= y && yEtapes + hauteur <= bas - 14) {
                boolean survol = sx >= x && sx < x + colonne && sy >= yEtapes && sy < yEtapes + hauteur;
                if (survol) {
                    g.fill(x - 2, yEtapes - 1, x + colonne, yEtapes + hauteur - 1, 0x20FFFFFF);
                }
                zones.add(new Zone(x, yEtapes, colonne, hauteur, null, numero, null));
                ecrire(g, texte, x, yEtapes, colonne, survol ? Hud.BLEU : fait ? Hud.VERT : courant ? Hud.JAUNE : Hud.BLANC);
            }
            numero++;
            yEtapes += hauteur + 2;
        }
        // Colonne de droite : tous les objets de l'objectif, avec leur icône (clic = recette dans EMI)
        int xd = x + colonne + 12, yd = y - defilement * LIGNE;
        for (JsonElement s : Pont.liste(ecran, "sections")) {
            JsonObject section = s.getAsJsonObject();
            if (yd >= y - LIGNE && yd < bas) {
                g.drawString(this.font, Pont.texte(section, "titre"), xd, yd, Hud.couleur(Pont.texte(section, "couleur")), false);
            }
            yd += 12;
            for (JsonElement o : Pont.liste(section, "objets")) {
                JsonObject objet = o.getAsJsonObject();
                if (yd >= y - 4 && yd < bas - 16) {
                    String item = Pont.texte(objet, "item");
                    ItemStack stack = Pont.objet(item);
                    g.renderItem(stack, xd, yd);
                    boolean survol = sx >= xd && sx < xd + colonne && sy >= yd && sy < yd + 17;
                    String ligne = Pont.texte(objet, "nom") + "  " + Pont.texte(objet, "texte");
                    g.drawString(this.font, this.font.plainSubstrByWidth(ligne, colonne - 22), xd + 20, yd + 4,
                            survol ? Hud.BLEU : Hud.BLANC, false);
                    zones.add(new Zone(xd, yd, colonne, 17, item, -1, null));
                }
                yd += 18;
            }
            yd += 4;
        }
        // Pas plus bas que la fin de la plus longue des deux colonnes (pris en compte à l'image suivante)
        int depassement = Math.max(yd, yEtapes) + defilement * LIGNE - (bas - 16);
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
        g.drawString(this.font, this.font.plainSubstrByWidth("Clic sur une étape : la cocher · sur un objet : sa "
                + "recette (clic droit : à quoi ça sert) · molette : défiler", largeur), x, bas - 10, Hud.GRIS, false);
    }

    // ---------- Onglet Plan ----------

    private void plan(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas) {
        JsonObject plan = partie(ecran, "plan");
        JsonArray objectifs = Pont.liste(plan, "objectifs");
        if (objectifs.isEmpty()) {
            ecrire(g, "Pas encore de plan. « Nouveau plan » : l'assistant choisit ton prochain grand objectif "
                    + "(ou celui que tu lui demandes) et le découpe en objectifs, dans l'ordre.", x, y, largeur, Hud.GRIS);
            return;
        }
        String titre = Pont.texte(plan, "titre");
        g.drawString(this.font, "✦ Plan : " + (titre.isEmpty() ? "en cours" : titre), x, y, Hud.OR, true);
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
        y = ecrire(g, faits + " objectif" + (faits > 1 ? "s" : "") + " fait" + (faits > 1 ? "s" : "") + " sur "
                + objectifs.size(), x, y, largeur, Hud.GRIS) + 4;
        int numero = 1, debut = y;
        y -= defilement * LIGNE;
        for (JsonElement e : objectifs) {
            JsonObject o = e.getAsJsonObject();
            String etat = Pont.texte(o, "etat");
            String marque = "fait".equals(etat) ? "✔ " : "en_cours".equals(etat) ? "▶ " : numero + ". ";
            int couleur = "fait".equals(etat) ? Hud.VERT : "en_cours".equals(etat) ? Hud.JAUNE : Hud.BLANC;
            String texte = marque + Pont.texte(o, "titre") + ("en_cours".equals(etat) ? "  (en cours)" : "");
            if (y >= debut && y < bas - 12) {
                ecrire(g, texte, x, y, largeur, couleur);
            }
            y += Math.max(1, this.font.split(Component.literal(texte), largeur).size()) * LIGNE + 3;
            numero++;
        }
        int depassement = y + defilement * LIGNE - (bas - 12);
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
        g.drawString(this.font, this.font.plainSubstrByWidth("« Objectif suivant » : passer au prochain objectif "
                + "du plan · « Nouveau plan » : en choisir un autre", largeur), x, bas - 10, Hud.GRIS, false);
    }

    // ---------- Onglet Base ----------

    private void base(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas) {
        int coffres = (int) Pont.nombre(ecran, "coffres", 0);
        y = ecrire(g, coffres + " coffres connus" + (Pont.vrai(ecran, "serveur")
                ? " · le serveur voit tes machines en direct" : " · serveur non connecté"), x, y, largeur, Hud.GRIS) + 4;
        JsonArray machines = Pont.liste(ecran, "machines");
        for (JsonElement e : machines) {
            if (y > bas) {
                return;
            }
            JsonObject m = e.getAsJsonObject();
            String ligne = Pont.texte(m, "nom") + " (" + (int) Pont.nombre(m, "x", 0) + " " + (int) Pont.nombre(m, "y", 0)
                    + " " + (int) Pont.nombre(m, "z", 0) + ") : " + Pont.texte(m, "etat");
            y = ecrire(g, "● " + ligne, x, y, largeur, Hud.couleur(Pont.texte(m, "couleur"))) + 2;
        }
        JsonArray joueurs = Pont.liste(ecran, "joueurs");
        if (!joueurs.isEmpty() && y < bas) {
            y = ecrire(g, "Autres joueurs :", x, y + 6, largeur, Hud.GRIS);
            for (JsonElement j : joueurs) {
                y = ecrire(g, "  " + j.getAsString(), x, y, largeur, Hud.BLANC);
            }
        }
    }

    // ---------- Onglet Réglages ----------

    /** La valeur affichée d'un réglage : celle qu'on vient de choisir, en attendant que l'assistant la confirme. */
    private boolean valeurReglage(String cle, boolean parDefaut) {
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

    private void reglages(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas, int sx, int sy) {
        JsonArray reglages = Pont.liste(ecran, "reglages");
        if (reglages.isEmpty()) {
            ecrire(g, "Les réglages s'affichent quand l'assistant tourne.", x, y, largeur, Hud.GRIS);
            return;
        }
        int bouton = 96, texte = largeur - bouton - 12, debut = y;
        y -= defilement * LIGNE;
        for (JsonElement e : reglages) {
            JsonObject r = e.getAsJsonObject();
            String cle = Pont.texte(r, "cle");
            boolean active = valeurReglage(cle, false);
            List<FormattedCharSequence> description = this.font.split(Component.literal(Pont.texte(r, "description")), texte);
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
                g.fill(bx, by, bx + bouton, by + 18, active ? (survol ? 0xFF3FA845 : 0xFF2E8B36)
                        : (survol ? 0xFF5A5A5A : 0xFF3C3C3C));
                g.renderOutline(bx, by, bouton, 18, survol ? 0xFFFFFFFF : 0x60FFFFFF);
                String etat = active ? "✔ Activé" : "✘ Désactivé";
                g.drawString(this.font, etat, bx + (bouton - this.font.width(etat)) / 2, by + 5,
                        active ? 0xFFFFFF : 0xBBBBBB, false);
                zones.add(new Zone(bx, by, bouton, 18, null, -1, cle));
                g.fill(x, y + hauteur - 3, x + largeur, y + hauteur - 2, 0x18FFFFFF);
            }
            y += hauteur;
        }
        int depassement = y + defilement * LIGNE - bas;
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
    }

    // ---------- Onglet Discussion ----------

    private void conversation(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas) {
        List<FormattedCharSequence> lignes = new ArrayList<>();
        List<Integer> couleurs = new ArrayList<>();
        boolean dejaDedans = false;
        for (JsonElement e : Pont.liste(ecran, "conversation")) {
            JsonObject message = e.getAsJsonObject();
            boolean joueur = "toi".equals(Pont.texte(message, "de"));
            String texte = Pont.texte(message, "texte");
            dejaDedans |= joueur && texte.equals(enAttente);
            for (FormattedCharSequence l : this.font.split(Component.literal((joueur ? "Toi : " : "") + texte), largeur)) {
                lignes.add(l);
                couleurs.add(joueur ? Hud.BLEU : Hud.BLANC);
            }
            lignes.add(FormattedCharSequence.EMPTY);
            couleurs.add(Hud.BLANC);
        }
        if (enAttente != null && !dejaDedans) {
            String suite = Pont.assistantActif() ? " (envoyé…)" : " (en attente : l'assistant est arrêté)";
            for (FormattedCharSequence l : this.font.split(Component.literal("Toi : " + enAttente + suite), largeur)) {
                lignes.add(l);
                couleurs.add(Hud.GRIS);
            }
        }
        if (lignes.isEmpty()) {
            ecrire(g, "Écris une question en bas, ou maintiens F8 pour parler.", x, y, largeur, Hud.GRIS);
            return;
        }
        int visibles = (bas - y) / LIGNE;
        defilement = Math.min(defilement, Math.max(0, lignes.size() - visibles));
        int fin = lignes.size() - defilement;
        for (int i = Math.max(0, fin - visibles); i < fin; i++) {
            g.drawString(this.font, lignes.get(i), x, y, couleurs.get(i), false);
            y += LIGNE;
        }
    }

    private static JsonObject partie(JsonObject objet, String cle) {
        return objet.has(cle) && objet.get(cle).isJsonObject() ? objet.getAsJsonObject(cle) : new JsonObject();
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
