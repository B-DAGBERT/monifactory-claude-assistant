package fr.assistantia;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/** Des lignes dessinées par-dessus tout (sans test de profondeur) : on voit le contour à travers les murs. */
final class Lignes extends RenderType {
    static final RenderType TRAVERSANTES = create("assistantia_lignes", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 256, false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(3.0)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(ITEM_ENTITY_TARGET)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false));

    private Lignes(String nom, VertexFormat format, VertexFormat.Mode mode, int taille, boolean a, boolean b,
                   Runnable avant, Runnable apres) {
        super(nom, format, mode, taille, a, b, avant, apres);
    }
}
