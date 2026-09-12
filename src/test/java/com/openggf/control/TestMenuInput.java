package com.openggf.control;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestMenuInput {
    @Test
    void promptsFollowKeyboardControllerKeyboardWithoutHeldInputsStealingFocus() {
        Fixture fixture = new Fixture();
        InputHandler input = fixture.input;
        assertEquals("Enter", MenuInput.confirmLabel(input));
        input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.frame();
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_A);
        fixture.frame();
        assertTrue(MenuInput.controller(input));
        assertEquals("A", MenuInput.confirmLabel(input));
        assertEquals("B", MenuInput.backLabel(input));
        assertEquals("D-Pad", MenuInput.directionLabel(input));
        input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_REPEAT);
        fixture.frame();
        assertTrue(MenuInput.controller(input), "Keyboard repeat is not a new physical edge");
        input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        fixture.frame();
        assertFalse(MenuInput.controller(input));
        fixture.frame();
        assertFalse(MenuInput.controller(input), "Held controller button must not steal keyboard hints");
        assertEquals("Esc", MenuInput.backLabel(input));
    }

    @Test
    void connectionAndReconnectDoNotSelectControllerHints() {
        Fixture fixture = new Fixture();
        fixture.devices = List.of();
        fixture.frame();
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_A);
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
        fixture.pad(0);
        fixture.frame();
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_A);
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
    }

    @Test
    void deadzoneNoiseAndThresholdJitterDoNotRepeatedlyStealKeyboardHints() {
        Fixture fixture = new Fixture();
        fixture.pad(0.15f);
        fixture.frame();
        fixture.pad(-0.15f);
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
        fixture.pad(0.6f);
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
        fixture.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.frame();
        fixture.pad(0.29f);
        fixture.frame();
        fixture.pad(0.31f);
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
        fixture.pad(0);
        fixture.frame();
        fixture.pad(0.6f);
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
    }

    @Test
    void logicalReplayDoesNotChangeHintsButPhysicalInputDuringReplayDoes() {
        Fixture fixture = new Fixture();
        fixture.input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, InputActionMasks.ACTION_B, InputActionMasks.ACTION_B, false, false),
                PlayerInputState.neutral()));
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_A);
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
        fixture.input.setLogicalOverride(LogicalInputSnapshot.neutral());
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
        fixture.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
        fixture.input.clearLogicalOverride();
        fixture.frame();
        assertFalse(MenuInput.controller(fixture.input));
    }

    @Test
    void menuBackCannotAlsoConfirmAndFixedKeyboardKeysSurviveRemapping() {
        Fixture fixture = new Fixture();
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_B);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(MenuInput.back(fixture.input));
        assertFalse(MenuInput.accept(fixture.input));
        fixture.pad(0);
        fixture.frame();
        fixture.config.setConfigValue(SonicConfiguration.START, GLFW_KEY_P);
        fixture.config.setConfigValue(SonicConfiguration.RIGHT, GLFW_KEY_D);
        fixture.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(MenuInput.accept(fixture.input));
        assertTrue(MenuInput.right(fixture.input));
        fixture.input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        assertTrue(MenuInput.back(fixture.input));
        assertFalse(MenuInput.accept(fixture.input));
    }

    @Test
    void fixedArrowChangesHintsEvenWhenGameplayDirectionIsRemapped() {
        Fixture fixture = new Fixture();
        fixture.config.setConfigValue(SonicConfiguration.RIGHT, GLFW_KEY_D);
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_A);
        fixture.frame();
        assertTrue(MenuInput.controller(fixture.input));
        fixture.input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertFalse(fixture.input.logical().menuRight());
        assertTrue(MenuInput.right(fixture.input));
        assertFalse(MenuInput.controller(fixture.input));
    }

    @Test
    void configuredKeyboardActionsRemainAvailable() {
        Fixture fixture = new Fixture();
        fixture.config.setConfigValue(SonicConfiguration.P1_A, GLFW_KEY_Z);
        fixture.config.setConfigValue(SonicConfiguration.UP, GLFW_KEY_W);
        fixture.input.handleKeyEvent(GLFW_KEY_Z, GLFW_PRESS);
        fixture.input.handleKeyEvent(GLFW_KEY_W, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(MenuInput.accept(fixture.input));
        assertTrue(MenuInput.up(fixture.input));
        assertFalse(MenuInput.controller(fixture.input));
    }

    @Test
    void fixedEnterConfirmsEvenWhenBoundToGameplayBack() {
        Fixture fixture = new Fixture();
        fixture.config.setConfigValue(SonicConfiguration.P1_C, GLFW_KEY_ENTER);
        fixture.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(fixture.input.logical().menuBack(), "Gameplay binding remains intact");
        assertTrue(MenuInput.accept(fixture.input));
        assertFalse(MenuInput.back(fixture.input));
    }

    @Test
    void fixedArrowSuppressesConflictingKeyboardDirectionsAndActions() {
        Fixture fixture = new Fixture();
        fixture.config.setConfigValue(SonicConfiguration.RIGHT, GLFW_KEY_LEFT);
        fixture.config.setConfigValue(SonicConfiguration.P1_C, GLFW_KEY_LEFT);
        fixture.input.handleKeyEvent(GLFW_KEY_LEFT, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(fixture.input.logical().menuRight());
        assertTrue(MenuInput.left(fixture.input));
        assertFalse(MenuInput.right(fixture.input));
        assertFalse(MenuInput.back(fixture.input));
        assertFalse(MenuInput.accept(fixture.input));
    }

    @Test
    void physicalControllerBackStillWinsAgainstFixedKeyboardEnter() {
        Fixture fixture = new Fixture();
        fixture.config.setConfigValue(SonicConfiguration.P1_C, GLFW_KEY_ENTER);
        fixture.pad(0, GLFW_GAMEPAD_BUTTON_B);
        fixture.input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        fixture.input.refreshLogicalSnapshot();
        assertTrue(MenuInput.back(fixture.input));
        assertFalse(MenuInput.accept(fixture.input));
    }

    private static final class Fixture {
        private final SonicConfigurationService config = SonicConfigurationService.createStandalone();
        private List<GamepadStateSource.DeviceState> devices = List.of();
        private final InputHandler input;

        Fixture() {
            config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
            config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
            config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
            config.setConfigValue(SonicConfiguration.CONTROLLER_DEADZONE, 0.3);
            input = new InputHandler(InputBindingFactory.supplier(config), () -> devices);
            pad(0);
            frame();
        }

        void pad(float x, int... pressed) {
            boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            for (int button : pressed) {
                buttons[button] = true;
            }
            devices = List.of(GamepadStateSource.DeviceState.connected(0, "Test pad", buttons, x, 0));
        }

        void frame() {
            input.refreshLogicalSnapshot();
            input.update();
        }
    }
}
