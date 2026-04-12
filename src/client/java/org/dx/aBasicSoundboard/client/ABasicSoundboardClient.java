package org.dx.aBasicSoundboard.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.dx.aBasicSoundboard.ABasicSoundboard;

public class ABasicSoundboardClient implements ClientModInitializer {

    private static boolean guiOpen = false;
    private static boolean soundsLoaded = false;

    @Override
    public void onInitializeClient() {
        // Register key mappings
        SoundboardKeybinds.register();

        // Start loading sounds immediately
        SoundboardManager.getInstance().loadSounds().thenAccept(count -> {
            ABasicSoundboard.LOGGER.info("Initial sound scan complete: {} sounds found", count);
        });
        soundsLoaded = true;

        // Register client tick event to handle key mappings
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Open soundboard GUI
            while (SoundboardKeybinds.OPEN_SOUNDBOARD_KEY.consumeClick()) {
                if (guiOpen) {
                    client.setScreen(null);
                    guiOpen = false;
                } else {
                    client.setScreen(new SoundboardScreen());
                    guiOpen = true;
                }
            }

            // Stop all sounds
            while (SoundboardKeybinds.STOP_ALL_SOUNDS_KEY.consumeClick()) {
                SoundboardManager manager = SoundboardManager.getInstance();
                manager.stopAllSounds();
                if (client.player != null) {
                    client.player.sendSystemMessage(
                            Component.translatable("message.a-basic-soundboard.stopped_all")
                    );
                }
            }
        });

        // Track when GUI closes
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Screen current = client.screen;
            if (guiOpen && !(current instanceof SoundboardScreen)) {
                guiOpen = false;
            }
        });

        ABasicSoundboard.LOGGER.info("Soundboard client initialized");
    }
}
