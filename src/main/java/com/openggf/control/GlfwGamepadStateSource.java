package com.openggf.control;

import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST;
import static org.lwjgl.glfw.GLFW.glfwGetGamepadName;
import static org.lwjgl.glfw.GLFW.glfwGetGamepadState;
import static org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad;
import static org.lwjgl.system.MemoryStack.stackPush;

public class GlfwGamepadStateSource implements GamepadStateSource {

    @Override
    public List<DeviceState> pollDevices() {
        List<DeviceState> devices = new ArrayList<>();
        try (MemoryStack stack = stackPush()) {
            GLFWGamepadState state = GLFWGamepadState.malloc(stack);
            for (int joystickId = GLFW_JOYSTICK_1; joystickId <= GLFW_JOYSTICK_LAST; joystickId++) {
                if (!glfwJoystickIsGamepad(joystickId) || !glfwGetGamepadState(joystickId, state)) {
                    continue;
                }
                boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
                for (int button = 0; button <= GLFW_GAMEPAD_BUTTON_LAST; button++) {
                    buttons[button] = state.buttons(button) != 0;
                }
                devices.add(DeviceState.connected(
                        joystickId,
                        glfwGetGamepadName(joystickId),
                        buttons,
                        state.axes(GLFW_GAMEPAD_AXIS_LEFT_X),
                        state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y)));
            }
        }
        return List.copyOf(devices);
    }
}
