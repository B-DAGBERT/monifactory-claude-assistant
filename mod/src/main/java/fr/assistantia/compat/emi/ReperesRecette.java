package fr.assistantia.compat.emi;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.assistantia.TraitsReperes;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Rangs du plan sur les machines surlignées en rouge : numéro de l'étape, étoile sur la machine de
 * l'étape en cours, grisé quand l'étape attend, « ✓ » ou « … » pour un lot prêt ou en cours. Les
 * repères sont calculés pendant le tick ; le rendu se contente de les tracer, en segments ({@link
 * TraitsReperes}) avec les mêmes lignes que les contours rouges : la police du jeu ne s'affichait
 * pas dans le monde chez le joueur, alors que les contours, si.
 */
final class ReperesRecette {
    private static final int OR = 0xFFFFC94A,
            BLANC = 0xFFF2F2F2,
            GRIS = 0xC09AA3AE,
            BLEU = 0xFF8FC7FF;

    /** Hauteur des chiffres près de la machine, en blocs ; ils grossissent avec la distance. */
    static final float HAUTEUR = 0.34F;

    /** L'étiquette est posée devant la machine, du côté du joueur, au milieu de sa face. */
    static final double AVANCE = 0.62;

    private static volatile Map<BlockPos, RangsRecette.Repere> reperes = Map.of();

    private ReperesRecette() {}

    static void publier(Map<BlockPos, RangsRecette.Repere> nouveaux) {
        reperes = Map.copyOf(nouveaux);
    }

    static Map<BlockPos, RangsRecette.Repere> reperes() {
        return reperes;
    }

    static int couleur(RangsRecette.Repere repere) {
        return repere.etoile()
                ? OR
                : repere.attenue() ? GRIS : repere.texte().contains("·") ? BLEU : BLANC;
    }

    /** Appelé avec le tampon des contours rouges, avant son envoi. */
    static void dessiner(RenderLevelStageEvent event, VertexConsumer lignes) {
        var actuels = reperes;
        if (actuels.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = event.getCamera();
        var oeil = camera.getPosition();
        Vector3f gauche = camera.getLeftVector(), haut = camera.getUpVector();
        var pose = event.getPoseStack().last();
        Matrix4f matrice = pose.pose();
        Matrix3f normales = pose.normal();
        for (var entree : actuels.entrySet()) {
            var pos = entree.getKey();
            var repere = entree.getValue();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockState(pos).isAir()) continue;
            var dessin = TraitsReperes.dessiner(TraitsReperes.symboles(repere.texte()));
            if (dessin.traits().isEmpty()) continue;
            double cx = pos.getX() + 0.5 - oeil.x,
                    cy = pos.getY() + 0.5 - oeil.y,
                    cz = pos.getZ() + 0.5 - oeil.z;
            double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (distance > AVANCE + 0.05) {
                double k = (distance - AVANCE) / distance;
                cx *= k;
                cy *= k;
                cz *= k;
            }
            float h = HAUTEUR * (float) Math.max(1, distance / 10);
            // Coin bas gauche de l'étiquette centrée ; la droite de l'écran est l'opposé de gauche.
            float ox = (float) cx + (gauche.x() * dessin.largeur() / 2 - haut.x() / 2) * h,
                    oy = (float) cy + (gauche.y() * dessin.largeur() / 2 - haut.y() / 2) * h,
                    oz = (float) cz + (gauche.z() * dessin.largeur() / 2 - haut.z() / 2) * h;
            int argb = couleur(repere);
            for (var t : dessin.traits())
                segment(
                        lignes,
                        matrice,
                        normales,
                        ox + (-gauche.x() * t.x1() + haut.x() * t.y1()) * h,
                        oy + (-gauche.y() * t.x1() + haut.y() * t.y1()) * h,
                        oz + (-gauche.z() * t.x1() + haut.z() * t.y1()) * h,
                        ox + (-gauche.x() * t.x2() + haut.x() * t.y2()) * h,
                        oy + (-gauche.y() * t.x2() + haut.y() * t.y2()) * h,
                        oz + (-gauche.z() * t.x2() + haut.z() * t.y2()) * h,
                        argb);
        }
    }

    private static void segment(
            VertexConsumer lignes,
            Matrix4f matrice,
            Matrix3f normales,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            int argb) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-6F) return;
        dx /= l;
        dy /= l;
        dz /= l;
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        lignes.vertex(matrice, x1, y1, z1)
                .color(r, g, b, a)
                .normal(normales, dx, dy, dz)
                .endVertex();
        lignes.vertex(matrice, x2, y2, z2)
                .color(r, g, b, a)
                .normal(normales, dx, dy, dz)
                .endVertex();
    }
}
