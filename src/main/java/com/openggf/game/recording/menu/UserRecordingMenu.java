package com.openggf.game.recording.menu;

import com.openggf.control.InputHandler;
import com.openggf.graphics.PixelFont;
import com.openggf.game.recording.UserRecordingEntry;
import com.openggf.game.recording.UserRecordingPlaybackOptions;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UserRecordingMenu {
    private static final Logger LOGGER = Logger.getLogger(UserRecordingMenu.class.getName());
    private static final float SCALE = 0.5f;
    private static final int LINE_HEIGHT = 6;
    private static final int LIST_TOP = 18;
    private static final int LIST_BOTTOM = 150;

    private final UserRecordingMenuState state;
    private final PixelFont font;
    private final PlaybackStarter playbackStarter;
    private String statusText;

    public UserRecordingMenu(String gameId,
            List<UserRecordingEntry> entries,
            PixelFont font,
            PlaybackStarter playbackStarter) {
        this.state = new UserRecordingMenuState(gameId, entries);
        this.font = font;
        this.playbackStarter = Objects.requireNonNull(playbackStarter, "playbackStarter");
    }

    public void update(InputHandler input) {
        state.update(input);
        UserRecordingMenuState.PlaybackRequest request = state.consumePlaybackRequest();
        if (request != null) {
            try {
                playbackStarter.start(request.entry(), request.options());
                statusText = null;
            } catch (Exception ex) {
                statusText = "Playback failed: " + ex.getMessage();
                LOGGER.log(Level.WARNING, "Failed to start user recording playback", ex);
            }
        }
    }

    public void render() {
        if (font == null) {
            return;
        }
        font.beginMegaBatch();
        try {
            renderContents();
        } finally {
            font.endMegaBatch();
        }
    }

    private void renderContents() {
        List<UserRecordingEntry> entries = state.entries();
        font.drawText(String.format("RECORDINGS: %s   (%d/%d)",
                        state.gameId().toUpperCase(), entries.isEmpty() ? 0 : state.cursor() + 1, entries.size()),
                8, 6, SCALE, 1f, 1f, 1f, 1f);

        if (entries.isEmpty()) {
            font.drawText("No recordings found.", 8, 28, SCALE, 1f, 0.5f, 0.5f, 1f);
            font.drawText("Esc Back", 8, 44, SCALE, 0.7f, 0.7f, 0.7f, 1f);
            return;
        }

        int visibleRows = visibleRows();
        int first = Math.max(0, Math.min(state.cursor(), entries.size() - visibleRows));
        int last = Math.min(entries.size() - 1, first + visibleRows - 1);
        int y = LIST_TOP;
        if (first > 0) {
            font.drawText("  ^ more above", 12, y, SCALE, 0.7f, 0.7f, 0.4f, 1f);
            y += LINE_HEIGHT;
        }
        for (int i = first; i <= last; i++) {
            UserRecordingEntry entry = entries.get(i);
            boolean selected = i == state.cursor();
            float brightness = selected ? 1f : 0.62f;
            String prefix = selected ? ">" : " ";
            font.drawText(prefix + " " + entry.displayName(), 12, y, SCALE,
                    brightness, brightness, brightness, 1f);
            y += LINE_HEIGHT;
        }
        if (last < entries.size() - 1) {
            font.drawText("  v more below", 12, y, SCALE, 0.7f, 0.7f, 0.4f, 1f);
        }

        renderInfoPanel();
    }

    private int visibleRows() {
        return Math.max(1, (LIST_BOTTOM - LIST_TOP) / LINE_HEIGHT - 1);
    }

    private void renderInfoPanel() {
        int y = 160;
        for (String line : state.selectedInfoLines()) {
            font.drawText(line, 4, y, SCALE, 0.9f, 0.9f, 0.9f, 1f);
            y += LINE_HEIGHT;
        }
        if (state.selectedEntry() != null) {
            font.drawText(String.format("Target:%d  P:%s  F:%s",
                            state.options().targetFrame(),
                            state.options().pauseOnDesync() ? "on" : "off",
                            state.options().fastForward() ? "on" : "off"),
                    4, y, SCALE, 0.85f, 0.85f, 0.85f, 1f);
            y += LINE_HEIGHT;
        }
        String warningText = state.warningText();
        if (warningText != null) {
            font.drawText(warningText, 4, y, SCALE, 1f, 0.64f, 0.12f, 1f);
            y += LINE_HEIGHT;
        }
        if (state.isPromptingForTargetFrame()) {
            font.drawText("Target frame: " + state.promptBuffer(), 4, y, SCALE,
                    1f, 1f, 0.7f, 1f);
            y += LINE_HEIGHT;
        }
        if (statusText != null && !statusText.isBlank()) {
            font.drawText(statusText, 4, y, SCALE, 1f, 0.4f, 0.4f, 1f);
            y += LINE_HEIGHT;
        }
        font.drawText("Enter Target/Play   P Pause   F Fast   Esc Back", 4, y, SCALE,
                0.65f, 0.65f, 0.65f, 1f);
    }

    public boolean consumeCloseRequested() {
        return state.consumeCloseRequested();
    }

    public UserRecordingMenuState state() {
        return state;
    }

    @FunctionalInterface
    public interface PlaybackStarter {
        void start(UserRecordingEntry entry, UserRecordingPlaybackOptions options) throws Exception;
    }
}
