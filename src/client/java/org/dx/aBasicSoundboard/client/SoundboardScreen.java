package org.dx.aBasicSoundboard.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.dx.aBasicSoundboard.ABasicSoundboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Soundboard GUI with search, volume control, and format indicators.
 * Uses only basic MC 26.1 widgets that are known to exist.
 */
public class SoundboardScreen extends Screen {

    private final SoundboardManager manager = SoundboardManager.getInstance();

    private EditBox searchField;
    private Button reloadButton;
    private Button stopAllButton;
    private Button volumeDownBtn;
    private Button volumeUpBtn;
    private Button volumeLabelBtn;

    private final List<Button> soundButtons = new ArrayList<>();
    private String searchText = "";
    private int scrollOffset = 0;
    private int lastKnownSoundCount = -1;

    private static final int TEXT_WHITE = 0xFFFFFF;
    private static final int TEXT_GRAY = 0xAAAAAA;
    private static final int TEXT_DIM = 0x666666;
    private static final int ACCENT = 0x44AAFF;
    private static final int PLAYING_COLOR = 0xFFAA44;

    public SoundboardScreen() {
        super(Component.literal("Soundboard"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2;

        // === Search bar ===
        searchField = new EditBox(
                this.font,
                centerX - 150, 48,
                300, 18,
                Component.literal("Search")
        );
        searchField.setResponder(this::onSearchChanged);
        searchField.setHint(Component.literal("Search sounds..."));
        this.addRenderableWidget(searchField);

        // === Volume controls ===
        int volY = 72;
        volumeDownBtn = Button.builder(
                Component.literal("[-]"),
                btn -> changeVolume(-0.1f)
        ).bounds(centerX - 150, volY, 40, 18).build();
        this.addRenderableWidget(volumeDownBtn);

        volumeUpBtn = Button.builder(
                Component.literal("[+]"),
                btn -> changeVolume(0.1f)
        ).bounds(centerX + 110, volY, 40, 18).build();
        this.addRenderableWidget(volumeUpBtn);

        volumeLabelBtn = Button.builder(
                Component.literal("Volume: " + (int) (manager.getGlobalVolume() * 100) + "%"),
                btn -> changeVolume(0.05f)
        ).bounds(centerX - 108, volY, 216, 18).build();
        volumeLabelBtn.active = false; // Just a label, not clickable
        this.addRenderableWidget(volumeLabelBtn);

        // === Sound buttons ===
        rebuildSoundButtons();

        // === Bottom controls ===
        int bottomY = height - 30;
        reloadButton = Button.builder(
                Component.literal("Reload"),
                btn -> onReloadClicked()
        ).bounds(centerX - 150, bottomY, 100, 20).build();
        this.addRenderableWidget(reloadButton);

        stopAllButton = Button.builder(
                Component.literal("Stop All"),
                btn -> onStopAllClicked()
        ).bounds(centerX - 45, bottomY, 100, 20).build();
        this.addRenderableWidget(stopAllButton);

        Button closeBtn = Button.builder(
                Component.literal("Close"),
                btn -> onClose()
        ).bounds(centerX + 60, bottomY, 90, 20).build();
        this.addRenderableWidget(closeBtn);
    }

    private void changeVolume(float delta) {
        manager.setGlobalVolume(Math.max(0.0f, Math.min(1.0f, manager.getGlobalVolume() + delta)));
        if (volumeLabelBtn != null) {
            volumeLabelBtn.setMessage(Component.literal("Volume: " + (int) (manager.getGlobalVolume() * 100) + "%"));
        }
    }

    @Override
    public void tick() {
        super.tick();
        int currentCount = manager.getLoadedSoundIds().size();
        if (currentCount > 0 && currentCount != lastKnownSoundCount) {
            lastKnownSoundCount = currentCount;
            this.scrollOffset = 0;
            this.searchText = "";
            if (searchField != null) {
                searchField.setValue("");
            }
            rebuildSoundButtons();
        }
        // Update volume label
        if (volumeLabelBtn != null && volumeLabelBtn.active == false) {
            volumeLabelBtn.setMessage(Component.literal("Volume: " + (int) (manager.getGlobalVolume() * 100) + "%"));
        }
    }

    private void onSearchChanged(String text) {
        this.searchText = text.toLowerCase().trim();
        this.scrollOffset = 0;
        rebuildSoundButtons();
    }

    private void onReloadClicked() {
        reloadButton.setMessage(Component.literal("Loading..."));
        reloadButton.active = false;
        manager.reloadSounds().thenAccept(count -> {
            minecraft.execute(() -> {
                reloadButton.setMessage(Component.literal("Reload"));
                reloadButton.active = true;
                lastKnownSoundCount = manager.getLoadedSoundIds().size();
                rebuildSoundButtons();
            });
        });
    }

    private void onStopAllClicked() {
        manager.stopAllSounds();
    }

    private void rebuildSoundButtons() {
        for (Button btn : soundButtons) {
            this.removeWidget(btn);
        }
        soundButtons.clear();

        List<String> allSounds = manager.getLoadedSoundIds();
        List<String> filtered;
        if (searchText.isEmpty()) {
            filtered = allSounds;
        } else {
            filtered = new ArrayList<>();
            for (String id : allSounds) {
                if (id.toLowerCase().contains(searchText)) {
                    filtered.add(id);
                }
            }
        }

        int startX = width / 2 - 150;
        int startY = 98;
        int buttonWidth = 300;
        int buttonHeight = 22;
        int spacing = 3;
        int visibleArea = height - startY - 80;
        int maxVisible = Math.max(1, visibleArea / (buttonHeight + spacing));

        for (int i = 0; i < Math.min(filtered.size(), maxVisible); i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;

            String soundId = filtered.get(idx);

            // Determine file type badge
            short[] data = manager.getSoundData(soundId);
            String ext;
            if (data != null && data.length > 500000) {
                ext = "MP3";
            } else if (data != null) {
                ext = "WAV";
            } else {
                ext = "???";
            }

            String displayText = "> " + soundId + "  [" + ext + "]";

            Button playBtn = Button.builder(
                    Component.literal(displayText),
                    btn -> {
                        ABasicSoundboard.LOGGER.info("Playing: {}", soundId);
                        manager.playSound(soundId);
                    }
            ).bounds(startX, startY + i * (buttonHeight + spacing), buttonWidth, buttonHeight).build();
            this.addRenderableWidget(playBtn);
            soundButtons.add(playBtn);
        }

        lastKnownSoundCount = allSounds.size();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<String> filtered = getFilteredSounds();
        int startY = 98;
        int buttonHeight = 25;
        int visibleArea = height - startY - 80;
        int maxVisible = Math.max(1, visibleArea / buttonHeight);
        int maxScroll = Math.max(0, filtered.size() - maxVisible);

        if (verticalAmount > 0 && scrollOffset > 0) {
            scrollOffset--;
            rebuildSoundButtons();
            return true;
        } else if (verticalAmount < 0 && scrollOffset < maxScroll) {
            scrollOffset++;
            rebuildSoundButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private List<String> getFilteredSounds() {
        List<String> all = manager.getLoadedSoundIds();
        if (searchText.isEmpty()) return all;
        List<String> result = new ArrayList<>();
        for (String id : all) {
            if (id.toLowerCase().contains(searchText)) {
                result.add(id);
            }
        }
        return result;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = width / 2;

        // Title
        String title = "SOUNDBOARD";
        graphics.text(this.font, title, centerX - this.font.width(title) / 2, 5, ACCENT, true);

        // Status
        List<String> allSounds = manager.getLoadedSoundIds();
        String status;
        if (allSounds.isEmpty()) {
            status = "Place .mp3 or .wav files in your soundboard folder";
        } else {
            status = allSounds.size() + " sounds loaded";
            if (manager.isCurrentlyPlaying()) {
                status += " | Playing...";
            }
        }
        int statusColor = manager.isCurrentlyPlaying() ? PLAYING_COLOR : TEXT_GRAY;
        graphics.text(this.font, status, 5, 30, statusColor, false);

        // Empty state
        if (allSounds.isEmpty()) {
            String dir = manager.getSoundDirectory();
            String msg1 = "No sounds found";
            String msg2 = "Folder: " + dir;
            graphics.text(this.font, msg1, centerX - this.font.width(msg1) / 2, height / 2 - 20, TEXT_WHITE, false);
            graphics.text(this.font, msg2, centerX - this.font.width(msg2) / 2, height / 2, TEXT_DIM, false);
        } else if (getFilteredSounds().isEmpty() && !searchText.isEmpty()) {
            String msg = "No sounds match \"" + searchText + "\"";
            graphics.text(this.font, msg, centerX - this.font.width(msg) / 2, height / 2, TEXT_DIM, false);
        }

        // Scroll indicators
        List<String> filtered = getFilteredSounds();
        int startY = 98;
        int buttonHeight = 25;
        int visibleArea = height - startY - 80;
        int maxVisible = Math.max(1, visibleArea / buttonHeight);
        if (filtered.size() > maxVisible) {
            if (scrollOffset > 0) {
                String up = "^";
                graphics.text(this.font, up, centerX - this.font.width(up) / 2, startY - 14, TEXT_DIM, false);
            }
            if (scrollOffset < filtered.size() - maxVisible) {
                String down = "v";
                graphics.text(this.font, down, centerX - this.font.width(down) / 2, height - 38, TEXT_DIM, false);
            }
        }
    }
}
