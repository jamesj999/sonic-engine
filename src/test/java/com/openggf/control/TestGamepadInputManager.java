package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_B;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_Y;

class TestGamepadInputManager {

    @Test
    void mapsWestSouthEastFaceButtonsToMegaDriveActionsAndJump() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X, GLFW_GAMEPAD_BUTTON_A, GLFW_GAMEPAD_BUTTON_B)));
        GamepadInputManager manager = new GamepadInputManager(source);

        PlayerInputState p1 = manager.poll(enabledBindings("auto", "none")).player1();

        int expectedActions = InputActionMasks.ACTION_A | InputActionMasks.ACTION_B | InputActionMasks.ACTION_C;
        assertEquals(expectedActions, p1.actionHeldMask());
        assertEquals(expectedActions, p1.actionPressedMask());
        assertTrue((p1.heldMask() & AbstractPlayableSprite.INPUT_JUMP) != 0);
        assertTrue((p1.pressedMask() & AbstractPlayableSprite.INPUT_JUMP) != 0);
    }

    @Test
    void mapsDpadAndLeftStickDirectionsUsingDeadzone() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        source.setDevices(GamepadStateSource.DeviceState.connected(
                0,
                "pad",
                buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT),
                -0.60f,
                0.60f));
        GamepadInputManager manager = new GamepadInputManager(source);

        PlayerInputState p1 = manager.poll(enabledBindings("auto", "none")).player1();

        int allDirections = AbstractPlayableSprite.INPUT_UP
                | AbstractPlayableSprite.INPUT_DOWN
                | AbstractPlayableSprite.INPUT_LEFT
                | AbstractPlayableSprite.INPUT_RIGHT;
        assertEquals(allDirections, p1.heldMask() & allDirections);
        assertEquals(allDirections, p1.pressedMask() & allDirections);
    }

    @Test
    void disabledControllersReturnNeutralAndResetPressedEdges() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X)));
        GamepadInputManager manager = new GamepadInputManager(source);

        assertEquals(InputActionMasks.ACTION_A,
                manager.poll(enabledBindings("auto", "none")).player1().actionPressedMask());

        LogicalInputSnapshot disabled = manager.poll(disabledBindings());
        assertEquals(PlayerInputState.neutral(), disabled.player1());
        assertEquals(PlayerInputState.neutral(), disabled.player2());

        assertEquals(InputActionMasks.ACTION_A,
                manager.poll(enabledBindings("auto", "none")).player1().actionPressedMask());
    }

    @Test
    void secondConnectedPadMapsToPlayerTwoWhenBothPlayersAreAuto() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        source.setDevices(
                connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X)),
                connectedPad(1, buttons(GLFW_GAMEPAD_BUTTON_B)));
        GamepadInputManager manager = new GamepadInputManager(source);

        LogicalInputSnapshot snapshot = manager.poll(enabledBindings("auto", "auto"));

        assertEquals(InputActionMasks.ACTION_A, snapshot.player1().actionHeldMask());
        assertEquals(InputActionMasks.ACTION_C, snapshot.player2().actionHeldMask());
    }

    @Test
    void playerOneNoneLeavesFirstPadAvailableForPlayerTwoAuto() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_A)));
        GamepadInputManager manager = new GamepadInputManager(source);

        LogicalInputSnapshot snapshot = manager.poll(enabledBindings("none", "auto"));

        assertEquals(PlayerInputState.neutral(), snapshot.player1());
        assertEquals(InputActionMasks.ACTION_B, snapshot.player2().actionHeldMask());
    }

    @Test
    void pressedEdgesFireOnceWhileHeldAndAgainAfterReleaseRepress() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);
        InputBindings bindings = enabledBindings("auto", "none");

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X)));
        assertEquals(InputActionMasks.ACTION_A, manager.poll(bindings).player1().actionPressedMask());
        assertEquals(0, manager.poll(bindings).player1().actionPressedMask());

        source.setDevices(connectedPad(0, buttons()));
        assertEquals(0, manager.poll(bindings).player1().actionPressedMask());

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_X)));
        assertEquals(InputActionMasks.ACTION_A, manager.poll(bindings).player1().actionPressedMask());
    }

    @Test
    void mapsNorthFaceButtonToDebugModeTogglePressedEdge() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);
        InputBindings bindings = enabledBindings("auto", "none");

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_Y)));
        manager.poll(bindings);
        assertTrue(manager.isDebugModeTogglePressed());

        manager.poll(bindings);
        assertFalse(manager.isDebugModeTogglePressed());

        source.setDevices(connectedPad(0, buttons()));
        manager.poll(bindings);
        assertFalse(manager.isDebugModeTogglePressed());

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_Y)));
        manager.poll(bindings);
        assertTrue(manager.isDebugModeTogglePressed());
    }

    @Test
    void mapsLeftBumperToRewindHeldWhileButtonIsDown() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);
        InputBindings bindings = enabledBindings("auto", "none");

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER)));
        manager.poll(bindings);
        assertTrue(manager.isRewindHeld());
        manager.poll(bindings);
        assertTrue(manager.isRewindHeld());

        source.setDevices(connectedPad(0, buttons()));
        manager.poll(bindings);
        assertFalse(manager.isRewindHeld());
    }

    @Test
    void disabledControllerClearsDebugModeAndRewindGamepadState() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_Y, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER)));
        manager.poll(enabledBindings("auto", "none"));
        assertTrue(manager.isDebugModeTogglePressed());
        assertTrue(manager.isRewindHeld());

        manager.poll(disabledBindings());
        assertFalse(manager.isDebugModeTogglePressed());
        assertFalse(manager.isRewindHeld());
    }

    @Test
    void mapsBackButtonToBackButtonPressedEdge() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);
        InputBindings bindings = enabledBindings("auto", "none");

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_BACK)));
        manager.poll(bindings);
        assertTrue(manager.isBackButtonPressed());

        manager.poll(bindings);
        assertFalse(manager.isBackButtonPressed());

        source.setDevices(connectedPad(0, buttons()));
        manager.poll(bindings);
        assertFalse(manager.isBackButtonPressed());

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_BACK)));
        manager.poll(bindings);
        assertTrue(manager.isBackButtonPressed());
    }

    @Test
    void mapsRightBumperToFrameStepTogglePressedEdge() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);
        InputBindings bindings = enabledBindings("auto", "none");

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)));
        manager.poll(bindings);
        assertTrue(manager.isFrameStepTogglePressed());

        manager.poll(bindings);
        assertFalse(manager.isFrameStepTogglePressed());

        source.setDevices(connectedPad(0, buttons()));
        manager.poll(bindings);
        assertFalse(manager.isFrameStepTogglePressed());

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)));
        manager.poll(bindings);
        assertTrue(manager.isFrameStepTogglePressed());
    }

    @Test
    void disabledControllerClearsBackButtonAndFrameStepGamepadState() {
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        GamepadInputManager manager = new GamepadInputManager(source);

        source.setDevices(connectedPad(0, buttons(GLFW_GAMEPAD_BUTTON_BACK, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER)));
        manager.poll(enabledBindings("auto", "none"));
        assertTrue(manager.isBackButtonPressed());
        assertTrue(manager.isFrameStepTogglePressed());

        manager.poll(disabledBindings());
        assertFalse(manager.isBackButtonPressed());
        assertFalse(manager.isFrameStepTogglePressed());
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

    private static InputBindings enabledBindings(String player1, String player2) {
        return new InputBindings(
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                true,
                0.35,
                player1,
                player2,
                -1,
                -1,
                -1);
    }

    private static InputBindings disabledBindings() {
        return new InputBindings(
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                false,
                0.35,
                "auto",
                "auto",
                -1,
                -1,
                -1);
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
