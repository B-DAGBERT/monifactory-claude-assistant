package fr.assistantia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Faisceaux dans le monde, comme ceux des balises : bleu vers la destination du guidage (coffre, filon, base...),
 * rouge ou jaune, plus court, au-dessus d'une machine qui a un problème.
 */
public final class Guidage {
    private Guidage() {
    }

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || Pont.cibles().isEmpty()) {
            return;
        }
        String dimension = mc.level.dimension().location().toString();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource tampons = mc.renderBuffers().bufferSource();
        long temps = mc.level.getGameTime();
        for (Pont.Cible c : Pont.cibles()) {
            if (c.dimension() != null && !c.dimension().equals(dimension)) {
                continue;
            }
            double dx = c.x() + 0.5 - mc.player.getX();
            double dz = c.z() + 0.5 - mc.player.getZ();
            if (c.hauteur() > 100 && dx * dx + dz * dz < 4) {
                continue; // arrivé : le faisceau gênerait
            }
            pose.pushPose();
            pose.translate(c.x() - camera.x, c.y() + 1 - camera.y, c.z() - camera.z);
            BeaconRenderer.renderBeaconBeam(pose, tampons, BeaconRenderer.BEAM_LOCATION, event.getPartialTick(), 1.0F,
                    temps, 0, c.hauteur(), c.couleur(), 0.15F, 0.2F);
            pose.popPose();
        }
        tampons.endBatch();
    }
}
