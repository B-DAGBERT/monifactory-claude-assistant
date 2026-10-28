package fr.assistantia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Les touches du mod (modifiables dans Options > Contrôles) : Y ouvre l'écran de l'assistant ; I ouvre la fiche
 * « à quoi ça sert » de l'objet survolé dans un inventaire ou dans EMI, sinon du bloc regardé, sinon de l'objet en
 * main (Maj + I : toujours l'objet en main).
 */
public final class Touches {
    public static final KeyMapping OUVRIR = new KeyMapping("key.assistantia.ouvrir", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y, "key.categories.assistantia");
    public static final KeyMapping INFO = new KeyMapping("key.assistantia.info", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I, "key.categories.assistantia");

    private Touches() {
    }

    public static void enregistrer(RegisterKeyMappingsEvent event) {
        event.register(OUVRIR);
        event.register(INFO);
        TouchesMac.enregistrer(event);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Pont.rafraichir();
        TouchesMac.tick();
        Minecraft mc = Minecraft.getInstance();
        while (OUVRIR.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new EcranAssistant());
            }
        }
        while (INFO.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                infoEnJeu(mc);
            }
        }
    }

    /** En jeu : le bloc regardé, sinon l'objet en main. */
    private static void infoEnJeu(Minecraft mc) {
        if (!Screen.hasShiftDown() && mc.level != null && mc.hitResult instanceof BlockHitResult bloc
                && bloc.getType() == HitResult.Type.BLOCK) {
            ItemStack stack = mc.level.getBlockState(bloc.getBlockPos())
                    .getCloneItemStack(bloc, mc.level, bloc.getBlockPos(), mc.player);
            if (!stack.isEmpty()) {
                EcranInfo.ouvrir(stack, "bloc", bloc.getBlockPos());
                return;
            }
        }
        ItemStack main = mc.player.getMainHandItem();
        if (main.isEmpty()) {
            mc.player.displayClientMessage(Component.literal("Regarde un bloc ou prends un objet en main, puis I"), true);
        } else {
            EcranInfo.ouvrir(main, "main", null);
        }
    }

    /** Dans un écran (inventaire, coffre, machine, EMI) : l'objet sous la souris. Post : seulement si l'écran
     *  n'a pas déjà utilisé la touche (texte tapé dans une recherche, par exemple). */
    @SubscribeEvent
    public static void toucheEcran(ScreenEvent.KeyPressed.Post event) {
        Screen ecran = event.getScreen();
        if (!INFO.matches(event.getKeyCode(), event.getScanCode()) || ecran instanceof EcranAssistant
                || ecran instanceof EcranInfo || ecran.getFocused() instanceof EditBox || Emi.rechercheActive()) {
            return;
        }
        ItemStack stack = ItemStack.EMPTY;
        if (ecran instanceof AbstractContainerScreen<?> conteneur) {
            Slot survol = conteneur.getSlotUnderMouse();
            if (survol != null && survol.hasItem()) {
                stack = survol.getItem();
            }
        }
        if (stack.isEmpty()) {
            stack = Emi.objetSurvole();
        }
        if (!stack.isEmpty()) {
            EcranInfo.ouvrir(stack, "inventaire", null);
        }
    }
}
