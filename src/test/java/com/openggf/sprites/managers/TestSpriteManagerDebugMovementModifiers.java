package com.openggf.sprites.managers;

import org.junit.jupiter.api.Test;
import com.openggf.control.InputHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

public class TestSpriteManagerDebugMovementModifiers {

    @Test
    public void speedUpModifierUsesEitherShiftKey() {
        InputHandler leftShift = new InputHandler();
        leftShift.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        assertTrue(SpriteManager.isDebugSpeedUpModifierDown(leftShift));

        InputHandler rightShift = new InputHandler();
        rightShift.handleKeyEvent(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS);
        assertTrue(SpriteManager.isDebugSpeedUpModifierDown(rightShift));
    }

    @Test
    public void slowDownModifierUsesEitherControlKey() {
        InputHandler leftCtrl = new InputHandler();
        leftCtrl.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        assertTrue(SpriteManager.isDebugSlowDownModifierDown(leftCtrl));

        InputHandler rightCtrl = new InputHandler();
        rightCtrl.handleKeyEvent(GLFW_KEY_RIGHT_CONTROL, GLFW_PRESS);
        assertTrue(SpriteManager.isDebugSlowDownModifierDown(rightCtrl));
    }

    @Test
    public void speedUpModifierIsFalseWithoutShift() {
        InputHandler handler = new InputHandler();
        assertFalse(SpriteManager.isDebugSpeedUpModifierDown(handler));
    }

    @Test
    public void slowDownModifierIsFalseWithoutControl() {
        InputHandler handler = new InputHandler();
        assertFalse(SpriteManager.isDebugSlowDownModifierDown(handler));
    }
}


