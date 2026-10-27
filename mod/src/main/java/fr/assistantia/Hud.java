package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.List;

/**
 * Ce qui reste à l'écran pendant qu'on joue, volontairement léger : l'objectif et sa progression, l'étape en
 * cours, les trois prochains objets utiles, la direction, et seulement les alertes qui comptent. Les réponses
 * de Claude et du coach arrivent dans une bulle qui s'efface toute seule. Le détail est dans l'écran (touche Y).
 */
public final class Hud {
    static final int OR = 0xFFC94A, BLANC = 0xF2F2F2, GRIS = 0x9A9A9A, VERT = 0x6BE36B, JAUNE = 0xFFE45C,
            ROUGE = 0xFF7A6B, BLEU = 0x8FC7FF, CYAN = 0x6FE3FF;
    private static final int LARGEUR = 176, BULLE = 210;
    private static final String[] FLECHES = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private Hud() {
    }

    public static void enregistrer(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("assistant", Hud::dessiner);
    }

    private static void dessiner(ForgeGui gui, GuiGraphics g, float partialTick, int largeurEcran, int hauteurEcran) {
        Minecraft mc = Minecraft.getInstance();
        // Rien par-dessus les menus et les interfaces (EMI s'y affiche) : la surbrillance et « Remplir » prennent le relais
        if (mc.player == null || mc.options.hideGui || mc.options.renderDebug
                || mc.screen != null && !(mc.screen instanceof ChatScreen)) {
            return;
        }
        JsonObject hud = Pont.partie("hud");
        if (hud.has("visible") && !Pont.vrai(hud, "visible")) {
            return;
        }
        float echelle = Math.min(1F, largeurEcran * 0.24F / LARGEUR);
        g.pose().pushPose();
        g.pose().translate(4, 15, 0); // sous le compteur de FPS
        g.pose().scale(echelle, echelle, 1);
        panneau(g, mc, hud);
        g.pose().popPose();
        bulle(g, mc, largeurEcran, hauteurEcran, echelle);
    }

    private static void panneau(GuiGraphics g, Minecraft mc, JsonObject hud) {
        Font font = mc.font;
        String objectif = Pont.texte(hud, "objectif");
        int y = 0;
        int hauteur = hauteurPanneau(font, hud); // le fond se dessine avant le contenu
        if (hauteur == 0) {
            return;
        }
        g.fill(-3, -3, LARGEUR + 3, hauteur + 1, 0xC80D1013);
        g.fill(-3, -3, -1, hauteur + 1, 0xFFFFC94A);
        if (Pont.vrai(hud, "recharger")) {
            g.drawString(font, "⚠ F3 + T pour recharger le script", 2, y, ROUGE, false);
            y += 11;
        }
        for (JsonElement a : Pont.liste(hud, "alertes")) {
            String texte = a.getAsString();
            y = ecrire(g, font, texte, 2, y, LARGEUR - 4, texte.startsWith("⚠") ? ROUGE : JAUNE, 2) + 1;
        }
        String recherche = Pont.texte(hud, "recherche");
        if (!recherche.isEmpty()) { // ce que tu as demandé à chercher, en surbrillance dans les coffres
            y = ecrire(g, font, "⌕ " + recherche, 2, y, LARGEUR - 4, CYAN, 2) + 1;
        }
        if (objectif.isEmpty()) {
            g.drawString(font, "Je prépare ton prochain objectif…", 2, y, GRIS, false);
            return;
        }
        double progres = Mth.clamp(Pont.nombre(hud, "progres", 0), 0, 1);
        String pourcent = Math.round(progres * 100) + " %";
        g.drawString(font, font.plainSubstrByWidth("✦ " + objectif, LARGEUR - font.width(pourcent) - 8), 2, y, OR, true);
        g.drawString(font, pourcent, LARGEUR - font.width(pourcent) - 2, y, GRIS, false);
        y += 11;
        g.fill(2, y, LARGEUR - 2, y + 3, 0x30FFFFFF);
        g.fill(2, y, 2 + (int) ((LARGEUR - 4) * progres), y + 3, 0xFF6BE36B);
        y += 6;
        String etape = Pont.texte(hud, "etape");
        if (!etape.isEmpty()) {
            y = ecrire(g, font, "▶ " + etape, 2, y, LARGEUR - 4, BLANC, 2) + 2;
        }
        for (JsonElement e : Pont.liste(hud, "prochains")) {
            y = ligneObjet(g, font, e.getAsJsonObject(), 2, y);
        }
        if (hud.has("direction") && hud.get("direction").isJsonObject()) {
            JsonObject d = hud.getAsJsonObject("direction");
            String texte = "➜ " + Pont.texte(d, "label") + direction(mc.player, d);
            g.drawString(font, font.plainSubstrByWidth(texte, LARGEUR - 4), 2, y + 1, BLEU, false);
            y += 11;
        }
        String statut = Pont.texte(hud, "statut");
        if (!statut.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(statut + points(), LARGEUR - 4), 2, y + 1, GRIS, false);
        }
    }

    /** Hauteur du panneau, pour dessiner son fond avant son contenu. */
    private static int hauteurPanneau(Font font, JsonObject hud) {
        int h = Pont.vrai(hud, "recharger") ? 11 : 0;
        for (JsonElement a : Pont.liste(hud, "alertes")) {
            h += Math.min(2, font.split(Component.literal(a.getAsString()), LARGEUR - 4).size()) * 10 + 1;
        }
        String recherche = Pont.texte(hud, "recherche");
        if (!recherche.isEmpty()) {
            h += Math.min(2, font.split(Component.literal("⌕ " + recherche), LARGEUR - 4).size()) * 10 + 1;
        }
        if (Pont.texte(hud, "objectif").isEmpty()) {
            return h + 10;
        }
        h += 17;
        String etape = Pont.texte(hud, "etape");
        if (!etape.isEmpty()) {
            h += Math.min(2, font.split(Component.literal("▶ " + etape), LARGEUR - 4).size()) * 10 + 2;
        }
        h += Pont.liste(hud, "prochains").size() * 18;
        if (hud.has("direction") && hud.get("direction").isJsonObject()) {
            h += 11;
        }
        if (!Pont.texte(hud, "statut").isEmpty()) {
            h += 11;
        }
        return h;
    }

    /** Un objet utile : icône, nom, où le trouver, et le compteur (vert quand c'est bon). */
    private static int ligneObjet(GuiGraphics g, Font font, JsonObject o, int x, int y) {
        g.renderItem(Pont.objet(Pont.texte(o, "item")), x, y);
        int a = (int) Pont.nombre(o, "a", 0), besoin = (int) Pont.nombre(o, "besoin", 1);
        String compte = Math.min(a, besoin) + "/" + besoin;
        int couleurCompte = a >= besoin ? VERT : couleur(Pont.texte(o, "c"));
        int largeurNom = LARGEUR - 22 - font.width(compte) - 6;
        g.drawString(font, font.plainSubstrByWidth(Pont.texte(o, "nom"), largeurNom), x + 19, y, BLANC, false);
        g.drawString(font, compte, LARGEUR - font.width(compte) - 2, y, couleurCompte, false);
        g.pose().pushPose();
        g.pose().translate(x + 19, y + 9, 0);
        g.pose().scale(0.75F, 0.75F, 1);
        g.drawString(font, font.plainSubstrByWidth(Pont.texte(o, "ou"), (int) (largeurNom / 0.75F)), 0, 0, GRIS, false);
        g.pose().popPose();
        return y + 18;
    }

    /** La dernière réponse de Claude ou du coach, à droite sous la mini-carte, qui s'efface toute seule. */
    private static void bulle(GuiGraphics g, Minecraft mc, int largeurEcran, int hauteurEcran, float echelle) {
        JsonObject b = Pont.partie("bulle");
        String texte = Pont.texte(b, "texte");
        if (texte.isEmpty()) {
            return;
        }
        boolean enCours = Pont.vrai(b, "en_cours");
        long reste = (long) Pont.nombre(b, "fin", 0) - System.currentTimeMillis();
        if (!enCours && reste <= 0) {
            return;
        }
        float opacite = enCours ? 1F : Mth.clamp(reste / 1500F, 0F, 1F);
        int alpha = (int) (opacite * 255) << 24;
        if (alpha == 0) {
            return;
        }
        Font font = mc.font;
        List<FormattedCharSequence> lignes = font.split(Component.literal(texte + (enCours && (System.currentTimeMillis() / 400) % 2 == 0 ? " ▌" : "")), BULLE - 10);
        int visibles = Math.min(lignes.size(), 9);
        int h = 14 + visibles * 10;
        g.pose().pushPose();
        g.pose().translate(largeurEcran - BULLE * echelle - 6, hauteurEcran * 0.30F, 0);
        g.pose().scale(echelle, echelle, 1);
        g.fill(0, 0, BULLE, h, (int) (0xC8 * opacite) << 24 | 0x0D1013);
        g.fill(0, 0, 2, h, alpha | 0xFFC94A);
        g.drawString(font, "Claude", 6, 3, alpha | OR, false);
        int y = 14;
        for (int i = lignes.size() - visibles; i < lignes.size(); i++) { // les dernières lignes si c'est long
            g.drawString(font, lignes.get(i), 6, y, alpha | BLANC, false);
            y += 10;
        }
        g.pose().popPose();
    }

    /** Distance et flèche vers la destination, recalculées à chaque image avec l'orientation du joueur. */
    static String direction(Player p, JsonObject d) {
        double dx = Pont.nombre(d, "x", 0) + 0.5 - p.getX();
        double dz = Pont.nombre(d, "z", 0) + 0.5 - p.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 3) {
            return " · tu y es";
        }
        double angle = Math.toDegrees(Math.atan2(-dx, dz)); // orientation qui regarde la destination
        double relatif = ((angle - p.getYRot()) % 360 + 360) % 360;
        return " · " + Math.round(distance) + " m " + FLECHES[(int) Math.round(relatif / 45) % 8];
    }

    static int couleur(String nom) {
        return switch (nom) {
            case "rouge" -> ROUGE;
            case "jaune" -> JAUNE;
            case "vert" -> VERT;
            case "bleu" -> BLEU;
            case "or" -> OR;
            case "gris" -> GRIS;
            default -> BLANC;
        };
    }

    static int ecrire(GuiGraphics g, Font font, String texte, int x, int y, int largeur, int couleur, int maxLignes) {
        List<FormattedCharSequence> lignes = font.split(Component.literal(texte), largeur);
        for (int i = 0; i < Math.min(maxLignes, lignes.size()); i++) {
            g.drawString(font, lignes.get(i), x, y, couleur, false);
            y += 10;
        }
        return y;
    }

    private static String points() {
        return ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
    }
}
