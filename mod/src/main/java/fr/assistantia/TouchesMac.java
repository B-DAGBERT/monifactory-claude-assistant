package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** On Mac, forward the original F7–F10 shortcuts without a global keyboard hook. */
public final class TouchesMac {
    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
    private static final String[] NAMES = {"f7", "f8", "f9", "f10"};
    private static final KeyMapping[] KEYS = {
        key("objectif", GLFW.GLFW_KEY_F7), key("parler", GLFW.GLFW_KEY_F8),
        key("panneau", GLFW.GLFW_KEY_F9), key("voix", GLFW.GLFW_KEY_F10)
    };
    private static final Path PATH = FMLPaths.GAMEDIR.get().resolve("kubejs/assistant/codex_touches.json");
    private static final String SESSION = UUID.randomUUID().toString();
    private static final ArrayDeque<JsonObject> EVENTS = new ArrayDeque<>();
    private static Set<String> previous = Set.of();
    private static long sequence, lastWrite, lastError;

    private TouchesMac() { }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.assistantia." + name, InputConstants.Type.KEYSYM, code,
                "key.categories.assistantia");
    }

    public static void enregistrer(RegisterKeyMappingsEvent event) {
        if (MAC) for (KeyMapping key : KEYS) event.register(key);
    }

    public static void tick() {
        if (!MAC) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        Set<String> pressed = new LinkedHashSet<>();
        if (mc.isWindowActive() && mc.player != null) {
            long handle = mc.getWindow().getWindow();
            for (int i = 0; i < KEYS.length; i++) {
                InputConstants.Key key = KEYS[i].getKey();
                boolean down = key.getType() == InputConstants.Type.MOUSE
                        ? GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS
                        : key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 0
                            && InputConstants.isKeyDown(handle, key.getValue());
                if (down) pressed.add(NAMES[i]);
                while (KEYS[i].consumeClick()) { /* transitions below handle repeats */ }
            }
            if (InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_SHIFT)
                    || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_SHIFT)) pressed.add("shift");
            if (InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL)) pressed.add("ctrl");
        }
        if (!pressed.equals(previous)) {
            JsonObject event = new JsonObject();
            event.addProperty("n", ++sequence);
            event.addProperty("t", now);
            JsonArray keys = new JsonArray();
            pressed.forEach(keys::add);
            event.add("keys", keys);
            EVENTS.add(event);
            while (EVENTS.size() > 64) EVENTS.removeFirst();
            previous = pressed;
        }
        if (now - lastWrite < 100) return;
        lastWrite = now;
        JsonObject state = new JsonObject();
        state.addProperty("session", SESSION);
        state.addProperty("t", now);
        JsonArray events = new JsonArray();
        EVENTS.forEach(events::add);
        state.add("events", events);
        try {
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling("codex_touches.json.tmp");
            Files.writeString(temporary, state.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (now - lastError > 30_000) {
                lastError = now;
                LogUtils.getLogger().warn("Assistant Codex : raccourcis Mac non transmis", e);
            }
        }
    }
}
