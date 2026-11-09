package fr.assistantia.compat.emi;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.assistantia.TraceReperes;
import fr.assistantia.TraitsReperes;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.RenderLevelStageEvent;

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
        var pose = event.getPoseStack().last();
        for (var entree : actuels.entrySet()) {
            var pos = entree.getKey();
            var repere = entree.getValue();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockState(pos).isAir()) continue;
            TraceReperes.tracer(
                    lignes,
                    pose.pose(),
                    pose.normal(),
                    event.getCamera(),
                    pos,
                    TraitsReperes.symboles(repere.texte()),
                    couleur(repere));
        }
    }
}
