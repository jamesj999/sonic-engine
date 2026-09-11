package com.openggf.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestInputHandlerMouse {

    @Test
    void mouseMoveStoresLatestPosition() {
        InputHandler input = new InputHandler();

        input.handleMouseMove(123.5, 45.25);

        assertEquals(123.5, input.getMouseX());
        assertEquals(45.25, input.getMouseY());
    }

    @Test
    void mouseButtonPressedIsOneFrameEdge() {
        InputHandler input = new InputHandler();

        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        assertTrue(input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(input.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));

        input.update();

        assertTrue(input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(input.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));

        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);

        assertFalse(input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(input.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }
}
