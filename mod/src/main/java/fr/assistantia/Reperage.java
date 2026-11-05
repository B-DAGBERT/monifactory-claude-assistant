package fr.assistantia;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Les coffres qui contiennent ce que tu cherches (« trouve-moi mes torches ») : un contour cyan qui
 * pulse, visible à travers les murs, et au-dessus ce qu'il y a dedans (« Torch ×18 »), lisible même
 * de loin.
 */
public final class Reperage {
    // Un tampon propre évite de vider les dessins des coffres et des autres mods.
    private static final MultiBufferSource.BufferSource TAMPONS =
            MultiBufferSource.immediate(new BufferBuilder(1024));

    private Reperage() {}

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var reperes = RechercheEmi.actif() ? RechercheEmi.reperes() : Pont.reperes();
        if (mc.level == null || mc.player == null || reperes.isEmpty()) {
            return;
        }
        String dimension = mc.level.dimension().location().toString();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource tampons = TAMPONS;
        float pulsation = (float) (0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 220.0));
        VertexConsumer lignes = tampons.getBuffer(Lignes.TRAVERSANTES);
        for (Pont.Repere r : reperes) {
            if (visible(r, dimension)) {
                boolean resultat = RechercheEmi.resultatPret(r);
                float rouge = resultat ? 1F : 0.35F;
                float vert = resultat ? 0.8F : 0.95F;
                float bleu = resultat ? 0.15F : 1F;
                BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
                List<AABB> formes = List.of(new AABB(pos));
                if (mc.level.hasChunkAt(pos)) {
                    var state = mc.level.getBlockState(pos);
                    if (state.isAir()) continue; // Coffre cassé depuis le dernier relevé.
                    var shape = state.getShape(mc.level, pos);
                    if (!shape.isEmpty())
                        formes = shape.toAabbs().stream().map(box -> box.move(pos)).toList();
                }
                for (AABB forme : formes) {
                    AABB boite = forme.move(-camera.x, -camera.y, -camera.z);
                    LevelRenderer.renderLineBox(
                            pose, lignes, boite.inflate(0.025), rouge, vert, bleu, 0.25F);
                    LevelRenderer.renderLineBox(
                            pose,
                            lignes,
                            boite.inflate(0.006),
                            rouge,
                            vert,
                            bleu,
                            0.85F + pulsation * 0.15F);
                }
            }
        }
        tampons.endBatch(Lignes.TRAVERSANTES);
        if (RechercheEmi.actif())
            return; // Des contours uniquement, sans texte empilé sur les coffres.
        Font font = mc.font;
        for (Pont.Repere r : reperes) {
            if (!visible(r, dimension) || r.texte().isEmpty()) {
                continue;
            }
            double dx = r.x() + 0.5 - camera.x,
                    dy = r.y() + 1.4 - camera.y,
                    dz = r.z() + 0.5 - camera.z;
            float echelle =
                    0.025F
                            * (float)
                                    Math.max(
                                            1,
                                            Math.sqrt(dx * dx + dy * dy + dz * dz)
                                                    / 8); // lisible de loin
            pose.pushPose();
            try {
                pose.translate(dx, dy, dz);
                pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
                pose.scale(-echelle, -echelle, echelle);
                font.drawInBatch(
                        r.texte(),
                        -font.width(r.texte()) / 2F,
                        0,
                        0xFF9FEFFF,
                        false,
                        pose.last().pose(),
                        tampons,
                        Font.DisplayMode.SEE_THROUGH,
                        0x70000000,
                        LightTexture.FULL_BRIGHT);
            } finally {
                pose.popPose();
            }
        }
        tampons.endBatch();
    }

    private static boolean visible(Pont.Repere r, String dimension) {
        return r.dimension().isEmpty() || r.dimension().equals(dimension);
    }
}
