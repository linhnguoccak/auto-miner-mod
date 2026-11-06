package com.albscript.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AutoMinerMod implements ClientModInitializer {
    public static final String MOD_ID = "auto-miner";
    public static final String PREFIX = "§b[AutoMiner]§r ";
    private static KeyBinding toggleKey;
    private static KeyBinding frameKey;
    public static AutoMinerController controller;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J,
            KeyBinding.Category.MISC));
        frameKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.frame", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
            KeyBinding.Category.MISC));
        controller = new AutoMinerController();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (toggleKey.wasPressed()) controller.toggle(client);
            while (frameKey.wasPressed())  controller.handleFrameKey(client);
            if (controller.isActive())     controller.tick(client);
        });
    }

    public static void msg(MinecraftClient client, String message) {
        if (client.player != null)
            client.player.sendMessage(Text.literal(PREFIX + message), false);
    }
}
