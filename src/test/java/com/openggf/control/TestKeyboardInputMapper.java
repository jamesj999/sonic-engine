package com.openggf.control;

import com.openggf.tests.TestTempFiles;
import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestKeyboardInputMapper {

    @Test
    void mapsConfiguredPlayerOneDirectionsActionsAndStart() throws IOException {
        SonicConfigurationService config = newConfig();
        config.setConfigValue(SonicConfiguration.P1_A, "SPACE");
        config.setConfigValue(SonicConfiguration.P1_B, "A");
        config.setConfigValue(SonicConfiguration.P1_C, "B");
        config.setConfigValue(SonicConfiguration.START, "BACKSPACE");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config));
        handler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_BACKSPACE, GLFW_PRESS);

        PlayerInputState p1 = new KeyboardInputMapper().mapPlayer1(handler, InputBindingFactory.fromConfig(config));

        int expectedHeld = AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP;
        int expectedActions = InputActionMasks.ACTION_A | InputActionMasks.ACTION_B | InputActionMasks.ACTION_C;
        assertEquals(expectedHeld, p1.heldMask() & expectedHeld);
        assertEquals(expectedHeld, p1.pressedMask() & expectedHeld);
        assertEquals(expectedActions, p1.actionHeldMask() & expectedActions);
        assertEquals(expectedActions, p1.actionPressedMask() & expectedActions);
        assertTrue(p1.startHeld());
        assertTrue(p1.startPressed());
    }

    @Test
    void unboundKeyboardActionsStayNeutral() throws IOException {
        SonicConfigurationService config = newConfig();
        config.setConfigValue(SonicConfiguration.P1_B, "");
        config.setConfigValue(SonicConfiguration.P1_C, "");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config));
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_PRESS);

        PlayerInputState p1 = new KeyboardInputMapper().mapPlayer1(handler, InputBindingFactory.fromConfig(config));

        assertEquals(0, p1.actionHeldMask() & (InputActionMasks.ACTION_B | InputActionMasks.ACTION_C));
        assertEquals(0, p1.actionPressedMask() & (InputActionMasks.ACTION_B | InputActionMasks.ACTION_C));
    }

    @Test
    void mapsConfiguredPlayerTwoActionAndStart() throws IOException {
        SonicConfigurationService config = newConfig();
        config.setConfigValue(SonicConfiguration.P2_A, "RIGHT_SHIFT");
        config.setConfigValue(SonicConfiguration.P2_START, "RIGHT_CONTROL");
        InputHandler handler = new InputHandler(InputBindingFactory.supplier(config));
        handler.handleKeyEvent(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_RIGHT_CONTROL, GLFW_PRESS);

        PlayerInputState p2 = new KeyboardInputMapper().mapPlayer2(handler, InputBindingFactory.fromConfig(config));

        assertEquals(InputActionMasks.ACTION_A, p2.actionHeldMask() & InputActionMasks.ACTION_A);
        assertEquals(InputActionMasks.ACTION_A, p2.actionPressedMask() & InputActionMasks.ACTION_A);
        assertTrue(p2.startHeld());
        assertTrue(p2.startPressed());
    }

    private static SonicConfigurationService newConfig() throws IOException {
        return SonicConfigurationService.createStandalone(TestTempFiles.createTempDirectory("keyboard-input-mapper"));
    }
}
