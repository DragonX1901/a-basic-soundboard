package org.dx.aBasicSoundboard.client;

import net.minecraft.client.Minecraft;
import org.dx.aBasicSoundboard.ABasicSoundboard;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central manager for the soundboard. Handles sound loading, local playback,
 * and broadcasting through Simple Voice Chat.
 */
public class SoundboardManager {

    private static SoundboardManager instance;

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private volatile float globalVolume = 1.0f;

    private final Map<String, short[]> loadedSounds = new LinkedHashMap<>();
    private final ExecutorService playbackExecutor = Executors.newCachedThreadPool();

    private String soundDirectory;

    private SoundboardManager() {
    }

    public static SoundboardManager getInstance() {
        if (instance == null) {
            instance = new SoundboardManager();
        }
        return instance;
    }

    public String getSoundDirectory() {
        if (soundDirectory == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                soundDirectory = new File(mc.gameDirectory, "soundboard").getAbsolutePath();
            } else {
                soundDirectory = new File(System.getProperty("user.home"), ".minecraft/soundboard").getAbsolutePath();
            }
        }
        return soundDirectory;
    }

    public CompletableFuture<Integer> loadSounds() {
        return CompletableFuture.supplyAsync(this::loadSoundsInternal, playbackExecutor);
    }

    private int loadSoundsInternal() {
        try {
            String dirPath = getSoundDirectory();
            ABasicSoundboard.LOGGER.info("Scanning for sounds in: {}", dirPath);

            int loaded = 0;
            File dir = new File(dirPath);
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    ABasicSoundboard.LOGGER.info("Created soundboard directory: {}", dirPath);
                }
                return 0;
            }

            File[] allFiles = dir.listFiles();
            if (allFiles != null) {
                ABasicSoundboard.LOGGER.info("Found {} total files in soundboard directory", allFiles.length);
            }

            File[] files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp3") || lower.endsWith(".wav");
            });
            if (files == null) {
                return 0;
            }

            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

            for (File file : files) {
                String name = file.getName();
                String id = name.substring(0, name.lastIndexOf('.'));
                try {
                    short[] pcmData = AudioDecoder.decodeToPcm(file);
                    loadedSounds.put(id, pcmData);
                    loaded++;
                    ABasicSoundboard.LOGGER.info("Loaded sound: {} ({} samples, {})", id, pcmData.length, name.substring(name.lastIndexOf('.') + 1).toUpperCase());
                } catch (IOException e) {
                    ABasicSoundboard.LOGGER.error("Failed to load sound {}: {}", file.getName(), e.getMessage());
                }
            }

            ABasicSoundboard.LOGGER.info("=== Loaded {}/{} audio files from {} ===", loaded, files.length, dirPath);
            return loaded;
        } catch (Exception e) {
            ABasicSoundboard.LOGGER.error("Sound loading task crashed: {}", e.getMessage(), e);
            return 0;
        }
    }

    public CompletableFuture<Integer> reloadSounds() {
        loadedSounds.clear();
        return loadSounds();
    }

    public List<String> getLoadedSoundIds() {
        return new ArrayList<>(loadedSounds.keySet());
    }

    @Nullable
    public short[] getSoundData(String id) {
        return loadedSounds.get(id);
    }

    public void setGlobalVolume(float volume) {
        this.globalVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public float getGlobalVolume() {
        return globalVolume;
    }

    /**
     * Play a sound: locally through JavaSound AND broadcast through voice chat.
     */
    public void playSound(String soundId) {
        short[] pcmData = getSoundData(soundId);
        if (pcmData == null) {
            ABasicSoundboard.LOGGER.warn("Sound not found: {}", soundId);
            return;
        }

        if (isPlaying.get()) {
            stopAllSounds();
        }

        isPlaying.set(true);
        playbackExecutor.submit(() -> {
            try {
                // Apply volume
                short[] audioData = pcmData;
                if (globalVolume != 1.0f) {
                    audioData = applyVolume(pcmData, globalVolume);
                }

                // 1. Broadcast through voice chat
                SoundboardVoicechatPlugin.queueAudioForBroadcast(audioData);
                ABasicSoundboard.LOGGER.info("Broadcasting sound to voice chat: {} ({} samples, vol={})", soundId, audioData.length, globalVolume);

                // 2. Play locally
                playLocallyViaJavaSound(soundId, audioData);

                ABasicSoundboard.LOGGER.info("Finished playing: {}", soundId);
            } catch (Exception e) {
                ABasicSoundboard.LOGGER.error("Error playing sound {}: {}", soundId, e.getMessage(), e);
            } finally {
                isPlaying.set(false);
                SoundboardVoicechatPlugin.stopBroadcasting();
            }
        });
    }

    private short[] applyVolume(short[] pcm, float volume) {
        short[] result = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            result[i] = (short) (pcm[i] * volume);
        }
        return result;
    }

    private void playLocallyViaJavaSound(String soundId, short[] pcmData) {
        try {
            AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, pcmData.length * 2);
            line.start();

            byte[] buffer = new byte[pcmData.length * 2];
            for (int i = 0; i < pcmData.length; i++) {
                buffer[i * 2] = (byte) (pcmData[i] & 0xFF);
                buffer[i * 2 + 1] = (byte) ((pcmData[i] >> 8) & 0xFF);
            }

            line.write(buffer, 0, buffer.length);
            line.drain();
            line.stop();
            line.close();
        } catch (LineUnavailableException e) {
            ABasicSoundboard.LOGGER.error("Failed to play sound locally: {}", e.getMessage());
        }
    }

    public void stopAllSounds() {
        if (isPlaying.get()) {
            ABasicSoundboard.LOGGER.info("Stopping all sounds");
            isPlaying.set(false);
            SoundboardVoicechatPlugin.stopBroadcasting();
        }
    }

    public boolean isCurrentlyPlaying() {
        return isPlaying.get();
    }

    public void shutdown() {
        stopAllSounds();
        playbackExecutor.shutdown();
        loadedSounds.clear();
    }
}
