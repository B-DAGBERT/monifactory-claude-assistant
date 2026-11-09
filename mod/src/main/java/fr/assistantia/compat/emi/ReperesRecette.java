package fr.assistantia.compat.emi;

import fr.assistantia.Reperage;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * Rangs du plan au-dessus des machines surlignées en rouge : numéro de l'étape, étoile sur la
 * machine de l'étape en cours, grisé quand l'étape attend. Les repères sont calculés pendant le
 * tick ; le rendu se contente de les écrire.
 */
final class ReperesRecette {
    private static final int OR = 0xFFFFC94A,
            BLANC = 0xFFF2F2F2,
            GRIS = 0xB09AA3AE,
            BLEU = 0xFF8FC7FF,
            FOND = 0x80000000,
            FOND_GRIS = 0x50000000;

    /** Hauteur du texte au-dessus du bas de la machine : lisible même entourée d'autres blocs. */
    static final double HAUTEUR = 1.3;

    private static volatile Map<BlockPos, RangsRecette.Repere> reperes = Map.of();

    private ReperesRecette() {}

    static void publier(Map<BlockPos, RangsRecette.Repere> nouveaux) {
        reperes = Map.copyOf(nouveaux);
    }

    static Map<BlockPos, RangsRecette.Repere> reperes() {
        return reperes;
    }

    /** Appelé après les contours rouges, dans la même étape de rendu. */
    static void dessiner(RenderLevelStageEvent event) {
        var actuels = reperes;
        if (actuels.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        // Le tampon principal du jeu, comme les noms au-dessus des entités : le chemin le plus sûr
        // avec les mods qui regroupent les rendus (ImmediatelyFast, Embeddium).
        var tampons = mc.renderBuffers().bufferSource();
        for (var entree : actuels.entrySet()) {
            var pos = entree.getKey();
            var repere = entree.getValue();
            if (!mc.level.hasChunkAt(pos) || mc.level.getBlockState(pos).isAir()) continue;
            int couleur =
                    repere.etoile()
                            ? OR
                            : repere.attenue() ? GRIS : repere.texte().contains("·") ? BLEU : BLANC;
            Reperage.texte(
                    pose,
                    tampons,
                    mc.font,
                    camera,
                    pos.getX() + 0.5,
                    pos.getY() + HAUTEUR,
                    pos.getZ() + 0.5,
                    repere.texte(),
                    couleur,
                    repere.attenue() ? FOND_GRIS : FOND);
        }
        tampons.endBatch();
    }
}
