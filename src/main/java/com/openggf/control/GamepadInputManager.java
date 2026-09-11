package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_B;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_START;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_Y;

public class GamepadInputManager {
    private final GamepadStateSource stateSource;
    private PlayerInputState previousP1 = PlayerInputState.neutral();
    private PlayerInputState previousP2 = PlayerInputState.neutral();
    private boolean previousDebugModeButtonHeld;
    private boolean debugModeTogglePressed;
    private boolean rewindHeld;
    private boolean previousBackButtonHeld;
    private boolean backButtonPressed;
    private boolean previousFrameStepButtonHeld;
    private boolean frameStepTogglePressed;

    public GamepadInputManager(GamepadStateSource stateSource) {
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource");
    }

    public LogicalInputSnapshot poll(InputBindings bindings) {
        if (bindings == null || !bindings.controllerEnabled()) {
            resetPreviousStates();
            return LogicalInputSnapshot.ofPlayers(PlayerInputState.neutral(), PlayerInputState.neutral());
        }

        List<GamepadStateSource.DeviceState> connected = connectedDevices();
        int nextPad = 0;

        PlayerInputState p1 = PlayerInputState.neutral();
        if (isAuto(bindings.controllerPlayer1()) && nextPad < connected.size()) {
            p1 = mapDevice(connected.get(nextPad++), bindings.controllerDeadzone(), previousP1);
        }

        PlayerInputState p2 = PlayerInputState.neutral();
        if (isAuto(bindings.controllerPlayer2()) && nextPad < connected.size()) {
            p2 = mapDevice(connected.get(nextPad), bindings.controllerDeadzone(), previousP2);
        }

        previousP1 = p1;
        previousP2 = p2;

        GamepadStateSource.DeviceState primary = connected.isEmpty() ? null : connected.get(0);
        boolean debugModeHeld = primary != null && primary.buttonDown(GLFW_GAMEPAD_BUTTON_Y);
        debugModeTogglePressed = debugModeHeld && !previousDebugModeButtonHeld;
        previousDebugModeButtonHeld = debugModeHeld;
        rewindHeld = primary != null && primary.buttonDown(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);

        boolean backButtonHeld = primary != null && primary.buttonDown(GLFW_GAMEPAD_BUTTON_BACK);
        backButtonPressed = backButtonHeld && !previousBackButtonHeld;
        previousBackButtonHeld = backButtonHeld;

        boolean frameStepButtonHeld = primary != null && primary.buttonDown(GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER);
        frameStepTogglePressed = frameStepButtonHeld && !previousFrameStepButtonHeld;
        previousFrameStepButtonHeld = frameStepButtonHeld;

        return LogicalInputSnapshot.ofPlayers(p1, p2);
    }

    /**
     * Edge-triggered: true only on the frame the North face button (Y / Triangle)
     * transitions to held on the primary connected pad. Mirrors the Debug Movement
     * toggle keyboard binding ({@code debug.keys.debugMode}).
     */
    public boolean isDebugModeTogglePressed() {
        return debugModeTogglePressed;
    }

    /**
     * Held state of the left bumper (L1) on the primary connected pad. Mirrors the
     * live-rewind hold-key keyboard binding ({@code rewind.liveKey}).
     */
    public boolean isRewindHeld() {
        return rewindHeld;
    }

    /**
     * Edge-triggered: true only on the frame the Back/Select/View button transitions
     * to held on the primary connected pad. Mirrors the main-menu options-panel Tab
     * toggle ({@link com.openggf.game.MasterTitleScreen} / {@link com.openggf.game.LaunchConfigPanel}).
     */
    public boolean isBackButtonPressed() {
        return backButtonPressed;
    }

    /**
     * Edge-triggered: true only on the frame the right bumper (RB/R1) transitions to
     * held on the primary connected pad. Mirrors the frame-advance keyboard binding
     * ({@code debug.keys.frameStep}).
     */
    public boolean isFrameStepTogglePressed() {
        return frameStepTogglePressed;
    }

    private List<GamepadStateSource.DeviceState> connectedDevices() {
        List<GamepadStateSource.DeviceState> connected = new ArrayList<>();
        for (GamepadStateSource.DeviceState device : stateSource.pollDevices()) {
            if (device != null && device.connected()) {
                connected.add(device);
            }
        }
        return connected;
    }

    private PlayerInputState mapDevice(
            GamepadStateSource.DeviceState device,
            double deadzone,
            PlayerInputState previous) {
        int heldMask = directionMask(device, deadzone);
        int actionHeldMask = actionMask(device);
        boolean startHeld = device.buttonDown(GLFW_GAMEPAD_BUTTON_START);

        int pressedMask = heldMask & ~previous.heldMask();
        int actionPressedMask = actionHeldMask & ~previous.actionHeldMask();
        boolean startPressed = startHeld && !previous.startHeld();

        return PlayerInputState.of(
                heldMask,
                pressedMask,
                actionHeldMask,
                actionPressedMask,
                startHeld,
                startPressed);
    }

    private int directionMask(GamepadStateSource.DeviceState device, double deadzone) {
        int mask = 0;
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_DPAD_UP) || device.leftY() < -deadzone) {
            mask |= AbstractPlayableSprite.INPUT_UP;
        }
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_DPAD_DOWN) || device.leftY() > deadzone) {
            mask |= AbstractPlayableSprite.INPUT_DOWN;
        }
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_DPAD_LEFT) || device.leftX() < -deadzone) {
            mask |= AbstractPlayableSprite.INPUT_LEFT;
        }
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) || device.leftX() > deadzone) {
            mask |= AbstractPlayableSprite.INPUT_RIGHT;
        }
        return mask;
    }

    private int actionMask(GamepadStateSource.DeviceState device) {
        int mask = 0;
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_X)) {
            mask |= InputActionMasks.ACTION_A;
        }
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_A)) {
            mask |= InputActionMasks.ACTION_B;
        }
        if (device.buttonDown(GLFW_GAMEPAD_BUTTON_B)) {
            mask |= InputActionMasks.ACTION_C;
        }
        return mask;
    }

    private boolean isAuto(String assignment) {
        return assignment != null && "auto".equalsIgnoreCase(assignment.trim());
    }

    private void resetPreviousStates() {
        previousP1 = PlayerInputState.neutral();
        previousP2 = PlayerInputState.neutral();
        previousDebugModeButtonHeld = false;
        debugModeTogglePressed = false;
        rewindHeld = false;
        previousBackButtonHeld = false;
        backButtonPressed = false;
        previousFrameStepButtonHeld = false;
        frameStepTogglePressed = false;
    }
}
