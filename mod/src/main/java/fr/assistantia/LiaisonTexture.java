package fr.assistantia;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

/** Préserve la texture courante pendant une mise à jour de la lightmap. */
final class LiaisonTexture {
    private LiaisonTexture() {}

    static void preserver(Runnable miseAJour) {
        RenderSystem.assertOnRenderThread();
        int unite = GlStateManager._getActiveTexture();
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            miseAJour.run();
        } finally {
            // DynamicTexture.upload() lie sa texture sans rétablir celle du rendu en cours.
            RenderSystem.activeTexture(unite);
            RenderSystem.bindTexture(texture);
        }
    }
}
