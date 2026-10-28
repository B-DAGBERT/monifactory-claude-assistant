package fr.assistantia;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Assistant IA (Codex) dans le jeu : écran de l'assistant, objets surlignés dans les interfaces et guidage 3D.
 * Le mod ne fait que dessiner : les calculs et Codex sont dans assistant_mc.py, avec qui il échange des
 * fichiers JSON dans kubejs/assistant (voir {@link Pont}).
 */
@Mod(AssistantIA.MODID)
public class AssistantIA {
    public static final String MODID = "assistantia";

    public AssistantIA() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.init();
        }
    }
}
