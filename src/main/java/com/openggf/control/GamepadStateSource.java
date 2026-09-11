package com.openggf.control;

import java.util.List;

public interface GamepadStateSource {
    List<DeviceState> pollDevices();

    static GamepadStateSource noop() {
        return List::of;
    }

    record DeviceState(
            int joystickId,
            String name,
            boolean connected,
            boolean[] buttons,
            float leftX,
            float leftY) {

        public DeviceState {
            name = name != null ? name : "";
            buttons = buttons != null ? buttons.clone() : new boolean[0];
        }

        public static DeviceState connected(
                int joystickId,
                String name,
                boolean[] buttons,
                float leftX,
                float leftY) {
            return new DeviceState(joystickId, name, true, buttons, leftX, leftY);
        }

        @Override
        public boolean[] buttons() {
            return buttons.clone();
        }

        public boolean buttonDown(int button) {
            return button >= 0 && button < buttons.length && buttons[button];
        }
    }
}
