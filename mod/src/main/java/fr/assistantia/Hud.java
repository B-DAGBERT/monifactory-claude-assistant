package fr.assistantia;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.List;
import java.util.function.Supplier;

/**
 * Affichage en jeu, indépendant en deux parties : la bulle des réponses, à droite, et en haut à
 * gauche un panneau compact de l'étape à faire, fourni par {@link #source(Supplier)}. Le réglage «
 * Panneau en jeu » ne masque que le panneau : les réponses restent visibles dans leur bulle.
 */
public final class Hud {
    static final int OR = 0xFFC94A,
            BLANC = 0xF2F2F2,
            GRIS = 0x9A9A9A,
            VERT = 0x6BE36B,
            JAUNE = 0xFFE45C,
            ROUGE = 0xFF7A6B,
            BLEU = 0x8FC7FF,
            CYAN = 0x6FE3FF;
    private static final int LARGEUR = 176, BULLE = 210, ICONE = 16;
    private static final String[] FLECHES = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /**
     * L'étape affichée en haut à gauche : une icône, un titre doré et un détail gris, chacun
     * facultatif (une icône vide et des textes vides ne dessinent rien).
     */
    public record Etape(ItemStack icone, String titre, String detail) {}

    private static final Supplier<Etape> AUCUNE = () -> null;
    private static volatile Supplier<Etape> source = AUCUNE;
    private static boolean erreurSignalee;

    private Hud() {}

    /**
     * Branche la source du panneau en haut à gauche ; {@code null} la débranche. Elle est lue à
     * chaque image sur le thread de rendu : elle doit rendre une valeur déjà calculée, sans calcul
     * lourd ni lecture de fichier. Une source qui renvoie {@code null} ou lève une exception ne
     * dessine rien.
     */
    public static void source(Supplier<Etape> source) {
        Hud.source = source == null ? AUCUNE : source;
        erreurSignalee = false;
    }

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
        float echelle = Math.min(1F, largeurEcran * 0.24F / LARGEUR);
        JsonObject hud = Pont.partie("hud");
        if (!hud.has("visible") || Pont.vrai(hud, "visible")) {
            panneau(g, mc, echelle);
        }
        bulle(g, mc, largeurEcran, hauteurEcran, echelle);
    }

    private static Etape etape() {
        try {
            return source.get();
        } catch (RuntimeException | LinkageError e) {
            if (!erreurSignalee) {
                erreurSignalee = true; // une seule trace, pas une par image
                LogUtils.getLogger().warn("Assistant : étape du panneau indisponible", e);
            }
            return null;
        }
    }

    private static String nettoyer(String texte) {
        return texte == null ? "" : texte.strip();
    }

    /** Le panneau compact : icône, titre doré et détail gris, coupés par « … » si besoin. */
    private static void panneau(GuiGraphics g, Minecraft mc, float echelle) {
        Etape etape = etape();
        if (etape == null) {
            return;
        }
        String titre = nettoyer(etape.titre()), detail = nettoyer(etape.detail());
        ItemStack icone = etape.icone() == null ? ItemStack.EMPTY : etape.icone();
        int lignes = (titre.isEmpty() ? 0 : 1) + (detail.isEmpty() ? 0 : 1);
        if (lignes == 0 && icone.isEmpty()) {
            return;
        }
        Font font = mc.font;
        int hauteur =
                Math.max(icone.isEmpty() ? 0 : ICONE + 2 * Theme.ECART, lignes * Theme.LIGNE + 6);
        int gauche = Theme.BARRE + Theme.ECART;
        int texteX = icone.isEmpty() ? gauche : gauche + ICONE + Theme.ECART;
        int place = LARGEUR - texteX - Theme.ECART;
        g.pose().pushPose();
        g.pose().translate(4, 15, 0); // sous le compteur de FPS
        g.pose().scale(echelle, echelle, 1);
        Theme.panneau(g, 0, 0, LARGEUR, hauteur);
        if (!icone.isEmpty()) {
            g.renderItem(icone, gauche, (hauteur - ICONE) / 2);
        }
        int y = (hauteur - lignes * Theme.LIGNE) / 2 + 2;
        if (!titre.isEmpty()) {
            Theme.texte(g, font, titre, texteX, y, place, Theme.ACCENT);
            y += Theme.LIGNE;
        }
        if (!detail.isEmpty()) {
            Theme.texte(g, font, detail, texteX, y, place, Theme.TEXTE_SECONDAIRE);
        }
        g.pose().popPose();
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
}
