package org.dx.aBasicSoundboard.client;

import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import org.dx.aBasicSoundboard.ABasicSoundboard;
import org.lwjgl.glfw.GLFW;

/**
 * Registers key mappings for the soundboard mod.
 */
public class SoundboardKeybinds {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(ABasicSoundboard.MOD_ID, "general")
    );

    public static KeyMapping OPEN_SOUNDBOARD_KEY;
    public static KeyMapping STOP_ALL_SOUNDS_KEY;

    public static void register() {
        OPEN_SOUNDBOARD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.a-basic-soundboard.open_soundboard",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY
        ));

        STOP_ALL_SOUNDS_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.a-basic-soundboard.stop_all_sounds",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        ));

        ABasicSoundboard.LOGGER.info("Soundboard key mappings registered");
    }
}
