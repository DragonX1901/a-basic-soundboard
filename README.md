# A Basic Soundboard - Fabric Mod for Minecraft 26.1

A client-side soundboard mod for Minecraft Java Edition 26.1 (Tiny Takeover) using Fabric. Integrates with **Simple Voice Chat** so that sounds play through the voice chat microphone channel, allowing nearby players to hear them.

## Features

- Load custom `.mp3` audio files from `.minecraft/soundboard/`
- Press **U** to open the soundboard GUI with a searchable list of sounds
- Sounds are injected into the Simple Voice Chat microphone stream
- Nearby players using Simple Voice Chat can hear the sounds
- **I** key toggles local playback preference
- **J** key stops all currently playing sounds
- Scroll through sound list when there are many sounds loaded

## Project Structure

```
A Basic Soundboard/
├── build.gradle                          # Gradle build configuration
├── gradle.properties                     # Version properties
├── settings.gradle                       # Plugin repositories
├── gradle/wrapper/
│   ├── gradle-wrapper.jar               # Gradle wrapper JAR
│   └── gradle-wrapper.properties        # Gradle 9.4.0 distribution
├── gradlew.bat                          # Gradle wrapper script (Windows)
│
├── src/main/
│   ├── java/org/dx/aBasicSoundboard/
│   │   └── ABasicSoundboard.java        # Main mod initializer (common)
│   └── resources/
│       ├── fabric.mod.json              # Mod metadata & entrypoints
│       └── a-basic-soundboard.mixins.json
│
├── src/client/
│   ├── java/org/dx/aBasicSoundboard/
│   │   ├── client/
│   │   │   ├── ABasicSoundboardClient.java      # Client initializer & keybind handling
│   │   │   ├── SoundboardManager.java            # Core sound loading & playback
│   │   │   ├── SoundboardScreen.java             # In-game GUI screen
│   │   │   ├── SoundboardKeybinds.java           # Key mapping registration
│   │   │   ├── SoundboardVoicechatPlugin.java    # Voice Chat API entrypoint
│   │   │   └── Mp3Decoder.java                   # MP3 → PCM decoder
│   │   └── mixin/client/
│   │       └── SoundboardMicrophoneMixin.java    # Microphone injection mixin
│   └── resources/
│       ├── a-basic-soundboard.client.mixins.json
│       └── assets/a-basic-soundboard/lang/
│           └── en_us.json                # English translations
│
└── soundboard/                          # Place your .mp3 files here
```

## Key Classes Explained

### `ABasicSoundboard.java` (Main)
The common mod initializer. Simply logs initialization and provides the `MOD_ID` and `LOGGER`.

### `ABasicSoundboardClient.java` (Client Initializer)
- Registers key mappings via `SoundboardKeybinds.register()`
- Initializes `SoundboardManager` and triggers initial sound loading
- Registers `ClientTickEvents` to handle keybind presses:
  - **U**: Open/close soundboard GUI
  - **I**: Toggle local playback
  - **J**: Stop all sounds
- Tracks GUI open/close state

### `SoundboardManager.java` (Core Manager)
- Singleton pattern via `getInstance()`
- **Sound Loading**: Scans `.minecraft/soundboard/` for `.mp3` files, decodes them to 48kHz 16-bit mono PCM using `Mp3Decoder`
- **Microphone Injection**: Provides `getPendingAudioChunk(sampleCount)` which returns PCM audio chunks to be mixed into the microphone stream by `SoundboardMicrophoneMixin`
- **Playback Control**: `playSound(id)`, `stopAllSounds()`, `isCurrentlyPlaying()`

### `SoundboardMicrophoneMixin.java` (Microphone Injection)
- Uses `@ModifyVariable` to intercept Simple Voice Chat's microphone audio output
- Mixes soundboard audio with the player's actual microphone input by averaging both signals
- **IMPORTANT**: The `@Mixin` target class name (`de.maxhenkel.voicechat.impl.microphone.Microphone`) and method name (`getAudio`) are placeholders based on typical Simple Voice Chat structure. You **must** verify and update these against your specific Simple Voice Chat version:
  1. Decompile the `voicechat-fabric-*.jar` file
  2. Find the class that handles microphone audio capture
  3. Identify the method that produces `short[]` audio samples
  4. Update the `@Mixin(targets = ...)` and `@ModifyVariable(method = ...)` accordingly

### `SoundboardScreen.java` (GUI)
- Uses MC 26.1's `GuiGraphicsExtractor` for rendering (not `GuiGraphics`)
- `EditBox` for searching sounds
- Dynamic `Button` widgets for each sound with a play (▶) button
- Scroll support via mouse wheel
- Status bar showing voice chat availability and loaded sound count

### `SoundboardVoicechatPlugin.java` (Voice Chat Entrypoint)
- Implements `VoicechatPlugin` registered via the `"voicechat"` entrypoint in `fabric.mod.json`
- Tracks whether Simple Voice Chat is loaded and available
- Provides `isVoicechatAvailable()` static method used by the GUI

### `Mp3Decoder.java` (MP3 Decoder)
- Uses **JavaZoom JLayer** (`javazoom:jlayer:1.0.1`) to decode MP3 files
- Converts decoded audio to 16-bit PCM `short[]` array
- Resamples from source sample rate (typically 44.1kHz) to **48kHz** using linear interpolation
- Converts stereo to mono by averaging left and right channels
- Output format matches Simple Voice Chat's expected format: **48,000 Hz, 16-bit, mono PCM**

## Build Instructions

### Prerequisites
- **Java 25** (required for MC 26.1)
- No global Gradle installation needed — the Gradle wrapper handles everything

### Build
```bash
# Set JAVA_HOME to your Java 25 installation
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"

# Build the mod
gradlew.bat build
```

The built JAR will be at:
```
build/libs/a-basic-soundboard-1.0.0.jar
```

### Run in Development
```bash
gradlew.bat runClient
```

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| Minecraft | 26.1 | Target game version |
| Fabric Loader | 0.19.1 | Mod loader |
| Fabric API | 0.145.4+26.1.2 | Core Fabric API |
| Simple Voice Chat API | 2.6.13 | Voice chat integration API |
| Simple Voice Chat (runtime) | 2.6.16+26.1.2 | Actual voice chat mod |
| JavaZoom JLayer | 1.0.1 | MP3 decoding |

### build.gradle Highlights

```groovy
repositories {
    maven {
        name = "henkelmax.public"
        url = 'https://maven.maxhenkel.de/repository/public'
    }
    maven {
        name = "Modrinth"
        url = "https://api.modrinth.com/maven"
        content { includeGroup "maven.modrinth" }
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // Simple Voice Chat API
    implementation "de.maxhenkel.voicechat:voicechat-api:${project.voicechat_api_version}"
    runtimeOnly "de.maxhenkel.voicechat:voicechat-api:${project.voicechat_api_version}:fabric-stub"
    runtimeOnly "maven.modrinth:simple-voice-chat:fabric-${project.voicechat_mod_version}"

    // MP3 decoding
    implementation "javazoom:jlayer:${project.jl_version}"
}
```

## How Simple Voice Chat AudioChannel API Works

### Server-Side API
The Simple Voice Chat API provides `VoicechatServerApi` with these key methods for audio playback:
- `createLocationalAudioChannel(UUID, ServerLevel, Position)` — creates a positional audio channel
- `createAudioPlayer(AudioChannel, OpusEncoder, short[])` — plays PCM audio through a channel
- `createEncoder()` — creates an Opus encoder for compressing PCM

The audio channel system works as follows:
1. Create an `AudioChannel` (locational, static, or entity-bound)
2. Set its category (e.g., `"master"`) and distance (hearing radius)
3. Create an `AudioPlayer` with the channel, an Opus encoder, and PCM audio data
4. Call `startPlaying()` — the API handles threading, encoding, and packet pacing

### Client-Side Limitation
**Important**: `createAudioPlayer` and `createAudioSender` are **server-side only**. The `VoicechatClientApi` provides client-side audio channel creation methods (`createLocationalAudioChannel`, etc.) but **not** audio playback methods.

### This Mod's Approach: Microphone Injection
Since there's no client-side `createAudioPlayer`, this mod uses a **mixin-based approach**:
1. `SoundboardMicrophoneMixin` targets Simple Voice Chat's microphone audio capture method
2. Uses `@ModifyVariable` at the method's return point to intercept the `short[]` microphone samples
3. Mixes in soundboard audio by averaging both signals: `mixed[i] = (mic[i] / 2) + (soundboard[i] / 2)`
4. This makes the soundboard audio appear as if it's coming from the player's microphone

## MP3 Decoding Pipeline

```
MP3 File → JavaZoom JLayer Bitstream → Decoder → SampleBuffer (short[])
    ↓
Detect sample rate (typically 44100 Hz)
    ↓
Resample to 48000 Hz (linear interpolation)
    ↓
Convert stereo to mono (average L+R)
    ↓
Output: short[] at 48kHz, 16-bit, mono PCM
```

The 48kHz target matches Simple Voice Chat's expected sample rate. The resampling uses simple linear interpolation:

```java
double ratio = (double) outputRate / inputRate;
int outputLength = (int) Math.ceil(input.length * ratio);
for (int i = 0; i < outputLength; i++) {
    double srcIndex = i / ratio;
    int index1 = (int) Math.floor(srcIndex);
    int index2 = Math.min(index1 + 1, input.length - 1);
    double fraction = srcIndex - index1;
    output[i] = (short) (input[index1] * (1.0 - fraction) + input[index2] * fraction);
}
```

## Fabric 26.1 Breaking Changes & Notes

### 1. Unobfuscated Minecraft
MC 26.1 is the first fully unobfuscated release. This means:
- **No mappings (Yarn/Mojang) needed** — Loom uses official names directly
- `mappings` line removed from `build.gradle`
- Class names use official Mojang names: `KeyMapping` (not `KeyBinding`), `Component` (not `Text`), `Minecraft` (not `MinecraftClient`)

### 2. Package Name Changes (Official Mappings)
| Old (Yarn) | New (Official) |
|---|---|
| `net.minecraft.client.util.InputUtil` | `com.mojang.blaze3d.platform.InputConstants` |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
| `net.minecraft.client.gui.DrawContext` | `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `net.minecraft.util.math.Vec3d` | `net.minecraft.world.phys.Vec3` |

### 3. GUI Rendering Changes
- `render(GuiGraphics, ...)` → `extractRenderState(GuiGraphicsExtractor, ...)`
- `keyPressed(int, int, int)` → `keyPressed(KeyEvent)` (event objects)
- `charTyped(char, int)` → `charTyped(CharacterEvent)` (event objects)

### 4. Fabric API Changes
- `KeyBindingHelper` → `KeyMappingHelper` at `net.fabricmc.fabric.api.client.keymapping.v1`
- `KeyBinding` → `KeyMapping`
- `InputUtil.Type.KEYSYM` → `InputConstants.Type.KEYSYM`
- Key mappings now require a `KeyMapping.Category` registered via `KeyMapping.Category.register(Identifier)`

### 5. `ItemStackTemplate` Removal
The deprecated `ItemStackTemplate` class has been removed. Use `ItemStack` directly.

### 6. Data Generation
The `fabric-datagen` entrypoint and `fabricApi.configureDataGeneration()` are still available but require the `fabric-data-generation-api-v1` module.

## Usage

1. Place `.mp3` files in `.minecraft/soundboard/`
2. Launch Minecraft with the mod installed (and Simple Voice Chat)
3. Press **U** in-game to open the soundboard GUI
4. Click **▶** next to any sound to play it through voice chat
5. Use the search box to filter sounds by name
6. Scroll with mouse wheel if there are many sounds
7. Press **I** to toggle local playback on/off
8. Press **J** to stop all currently playing sounds

## Important Notes

### Mixin Target Verification Required
The `SoundboardMicrophoneMixin` targets a placeholder class name. You **must** update it for your specific Simple Voice Chat version:

```java
@Mixin(targets = "de.maxhenkel.voicechat.impl.microphone.Microphone", priority = 1100)
public abstract class SoundboardMicrophoneMixin {
    @ModifyVariable(
            method = "getAudio",
            at = @At("RETURN"),
            index = 1
    )
    private short[] injectSoundboardAudio(short[] microphoneAudio) {
        // ...
    }
}
```

To find the correct target:
1. Open the `voicechat-fabric-2.6.16+26.1.2.jar` in a decompiler (e.g., Fernflower, CFR)
2. Search for classes related to "microphone" or "mic"
3. Find the method that produces `short[]` audio samples at 48kHz
4. Update the `@Mixin(targets = ...)` and `@ModifyVariable(method = ...)` values

### Client-Side Only
This mod is client-side only. No server-side installation is required, but **Simple Voice Chat must be installed on the client** for the microphone mixin to work.

### Server Not Required to Have Simple Voice Chat Plugin
Other players only need the Simple Voice Chat **client** mod installed to hear your soundboard sounds. The server does not need the Simple Voice Chat plugin.

## License

Apache-2.0
