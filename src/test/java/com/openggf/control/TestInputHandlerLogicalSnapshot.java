package com.openggf.control;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestInputHandlerLogicalSnapshot {

    @Test
    void physicalModifiersRemainVisibleWhenPlaybackOverridesLogicalInput() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());

        assertFalse(input.isShiftDown());
        assertTrue(input.isPhysicalShiftDown());
        assertTrue(input.isPhysicalKeyDown(GLFW_KEY_LEFT_SHIFT));
    }

    @Test
    void refreshLogicalSnapshotReadsCurrentKeyboardState() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.P1_A, GLFW_KEY_SPACE);
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));

        input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        input.refreshLogicalSnapshot();

        assertEquals(
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                input.logical().player1().heldMask());
    }

    @Test
    void logicalOverrideSuppressesLiveInputForPlaybackOwnedFrame() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(AbstractPlayableSprite.INPUT_LEFT, 0, 0, 0, false, false),
                PlayerInputState.neutral()));

        input.refreshLogicalSnapshot();

        assertEquals(AbstractPlayableSprite.INPUT_LEFT, input.logical().player1().heldMask());
        input.clearLogicalOverride();
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void logicalOverrideCarriesRecordedDebugModifiers() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        int debugModeKey = config.getInt(SonicConfiguration.DEBUG_MODE_KEY);
        input.setLogicalOverride(
                LogicalInputSnapshot.neutral().withDebugInput(true, true, true, true, true));

        assertTrue(input.isShiftDown());
        assertTrue(input.isControlDown());
        assertTrue(input.isKeyPressed(debugModeKey));

        input.clearLogicalOverride();

        assertFalse(input.isShiftDown());
        assertFalse(input.isControlDown());
    }

    /**
     * All four modifier queries must answer from the same source. Shift and Ctrl
     * already consulted the logical override; Alt read live hardware, so an Alt
     * chord was not reproducible under playback while the same chord on Ctrl was.
     */
    @Test
    void allFourModifierQueriesAnswerFromTheLogicalOverride() {
        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.neutral()
                .withDebugInput(false, true, true, true, true));

        assertTrue(input.isShiftDown());
        assertTrue(input.isControlDown());
        assertTrue(input.isAltDown());
        assertTrue(input.isSuperDown());
    }

    @Test
    void aLogicalOverrideHidesLiveModifierHardware() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_LEFT_ALT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);

        input.setLogicalOverride(LogicalInputSnapshot.neutral()
                .withDebugInput(false, false, false, false, false));

        assertFalse(input.isAltDown());
        assertFalse(input.isSuperDown());

        input.clearLogicalOverride();

        assertTrue(input.isAltDown());
        assertTrue(input.isSuperDown());
    }

    @Test
    void logicalOverrideLeavesUnrelatedRawKeysLive() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());

        assertTrue(input.isKeyPressed(GLFW_KEY_F1));
    }

    @Test
    void logicalOverrideDiscardsGamepadPressedEdgesBeforeReturningToLiveInput() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));
        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X)));
        input.setLogicalOverride(LogicalInputSnapshot.neutral());

        input.refreshLogicalSnapshot();
        input.clearLogicalOverride();
        input.refreshLogicalSnapshot();

        assertEquals(InputActionMasks.ACTION_A, input.logical().player1().actionHeldMask());
        assertEquals(0, input.logical().player1().actionPressedMask());
    }

    @Test
    void menuAcceptExcludingBackActionAcceptsOnlyABOrStart() {
        InputHandler input = new InputHandler();

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_C, false, false),
                PlayerInputState.neutral()));
        assertFalse(input.menuAcceptExcludingBackAction());

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_A, false, false),
                PlayerInputState.neutral()));
        assertTrue(input.menuAcceptExcludingBackAction());

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_B, false, false),
                PlayerInputState.neutral()));
        assertTrue(input.menuAcceptExcludingBackAction());

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, 0, false, true),
                PlayerInputState.neutral()));
        assertTrue(input.menuAcceptExcludingBackAction());
    }

    @Test
    void gamepadNorthButtonTriggersDebugModeKeyPressedEdge() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));
        int debugModeKey = config.getInt(SonicConfiguration.DEBUG_MODE_KEY);

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_Y)));
        input.refreshLogicalSnapshot();

        assertTrue(input.isKeyPressed(debugModeKey));
    }

    @Test
    void gamepadLeftBumperHoldsRewindKeyDown() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));
        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER)));
        input.refreshLogicalSnapshot();

        assertTrue(input.isKeyDown(rewindKey));

        source.setDevices(connectedPad(0, buttons()));
        input.refreshLogicalSnapshot();

        assertFalse(input.isKeyDown(rewindKey));
    }

    @Test
    void gamepadRightBumperTriggersFrameStepKeyPressedEdge() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));
        int frameStepKey = config.getInt(SonicConfiguration.FRAME_STEP_KEY);

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)));
        input.refreshLogicalSnapshot();

        assertTrue(input.isKeyPressed(frameStepKey));
    }

    @Test
    void gamepadBackButtonPressedEdgeIsExposedAndSuppressedUnderOverride() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_BACK)));
        input.refreshLogicalSnapshot();

        assertTrue(input.isGamepadBackButtonPressed());

        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        assertFalse(input.isGamepadBackButtonPressed());
    }

    @Test
    void isDirectionHeldIsTrueForKeyboardOnlyGamepadOnlyBothOrNeither() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), source);
        int upKey = config.getInt(SonicConfiguration.UP);

        source.setDevices(connectedPad(0, buttons()));
        input.refreshLogicalSnapshot();
        assertFalse(input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP));

        input.handleKeyEvent(upKey, GLFW_PRESS);
        input.refreshLogicalSnapshot();
        assertTrue(input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP));
        input.handleKeyEvent(upKey, GLFW_RELEASE);
        input.update();

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP)));
        input.refreshLogicalSnapshot();
        assertTrue(input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP));

        source.setDevices(connectedPad(0, buttons()));
        input.refreshLogicalSnapshot();
        assertFalse(input.isDirectionHeld(upKey, AbstractPlayableSprite.INPUT_UP));
    }

    @Test
    void holdingDirectionForOneHundredTwentyFramesNeverReadsZeroedWhileKeyboardKeyStaysDown() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        int rightKey = config.getInt(SonicConfiguration.RIGHT);

        input.handleKeyEvent(rightKey, GLFW_PRESS);
        for (int frame = 0; frame < 120; frame++) {
            input.refreshLogicalSnapshot();
            assertTrue((input.logical().player1().heldMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                    "Held-right should not read as released on frame " + frame
                            + " while the keyboard key remains physically down");
            input.update();
        }
    }

    @Test
    void holdingDirectionForOneHundredTwentyFramesNeverReadsZeroedWhileGamepadDpadStaysHeld() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), new GamepadInputManager(source));

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT)));
        for (int frame = 0; frame < 120; frame++) {
            input.refreshLogicalSnapshot();
            assertTrue((input.logical().player1().heldMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                    "Held-right should not read as released on frame " + frame
                            + " while the gamepad D-pad remains physically held");
            input.update();
        }
    }

    private static GamepadStateSource.DeviceState connectedPad(int joystickId, boolean[] buttons) {
        return GamepadStateSource.DeviceState.connected(joystickId, "pad-" + joystickId, buttons, 0.0f, 0.0f);
    }

    private static boolean[] buttons(int... pressedButtons) {
        boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
        for (int button : pressedButtons) {
            buttons[button] = true;
        }
        return buttons;
    }

    private static final class FakeGamepadStateSource implements GamepadStateSource {
        private final List<DeviceState> devices = new ArrayList<>();

        void setDevices(DeviceState... devices) {
            this.devices.clear();
            this.devices.addAll(List.of(devices));
        }

        @Override
        public List<DeviceState> pollDevices() {
            return List.copyOf(devices);
        }
    }
}
