package fr.assistantia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Les coffres qui contiennent ce que tu cherches (« trouve-moi mes torches ») : un contour cyan qui pulse, visible
 * à travers les murs, et au-dessus ce qu'il y a dedans (« Torch ×18 »), lisible même de loin.
 */
public final class Reperage {
    private Reperage() {
    }

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || Pont.reperes().isEmpty()) {
            return;
        }
        String dimension = mc.level.dimension().location().toString();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource tampons = mc.renderBuffers().bufferSource();
        float pulsation = (float) (0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 220.0));
        VertexConsumer lignes = tampons.getBuffer(Lignes.TRAVERSANTES);
        for (Pont.Repere r : Pont.reperes()) {
            if (visible(r, dimension)) {
                AABB boite = new AABB(r.x(), r.y(), r.z(), r.x() + 1, r.y() + 1, r.z() + 1).inflate(0.02)
                        .move(-camera.x, -camera.y, -camera.z);
                LevelRenderer.renderLineBox(pose, lignes, boite, 0.25F, 0.88F, 1.0F, pulsation);
            }
        }
        tampons.endBatch(Lignes.TRAVERSANTES);
        Font font = mc.font;
        for (Pont.Repere r : Pont.reperes()) {
            if (!visible(r, dimension) || r.texte().isEmpty()) {
                continue;
            }
            double dx = r.x() + 0.5 - camera.x, dy = r.y() + 1.4 - camera.y, dz = r.z() + 0.5 - camera.z;
            float echelle = 0.025F * (float) Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz) / 8); // lisible de loin
            pose.pushPose();
            pose.translate(dx, dy, dz);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(-echelle, -echelle, echelle);
            font.drawInBatch(r.texte(), -font.width(r.texte()) / 2F, 0, 0xFF9FEFFF, false, pose.last().pose(), tampons,
                    Font.DisplayMode.SEE_THROUGH, 0x70000000, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        tampons.endBatch();
    }

    private static boolean visible(Pont.Repere r, String dimension) {
        return r.dimension().isEmpty() || r.dimension().equals(dimension);
    }
}
