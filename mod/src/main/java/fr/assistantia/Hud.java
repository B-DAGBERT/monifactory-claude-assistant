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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Affichage en jeu, indépendant en deux parties : la bulle des réponses, à droite, et en haut à
 * gauche un panneau compact de l'étape à faire, fourni par {@link #source(Supplier)}. Le réglage «
 * Panneau en jeu » ne masque que le panneau : les réponses restent visibles dans leur bulle.
 *
 * <p>Sur un petit écran, les deux gardent un texte lisible (jamais sous les trois quarts de la
 * taille normale) : leur largeur se borne à une part de l'écran et le texte passe à la ligne.
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

    /**
     * Largeurs au plus à l'échelle normale, et part de la largeur d'écran qu'elles peuvent prendre.
     */
    private static final int PANNEAU = 220, BULLE = 210, ICONE = 16, LIGNES_BULLE = 9;

    private static final float PART_PANNEAU = 0.42F, PART_BULLE = 0.40F, ECHELLE_MIN = 0.75F;
    private static final String[] FLECHES = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /**
     * L'étape affichée en haut à gauche : une icône, un titre doré et un détail gris, chacun
     * facultatif (une icône vide et des textes vides ne dessinent rien).
     */
    public record Etape(ItemStack icone, String titre, String detail) {}

    private static final Supplier<Etape> AUCUNE = () -> null;
    private static volatile Supplier<Etape> source = AUCUNE;
    private static boolean erreurSignalee;

    /** Le détail découpé pour la dernière largeur : recoupé seulement quand il change. */
    private static String detailLu = "";

    private static int placeLue = -1;
    private static List<String> detailLignes = List.of();

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

    /**
     * Taille normale dès 427 pixels de large (1280 × 720 à l'échelle 3), jamais moins des trois
     * quarts : la place manquante se gagne par les largeurs bornées et les retours à la ligne.
     */
    static float echelle(int largeurEcran) {
        return Mth.clamp(largeurEcran / 427F, ECHELLE_MIN, 1F);
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
        float echelle = echelle(largeurEcran);
        JsonObject hud = Pont.partie("hud");
        if (!hud.has("visible") || Pont.vrai(hud, "visible")) {
            panneau(g, mc, largeurEcran, echelle);
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

    /** Le détail sur deux lignes au plus, la seconde finie par « … » s'il en reste. */
    private static List<String> detail(Font font, String detail, int place) {
        if (detail.equals(detailLu) && place == placeLue) return detailLignes;
        detailLu = detail;
        placeLue = place;
        List<String> lignes = new ArrayList<>();
        if (!detail.isEmpty()) {
            List<FormattedCharSequence> decoupe = font.split(Component.literal(detail), place);
            for (int i = 0; i < Math.min(2, decoupe.size()); i++) {
                StringBuilder ligne = new StringBuilder();
                decoupe.get(i)
                        .accept(
                                (index, style, point) -> {
                                    ligne.appendCodePoint(point);
                                    return true;
                                });
                String texte = ligne.toString().strip();
                if (i == 1 && decoupe.size() > 2)
                    texte = Theme.couper(font, texte + Theme.POINTS, place);
                lignes.add(texte);
            }
        }
        detailLignes = List.copyOf(lignes);
        return detailLignes;
    }

    /**
     * Le panneau compact : icône, titre doré sur une ligne et détail gris sur deux lignes au plus,
     * à la largeur de son texte dans la limite de l'écran ; « … » au bout de ce qui dépasse.
     */
    private static void panneau(GuiGraphics g, Minecraft mc, int largeurEcran, float echelle) {
        Etape etape = etape();
        if (etape == null) {
            return;
        }
        String titre = nettoyer(etape.titre()), detail = nettoyer(etape.detail());
        ItemStack icone = etape.icone() == null ? ItemStack.EMPTY : etape.icone();
        if (titre.isEmpty() && detail.isEmpty() && icone.isEmpty()) {
            return;
        }
        Font font = mc.font;
        int gauche = Theme.BARRE + Theme.ECART;
        int texteX = icone.isEmpty() ? gauche : gauche + ICONE + Theme.ECART;
        int maximum =
                Math.max(
                        texteX + 60,
                        Math.min(PANNEAU, (int) (largeurEcran * PART_PANNEAU / echelle)));
        int place = maximum - texteX - Theme.ECART - 2;
        String visible = Theme.couper(font, titre, place);
        List<String> lignesDetail = detail(font, detail, place);
        int texteLargeur = font.width(visible);
        for (String ligne : lignesDetail) texteLargeur = Math.max(texteLargeur, font.width(ligne));
        int largeur = Math.min(maximum, texteX + texteLargeur + Theme.ECART + 2);
        int lignes = (visible.isEmpty() ? 0 : 1) + lignesDetail.size();
        int hauteur =
                Math.max(icone.isEmpty() ? 0 : ICONE + 2 * Theme.ECART, lignes * Theme.LIGNE + 6);
        g.pose().pushPose();
        g.pose().translate(4, 15, 0); // sous le compteur de FPS
        g.pose().scale(echelle, echelle, 1);
        Theme.panneau(g, 0, 0, largeur, hauteur);
        if (!icone.isEmpty()) {
            g.renderItem(icone, gauche, (hauteur - ICONE) / 2);
        }
        int y = (hauteur - lignes * Theme.LIGNE) / 2 + 2;
        if (!visible.isEmpty()) {
            g.drawString(font, visible, texteX, y, Theme.ACCENT, false);
            y += Theme.LIGNE;
        }
        for (String ligne : lignesDetail) {
            g.drawString(font, ligne, texteX, y, Theme.TEXTE_SECONDAIRE, false);
            y += Theme.LIGNE;
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
        // Sous 4 d'alpha, Minecraft dessinerait le texte opaque : la bulle a fini de s'effacer.
        if (opacite < 0.04F) {
            return;
        }
        int alpha = (int) (opacite * 255) << 24;
        Font font = mc.font;
        int largeur = Math.max(90, Math.min(BULLE, (int) (largeurEcran * PART_BULLE / echelle)));
        List<FormattedCharSequence> lignes =
                font.split(
                        Component.literal(
                                texte
                                        + (enCours && (System.currentTimeMillis() / 400) % 2 == 0
                                                ? " ▌"
                                                : "")),
                        largeur - 12);
        // Au plus 9 lignes, et jamais plus haute que la moitié de l'écran : les dernières si c'est
        // long.
        int place = (int) (hauteurEcran * 0.55F / echelle) - 16;
        int visibles =
                Math.max(1, Math.min(lignes.size(), Math.min(LIGNES_BULLE, place / Theme.LIGNE)));
        int h = 16 + visibles * Theme.LIGNE;
        String moteur = Pont.texte(Pont.vue(), "moteur");
        g.pose().pushPose();
        g.pose().translate(largeurEcran - largeur * echelle - 6, hauteurEcran * 0.30F, 0);
        g.pose().scale(echelle, echelle, 1);
        g.fill(
                0,
                0,
                largeur,
                h,
                (int) ((Theme.FOND >>> 24) * opacite) << 24 | Theme.FOND & 0xFFFFFF);
        g.renderOutline(0, 0, largeur, h, alpha | Theme.BORDURE & 0xFFFFFF);
        g.fill(0, 0, Theme.BARRE, h, alpha | Theme.ACCENT & 0xFFFFFF);
        g.drawString(
                font,
                Theme.couper(font, moteur.isEmpty() ? "Assistant" : moteur, largeur - 12),
                Theme.BARRE + Theme.ECART,
                4,
                alpha | Theme.ACCENT & 0xFFFFFF,
                false);
        int y = 15;
        for (int i = lignes.size() - visibles; i < lignes.size(); i++) {
            g.drawString(
                    font,
                    lignes.get(i),
                    Theme.BARRE + Theme.ECART,
                    y,
                    alpha | Theme.TEXTE & 0xFFFFFF,
                    false);
            y += Theme.LIGNE;
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
