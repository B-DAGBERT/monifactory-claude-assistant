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
import java.util.List;

/**
 * Le tableau de bord de l'assistant (touche Y), en onglets : Plan (objectif, pourquoi, étapes, et tous les objets
 * avec leur icône, cliquables pour ouvrir leur recette dans EMI), Feuille de route, Base (machines vues par le
 * serveur), Discussion. Une question s'écrit en bas, quel que soit l'onglet.
 */
public class EcranAssistant extends Screen {
    private static final int FOND = 0xD00D1013, MARGE = 10, LIGNE = 10;
    private static final String[] ONGLETS = {"Plan", "Feuille de route", "Base", "Discussion"};
    private static int onglet = 0; // gardé d'une ouverture à l'autre

    /** Zone cliquable : une icône d'objet qui ouvre sa recette. */
    private record Zone(int x, int y, int l, int h, String item) {
    }

    private final List<Zone> zones = new ArrayList<>();
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
        for (int i = 0; i < ONGLETS.length; i++) {
            int numero = i;
            int largeur = this.font.width(ONGLETS[i]) + 16;
            addRenderableWidget(Button.builder(Component.literal(ONGLETS[i]), b -> {
                onglet = numero;
                defilement = 0;
            }).bounds(x, MARGE, largeur, 18).build());
            x += largeur + 4;
        }
        int bas = this.height - MARGE - 18;
        saisie = new EditBox(this.font, MARGE, bas, this.width - 2 * MARGE - 70, 18, Component.literal("Question"));
        saisie.setMaxLength(400);
        saisie.setHint(Component.literal("Une question pour Claude ? Écris-la puis Entrée"));
        addRenderableWidget(saisie);
        addRenderableWidget(Button.builder(Component.literal("Envoyer"), b -> envoyerQuestion())
                .bounds(this.width - MARGE - 64, bas - 1, 64, 20).build());
        String[][] actions = {{"Objectif suivant", "objectif_suivant"}, {"Nouveau plan", "plan"},
                {"Panneau on/off", "affichage"}, {"Voix on/off", "voix"}};
        int largeur = (this.width - 2 * MARGE - 3 * 6 - 110) / 4;
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
        onglet = 3;
        defilement = 0;
    }

    private void envoyerAction(String code) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "action");
        demande.addProperty("action", code);
        Pont.envoyer(demande);
    }

    /** L'arbre de fabrication d'EMI, avec l'objectif du plan : EMI y suit ce que tu as déjà. */
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
            if (x >= z.x() && x < z.x() + z.l() && y >= z.y() && y < z.y() + z.h() && this.minecraft != null
                    && this.minecraft.player != null) {
                if (bouton == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    EcranInfo.ouvrir(Pont.objet(z.item()), "plan", null);
                    return true;
                }
                this.minecraft.setScreen(new InventoryScreen(this.minecraft.player)); // EMI s'ouvre depuis un inventaire
                Emi.afficherRecettes(Pont.objet(z.item()));
                return true;
            }
        }
        return super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        // Molette vers le haut : on remonte. Le plan compte depuis le haut, la discussion depuis le bas.
        int pas = (delta > 0 ? 3 : -3) * (onglet == 3 ? 1 : -1);
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
        JsonObject ecran = ecran();
        int haut = MARGE + 26, bas = this.height - MARGE - 18 - 32;
        g.fill(MARGE - 4, haut - 4, this.width - MARGE + 4, bas, FOND);
        boolean actif = Pont.assistantActif();
        relancer.visible = !actif && !Pont.relanceEnCours();
        if (actif) {
            lanceurInconnu = false;
            String statut = Pont.texte(ecran, "statut");
            String droite = !statut.isEmpty() ? statut : Pont.vrai(ecran, "serveur") ? "serveur ✔" : "";
            g.drawString(this.font, droite, this.width - MARGE - this.font.width(droite), MARGE + 5, Hud.GRIS, false);
        } else { // l'assistant ne tourne pas : rien ne répondra tant qu'il n'est pas relancé
            String arret = Pont.relanceEnCours() ? "L'assistant démarre…" : lanceurInconnu
                    ? "Lance « Lancer assistant.bat »" : "Assistant arrêté";
            int fin = relancer.visible ? relancer.getX() - 6 : this.width - MARGE;
            g.drawString(this.font, arret, fin - this.font.width(arret), MARGE + 5,
                    Pont.relanceEnCours() ? Hud.GRIS : Hud.ROUGE, false);
        }
        int largeur = this.width - 2 * MARGE;
        switch (onglet) {
            case 0 -> plan(g, ecran, MARGE, haut, largeur, bas - 4, sourisX, sourisY);
            case 1 -> feuilleDeRoute(g, ecran, MARGE, haut, largeur, bas - 4);
            case 2 -> base(g, ecran, MARGE, haut, largeur, bas - 4);
            default -> conversation(g, ecran, MARGE, haut, largeur, bas - 4);
        }
        super.render(g, sourisX, sourisY, partialTick);
    }

    // ---------- Onglet Plan ----------

    private void plan(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas, int sx, int sy) {
        String objectif = Pont.texte(ecran, "objectif");
        if (objectif.isEmpty()) {
            ecrire(g, "Pas encore d'objectif : « Nouveau plan », et l'assistant choisit le plus utile pour toi.",
                    x, y, largeur, Hud.GRIS);
            return;
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
        int yEtapes = y;
        for (JsonElement e : Pont.liste(ecran, "etapes")) {
            JsonObject etape = e.getAsJsonObject();
            boolean fait = Pont.vrai(etape, "fait"), courant = Pont.vrai(etape, "courant");
            yEtapes = ecrire(g, (fait ? "✔ " : courant ? "▶ " : "· ") + Pont.texte(etape, "texte"), x, yEtapes, colonne,
                    fait ? Hud.VERT : courant ? Hud.JAUNE : Hud.BLANC) + 2;
        }
        // Colonne de droite : tous les objets du plan, avec leur icône (clic = recette dans EMI)
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
                    zones.add(new Zone(xd, yd, colonne, 17, item));
                }
                yd += 18;
            }
            yd += 4;
        }
        // Pas plus bas que la fin de la liste (pris en compte à l'image suivante)
        int depassement = yd + defilement * LIGNE - (bas - 16);
        defilement = Math.min(defilement, Math.max(0, (depassement + LIGNE - 1) / LIGNE));
        if (!zones.isEmpty()) {
            g.drawString(this.font, "Clic : recette dans EMI · clic droit : à quoi ça sert · molette pour défiler",
                    x, bas - 10, Hud.GRIS, false);
        }
    }

    // ---------- Onglet Feuille de route ----------

    private void feuilleDeRoute(GuiGraphics g, JsonObject ecran, int x, int y, int largeur, int bas) {
        String objectif = Pont.texte(ecran, "objectif");
        if (!objectif.isEmpty()) {
            y = ecrire(g, "✦ Maintenant : " + objectif, x, y, largeur, Hud.OR) + 4;
        }
        JsonArray feuille = Pont.liste(ecran, "feuille_de_route");
        if (feuille.isEmpty()) {
            ecrire(g, "Pas de feuille de route. Demande-la : « fais-moi une feuille de route pour passer en LV ».",
                    x, y, largeur, Hud.GRIS);
            return;
        }
        int numero = 1;
        for (JsonElement e : feuille) {
            if (y > bas) {
                return;
            }
            y = ecrire(g, numero++ + ". " + e.getAsString(), x, y, largeur, Hud.BLEU) + 3;
        }
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

    private int ecrire(GuiGraphics g, String texte, int x, int y, int largeur, int couleur) {
        Font font = this.font;
        for (FormattedCharSequence l : font.split(Component.literal(texte), largeur)) {
            g.drawString(font, l, x, y, couleur, false);
            y += LIGNE;
        }
        return y;
    }
}
