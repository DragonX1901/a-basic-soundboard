package org.dx.aBasicSoundboard;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ABasicSoundboard implements ModInitializer {

    public static final String MOD_ID = "a-basic-soundboard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("A Basic Soundboard initialized");
    }
}
