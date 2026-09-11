package com.openggf.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;

import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.junit.jupiter.api.Assertions.*;

public class TestInputHandler {

    @TempDir
    Path configDir;

    @Test
    public void testKeyPressRelease() {
        InputHandler handler = new InputHandler();
        // Simulate key press using GLFW key codes
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_PRESS);
        assertTrue(handler.isKeyDown(GLFW_KEY_A));
        // Simulate key release
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_RELEASE);
        assertFalse(handler.isKeyDown(GLFW_KEY_A));
        assertFalse(handler.isKeyDown(999));
    }

    @Test
    public void testModifierHelpersRecognizeHeldModifiers() {
        InputHandler handler = new InputHandler();

        handler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_RIGHT_CONTROL, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_LEFT_ALT, GLFW_PRESS);

        assertTrue(handler.isShiftDown());
        assertTrue(handler.isControlDown());
        assertTrue(handler.isAltDown());
        assertTrue(handler.isAnyModifierDown());
    }

    @Test
    public void testKeyPressedWithoutModifiersIsSuppressedByModifier() {
        InputHandler handler = new InputHandler();

        handler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertTrue(handler.isKeyPressed(GLFW_KEY_B));
        assertFalse(handler.isKeyPressedWithoutModifiers(GLFW_KEY_B));
    }

    @Test
    public void testKeyPressedWithoutModifiersAllowsPlainKey() {
        InputHandler handler = new InputHandler();

        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertTrue(handler.isKeyPressedWithoutModifiers(GLFW_KEY_B));
    }

    @Test
    public void testSuperIsAModifierLikeShiftControlAndAlt() {
        InputHandler handler = new InputHandler();

        handler.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);

        assertTrue(handler.isSuperDown());
        assertTrue(handler.isAnyModifierDown());
        assertFalse(handler.isKeyPressedWithoutModifiers(GLFW_KEY_B));
    }

    @Test
    public void testRightSuperCountsAsSuper() {
        InputHandler handler = new InputHandler();

        handler.handleKeyEvent(GLFW_KEY_RIGHT_SUPER, GLFW_PRESS);

        assertTrue(handler.isSuperDown());
    }

    /**
     * Super is the window-switch modifier on Linux and Windows, so its GLFW_RELEASE
     * is routinely delivered to whichever window took focus. Without a focus-loss
     * clear the key latches and every isKeyPressedWithoutModifiers call site stays
     * dead for the rest of the process.
     */
    @Test
    public void testFocusLossClearsALatchedModifierAndItsHeldKeys() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        handler.clearKeyState();

        assertFalse(handler.isSuperDown());
        assertFalse(handler.isAnyModifierDown());
        assertFalse(handler.isKeyDown(GLFW_KEY_B));
        assertFalse(handler.isKeyPressed(GLFW_KEY_B), "no stale rising edge survives the clear");
    }

    @Test
    public void testAKeyPressedAfterFocusLossStillRegisters() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);
        handler.clearKeyState();

        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertTrue(handler.isKeyPressedWithoutModifiers(GLFW_KEY_B));
    }

    /**
     * An explicitly empty binding is documented as switching a shortcut off, and
     * it resolves to key code -1. isKeyPressed(-1) used to reach the pad
     * substitution branch -- {@code keyCode == inputBindings.debugModeKey()} is
     * satisfied by an unbound debug-mode binding, which is -1 too -- so with
     * {@code debug.keys.debugMode: ""} a single pad debug-mode press fired every
     * other unbound binding at once, including all nine playback keys, which
     * ship unbound. The isKeyDown twin is closed in InputHandler too -- see
     * {@link #testAnUnboundBindingIsNotHeldFromTheGamepadRewindButton}.
     */
    @Test
    public void testAnUnboundBindingDoesNotFireFromTheGamepadDebugModeButton() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.DEBUG_MODE_KEY, "");
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config),
                new PadWithNorthFaceButtonHeld());
        handler.refreshLogicalSnapshot();

        assertEquals(-1, config.getInt(SonicConfiguration.DEBUG_MODE_KEY),
                "precondition: an empty binding resolves to -1");
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY),
                "precondition: the playback keys ship unbound, so they resolve to -1 too");

        assertFalse(handler.isKeyPressed(config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY)),
                "an unbound playback key must not fire from the pad's debug-mode button");
        assertFalse(handler.isKeyPressed(config.getInt(SonicConfiguration.DEBUG_MODE_KEY)),
                "an unbound debug-mode binding is switched off, pad or no pad");
    }

    /**
     * The isKeyDown half of the same hazard. {@code isKeyDown} fell through to
     * {@code keyCode == inputBindings.rewindKey() && gamepadInputManager.isRewindHeld()},
     * so with {@code rewind.liveKey: ""} both sides of that comparison were -1
     * and every unbound binding read as held for the whole time the pad's left
     * bumper was down. P1_B and P1_C ship unbound and are read through
     * KeyboardInputMapper's held path, so player one gained a phantom B and C
     * with no matching press edge -- held disagreeing with pressed, which is the
     * inconsistency this work exists to remove. rewindHeld is set from the
     * bumper unconditionally, so LIVE_REWIND_ENABLED does not save it either.
     */
    @Test
    public void testAnUnboundBindingIsNotHeldFromTheGamepadRewindButton() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_KEY, "");
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config),
                new PadWithLeftBumperHeld());
        handler.refreshLogicalSnapshot();

        assertEquals(-1, config.getInt(SonicConfiguration.LIVE_REWIND_KEY),
                "precondition: an empty binding resolves to -1");
        assertEquals(-1, config.getInt(SonicConfiguration.P1_B),
                "precondition: P1_B ships unbound, so it resolves to -1 too");

        assertFalse(handler.isKeyDown(config.getInt(SonicConfiguration.P1_B)),
                "an unbound P1_B must not read as held from the pad's rewind bumper");
        assertFalse(handler.isKeyDown(config.getInt(SonicConfiguration.LIVE_REWIND_KEY)),
                "an unbound live-rewind binding is switched off, pad or no pad");
    }

    /** The pad rewind path itself is untouched: a bound rewind key still reads as held. */
    @Test
    public void testABoundRewindKeyStillReadsAsHeldFromTheGamepad() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config),
                new PadWithLeftBumperHeld());
        handler.refreshLogicalSnapshot();

        assertTrue(handler.isKeyDown(config.getInt(SonicConfiguration.LIVE_REWIND_KEY)));
    }

    /** The pad path itself is untouched: a bound debug-mode key still fires. */
    @Test
    public void testABoundDebugModeKeyStillFiresFromTheGamepad() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config),
                new PadWithNorthFaceButtonHeld());
        handler.refreshLogicalSnapshot();

        assertTrue(handler.isKeyPressed(config.getInt(SonicConfiguration.DEBUG_MODE_KEY)));
    }

    /** One connected pad with the rewind (left bumper) button held. */
    private static final class PadWithLeftBumperHeld implements GamepadStateSource {
        @Override
        public List<DeviceState> pollDevices() {
            boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            buttons[GLFW_GAMEPAD_BUTTON_LEFT_BUMPER] = true;
            return List.of(DeviceState.connected(0, "pad-0", buttons, 0.0f, 0.0f));
        }
    }

    /** One connected pad with the debug-mode (North face) button held. */
    private static final class PadWithNorthFaceButtonHeld implements GamepadStateSource {
        @Override
        public List<DeviceState> pollDevices() {
            boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            buttons[GLFW_GAMEPAD_BUTTON_Y] = true;
            return List.of(DeviceState.connected(0, "pad-0", buttons, 0.0f, 0.0f));
        }
    }
}


