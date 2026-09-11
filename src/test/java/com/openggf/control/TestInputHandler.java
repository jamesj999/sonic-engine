package com.openggf.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestInputHandler {

    @Test
    void isAnyKeyJustPressedReturnsFalseWhenNoKeysPressed() {
        InputHandler handler = new InputHandler();
        assertFalse(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsTrueOnTransitionToPressed() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsFalseOnceFrameAdvances() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
        handler.update();
        assertFalse(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedDetectsAnyKeyNotJustConfiguredOnes() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_A, GLFW_PRESS);
        assertTrue(handler.isAnyKeyJustPressed());
    }

    @Test
    void isAnyKeyJustPressedReturnsFalseAfterKeyReleased() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        handler.update();
        handler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_RELEASE);
        assertFalse(handler.isAnyKeyJustPressed());
    }
}
