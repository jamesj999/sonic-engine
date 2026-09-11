package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static com.openggf.configuration.KeyChord.Modifier.ALT;
import static com.openggf.configuration.KeyChord.Modifier.CTRL;
import static com.openggf.configuration.KeyChord.Modifier.META;
import static com.openggf.configuration.KeyChord.Modifier.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;

class TestKeyChord {

    /**
     * Existing config.yaml files hold bare key codes and names. They must keep
     * their meaning exactly, as chords with no modifiers.
     */
    @ParameterizedTest
    @ValueSource(strings = {"O", "GLFW_KEY_O", "79"})
    void theFormsThatWorkedBeforeStillParseToTheSameUnmodifiedKey(String configured) {
        KeyChord chord = KeyChord.parse(configured);

        assertEquals(GLFW_KEY_O, chord.keyCode());
        assertTrue(chord.modifiers().isEmpty(), configured + " declares no modifier");
    }

    @Test
    void anIntegerConfigValueParsesWithoutGoingThroughText() {
        assertEquals(GLFW_KEY_O, KeyChord.parse(GLFW_KEY_O).keyCode());
    }

    @Test
    void modifiersParseInAnyOrderAndCase() {
        KeyChord expected = KeyChord.of(GLFW_KEY_O, CTRL, SHIFT);

        assertEquals(expected, KeyChord.parse("CTRL+SHIFT+O"));
        assertEquals(expected, KeyChord.parse("shift+ctrl+O"));
        assertEquals(expected, KeyChord.parse(" Ctrl + Shift + O "));
    }

    @Test
    void theNamesPlayersActuallyUseForEachModifierAreAccepted() {
        assertEquals(KeyChord.of(GLFW_KEY_O, CTRL), KeyChord.parse("CONTROL+O"));
        assertEquals(KeyChord.of(GLFW_KEY_O, ALT), KeyChord.parse("OPTION+O"));
        // GLFW calls it SUPER; macOS calls it Command; Windows calls it Win.
        for (String meta : new String[] {"META", "SUPER", "CMD", "COMMAND", "WIN"}) {
            assertEquals(KeyChord.of(GLFW_KEY_O, META), KeyChord.parse(meta + "+O"), meta);
        }
    }

    @Test
    void aMultiWordKeyNameSurvivesChording() {
        assertEquals(KeyChord.of(GLFW_KEY_LEFT_BRACKET, META),
                KeyChord.parse("META+LEFT_BRACKET"));
    }

    @Test
    void formattingRoundTripsThroughParsing() {
        for (String text : new String[] {"O", "CTRL+O", "CTRL+SHIFT+O",
                "CTRL+SHIFT+ALT+META+LEFT_BRACKET"}) {
            KeyChord chord = KeyChord.parse(text);
            assertEquals(text, chord.format(), "canonical form of " + text);
            assertEquals(chord, KeyChord.parse(chord.format()));
        }
    }

    @Test
    void formattingUsesOneCanonicalModifierOrderRegardlessOfInput() {
        assertEquals("CTRL+SHIFT+O", KeyChord.parse("shift+ctrl+O").format());
    }

    /**
     * A plain binding must not fire while a modifier is held, or it would steal
     * the chord a modified binding has claimed.
     */
    @Test
    void aPlainBindingDoesNotFireWhileAModifierIsHeld() {
        KeyChord plain = KeyChord.parse("O");

        assertTrue(plain.matchesModifiers(false, false, false, false));
        assertFalse(plain.matchesModifiers(true, false, false, false), "shift held");
        assertFalse(plain.matchesModifiers(false, true, false, false), "ctrl held");
        assertFalse(plain.matchesModifiers(false, false, true, false), "alt held");
        assertFalse(plain.matchesModifiers(false, false, false, true), "meta held");
    }

    @Test
    void aChordRequiresItsModifiersAndRejectsExtras() {
        KeyChord chord = KeyChord.parse("CTRL+SHIFT+O");

        assertTrue(chord.matchesModifiers(true, true, false, false));
        assertFalse(chord.matchesModifiers(true, false, false, false), "ctrl missing");
        assertFalse(chord.matchesModifiers(false, true, false, false), "shift missing");
        assertFalse(chord.matchesModifiers(true, true, true, false), "alt held as well");
    }

    @Test
    void unresolvableBindingsReportAsUnboundRatherThanThrowing() {
        for (Object configured : new Object[] {null, "", "   ", "NOT_A_KEY",
                "CTRL+NOT_A_KEY", "NOTAMODIFIER+O", "CTRL+"}) {
            KeyChord chord = KeyChord.parse(configured);
            assertFalse(chord.isBound(), "should be unbound: " + configured);
            assertEquals(KeyChord.NO_KEY, chord.keyCode());
        }
    }

    /**
     * getInt resolves a KEY value through the name table first so "1" means the
     * number-row key, not raw code 1. KeyChord must agree or a digit binding is
     * silently dead: 1 is below GLFW_KEY_SPACE (32), so isBound() would be true.
     */
    @Test
    void aDigitBindingMeansTheNumberRowKeyNotTheRawKeyCode() {
        assertEquals(GLFW_KEY_1, KeyChord.parse("1").keyCode());
        assertEquals(KeyChord.of(GLFW_KEY_1, CTRL), KeyChord.parse("CTRL+1"));
    }

    @Test
    void aNumericStringWithNoNameStillResolvesAsARawKeyCode() {
        assertEquals(GLFW_KEY_O, KeyChord.parse("79").keyCode());
    }

    /**
     * The guard must be a round-trip identity check, not a presence check.
     * {@code nameOf} returns the numeric string for a code with no
     * {@code GLFW_KEY_*} constant, and the lowest real constant is
     * {@code GLFW_KEY_SPACE} = 32 — so {@code nameOf(0)}..{@code nameOf(9)} are
     * {@code "0"}..{@code "9"}, which ARE real key names resolving to 48..57. A
     * {@code resolve(name).isEmpty()} guard would not skip them, and
     * {@code assertEquals(0, 48)} on the first iteration is unfixable by any
     * correct change to KeyChord.
     */
    @Test
    void everyNameInTheTableFormatsBackToAnEqualChord() {
        int covered = 0;
        for (int keyCode = 0; keyCode <= GLFW_KEY_LAST; keyCode++) {
            String name = GlfwKeyNameResolver.nameOf(keyCode);
            OptionalInt resolved = GlfwKeyNameResolver.resolve(name);
            if (resolved.isEmpty() || resolved.getAsInt() != keyCode) {
                continue; // no constant for this code; nameOf gave back a number
            }
            covered++;
            KeyChord plain = KeyChord.parse(name);
            assertEquals(keyCode, plain.keyCode(), name);
            assertEquals(plain, KeyChord.parse(plain.format()), name);

            KeyChord chorded = KeyChord.of(keyCode, CTRL, SHIFT, ALT, META);
            assertEquals(chorded, KeyChord.parse(chorded.format()), name);
        }
        assertTrue(covered >= 100, "guard must not skip the table itself: " + covered);
    }

    /**
     * {@code "+"} is what a player writes to bind the plus key once '+' is the
     * documented separator. {@code text.split("\\+")} returns a zero-length array
     * for it, so the last-segment index was -1. The capture-toggle conversion
     * parses per frame on the render path, where a throw is not recoverable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"+", "++", "+++"})
    void separatorOnlyInputIsUnboundRatherThanThrowing(String configured) {
        KeyChord chord = KeyChord.parse(configured);

        assertFalse(chord.isBound(), configured);
        assertEquals(KeyChord.NO_KEY, chord.keyCode(), configured);
    }

    @Test
    void aChordIsValueEqualRegardlessOfHowItsModifierSetWasBuilt() {
        assertEquals(KeyChord.of(GLFW_KEY_O, SHIFT, CTRL),
                KeyChord.of(GLFW_KEY_O, CTRL, SHIFT));
        assertEquals(KeyChord.of(GLFW_KEY_O, SHIFT, CTRL).hashCode(),
                KeyChord.of(GLFW_KEY_O, CTRL, SHIFT).hashCode());
    }
}
