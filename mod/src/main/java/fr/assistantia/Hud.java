package fr.assistantia;

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

/** Panneau compact : plan, objectif et progression. Les réponses restent dans la bulle séparée. */
public final class Hud {
    static final int OR = 0xFFC94A,
            BLANC = 0xF2F2F2,
            GRIS = 0x9A9A9A,
            VERT = 0x6BE36B,
            JAUNE = 0xFFE45C,
            ROUGE = 0xFF7A6B,
            BLEU = 0x8FC7FF,
            CYAN = 0x6FE3FF;
    private static final int LARGEUR = 176, BULLE = 210;
    private static final String[] FLECHES = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private Hud() {}

    public static void enregistrer(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("assistant", Hud::dessiner);
    }

    private static void dessiner(
            ForgeGui gui, GuiGraphics g, float partialTick, int largeurEcran, int hauteurEcran) {
        Minecraft mc = Minecraft.getInstance();
        // Rien par-dessus les menus et les interfaces (EMI s'y affiche) : la surbrillance et «
        // Remplir » prennent le relais
        if (mc.player == null
                || mc.options.hideGui
                || mc.options.renderDebug
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
        if (!arret().isEmpty()) {
            y =
                    ecrire(
                                    g,
                                    font,
                                    arret(),
                                    2,
                                    y,
                                    LARGEUR - 4,
                                    Pont.relanceEnCours() ? GRIS : ROUGE,
                                    2)
                            + 1;
        }
        if (objectif.isEmpty()) {
            g.drawString(font, "Je prépare ton prochain objectif…", 2, y, GRIS, false);
            return;
        }
        String plan = Pont.texte(hud, "plan"); // « Plan : Passer en LV · 2/6 »
        if (!plan.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(plan, LARGEUR - 4), 2, y, GRIS, false);
            y += 10;
        }
        double progres = Mth.clamp(Pont.nombre(hud, "progres", 0), 0, 1);
        String pourcent = Math.round(progres * 100) + " %";
        g.drawString(
                font,
                font.plainSubstrByWidth("✦ " + objectif, LARGEUR - font.width(pourcent) - 8),
                2,
                y,
                OR,
                true);
        g.drawString(font, pourcent, LARGEUR - font.width(pourcent) - 2, y, GRIS, false);
        y += 11;
        g.fill(2, y, LARGEUR - 2, y + 3, 0x30FFFFFF);
        g.fill(2, y, 2 + (int) ((LARGEUR - 4) * progres), y + 3, 0xFF6BE36B);
    }

    /** Le panneau ne contient que le plan, l'objectif et sa barre d'avancement. */
    private static int hauteurPanneau(Font font, JsonObject hud) {
        int h =
                arret().isEmpty()
                        ? 0
                        : Math.min(2, font.split(Component.literal(arret()), LARGEUR - 4).size())
                                        * 10
                                + 1;
        if (Pont.texte(hud, "objectif").isEmpty()) return h + 10;
        return h + 17 + (Pont.texte(hud, "plan").isEmpty() ? 0 : 10);
    }

    /**
     * La dernière réponse de Codex ou du coach, à droite sous la mini-carte, qui s'efface toute
     * seule.
     */
    private static void bulle(
            GuiGraphics g, Minecraft mc, int largeurEcran, int hauteurEcran, float echelle) {
        JsonObject b = Pont.partie("bulle");
        String texte = Pont.texte(b, "texte");
        if (texte.isEmpty() || !CatalogueProduction.apercuCourant(b)) {
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
        List<FormattedCharSequence> lignes =
                font.split(
                        Component.literal(
                                texte
                                        + (enCours && (System.currentTimeMillis() / 400) % 2 == 0
                                                ? " ▌"
                                                : "")),
                        BULLE - 10);
        int visibles = Math.min(lignes.size(), 9);
        int h = 14 + visibles * 10;
        g.pose().pushPose();
        g.pose().translate(largeurEcran - BULLE * echelle - 6, hauteurEcran * 0.30F, 0);
        g.pose().scale(echelle, echelle, 1);
        g.fill(0, 0, BULLE, h, (int) (0xC8 * opacite) << 24 | 0x0D1013);
        g.fill(0, 0, 2, h, alpha | 0xFFC94A);
        g.drawString(
                font,
                Pont.texte(Pont.vue(), "moteur").isEmpty()
                        ? "Assistant"
                        : Pont.texte(Pont.vue(), "moteur"),
                6,
                3,
                alpha | OR,
                false);
        int y = 14;
        for (int i = lignes.size() - visibles;
                i < lignes.size();
                i++) { // les dernières lignes si c'est long
            g.drawString(font, lignes.get(i), 6, y, alpha | BLANC, false);
            y += 10;
        }
        g.pose().popPose();
    }

    /** Ce qu'il faut savoir quand l'assistant ne tourne pas (rien s'il tourne). */
    static String arret() {
        if (Pont.assistantActif()) {
            return "";
        }
        return Pont.relanceEnCours()
                ? "L'assistant démarre…"
                : "⚠ Assistant arrêté : touche Y pour le relancer";
    }

    /**
     * Distance et flèche vers la destination, recalculées à chaque image avec l'orientation du
     * joueur.
     */
    static String direction(Player p, JsonObject d) {
        double dx = Pont.nombre(d, "x", 0) + 0.5 - p.getX();
        double dz = Pont.nombre(d, "z", 0) + 0.5 - p.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 3) {
            return " · tu y es";
        }
        double angle =
                Math.toDegrees(Math.atan2(-dx, dz)); // orientation qui regarde la destination
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

    static int ecrire(
            GuiGraphics g,
            Font font,
            String texte,
            int x,
            int y,
            int largeur,
            int couleur,
            int maxLignes) {
        List<FormattedCharSequence> lignes = font.split(Component.literal(texte), largeur);
        for (int i = 0; i < Math.min(maxLignes, lignes.size()); i++) {
            g.drawString(font, lignes.get(i), x, y, couleur, false);
            y += 10;
        }
        return y;
    }
}
