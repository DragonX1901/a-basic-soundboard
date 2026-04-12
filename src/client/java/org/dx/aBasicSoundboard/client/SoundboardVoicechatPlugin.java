package org.dx.aBasicSoundboard.client;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.dx.aBasicSoundboard.ABasicSoundboard;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Voice Chat API entrypoint. Uses MergeClientSoundEvent to inject
 * soundboard audio into the microphone stream — no mixins needed.
 *
 * MergeClientSoundEvent is emitted on the client before microphone audio
 * is encoded. It allows merging custom audio directly into the capture stream.
 */
@Environment(EnvType.CLIENT)
public class SoundboardVoicechatPlugin implements VoicechatPlugin {

    private static final AtomicReference<short[]> pendingAudio = new AtomicReference<>();
    private static volatile int audioOffset = 0;
    private static volatile boolean isBroadcasting = false;

    @Override
    public String getPluginId() {
        return ABasicSoundboard.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        ABasicSoundboard.LOGGER.info("Soundboard Voice Chat plugin initialized - using MergeClientSoundEvent");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MergeClientSoundEvent.class, this::onMergeClientSound);
    }

    /**
     * Called before microphone audio is encoded.
     * We inject our soundboard audio here by merging it into the stream.
     */
    private void onMergeClientSound(MergeClientSoundEvent event) {
        if (!isBroadcasting) {
            return;
        }

        // Standard voice chat frame: 48kHz * 20ms = 960 samples
        // We use a smaller chunk to be safe
        short[] soundboardAudio = getPendingAudioChunk(960);
        if (soundboardAudio == null) {
            isBroadcasting = false;
            audioOffset = 0;
            return;
        }

        // Merge our soundboard audio into the microphone stream
        event.mergeAudio(soundboardAudio);
    }

    /**
     * Get a chunk of pending audio data to inject.
     * Returns null when playback is finished.
     */
    private static short[] getPendingAudioChunk(int sampleCount) {
        short[] audio = pendingAudio.get();
        if (audio == null) {
            return null;
        }

        int remaining = audio.length - audioOffset;
        if (remaining <= 0) {
            pendingAudio.set(null);
            return null;
        }

        int count = Math.min(sampleCount, remaining);
        short[] chunk = new short[count];
        System.arraycopy(audio, audioOffset, chunk, 0, count);
        audioOffset += count;

        return chunk;
    }

    /**
     * Queue audio for broadcasting through voice chat.
     */
    public static void queueAudioForBroadcast(short[] pcmData) {
        pendingAudio.set(pcmData);
        audioOffset = 0;
        isBroadcasting = true;
    }

    /**
     * Stop broadcasting audio.
     */
    public static void stopBroadcasting() {
        isBroadcasting = false;
        pendingAudio.set(null);
        audioOffset = 0;
    }

    /**
     * Check if we are currently broadcasting.
     */
    public static boolean isBroadcasting() {
        return isBroadcasting;
    }
}
