package com.openggf.game.timeattack;

import com.openggf.game.MenuFeedback;
import static com.openggf.game.MenuFeedback.Cue.*;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import com.openggf.game.MenuStyle;
import com.openggf.game.MenuTextEditor;
import com.openggf.game.timeattack.mp.MenuTextField;

import java.util.List;
import java.util.Objects;

/**
 * Master-title sub-mode for launching a solo Time Attack run. Structural
 * template copied from {@code UserRecordingMenu}'s
 * {@code MasterTitleScreen} integration: {@link #update(InputHandler)} drives
 * a GL-free state object, {@link #render()} mega-batches the font draws, and
 * {@link #consumeCloseRequested()} tells the host when to drop the menu.
 */
@com.openggf.game.ModApi
public final class TimeAttackMenu {
    private int focus;
    private MenuTextEditor editor;

    private final TimeAttackMenuState state;
    private final PixelFont font;
    private InputHandler menuInput;
    private final LaunchStarter launchStarter;
    private final MenuTextField joinAddress = new MenuTextField(64, ".:-");
    private NetworkStarter networkStarter = NetworkStarter.NONE;

    public TimeAttackMenu(List<String> availableGameIds, String initialGameId,
            GhostStore ghostStore, PixelFont font, LaunchStarter launchStarter) {
        this.state = new TimeAttackMenuState(availableGameIds, initialGameId, ghostStore);
        this.font = font;
        this.launchStarter = Objects.requireNonNull(launchStarter, "launchStarter");
    }

    public void update(InputHandler input) {
        menuInput = input;
        if (editor != null) {
            editor.update(input);
            switch (editor.consumeResult()) {
                case ACCEPTED -> { joinAddress.setText(editor.value()); editor = null; }
                case CANCELLED -> editor = null;
                case NONE -> { }
            }
            return;
        }
        if (MenuInput.back(input)) { state.update(input); MenuFeedback.emit(CANCEL); return; }
        String before = selectionFeedbackState();
        int beforeFocus = focus;
        int count = state.visibleRows().size();
        int go = count + (state.mode() == TimeAttackMenuState.Mode.JOIN_LAN ? 1 : 0);
        if (MenuInput.up(input)) focus = Math.floorMod(focus - 1, go + 1);
        if (MenuInput.down(input)) focus = (focus + 1) % (go + 1);
        if (focus < count) {
            while (state.focusedRow() != state.visibleRows().get(focus)) state.moveFocus(1);
            if (MenuInput.left(input)) state.adjust(-1);
            if (MenuInput.right(input)) state.adjust(1);
        }
        if (beforeFocus != focus || !before.equals(selectionFeedbackState())) MenuFeedback.emit(NAVIGATE);
        if (MenuInput.accept(input)) {
            if (focus == go) {
                state.pressGo();
                if (state.currentTrack() == null || state.currentCharacter() == null) MenuFeedback.emit(ERROR);
            }
            else if (focus == count && state.mode() == TimeAttackMenuState.Mode.JOIN_LAN) {
                editor = new MenuTextEditor("JOIN ADDRESS", joinAddress.text(), 64);
                MenuFeedback.emit(CONFIRM);
            } else { focus = Math.min(focus + 1, go); MenuFeedback.emit(CONFIRM); }
        }
        TimeAttackLaunchRequest request = state.consumeLaunchRequest();
        if (request != null) {
            // A void host callback may display a failure and return normally.
            // Acknowledge the requested action before the host reports its outcome.
            MenuFeedback.emit(CONFIRM);
            try {
                switch (state.mode()) {
                    case SOLO -> launchStarter.launch(request);
                    case HOST_LAN -> networkStarter.host(request,
                            state.characterPolicy(), state.lockedCharacter(), state.windowSeconds());
                    case JOIN_LAN -> networkStarter.join(request, joinAddress.text());
                    case BROWSE -> networkStarter.browse(request,
                            state.characterPolicy(), state.lockedCharacter(), state.windowSeconds());
                }
            } catch (RuntimeException failure) { MenuFeedback.emit(ERROR); throw failure; }
        }
    }

    private String selectionFeedbackState() {
        return state.gameIndex() + ":" + state.trackIndex() + ":" + state.characterIndex()
                + ":" + state.mode() + ":" + state.characterPolicy() + ":" + state.windowSeconds();
    }

    private String confirmHint() { return menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput); }
    private String backHint() { return menuInput == null ? "Esc" : MenuInput.backLabel(menuInput); }

    public void render() {
        if (font == null) return;
        if (editor != null) { editor.render(font, 320); return; }
        MenuStyle.page(font, 320, "TIME ATTACK", "Choose a route and race mode");
        int index = 0;
        for (TimeAttackMenuState.Row row : state.visibleRows()) {
            String label;
            String value;
            switch (row) {
                case GAME -> { label = "Game"; value = state.currentGameId().toUpperCase(); }
                case TRACK -> { label = "Track"; value = state.currentTrack() == null ? "No tracks" : state.currentTrack().label(); }
                case CHARACTER -> { label = "Character"; value = state.currentCharacter() == null ? "None" : state.currentCharacter().toUpperCase(); }
                case MODE -> { label = "Mode"; value = state.mode().name().replace('_', ' '); }
                case POLICY -> { label = "Policy"; value = state.characterPolicy().equals("OPEN") ? "OPEN" : "LOCKED " + state.currentCharacter().toUpperCase(); }
                case WINDOW -> { label = "Window"; value = (state.windowSeconds() / 60) + " minutes"; }
                default -> throw new IllegalStateException("Unexpected row " + row);
            }
            drawRow(label, value, index++, false);
        }
        if (state.mode() == TimeAttackMenuState.Mode.JOIN_LAN)
            drawRow("Address", joinAddress.text().isBlank() ? "Select to enter" : joinAddress.text(), index++, true);
        String action = switch (state.mode()) {
            case SOLO -> "START RUN";
            case HOST_LAN -> "CREATE LAN ROOM";
            case JOIN_LAN -> "JOIN LAN ROOM";
            case BROWSE -> "BROWSE ROOMS";
        };
        int actionY = 47 + index * 18;
        if (focus == index) MenuStyle.focusLabel(font, 8, actionY, 304, 16);
        MenuStyle.label(font, action, 14, actionY, 292, 1, 1, 1);
        MenuStyle.text(font, (state.bestExists() ? "Best: saved" : "Best: none")
                + " / Imported ghosts: " + state.importCount(), 10, 181, 300, .7f, .8f, .9f);
        MenuStyle.footer(font, 320, "Up/Down Select  Left/Right Change",
                confirmHint() + " " + (focus == index ? "Go" : "Select") + "  " + backHint() + " Back");
    }

    private void drawRow(String label, String value, int index, boolean edit) {
        int y = 47 + index * 18;
        if (focus == index) MenuStyle.focusLabel(font, 8, y, 304, 16);
        MenuStyle.label(font, label, 14, y, 81, .7f, .8f, .9f);
        MenuStyle.label(font, (edit ? "" : "< ") + value + (edit ? "" : " >"),
                112, y, 198, 1, 1, 1);
    }

    public boolean consumeCloseRequested() {
        return state.consumeCloseRequested();
    }

    public TimeAttackMenuState state() {
        return state;
    }

    public void setNetworkStarter(NetworkStarter networkStarter) {
        this.networkStarter = Objects.requireNonNull(networkStarter, "networkStarter");
    }

    public void setJoinAddress(String address) {
        joinAddress.setText(address == null ? "" : address);
    }

    @FunctionalInterface
    @com.openggf.game.ModApi
    public interface LaunchStarter {
        void launch(TimeAttackLaunchRequest request);
    }

    @com.openggf.game.ModApi
    public interface NetworkStarter {
        NetworkStarter NONE = new NetworkStarter() {
            @Override public void host(TimeAttackLaunchRequest request, String policy,
                                       String lockedCharacter, int windowSeconds) { }
            @Override public void join(TimeAttackLaunchRequest request, String address) { }
        };

        void host(TimeAttackLaunchRequest request, String policy,
                  String lockedCharacter, int windowSeconds);

        void join(TimeAttackLaunchRequest request, String address);

        default void browse(TimeAttackLaunchRequest request, String policy,
                            String lockedCharacter, int windowSeconds) { }
    }
}
