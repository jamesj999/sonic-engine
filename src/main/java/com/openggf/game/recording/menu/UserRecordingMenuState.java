package com.openggf.game.recording.menu;

import com.openggf.control.InputHandler;
import com.openggf.game.recording.RecordingLaunchContext;
import com.openggf.game.recording.RecordingVersionWarning;
import com.openggf.game.recording.UserRecordingEntry;
import com.openggf.game.recording.UserRecordingManifest;
import com.openggf.game.recording.UserRecordingPlaybackOptions;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_0;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

public final class UserRecordingMenuState {
    private static final int TARGET_FRAME_STEP = 60;

    private final String gameId;
    private final List<UserRecordingEntry> entries;
    private int cursor;
    private UserRecordingPlaybackOptions options;
    private boolean promptingForTargetFrame;
    private String promptBuffer = "";
    private boolean targetPromptVisited;
    private boolean closeRequested;
    private PlaybackRequest playbackRequest;

    public UserRecordingMenuState(String gameId, List<UserRecordingEntry> entries) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.options = defaultOptions();
    }

    public void update(InputHandler input) {
        Objects.requireNonNull(input, "input");
        if (promptingForTargetFrame) {
            updatePrompt(input);
            return;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_DOWN)) {
            pressDown();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_UP)) {
            pressUp();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_LEFT)) {
            pressLeft();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_RIGHT)) {
            pressRight();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_P)) {
            pressP();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_F)) {
            pressF();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ENTER)) {
            pressEnter();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
            pressEscape();
        }
    }

    private void updatePrompt(InputHandler input) {
        for (int key = GLFW_KEY_0; key <= GLFW_KEY_9; key++) {
            if (input.isKeyPressedWithoutModifiers(key)) {
                typeDigit((char) ('0' + key - GLFW_KEY_0));
            }
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_BACKSPACE)) {
            pressBackspace();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ENTER)) {
            pressEnter();
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
            pressEscape();
        }
    }

    public void pressDown() {
        if (entries.isEmpty()) {
            return;
        }
        cursor = Math.min(entries.size() - 1, cursor + 1);
        resetOptionsForSelection();
    }

    public void pressUp() {
        if (entries.isEmpty()) {
            return;
        }
        cursor = Math.max(0, cursor - 1);
        resetOptionsForSelection();
    }

    public void pressLeft() {
        setTargetFrame(options.targetFrame() - TARGET_FRAME_STEP);
    }

    public void pressRight() {
        setTargetFrame(options.targetFrame() + TARGET_FRAME_STEP);
    }

    public void pressP() {
        options = new UserRecordingPlaybackOptions(
                options.targetFrame(),
                !options.pauseOnDesync(),
                options.fastForward());
    }

    public void pressF() {
        options = new UserRecordingPlaybackOptions(
                options.targetFrame(),
                options.pauseOnDesync(),
                !options.fastForward());
    }

    public void pressEnter() {
        if (entries.isEmpty()) {
            return;
        }
        if (promptingForTargetFrame) {
            commitPrompt();
            return;
        }
        if (targetPromptVisited) {
            pressPlay();
            return;
        }
        promptBuffer = Integer.toString(options.targetFrame());
        promptingForTargetFrame = true;
    }

    public void pressPlay() {
        UserRecordingEntry entry = selectedEntry();
        if (entry == null || !entry.isLoadable()) {
            return;
        }
        playbackRequest = new PlaybackRequest(entry, options);
    }

    public void pressEscape() {
        if (promptingForTargetFrame) {
            promptingForTargetFrame = false;
            promptBuffer = "";
            return;
        }
        closeRequested = true;
    }

    public void typeDigit(char digit) {
        if (!promptingForTargetFrame || digit < '0' || digit > '9') {
            return;
        }
        if (promptBuffer.length() < 9) {
            promptBuffer += digit;
        }
    }

    public void pressBackspace() {
        if (promptingForTargetFrame && !promptBuffer.isEmpty()) {
            promptBuffer = promptBuffer.substring(0, promptBuffer.length() - 1);
        }
    }

    private void commitPrompt() {
        int entered = promptBuffer.isBlank() ? options.targetFrame() : parsePromptBuffer();
        setTargetFrame(entered);
        promptingForTargetFrame = false;
        targetPromptVisited = true;
        promptBuffer = "";
    }

    private int parsePromptBuffer() {
        try {
            return Integer.parseInt(promptBuffer);
        } catch (NumberFormatException ex) {
            return maxTargetFrame();
        }
    }

    private void resetOptionsForSelection() {
        options = defaultOptions();
        promptingForTargetFrame = false;
        promptBuffer = "";
        targetPromptVisited = false;
    }

    private UserRecordingPlaybackOptions defaultOptions() {
        int frameCount = selectedFrameCount();
        if (frameCount <= 0) {
            return new UserRecordingPlaybackOptions(0, false, false);
        }
        return new UserRecordingPlaybackOptions(frameCount - 1, false, false);
    }

    private void setTargetFrame(int targetFrame) {
        options = new UserRecordingPlaybackOptions(
                clampTargetFrame(targetFrame),
                options.pauseOnDesync(),
                options.fastForward());
    }

    private int clampTargetFrame(int targetFrame) {
        return Math.max(0, Math.min(maxTargetFrame(), targetFrame));
    }

    private int maxTargetFrame() {
        return Math.max(0, selectedFrameCount() - 1);
    }

    private int selectedFrameCount() {
        UserRecordingEntry entry = selectedEntry();
        return entry == null ? 0 : entry.frameCount();
    }

    public String gameId() {
        return gameId;
    }

    public List<UserRecordingEntry> entries() {
        return entries;
    }

    public int cursor() {
        return cursor;
    }

    public UserRecordingEntry selectedEntry() {
        return cursor < entries.size() ? entries.get(cursor) : null;
    }

    public UserRecordingPlaybackOptions options() {
        return options;
    }

    public boolean isPromptingForTargetFrame() {
        return promptingForTargetFrame;
    }

    public String promptBuffer() {
        return promptBuffer;
    }

    public boolean consumeCloseRequested() {
        boolean result = closeRequested;
        closeRequested = false;
        return result;
    }

    public PlaybackRequest consumePlaybackRequest() {
        PlaybackRequest result = playbackRequest;
        playbackRequest = null;
        return result;
    }

    public boolean hasAmberWarning() {
        UserRecordingEntry entry = selectedEntry();
        return entry != null && entry.versionWarning() != RecordingVersionWarning.NONE;
    }

    public String warningText() {
        UserRecordingEntry entry = selectedEntry();
        if (entry == null || entry.versionWarning() == RecordingVersionWarning.NONE) {
            return null;
        }
        return switch (entry.versionWarning()) {
            case MISSING_METADATA -> "WARNING: Missing engine metadata";
            case OFFICIAL_VERSION_MISMATCH -> "WARNING: Engine version mismatch";
            case PRERELEASE_BUILD_MISMATCH -> "WARNING: Prerelease build mismatch";
            case DIRTY_BUILD -> "WARNING: Dirty build recording";
            case NONE -> null;
        };
    }

    public String selectedInfoLine() {
        List<String> lines = selectedInfoLines();
        return lines.isEmpty() ? "No recordings found." : lines.get(0);
    }

    public List<String> selectedInfoLines() {
        UserRecordingEntry entry = selectedEntry();
        if (entry == null) {
            return List.of("No recordings found.");
        }
        UserRecordingManifest manifest = entry.manifest();
        RecordingLaunchContext context = manifest == null ? null : manifest.launchContext();
        String engine = manifest == null || manifest.engineIdentity() == null
                ? "unknown" : manifest.engineIdentity().displayVersion();
        String createdAt = manifest == null || manifest.createdAt() == null
                ? "unknown" : manifest.createdAt().toString();
        String launch = context == null
                ? "Launch: unknown"
                : String.format("Launch: %s  Zone:%02X  Act:%d  Team:%s  Route:%s",
                        context.gameId(), context.zone(), context.act(), team(context), context.launchRoute());
        return List.of(
                String.format("%s  Frames:%d", entry.displayName(), entry.frameCount()),
                "File: " + entry.path().getFileName(),
                "Created: " + createdAt + "  Engine:" + engine,
                launch);
    }

    private static String team(RecordingLaunchContext context) {
        String sidekicks = context.sidekickCharacters().stream().collect(Collectors.joining("+"));
        if (sidekicks.isBlank()) {
            return context.mainCharacter();
        }
        return context.mainCharacter() + "+" + sidekicks;
    }

    public record PlaybackRequest(UserRecordingEntry entry, UserRecordingPlaybackOptions options) {
        public PlaybackRequest {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(options, "options");
        }
    }
}
