package com.openggf.debug;

import com.openggf.control.InputHandler;
import com.openggf.configuration.KeyChord;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestDebugOverlayManagerReset {

    @TempDir
    Path configDir;

    /** The shipped capture chord, exactly as GameLoop hands it to updateInput. */
    private KeyChord captureToggle() {
        return SonicConfigurationService.createStandalone(configDir)
                .getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY);
    }

    @Test
    public void testResetStateRestoresToggleDefaults() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();

        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            manager.setEnabled(toggle, !toggle.defaultEnabled());
        }

        manager.resetState();

        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            assertEquals(toggle.defaultEnabled(), manager.isEnabled(toggle), "resetState should restore default for " + toggle.name());
        }
    }

    @Test
    public void buildShortcutLines_reusesCachedStringsUntilToggleStateChanges() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();

        String initialOverlayLine = manager.buildShortcutLines().get(DebugOverlayToggle.OVERLAY.ordinal());
        String repeatedOverlayLine = manager.buildShortcutLines().get(DebugOverlayToggle.OVERLAY.ordinal());

        assertSame(initialOverlayLine, repeatedOverlayLine,
                "shortcut text should be reused when overlay state is unchanged");

        manager.setEnabled(DebugOverlayToggle.OVERLAY, !DebugOverlayToggle.OVERLAY.defaultEnabled());
        String updatedOverlayLine = manager.buildShortcutLines().get(DebugOverlayToggle.OVERLAY.ordinal());

        assertNotSame(initialOverlayLine, updatedOverlayLine,
                "changing a toggle should invalidate the cached shortcut text");
        String expectedState = DebugOverlayToggle.OVERLAY.defaultEnabled() ? "Off" : "On";
        assertTrue(updatedOverlayLine.endsWith(": " + expectedState));

        String repeatedUpdatedLine = manager.buildShortcutLines().get(DebugOverlayToggle.OVERLAY.ordinal());
        assertSame(updatedOverlayLine, repeatedUpdatedLine,
                "updated shortcut text should also be reused until the next toggle change");

        manager.resetState();
    }

    @Test
    public void updateInputIgnoresToggleKeysWhenDebugShortcutsAreDisabled() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW.GLFW_KEY_F12, GLFW.GLFW_PRESS);

        manager.updateInput(input, false);

        assertFalse(manager.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER),
                "F12 must not enable the art viewer when debug shortcuts are disabled");
        manager.resetState();
    }

    @Test
    public void updateInputAllowsPerformanceToggleWhenDebugShortcutsAreDisabled() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW.GLFW_KEY_P, GLFW.GLFW_PRESS);

        manager.updateInput(input, false);

        assertTrue(manager.isEnabled(DebugOverlayToggle.PERFORMANCE),
                "P must toggle the performance panel even when normal debug shortcuts are disabled");
        manager.resetState();
    }

    /**
     * OBJECT_DEBUG is GLFW_KEY_O and the toggles fired on a bare isKeyPressed, so
     * the SHIFT+O capture default toggled object debug on the same keystroke that
     * started a recording.
     */
    @Test
    public void aModifiedKeystrokeDoesNotToggleAnOverlay() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG);
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_O, GLFW.GLFW_PRESS);

        manager.updateInput(handler, true, captureToggle());

        assertEquals(before, manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG));
        manager.resetState();
    }

    /**
     * The blast radius of the fix above. Only the chord bound to the SAME key
     * may swallow a toggle; a modifier held for an unrelated reason must not.
     * P2's jump defaults to RIGHT_SHIFT, so a "no modifier held" rule switches
     * every debug overlay shortcut off for as long as player two holds jump --
     * and does the same for the Shift a developer holds for debug fast movement.
     */
    @Test
    public void player2HoldingJumpDoesNotDisableTheOverlayShortcuts() {
        assertEquals(GLFW.GLFW_KEY_RIGHT_SHIFT,
                SonicConfigurationService.createStandalone(configDir).getInt(SonicConfiguration.P2_A),
                "this guard is about P2's jump defaulting to a Shift key; re-point it");

        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PLAYER_PANEL);
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_F3, GLFW.GLFW_PRESS);

        manager.updateInput(handler, true, captureToggle());

        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.PLAYER_PANEL),
                "F3 must still toggle the player panel while P2 holds jump");
        manager.resetState();
    }

    /**
     * The same rule one key over: O is only claimed while the capture chord is
     * actually satisfied. Ctrl+Shift+O does not start a recording (matching is
     * exact), so it must keep reaching the overlay toggle.
     */
    @Test
    public void aModifierTheCaptureChordDoesNotDeclareStillTogglesObjectDebug() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG);
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_O, GLFW.GLFW_PRESS);

        manager.updateInput(handler, true, captureToggle());

        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG));
        manager.resetState();
    }

    /**
     * The claim follows the binding rather than a hardcoded Shift: rebind the
     * capture toggle to ALT+F5 and Alt+F5 must stop toggling object labels,
     * while Shift+O goes back to toggling object debug.
     */
    @Test
    public void theClaimFollowsTheConfiguredCaptureBinding() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.CAPTURE_TOGGLE_KEY, "ALT+F5");
        KeyChord chord = config.getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY);

        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean labelsBefore = manager.isEnabled(DebugOverlayToggle.OBJECT_LABELS);
        InputHandler altF5 = new InputHandler();
        altF5.handleKeyEvent(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_PRESS);
        altF5.handleKeyEvent(GLFW.GLFW_KEY_F5, GLFW.GLFW_PRESS);
        manager.updateInput(altF5, true, chord);
        assertEquals(labelsBefore, manager.isEnabled(DebugOverlayToggle.OBJECT_LABELS),
                "Alt+F5 is the capture chord now, so it must not toggle object labels");

        boolean debugBefore = manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG);
        InputHandler shiftO = new InputHandler();
        shiftO.handleKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS);
        shiftO.handleKeyEvent(GLFW.GLFW_KEY_O, GLFW.GLFW_PRESS);
        manager.updateInput(shiftO, true, chord);
        assertNotEquals(debugBefore, manager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG),
                "Shift+O no longer records, so it goes back to toggling object debug");
        manager.resetState();
    }

    @Test
    public void anUnmodifiedKeystrokeStillTogglesAnOverlay() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PLAYER_PANEL);
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_F3, GLFW.GLFW_PRESS);

        manager.updateInput(handler, true);

        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.PLAYER_PANEL));
        manager.resetState();
    }

    /**
     * PERFORMANCE is dispatched above the debugShortcutsEnabled gate, so it needs
     * the same treatment separately. Ctrl+P is the clipboard-copy chord and must
     * not also toggle the overlay -- a pre-existing double-fire.
     */
    @Test
    public void ctrlPCopiesStatsWithoutAlsoTogglingThePerformanceOverlay() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PERFORMANCE);
        StringBuilder copied = new StringBuilder();
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_P, GLFW.GLFW_PRESS);
        try {
            manager.setClipboardWriter(copied::append);
            manager.updateInput(handler, true, captureToggle());
        } finally {
            manager.setClipboardWriter(null);
        }

        assertFalse(copied.isEmpty(), "Ctrl+P must copy the stats with debug shortcuts enabled");
        assertEquals(before, manager.isEnabled(DebugOverlayToggle.PERFORMANCE));
        manager.resetState();
    }

    /**
     * The blast radius of the reservation above, and the limit of it. The stats
     * copy is a debug capability and {@code debug.viewEnabled} ships false, so
     * dispatching it above the gate to keep Ctrl+P alive handed every default
     * install a keystroke that silently overwrites the OS clipboard. The
     * reservation is narrowed instead: PERFORMANCE stands down for Ctrl+P only
     * while the copy can actually run, so with debug shortcuts off the keystroke
     * does what it did before the chord existed -- toggles, and copies nothing.
     */
    @Test
    public void ctrlPTogglesTheOverlayAndCopiesNothingOnADefaultInstall() {
        assertFalse(SonicConfigurationService.createStandalone(configDir)
                        .getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED),
                "this guard is about the shipped default; debug.viewEnabled is no longer false");

        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PERFORMANCE);
        StringBuilder copied = new StringBuilder();
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_P, GLFW.GLFW_PRESS);
        try {
            manager.setClipboardWriter(copied::append);
            manager.updateInput(handler, false, captureToggle());
        } finally {
            manager.setClipboardWriter(null);
        }

        assertTrue(copied.isEmpty(),
                "a debug-only clipboard copy must not run with debug shortcuts disabled");
        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.PERFORMANCE),
                "Ctrl+P must still toggle the performance overlay on a default install");
        manager.resetState();
    }

    /**
     * RIGHT_CONTROL is player two's default Start ({@code P2_START}), and
     * {@code isControlDown()} is left OR right -- so matching the copy chord
     * through it alone means P2 holding Start while P1 presses P clobbers the
     * system clipboard. Same oversight as RIGHT_SHIFT being P2's jump, one key
     * over. Right Ctrl+P is not the chord: it toggles, as it always has.
     */
    @Test
    public void playerTwosStartButtonDoesNotTurnPIntoTheStatsCopyChord() {
        assertEquals(GLFW.GLFW_KEY_RIGHT_CONTROL,
                SonicConfigurationService.createStandalone(configDir)
                        .getInt(SonicConfiguration.P2_START),
                "this guard is about P2's Start; it is no longer RIGHT_CONTROL");

        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PERFORMANCE);
        StringBuilder copied = new StringBuilder();
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_PRESS);
        handler.handleKeyEvent(GLFW.GLFW_KEY_P, GLFW.GLFW_PRESS);
        try {
            manager.setClipboardWriter(copied::append);
            manager.updateInput(handler, true, captureToggle());
        } finally {
            manager.setClipboardWriter(null);
        }

        assertTrue(copied.isEmpty(),
                "P2 holding Start must not turn P1's P into a clipboard copy");
        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.PERFORMANCE),
                "right Ctrl+P is not the copy chord, so P keeps its toggle");
        manager.resetState();
    }

    /** An unmodified P keeps the toggle it has always had, gate or no gate. */
    @Test
    public void aBarePStillTogglesThePerformanceOverlayWithoutCopying() {
        DebugOverlayManager manager = DebugOverlayManager.getInstance();
        manager.resetState();
        boolean before = manager.isEnabled(DebugOverlayToggle.PERFORMANCE);
        StringBuilder copied = new StringBuilder();
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW.GLFW_KEY_P, GLFW.GLFW_PRESS);
        try {
            manager.setClipboardWriter(copied::append);
            manager.updateInput(handler, false, captureToggle());
        } finally {
            manager.setClipboardWriter(null);
        }

        assertNotEquals(before, manager.isEnabled(DebugOverlayToggle.PERFORMANCE));
        assertTrue(copied.isEmpty(), "a bare P must not copy the stats");
        manager.resetState();
    }

    @Test
    public void ringBoundsToggleDoesNotShareTheLevelSelectShortcut() {
        int levelSelectKey = SonicConfigurationService.getInstance().getInt(SonicConfiguration.LEVEL_SELECT_KEY);

        assertFalse(DebugOverlayToggle.RING_BOUNDS.keyCode() == levelSelectKey,
                "Ring-bounds overlay must not share the runtime level-select debug shortcut");
    }
}

