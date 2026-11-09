package fr.assistantia;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Des lignes dessinées par-dessus tout (sans test de profondeur) : on voit le contour à travers les
 * murs.
 */
public final class Lignes extends RenderType {
    public static final RenderType TRAVERSANTES =
            create(
                    "assistantia_lignes",
                    DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES,
                    256,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_LINES_SHADER)
                            .setLineState(new LineStateShard(OptionalDouble.of(3.0)))
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(ITEM_ENTITY_TARGET)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .createCompositeState(false));

    public static final RenderType FILONS_CACHES = filons("assistantia_filons_caches", false);
    public static final RenderType FILONS_VISIBLES = filons("assistantia_filons_visibles", true);

    /** Faces translucides des minerais surlignés (option Remplissage). */
    public static final RenderType FILONS_FACES_CACHEES =
            faces("assistantia_filons_faces_c", false);

    public static final RenderType FILONS_FACES_VISIBLES =
            faces("assistantia_filons_faces_v", true);

    private static final Map<Integer, RenderType> CONTOURS = new HashMap<>();

    private static RenderType filons(String nom, boolean profondeur) {
        return contours(nom, 1.0, profondeur);
    }

    private static RenderType contours(String nom, double largeur, boolean profondeur) {
        return create(
                nom,
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                256,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(largeur)))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(profondeur ? LEQUAL_DEPTH_TEST : NO_DEPTH_TEST)
                        .createCompositeState(false));
    }

    /**
     * Contours de filons d'une épaisseur donnée (pixels, arrondie au quart), créés à la demande. Le
     * shader des lignes de 1.20.1 élargit chaque segment à l'écran selon cette épaisseur.
     */
    public static RenderType filons(float largeur, boolean profondeur) {
        int quart = Math.max(4, Math.min(64, Math.round(largeur * 4)));
        return CONTOURS.computeIfAbsent(
                quart * 2 + (profondeur ? 1 : 0),
                k ->
                        contours(
                                "assistantia_filons_" + quart + (profondeur ? "_v" : "_c"),
                                quart / 4.0,
                                profondeur));
    }

    private static RenderType faces(String nom, boolean profondeur) {
        return create(
                nom,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setLayeringState(POLYGON_OFFSET_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(profondeur ? LEQUAL_DEPTH_TEST : NO_DEPTH_TEST)
                        .createCompositeState(false));
    }

    private Lignes(
            String nom,
            VertexFormat format,
            VertexFormat.Mode mode,
            int taille,
            boolean a,
            boolean b,
            Runnable avant,
            Runnable apres) {
        super(nom, format, mode, taille, a, b, avant, apres);
    }
}
