package com.openggf.capture;

import com.openggf.configuration.KeyChord;

/**
 * Rising edge detector for the complete live-capture shortcut chord.
 *
 * <p>The modifiers come from the binding rather than from this class, so
 * {@code capture.toggleKey} is the whole truth about what toggles recording.
 */
public final class LiveCaptureChord {
    private boolean previousComplete;

    /**
     * @param chord      the configured binding; an unbound chord never fires
     * @param keyDown    whether the chord's key is held
     * @return true on the frame the chord becomes completely satisfied
     */
    public boolean update(KeyChord chord, boolean keyDown, boolean shiftDown,
                          boolean controlDown, boolean altDown, boolean superDown) {
        boolean complete = chord != null && chord.isBound() && keyDown
                && chord.matchesModifiers(shiftDown, controlDown, altDown, superDown);
        boolean rising = complete && !previousComplete;
        previousComplete = complete;
        return rising;
    }
}
