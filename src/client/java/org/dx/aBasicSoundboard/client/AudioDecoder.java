package org.dx.aBasicSoundboard.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;

/**
 * Decodes audio files (.mp3 and .wav) into 16-bit PCM samples at 48kHz mono.
 */
public class AudioDecoder {

    private static final int TARGET_SAMPLE_RATE = 48000;

    /**
     * Decode an audio file (.mp3 or .wav) into 16-bit PCM samples at 48kHz mono.
     */
    public static short[] decodeToPcm(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp3")) {
            return decodeMp3(file);
        } else if (name.endsWith(".wav")) {
            return decodeWav(file);
        } else {
            throw new IOException("Unsupported audio format: " + name + " (only .mp3 and .wav supported)");
        }
    }

    /**
     * Decode MP3 using JavaZoom JLayer.
     */
    private static short[] decodeMp3(File mp3File) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(mp3File);
             java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {

            javazoom.jl.decoder.Bitstream bitstream = new javazoom.jl.decoder.Bitstream(bis);
            javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();

            javazoom.jl.decoder.Header header;
            while ((header = bitstream.readFrame()) != null) {
                try {
                    javazoom.jl.decoder.SampleBuffer output = (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (output != null) {
                        short[] buffer = output.getBuffer();
                        int length = output.getBufferLength();
                        for (int i = 0; i < length; i++) {
                            baos.write(buffer[i] & 0xFF);
                            baos.write((buffer[i] >> 8) & 0xFF);
                        }
                    }
                } catch (javazoom.jl.decoder.JavaLayerException e) {
                    // Skip bad frames
                }
                bitstream.closeFrame();
            }

            byte[] rawPcm = baos.toByteArray();
            if (rawPcm.length == 0) {
                throw new IOException("Failed to decode MP3: " + mp3File.getName());
            }

            // Convert bytes to shorts (little-endian)
            short[] pcm = bytesToShorts(rawPcm);

            // Resample to 48kHz if needed
            int sourceRate = 44100; // JLayer typically outputs 44.1kHz
            if (sourceRate != TARGET_SAMPLE_RATE) {
                pcm = resamplePcm(pcm, sourceRate, TARGET_SAMPLE_RATE);
            }

            // Convert stereo to mono
            pcm = stereoToMono(pcm);

            return pcm;
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("Failed to decode MP3: " + e.getMessage(), e);
        }
    }

    /**
     * Decode WAV using Java's built-in AudioSystem.
     */
    private static short[] decodeWav(File wavFile) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile)) {
            AudioFormat format = ais.getFormat();

            // Read all audio data
            int bytesPerFrame = format.getFrameSize();
            if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                bytesPerFrame = 1;
            }

            byte[] audioBytes = ais.readAllBytes();
            if (audioBytes.length == 0) {
                throw new IOException("WAV file is empty: " + wavFile.getName());
            }

            // Convert to shorts based on format
            short[] pcm;
            if (format.getSampleSizeInBits() == 16) {
                pcm = bytesToShorts(audioBytes, format.isBigEndian());
            } else if (format.getSampleSizeInBits() == 8) {
                // 8-bit audio: convert unsigned bytes to signed shorts
                pcm = new short[audioBytes.length];
                for (int i = 0; i < audioBytes.length; i++) {
                    pcm[i] = (short) (((audioBytes[i] & 0xFF) - 128) * 256);
                }
            } else {
                throw new IOException("Unsupported WAV bit depth: " + format.getSampleSizeInBits());
            }

            // Resample if needed
            int sourceRate = (int) format.getSampleRate();
            if (sourceRate != TARGET_SAMPLE_RATE && sourceRate > 0) {
                pcm = resamplePcm(pcm, sourceRate, TARGET_SAMPLE_RATE);
            }

            // Convert stereo to mono if needed
            if (format.getChannels() > 1) {
                pcm = stereoToMono(pcm);
            }

            return pcm;
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV format: " + e.getMessage(), e);
        }
    }

    private static short[] bytesToShorts(byte[] bytes) {
        return bytesToShorts(bytes, true); // default little-endian
    }

    private static short[] bytesToShorts(byte[] bytes, boolean bigEndian) {
        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            int b1 = bytes[i * 2] & 0xFF;
            int b2 = bytes[i * 2 + 1] & 0xFF;
            shorts[i] = bigEndian ? (short) ((b1 << 8) | b2) : (short) ((b2 << 8) | b1);
        }
        return shorts;
    }

    private static short[] resamplePcm(short[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            return input;
        }

        double ratio = (double) outputRate / inputRate;
        int outputLength = (int) Math.ceil(input.length * ratio);
        short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i / ratio;
            int index1 = (int) Math.floor(srcIndex);
            int index2 = Math.min(index1 + 1, input.length - 1);
            double fraction = srcIndex - index1;

            if (index1 < 0) index1 = 0;

            double sample = input[index1] * (1.0 - fraction) + input[index2] * fraction;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample)));
        }

        return output;
    }

    private static short[] stereoToMono(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            int left = stereo[i * 2];
            int right = stereo[i * 2 + 1];
            mono[i] = (short) ((left + right) / 2);
        }
        return mono;
    }
}
