package com.openggf.testmode;

import com.openggf.control.InputHandler;
import com.openggf.graphics.PixelFont;
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
 * Trace picker screen shown when TEST_MODE_ENABLED is true. Owned by
 * MasterTitleScreen; it substitutes this screen's update/render for the
 * normal game-selection ACTIVE behaviour.
 */
public final class TestModeTracePicker {

    public enum Result { NONE, LAUNCH, BACK }

    private final List<TraceEntry> entries;
    private final PixelFont font;
    private int cursor;
    private int firstVisible;
    private Result pendingResult = Result.NONE;
    private TraceEntry loadingEntry;
    private boolean loadingPresented;
    private boolean launchIssued;

    public TestModeTracePicker(List<TraceEntry> entries, PixelFont font) {
        this.entries = entries;
        this.font = font;
    }

    public void update(InputHandler input) {
        if (hasHeldFailure()) {
            updateHeldFailure(input);
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
            if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
                pendingResult = Result.BACK;
            }
            return;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_DOWN)) {
            cursor = Math.min(entries.size() - 1, cursor + 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_UP)) {
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
        // Keep the scroll window in sync with the cursor after any movement
        // so the selected entry is always within the visible viewport.
        firstVisible = computeFirstVisible(firstVisible, cursor);
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ENTER)) {
            loadingEntry = entries.get(cursor);
            loadingPresented = false;
            launchIssued = false;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
            pendingResult = Result.BACK;
        }
    }

    private void updateHeldFailure(InputHandler input) {
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ENTER)
                || input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
            clearHeldFailure();
            return;
        }
        if (entries.isEmpty()) {
            return;
        }
        int previousCursor = cursor;
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_DOWN)) {
            cursor = Math.min(entries.size() - 1, cursor + 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_UP)) {
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
            clearHeldFailure();
        }
    }

    private static final float SCALE = 0.5f;
    private static final int LINE_HEIGHT = 6;
    private static final int GROUP_GAP = 3;
    private static final int HEADING_HEIGHT = 8;
    // The scrollable list occupies [LIST_TOP, LIST_AREA_BOTTOM]; below that sits
    // the selected-entry info panel (renderInfoPanel, y >= 192) on the 224px
    // virtual screen. Entries are windowed so the list never spills into it.
    private static final int LIST_TOP = 18;
    private static final int LIST_AREA_BOTTOM = 184;
    private static final int FAILURE_MAX_CHARS = 68;

    public void render() {
        // Entire screen is pure text on the font atlas — mega-batch into one GL draw.
        font.beginMegaBatch();
        try {
            Optional<TraceRunFailureStatus.Failure> failure =
                    TraceRunFailureStatus.current();
            if (failure.isPresent()) {
                renderFailure(failure.get());
                return;
            }
            Optional<TraceLaunchStatus.Failure> launchFailure =
                    TraceLaunchStatus.current();
            if (launchFailure.isPresent()) {
                renderLaunchFailure(launchFailure.get());
                return;
            }
            if (loadingEntry != null) {
                renderLoading();
                loadingPresented = true;
                return;
            }
            if (entries.isEmpty()) {
                font.drawText("TRACE TEST MODE", 8, 6, SCALE, 1f, 1f, 1f, 1f);
                font.drawText("No traces found.", 8, 24, SCALE, 1f, 0.5f, 0.5f, 1f);
                font.drawText("Check debug.testMode.catalogDir in config.yaml", 8, 32, SCALE,
                        0.8f, 0.8f, 0.8f, 1f);
                font.drawText("(default: src/test/resources/traces)", 8, 40, SCALE,
                        0.8f, 0.8f, 0.8f, 1f);
                font.drawText("ESC to return to master title", 8, 56, SCALE,
                        0.7f, 0.7f, 0.7f, 1f);
                return;
            }
            // Recompute defensively so rendering is correct even if update()
            // has not run this frame (e.g. first render after construction).
            firstVisible = computeFirstVisible(firstVisible, cursor);
            int lastVisible = lastFullyVisibleIndex(firstVisible);

            font.drawText(String.format("TRACE TEST MODE   (%d/%d)",
                    cursor + 1, entries.size()), 8, 6, SCALE, 1f, 1f, 1f, 1f);

            // Sticky heading for the topmost visible entry's game so the group
            // context is never lost when scrolled into the middle of a group.
            String topHeading = gameHeading(entries.get(firstVisible).gameId())
                    + (firstVisible > 0 ? "   ^ more above" : "");
            font.drawText(topHeading, 8, LIST_TOP, SCALE, 1f, 1f, 0.6f, 1f);

            int y = LIST_TOP + HEADING_HEIGHT;
            for (int i = firstVisible; i <= lastVisible; i++) {
                TraceEntry e = entries.get(i);
                if (i > firstVisible
                        && !e.gameId().equals(entries.get(i - 1).gameId())) {
                    y += GROUP_GAP;
                    font.drawText(gameHeading(e.gameId()), 8, y, SCALE, 1f, 1f, 0.6f, 1f);
                    y += HEADING_HEIGHT;
                }
                boolean selected = (i == cursor);
                float brightness = selected ? 1.0f : 0.6f;
                String prefix = selected ? ">" : " ";
                String line = prefix + " " + (e.isRun()
                        ? e.displayLabel()
                        : e.dir().getFileName());
                font.drawText(line, 12, y, SCALE, brightness, brightness, brightness, 1f);
                y += LINE_HEIGHT;
            }
            int below = entries.size() - 1 - lastVisible;
            if (below > 0) {
                font.drawText("  v " + below + " more below", 12, y, SCALE,
                        0.7f, 0.7f, 0.4f, 1f);
            }
            renderInfoPanel(entries.get(cursor));
        } finally {
            font.endMegaBatch();
        }
    }

    private void renderFailure(TraceRunFailureStatus.Failure failure) {
        int y = 28;
        font.drawText("TRACE FAILED", 8, y, SCALE, 1f, 0.35f, 0.35f, 1f);
        y += 16;
        font.drawText("Segment: " + failure.segmentIndex(), 8, y, SCALE,
                1f, 1f, 1f, 1f);
        y += LINE_HEIGHT;
        if (failure.isComparison()) {
            y = drawFailureText("Expected: " + failure.expectedIdentity(), y);
            y = drawFailureText("Actual: " + failure.actualIdentity(), y);
        } else {
            y = drawFailureText("Reason: " + failure.reason(), y);
        }
        font.drawText("Cursor: " + failure.cursor() + "   Steps: " + failure.stepCount(),
                8, y, SCALE, 0.9f, 0.9f, 0.9f, 1f);
        y += 18;
        font.drawText("ENTER/ESC to acknowledge", 8, y, SCALE,
                1f, 1f, 0.6f, 1f);
        y += LINE_HEIGHT;
        if (!entries.isEmpty()) {
            font.drawText("Move selection to dismiss", 8, y, SCALE,
                    0.7f, 0.7f, 0.7f, 1f);
        }
    }

    private void renderLaunchFailure(TraceLaunchStatus.Failure failure) {
        int y = 28;
        font.drawText("TRACE LAUNCH FAILED", 8, y, SCALE,
                1f, 0.35f, 0.35f, 1f);
        y += 16;
        y = drawFailureText("Trace: " + failure.traceLabel(), y);
        y = drawFailureText("Reason: " + failure.reason(), y);
        y += 12;
        font.drawText("ENTER/ESC to acknowledge", 8, y, SCALE,
                1f, 1f, 0.6f, 1f);
    }

    private void renderLoading() {
        font.drawText("LOADING TRACE...", 8, 80, SCALE,
                1f, 1f, 0.6f, 1f);
        font.drawText(TraceLaunchStatus.catalogLabel(loadingEntry), 8, 94, SCALE,
                1f, 1f, 1f, 1f);
        font.drawText("Parsing replay data", 8, 108, SCALE,
                0.75f, 0.75f, 0.75f, 1f);
    }

    private static boolean hasHeldFailure() {
        return TraceRunFailureStatus.current().isPresent()
                || TraceLaunchStatus.current().isPresent();
    }

    private static void clearHeldFailure() {
        TraceRunFailureStatus.clear();
        TraceLaunchStatus.clear();
    }

    private int drawFailureText(String text, int y) {
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + FAILURE_MAX_CHARS);
            font.drawText(text.substring(offset, end), 8, y, SCALE,
                    0.9f, 0.9f, 0.9f, 1f);
            offset = end;
            y += LINE_HEIGHT;
        }
        return y;
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
        int y = 192;
        font.drawText("SELECTED: " + TraceLaunchStatus.catalogLabel(e),
                4, y, SCALE, 1f, 1f, 1f, 1f);
        y += LINE_HEIGHT;
        font.drawText(String.format("%s   Frames: %d   BK2 offset: %d",
                        e.displayLabel(), e.frameCount(), e.bk2StartOffset()),
                4, y, SCALE, 0.9f, 0.9f, 0.9f, 1f);
        y += LINE_HEIGHT;
        font.drawText("Team: " + formatTeam(e) + "   Pre-osc: " + e.preTraceOscFrames(),
                4, y, SCALE, 0.9f, 0.9f, 0.9f, 1f);
        y += LINE_HEIGHT;
        font.drawText("BK2: " + e.bk2Path().getFileName(),
                4, y, SCALE, 0.7f, 0.7f, 0.7f, 1f);
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
