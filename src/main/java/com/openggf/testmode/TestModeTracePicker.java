package com.openggf.testmode;

import com.openggf.game.MenuFeedback;
import static com.openggf.game.MenuFeedback.Cue.*;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import com.openggf.game.MenuStyle;
import java.util.ArrayList;
import com.openggf.trace.catalog.TraceEntry;

import java.util.List;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

/**
 * Trace picker opened through Tools or the legacy TEST_MODE_ENABLED flag. Owned by
 * MasterTitleScreen; it substitutes this screen's update/render for the
 * normal game-selection ACTIVE behaviour.
 */
public final class TestModeTracePicker {

    public enum Result { NONE, LAUNCH, BACK }

    private final List<TraceEntry> entries;
    private final PixelFont font;
    private InputHandler menuInput;
    private int cursor;
    private int firstVisible;
    private int failurePage;
    private Result pendingResult = Result.NONE;
    private TraceEntry loadingEntry;
    private boolean loadingPresented;
    private boolean launchIssued;
    private boolean failureAnnounced;

    public TestModeTracePicker(List<TraceEntry> entries, PixelFont font) {
        this.entries = entries;
        this.font = font;
    }

    /** A host ROM error page has already announced this pending launch failure. */
    public void markFailureFeedbackAnnounced() {
        failureAnnounced = true;
    }

    public void update(InputHandler input) {
        menuInput = input;
        if (hasHeldFailure()) {
            if (!failureAnnounced) { MenuFeedback.emit(ERROR); failureAnnounced = true; }
            updateHeldFailure(input);
            return;
        }
        failureAnnounced = false;
        if ((!input.isAnyModifierDown() && MenuInput.back(input))) {
            MenuFeedback.emit(CANCEL);
            loadingEntry = null;
            pendingResult = Result.BACK;
            return;
        }
        if (loadingEntry != null) {
            if (loadingPresented && !launchIssued) {
                launchIssued = true;
                pendingResult = Result.LAUNCH;
            }
            return;
        }
        if (entries.isEmpty()) {
            if (MenuInput.accept(input)) MenuFeedback.emit(ERROR);
            if ((!input.isAnyModifierDown() && MenuInput.back(input))) {
                pendingResult = Result.BACK;
            }
            return;
        }
        int previousCursor = cursor;
        if ((!input.isAnyModifierDown() && MenuInput.down(input))) {
            cursor = Math.min(entries.size() - 1, cursor + 1);
        }
        if ((!input.isAnyModifierDown() && MenuInput.up(input))) {
            cursor = Math.max(0, cursor - 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_HOME)) {
            cursor = 0;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_END)) {
            cursor = entries.size() - 1;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_DOWN) || MenuInput.right(input)) {
            cursor = nextGroupStart(cursor);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_UP) || MenuInput.left(input)) {
            cursor = prevGroupStart(cursor);
        }
        if (cursor != previousCursor) MenuFeedback.emit(NAVIGATE);
        // Keep the scroll window in sync with the cursor after any movement
        // so the selected entry is always within the visible viewport.
        firstVisible = computeFirstVisible(firstVisible, cursor);
        if ((!input.isAnyModifierDown() && MenuInput.accept(input))) {
            MenuFeedback.emit(CONFIRM);
            loadingEntry = entries.get(cursor);
            loadingPresented = false;
            launchIssued = false;
        }
        if ((!input.isAnyModifierDown() && MenuInput.back(input))) {
            pendingResult = Result.BACK;
        }
    }

    private void updateHeldFailure(InputHandler input) {
        int previousPage = failurePage;
        if (MenuInput.left(input)) failurePage = Math.max(0, failurePage - 1);
        if (MenuInput.right(input)) failurePage = Math.min(failurePages() - 1, failurePage + 1);
        if (previousPage != failurePage) MenuFeedback.emit(NAVIGATE);
        if ((!input.isAnyModifierDown() && MenuInput.accept(input))
                || (!input.isAnyModifierDown() && MenuInput.back(input))) {
            MenuFeedback.emit(MenuInput.back(input) ? CANCEL : CONFIRM);
            clearHeldFailure();
            return;
        }
        if (entries.isEmpty()) {
            return;
        }
        int previousCursor = cursor;
        if ((!input.isAnyModifierDown() && MenuInput.down(input))) {
            cursor = Math.min(entries.size() - 1, cursor + 1);
        }
        if ((!input.isAnyModifierDown() && MenuInput.up(input))) {
            cursor = Math.max(0, cursor - 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_HOME)) {
            cursor = 0;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_END)) {
            cursor = entries.size() - 1;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_DOWN)) {
            cursor = nextGroupStart(cursor);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_UP)) {
            cursor = prevGroupStart(cursor);
        }
        firstVisible = computeFirstVisible(firstVisible, cursor);
        if (cursor != previousCursor) {
            MenuFeedback.emit(NAVIGATE);
            clearHeldFailure();
        }
    }

    private int failurePages() {
        List<String> messages = new ArrayList<>();
        TraceRunFailureStatus.current().ifPresent(f -> {
            messages.add("Segment: " + f.segmentIndex());
            if (f.isComparison()) { messages.add("Expected: " + f.expectedIdentity()); messages.add("Actual: " + f.actualIdentity()); }
            else messages.add("Reason: " + f.reason());
            messages.add("Cursor: " + f.cursor() + "  Steps: " + f.stepCount());
        });
        if (messages.isEmpty()) TraceLaunchStatus.current().ifPresent(f -> {
            messages.add("Trace: " + f.traceLabel()); messages.add("Reason: " + f.reason());
        });
        int lines = messages.stream().mapToInt(message -> (message.length() + FAILURE_MAX_CHARS - 1) / FAILURE_MAX_CHARS).sum();
        return Math.max(1, (lines + 10) / 11);
    }

    private String confirmHint() { return menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput); }
    private String backHint() { return menuInput == null ? "Esc" : MenuInput.backLabel(menuInput); }

    private static final float SCALE = MenuStyle.COMPACT;
    private static final int LINE_HEIGHT = 12;
    private static final int GROUP_GAP = 2;
    private static final int HEADING_HEIGHT = 12;
    private static final int LIST_TOP = 47;
    private static final int LIST_AREA_BOTTOM = 134;
    private static final int FAILURE_MAX_CHARS = 50;

    public void render() {
        if (font == null) return;
        font.beginMegaBatch();
        try {
            MenuStyle.page(font, 320, "TRACE REPLAYS", "ROM parity playback - native 320x224");
            Optional<TraceRunFailureStatus.Failure> failure = TraceRunFailureStatus.current();
            if (failure.isPresent()) {
                var f = failure.get();
                List<String> lines = new ArrayList<>();
                lines.add("Segment: " + f.segmentIndex());
                if (f.isComparison()) {
                    lines.add("Expected: " + f.expectedIdentity());
                    lines.add("Actual: " + f.actualIdentity());
                } else lines.add("Reason: " + f.reason());
                lines.add("Cursor: " + f.cursor() + "  Steps: " + f.stepCount());
                renderFailurePage("TRACE FAILED", lines);
                return;
            }
            Optional<TraceLaunchStatus.Failure> launchFailure = TraceLaunchStatus.current();
            if (launchFailure.isPresent()) {
                var f = launchFailure.get();
                renderFailurePage("TRACE LAUNCH FAILED", List.of("Trace: " + f.traceLabel(), "Reason: " + f.reason()));
                return;
            }
            failurePage = 0;
            if (loadingEntry != null) {
                MenuStyle.panel(font, 9, 66, 302, 71);
                line("LOADING TRACE...", 17, 76, 286, 1, .85f, .3f);
                line(TraceLaunchStatus.catalogLabel(loadingEntry), 17, 94, 286, 1, 1, 1);
                line("Parsing replay data", 17, 116, 286, .75f, .82f, 1);
                MenuStyle.footer(font, 320, "Preparing the recorded game and team", backHint() + " Cancel");
                loadingPresented = true;
                return;
            }
            if (entries.isEmpty()) {
                MenuStyle.panel(font, 9, 55, 302, 108);
                line("No traces found", 17, 67, 286, 1, .75f, .3f);
                line("Set the trace folder in Engine Settings", 17, 92, 286, 1, 1, 1);
                line("Files > Trace catalog folder", 17, 108, 286, .7f, .8f, 1);
                MenuStyle.footer(font, 320, "Return to the hub to open Settings", backHint() + " Back");
                return;
            }
            firstVisible = computeFirstVisible(firstVisible, cursor);
            int lastVisible = lastFullyVisibleIndex(firstVisible);
            line(gameHeading(entries.get(firstVisible).gameId()) + "  " + (cursor + 1) + "/" + entries.size(),
                    9, LIST_TOP, 302, 1, .82f, .3f);
            int y = LIST_TOP + HEADING_HEIGHT;
            for (int i = firstVisible; i <= lastVisible; i++) {
                TraceEntry e = entries.get(i);
                if (i > firstVisible && !e.gameId().equals(entries.get(i - 1).gameId())) {
                    y += GROUP_GAP;
                    line(gameHeading(e.gameId()), 9, y, 302, 1, .82f, .3f);
                    y += HEADING_HEIGHT;
                }
                if (i == cursor) MenuStyle.focusLabel(font, 9, y, 302, 12);
                MenuStyle.label(font, (i == cursor ? "> " : "  ") + e.displayLabel(), 13, y, 292,
                        i == cursor ? 1 : .72f, i == cursor ? 1 : .78f, 1);
                y += LINE_HEIGHT;
            }
            renderInfoPanel(entries.get(cursor));
            MenuStyle.footer(font, 320, "Up/Down Select   Left/Right Game",
                    confirmHint() + " Replay   " + backHint() + " Back");
        } finally {
            font.endMegaBatch();
        }
    }

    private void renderFailurePage(String title, List<String> messages) {
        List<String> lines = new ArrayList<>();
        for (String message : messages) {
            for (int start = 0; start < message.length(); start += FAILURE_MAX_CHARS)
                lines.add(message.substring(start, Math.min(message.length(), start + FAILURE_MAX_CHARS)));
        }
        int pages = Math.max(1, (lines.size() + 10) / 11);
        failurePage = Math.min(failurePage, pages - 1);
        line(title, 9, 49, 302, 1, .3f, .3f);
        MenuStyle.panel(font, 9, 62, 302, 133);
        for (int i = failurePage * 11, y = 67; i < Math.min(lines.size(), (failurePage + 1) * 11); i++, y += 11)
            line(lines.get(i), 12, y, 300, 1, 1, 1);
        MenuStyle.footer(font, 320, "Left/Right Details  " + (failurePage + 1) + "/" + pages,
                confirmHint() + "/" + backHint() + " Acknowledge");
    }

    private void line(String text, int x, int y, int width, float r, float g, float b) {
        MenuStyle.text(font, text, x, y, width, r, g, b);
    }

    private static boolean hasHeldFailure() {
        return TraceRunFailureStatus.current().isPresent()
                || TraceLaunchStatus.current().isPresent();
    }

    private static void clearHeldFailure() {
        TraceRunFailureStatus.clear();
        TraceLaunchStatus.clear();
    }

    /**
     * Index of the last entry that fully fits in the scroll viewport when the
     * window begins at {@code firstVisible}. Accounts for the sticky heading and
     * any inline group headings that appear within the window. Always returns at
     * least {@code firstVisible} so the selected row can render even on a screen
     * too short to fit it. Package-private for unit testing.
     */
    int lastFullyVisibleIndex(int firstVisible) {
        if (entries.isEmpty()) {
            return 0;
        }
        firstVisible = Math.max(0, Math.min(firstVisible, entries.size() - 1));
        int y = LIST_TOP + HEADING_HEIGHT; // below the sticky heading
        int last = firstVisible;
        for (int i = firstVisible; i < entries.size(); i++) {
            int rowHeight = LINE_HEIGHT;
            if (i > firstVisible
                    && !entries.get(i).gameId().equals(entries.get(i - 1).gameId())) {
                rowHeight += GROUP_GAP + HEADING_HEIGHT;
            }
            if (y + rowHeight > LIST_AREA_BOTTOM) {
                break;
            }
            y += rowHeight;
            last = i;
        }
        return last;
    }

    /**
     * Minimal scroll-window start that keeps {@code cursor} visible. Scrolls up
     * when the cursor is above the window and down (one entry at a time) until
     * the cursor fits, never moving past the cursor. Package-private for testing.
     */
    int computeFirstVisible(int firstVisible, int cursor) {
        if (entries.isEmpty()) {
            return 0;
        }
        firstVisible = Math.max(0, Math.min(firstVisible, entries.size() - 1));
        cursor = Math.max(0, Math.min(cursor, entries.size() - 1));
        if (firstVisible > cursor) {
            firstVisible = cursor;
        }
        while (firstVisible < cursor && cursor > lastFullyVisibleIndex(firstVisible)) {
            firstVisible++;
        }
        return firstVisible;
    }

    private void renderInfoPanel(TraceEntry e) {
        MenuStyle.panel(font, 9, 143, 302, 51);
        line("SELECTED: " + TraceLaunchStatus.catalogLabel(e), 13, 147, 292, 1, 1, 1);
        line("Frames: " + e.frameCount() + "   Team: " + formatTeam(e), 13, 159, 292, .85f, .9f, 1);
        line("BK2: " + e.bk2Path().getFileName(), 13, 171, 292, .75f, .82f, 1);
        line("BK2 offset: " + e.bk2StartOffset() + "   Pre-osc: " + e.preTraceOscFrames(),
                13, 183, 292, .75f, .82f, 1);
    }

    private static String gameHeading(String gameId) {
        return switch (gameId) {
            case "s1" -> "SONIC 1";
            case "s2" -> "SONIC 2";
            case "s3k" -> "SONIC 3&K";
            default -> gameId.toUpperCase();
        };
    }

    private static String formatTeam(TraceEntry e) {
        StringBuilder sb = new StringBuilder(e.team().mainCharacter());
        for (String sk : e.team().sidekicks()) {
            sb.append('+').append(sk);
        }
        return sb.toString();
    }

    // Package-private for targeted unit testing.
    int nextGroupStart(int from) {
        String current = entries.get(from).gameId();
        for (int i = from + 1; i < entries.size(); i++) {
            if (!entries.get(i).gameId().equals(current)) {
                return i;
            }
        }
        return from;
    }

    // Package-private for targeted unit testing.
    int prevGroupStart(int from) {
        String current = entries.get(from).gameId();
        // Walk backwards to find the group that precedes the current one,
        // then walk to that group's FIRST entry.
        int prevGroupLastIdx = -1;
        for (int i = from - 1; i >= 0; i--) {
            if (!entries.get(i).gameId().equals(current)) {
                prevGroupLastIdx = i;
                break;
            }
        }
        if (prevGroupLastIdx < 0) {
            return 0;
        }
        String prevGame = entries.get(prevGroupLastIdx).gameId();
        int start = prevGroupLastIdx;
        while (start > 0 && entries.get(start - 1).gameId().equals(prevGame)) {
            start--;
        }
        return start;
    }

    public Result consumeResult() {
        Result r = pendingResult;
        pendingResult = Result.NONE;
        return r;
    }

    // Package-private for targeted unit testing.
    int cursor() {
        return cursor;
    }

    public TraceEntry selectedEntry() {
        return loadingEntry != null
                ? loadingEntry
                : cursor < entries.size() ? entries.get(cursor) : null;
    }

    /** Returns the picker to its selected entry after a synchronous launch failure. */
    public void launchFailed() {
        loadingEntry = null;
        loadingPresented = false;
        launchIssued = false;
    }
}
