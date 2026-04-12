# Changelog

## [1.0.0] - 2026-04-12

### Initial Release

**A client-side soundboard mod for Minecraft Java Edition 26.1 (Tiny Takeover) using Fabric.**

Play `.mp3` and `.wav` sounds through Simple Voice Chat so nearby players can hear them.

---

### Features

- **Sound Playback**
  - Play `.mp3` files decoded via JavaZoom JLayer
  - Play `.wav` files via Java's built-in AudioSystem
  - Automatic resampling to 48kHz mono PCM (required by Simple Voice Chat)
  - Stereo-to-mono conversion for all audio formats
  - Supports any sample rate source files (auto-resampled to 48kHz)

- **Voice Chat Integration**
  - Sounds broadcast through Simple Voice Chat using `MergeClientSoundEvent` API
  - No server-side mod or plugin required
  - Nearby players with Simple Voice Chat installed hear the sounds
  - Sounds play through the player's own voice chat channel

- **Local Playback**
  - Sounds also play through your speakers so you hear them too
  - Uses JavaSound API (48kHz, 16-bit, mono)

- **Volume Control**
  - Adjustable volume (0-100%) via `+` and `-` buttons in GUI
  - Affects both voice chat broadcast and local playback

- **In-Game GUI**
  - Press **U** to open the soundboard screen
  - Scrollable list of loaded sounds with play buttons
  - Format badges showing `[MP3]` or `[WAV]` for each sound
  - Search/filter sounds by name
  - **Reload** button to rescan the soundboard folder
  - **Stop All** button to stop currently playing sounds
  - **Close** button
  - Auto-refreshes when sounds finish loading in background
  - Empty state message showing soundboard folder path

- **Sound Management**
  - Automatic sound loading from `.minecraft/soundboard/` folder
  - Launcher-compatible path resolution (works with Prism, Modrinth, PCL, etc.)
  - Async sound loading on background thread
  - Sounds sorted alphabetically

### Controls

| Key | Action |
|-----|--------|
| **U** | Open/close soundboard GUI |
| **J** | Stop all sounds |

### Technical Details

- **Target**: Minecraft 26.1, Fabric Loader 0.19.1, Java 25
- **Voice Chat API**: Simple Voice Chat 2.6.13+ (mergeAudio event)
- **Build**: Gradle 9.4.0, Fabric Loom 1.16-SNAPSHOT
- **MP3 Decoder**: JavaZoom JLayer 1.0.1 (bundled)
- **Audio format**: 48,000 Hz, 16-bit, mono PCM

### Known Limitations

- Soundboard audio only broadcasts when you are actively talking (Simple Voice Chat behavior)
- The `MergeClientSoundEvent` fires regardless of mute status, but requires Simple Voice Chat to be running
- Only `.mp3` and `.wav` formats supported
- Subfolders are not scanned (all files must be directly in the soundboard folder)

### File Structure

```
.minecraft/
  └── soundboard/          ← Place your .mp3 and .wav files here
      ├── my_sound.mp3
      ├── effect.wav
      └── ...
```
