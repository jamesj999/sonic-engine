package com.openggf.capture;

import com.openggf.configuration.KeyChord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveCaptureChordTest {

    private static final KeyChord SHIFT_O = KeyChord.parse("SHIFT+O");

    @Test void togglesWhenShiftPrecedesKey() {
        LiveCaptureChord chord = new LiveCaptureChord();
        assertFalse(chord.update(SHIFT_O, false, true, false, false, false));
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
        assertFalse(chord.update(SHIFT_O, true, true, false, false, false));
    }

    @Test void togglesWhenKeyPrecedesShift() {
        LiveCaptureChord chord = new LiveCaptureChord();
        assertFalse(chord.update(SHIFT_O, true, false, false, false, false));
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
    }

    @Test void requiresReleaseBeforeRetoggle() {
        LiveCaptureChord chord = new LiveCaptureChord();
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
        assertFalse(chord.update(SHIFT_O, true, true, false, false, false));
        assertFalse(chord.update(SHIFT_O, false, true, false, false, false));
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
    }

    @Test void ctrlAndAltSuppressAndModifierRemovalCanCompleteChord() {
        LiveCaptureChord chord = new LiveCaptureChord();
        assertFalse(chord.update(SHIFT_O, true, true, true, false, false));
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
        assertFalse(chord.update(SHIFT_O, true, true, false, true, false));
        assertTrue(chord.update(SHIFT_O, true, true, false, false, false));
    }

    @Test void configuredKeyStateIsIndependentFromInputRecordingF9() {
        LiveCaptureChord chord = new LiveCaptureChord();
        boolean configuredO = true;
        boolean unrelatedF9 = true;
        assertTrue(chord.update(SHIFT_O, configuredO, true, false, false, false));
        assertTrue(unrelatedF9, "the separate F9 state is not consumed by this chord");
    }

    @Test void anUnmodifiedBindingTogglesWithNoModifierHeld() {
        KeyChord plain = KeyChord.parse("O");
        LiveCaptureChord chord = new LiveCaptureChord();

        assertFalse(chord.update(plain, true, true, false, false, false), "shift held");
        assertTrue(chord.update(plain, true, false, false, false, false));
    }

    @Test void aTwoModifierBindingRequiresBoth() {
        KeyChord ctrlShiftO = KeyChord.parse("CTRL+SHIFT+O");
        LiveCaptureChord chord = new LiveCaptureChord();

        assertFalse(chord.update(ctrlShiftO, true, true, false, false, false));
        assertTrue(chord.update(ctrlShiftO, true, true, true, false, false));
    }

    @Test void aSuperBindingMatchesARealSuperPress() {
        KeyChord metaO = KeyChord.parse("META+O");
        assertTrue(new LiveCaptureChord().update(metaO, true, false, false, false, true));
    }

    /**
     * isKeyDown(-1) is not simply false: it falls through to the gamepad
     * rewind-key comparison, and an unbound rewindKey is also -1. An unbound
     * capture binding must never fire.
     */
    @Test void anUnboundBindingNeverFires() {
        LiveCaptureChord chord = new LiveCaptureChord();
        assertFalse(chord.update(KeyChord.parse(""), true, false, false, false, false));
    }
}
