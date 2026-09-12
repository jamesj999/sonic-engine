package com.openggf.game.recording.menu;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import com.openggf.game.MenuStyle;
import com.openggf.game.MenuTextEditor;
import java.util.ArrayList;
import com.openggf.game.recording.UserRecordingEntry;
import com.openggf.game.recording.UserRecordingPlaybackOptions;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

@com.openggf.game.ModApi
public final class UserRecordingMenu {
    private static final Logger LOGGER = Logger.getLogger(UserRecordingMenu.class.getName());

    private final UserRecordingMenuState state;
    private final PixelFont font;
    private InputHandler menuInput;
    private final PlaybackStarter playbackStarter;
    private String statusText;
    private boolean optionsOpen;
    private boolean detailsOpen;
    private int optionCursor;
    private int detailScroll;
    private MenuTextEditor targetEditor;

    public UserRecordingMenu(String gameId,
            List<UserRecordingEntry> entries,
            PixelFont font,
            PlaybackStarter playbackStarter) {
        this.state = new UserRecordingMenuState(gameId, entries);
        this.font = font;
        this.playbackStarter = Objects.requireNonNull(playbackStarter, "playbackStarter");
    }

    public void update(InputHandler input) {
        menuInput = input;
        if (targetEditor != null) {
            targetEditor.update(input);
            switch (targetEditor.consumeResult()) {
                case ACCEPTED -> {
                    try {
                        state.setTargetFrame(Integer.parseInt(targetEditor.value()));
                        targetEditor = null;
                    } catch (NumberFormatException invalid) {
                        targetEditor.reject("Enter a whole frame number");
                    }
                }
                case CANCELLED -> targetEditor = null;
                case NONE -> { }
            }
            return;
        }
        if (detailsOpen) {
            if (MenuInput.back(input)) detailsOpen = false;
            else if (MenuInput.up(input)) detailScroll = Math.max(0, detailScroll - 1);
            else if (MenuInput.down(input)) detailScroll++;
            return;
        }
        if (optionsOpen) {
            if (MenuInput.back(input)) { optionsOpen = false; return; }
            if (MenuInput.up(input)) optionCursor = Math.floorMod(optionCursor - 1, 5);
            if (MenuInput.down(input)) optionCursor = (optionCursor + 1) % 5;
            if (MenuInput.left(input) || MenuInput.right(input)) {
                if (optionCursor == 0) {
                    if (MenuInput.left(input)) state.pressLeft(); else state.pressRight();
                } else if (optionCursor == 1) state.pressP();
                else if (optionCursor == 2) state.pressF();
            }
            if (MenuInput.accept(input)) {
                switch (optionCursor) {
                    case 0 -> targetEditor = new MenuTextEditor("TARGET FRAME", Integer.toString(state.options().targetFrame()), 9);
                    case 1 -> state.pressP();
                    case 2 -> state.pressF();
                    case 3 -> state.pressPlay();
                    case 4 -> { detailsOpen = true; detailScroll = 0; }
                    default -> { }
                }
            }
        } else if (MenuInput.accept(input) && state.selectedEntry() != null) {
            optionsOpen = true;
            optionCursor = 3;
            return;
        } else {
            state.update(input);
        }
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

    private String confirmHint() { return menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput); }
    private String backHint() { return menuInput == null ? "Esc" : MenuInput.backLabel(menuInput); }

    public void render() {
        if (font == null) return;
        if (targetEditor != null) { targetEditor.render(font, 320); return; }
        font.beginMegaBatch();
        try {
            if (detailsOpen) renderDetails();
            else if (optionsOpen) renderOptions();
            else renderLibrary();
        } finally {
            font.endMegaBatch();
        }
    }

    private void renderLibrary() {
        List<UserRecordingEntry> entries = state.entries();
        MenuStyle.page(font, 320, "RECORDINGS", state.gameId().toUpperCase() + "  "
                + (entries.isEmpty() ? 0 : state.cursor() + 1) + "/" + entries.size());
        if (entries.isEmpty()) {
            MenuStyle.panel(font, 9, 56, 302, 99);
            line("No recordings found", 17, 70, 286, 1, .75f, .3f);
            line("Record a run while playing to add it here.", 17, 94, 286, 1, 1, 1);
            line("Recording preferences are in Settings.", 17, 118, 286, .75f, .85f, 1);
        } else {
            int first = Math.max(0, Math.min(state.cursor() - 3, entries.size() - 8));
            for (int i = first, y = 53; i < Math.min(entries.size(), first + 8); i++, y += 12) {
                boolean selected = i == state.cursor();
                if (selected) MenuStyle.focus(font, 9, y - 2, 302, 12);
                MenuStyle.label(font, (selected ? "> " : "  ") + entries.get(i).displayName(), 13, y, 292,
                        selected ? 1 : .7f, selected ? 1 : .78f, 1);
            }
            MenuStyle.panel(font, 9, 153, 302, 41);
            List<String> info = state.selectedInfoLines();
            for (int i = 0; i < Math.min(3, info.size()); i++)
                line(info.get(i), 13, 157 + i * 12, 292, .83f, .88f, 1);
        }
        MenuStyle.footer(font, 320, "Up/Down Select recording", confirmHint() + " Options   " + backHint() + " Back");
    }

    private void renderOptions() {
        MenuStyle.page(font, 320, "RECORDING OPTIONS", state.selectedEntry().displayName());
        String[] labels = {"Target frame", "Pause on desync", "Fast forward", "Play recording", "Details / warnings"};
        String[] values = {Integer.toString(state.options().targetFrame()), state.options().pauseOnDesync() ? "On" : "Off",
                state.options().fastForward() ? "On" : "Off", "", ""};
        for (int i = 0; i < labels.length; i++) {
            int y = 52 + i * 24;
            MenuStyle.panel(font, 9, y, 302, 21);
            if (optionCursor == i) MenuStyle.focus(font, 9, y, 302, 21);
            MenuStyle.label(font, labels[i], 17, y + 6, 188, 1, 1, 1);
            boolean changed = i == 0 ? state.options().targetFrame() != Math.max(0, state.selectedEntry().frameCount() - 1)
                    : i == 1 ? state.options().pauseOnDesync() : i == 2 && state.options().fastForward();
            MenuStyle.label(font, values[i], 211, y + 6, 92, 1, changed ? .72f : 1, changed ? .25f : 1);
        }
        String warning = statusText != null ? statusText : state.warningText();
        if (warning != null) line(warning, 9, 182, 302, 1, .65f, .3f);
        MenuStyle.footer(font, 320, "Up/Down Option   Left/Right Change",
                confirmHint() + (optionCursor == 3 ? " Play" : " Select") + "   " + backHint() + " Recordings");
    }

    private void renderDetails() {
        List<String> lines = new ArrayList<>();
        List<String> messages = new ArrayList<>(state.selectedInfoLines());
        if (state.warningText() != null) messages.add(state.warningText());
        if (statusText != null) messages.add(statusText);
        for (String message : messages) {
            for (int start = 0; start < message.length(); start += 48)
                lines.add(message.substring(start, Math.min(message.length(), start + 48)));
        }
        detailScroll = Math.min(detailScroll, Math.max(0, lines.size() - 12));
        MenuStyle.page(font, 320, "RECORDING DETAILS", state.selectedEntry().displayName());
        MenuStyle.panel(font, 9, 48, 302, 145);
        for (int i = detailScroll, y = 53; i < Math.min(lines.size(), detailScroll + 12); i++, y += 12)
            line(lines.get(i), 13, y, 292, .9f, .94f, 1);
        MenuStyle.footer(font, 320, "Up/Down Scroll details", backHint() + " Options");
    }

    private void line(String text, int x, int y, int width, float r, float g, float b) {
        MenuStyle.text(font, text, x, y, width, r, g, b);
    }

    public boolean consumeCloseRequested() {
        return state.consumeCloseRequested();
    }

    public UserRecordingMenuState state() {
        return state;
    }

    @FunctionalInterface
    @com.openggf.game.ModApi
    public interface PlaybackStarter {
        void start(UserRecordingEntry entry, UserRecordingPlaybackOptions options) throws Exception;
    }
}
