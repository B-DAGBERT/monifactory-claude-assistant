package fr.assistantia;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Trace une étiquette de {@link TraitsReperes} dans le monde, avec un tampon de lignes (celui des
 * contours, toujours visible chez le joueur), face à la caméra, au milieu de la face d'un bloc
 * tournée vers le joueur. Partagé par les rangs des machines et les quantités des drawers.
 */
public final class TraceReperes {
    /** Hauteur des chiffres près du bloc, en blocs ; ils grossissent avec la distance. */
    public static final float HAUTEUR = 0.34F;

    /** L'étiquette est posée devant le bloc, du côté du joueur. */
    public static final double AVANCE = 0.62;

    private TraceReperes() {}

    /**
     * @param symboles chiffres et marques de {@link TraitsReperes#dessiner}
     * @param argb couleur des traits
     */
    public static void tracer(
            VertexConsumer lignes,
            Matrix4f matrice,
            Matrix3f normales,
            Camera camera,
            BlockPos pos,
            String symboles,
            int argb) {
        var dessin = TraitsReperes.dessiner(symboles);
        if (dessin.traits().isEmpty()) return;
        var oeil = camera.getPosition();
        Vector3f gauche = camera.getLeftVector(), haut = camera.getUpVector();
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
