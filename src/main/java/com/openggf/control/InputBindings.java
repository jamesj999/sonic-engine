package com.openggf.control;

public record InputBindings(
        int p1Up,
        int p1Down,
        int p1Left,
        int p1Right,
        int p1A,
        int p1B,
        int p1C,
        int p1Start,
        int p2Up,
        int p2Down,
        int p2Left,
        int p2Right,
        int p2A,
        int p2B,
        int p2C,
        int p2Start,
        boolean controllerEnabled,
        double controllerDeadzone,
        String controllerPlayer1,
        String controllerPlayer2,
        int debugModeKey,
        int rewindKey,
        int frameStepKey) {

    public InputBindings {
        controllerDeadzone = clampDeadzone(controllerDeadzone);
    }

    public static double clampDeadzone(double value) {
        if (Double.isNaN(value)) {
            return 0.35;
        }
        return Math.max(0.0, Math.min(0.95, value));
    }
}
