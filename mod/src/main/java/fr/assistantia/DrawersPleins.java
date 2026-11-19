package fr.assistantia;

import com.mojang.blaze3d.vertex.BufferBuilder;
import fr.assistantia.compat.storage.CapaciteDrawers;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Les repères sont calculés depuis les chunks du client, hors du rendu. */
public final class DrawersPleins {
    private static final int RAYON = 32, INTERVALLE = 10;
    private static final int ROUGE = 0xFFFF4B42, ORANGE = 0xFFFFA030;
    private static final MultiBufferSource.BufferSource LIGNES =
            MultiBufferSource.immediate(new BufferBuilder(1024));
    private static Map<BlockPos, Integer> marques = Map.of();
    private static ClientLevel niveau;
    private static int attente;

    private DrawersPleins() {}

    public static Map<BlockPos, Integer> marques() {
        return ReglageDrawers.actif() && Minecraft.getInstance().level == niveau
                ? marques
                : Map.of();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (!ReglageDrawers.actif()
                || mc.level == null
                || mc.player == null
                || !CapaciteDrawers.disponible()) {
            niveau = null;
            marques = Map.of();
            attente = 0;
            return;
        }
        if (niveau != mc.level) {
            niveau = mc.level;
            attente = 0;
        }
        if (attente > 0) {
            attente--;
            return;
        }
        attente = INTERVALLE - 1;
        var centre = mc.player.blockPosition();
        Map<BlockPos, Integer> repere = new HashMap<>();
        for (int x = (centre.getX() - RAYON) >> 4; x <= (centre.getX() + RAYON) >> 4; x++)
            for (int z = (centre.getZ() - RAYON) >> 4; z <= (centre.getZ() + RAYON) >> 4; z++) {
                var chunk = niveau.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var bloc : chunk.getBlockEntities().values()) {
                    var pos = bloc.getBlockPos();
                    if (bloc.isRemoved() || pos.distSqr(centre) > RAYON * RAYON) continue;
                    switch (CapaciteDrawers.etat(bloc)) {
                        case PLEIN -> repere.put(pos.immutable(), ROUGE);
                        case PLEIN_VOID -> repere.put(pos.immutable(), ORANGE);
                        case NON_PLEIN -> {}
                    }
                }
            }
        marques = Map.copyOf(repere);
    }

    @SubscribeEvent
    public static void rendu(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var visibles = marques();
        if (visibles.isEmpty()) return;
        var camera = event.getCamera();
        var pose = event.getPoseStack().last();
        var type = Lignes.filons(3, true);
        var traits = LIGNES.getBuffer(type);
        for (var marque : visibles.entrySet()) {
            var pos = marque.getKey();
            if (Vec3.atCenterOf(pos).distanceToSqr(camera.getPosition()) > RAYON * RAYON) continue;
            TraceReperes.tracerCroix(
                    traits, pose.pose(), pose.normal(), camera, pos, marque.getValue());
        }
        LIGNES.endBatch(type);
    }
}
