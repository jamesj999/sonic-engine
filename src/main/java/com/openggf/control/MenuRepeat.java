package com.openggf.control;

import java.util.HashMap;
import java.util.Map;

/** Menu-only repeat at the UI update cadence; gameplay edges are never changed. */
final class MenuRepeat {
    static final int DELAY = 24;
    static final int INTERVAL = 4;
    private final Map<Integer, State> states = new HashMap<>();

    boolean pulse(int key, long frame, boolean held, boolean pressed) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.frame == frame && state.held == held && state.pressed == pressed) return state.pulse;
        state.held = held;
        state.pressed = pressed;
        if (pressed) {
            state.started = frame;
            state.pulse = true;
        } else if (!held) {
            state.started = -1;
            state.pulse = false;
        } else if (state.started < 0 || frame - state.frame > 1) {
            state.started = frame;
            state.pulse = false;
        } else {
            long age = frame - state.started;
            state.pulse = age >= DELAY && (age - DELAY) % INTERVAL == 0;
        }
        state.frame = frame;
        return state.pulse;
    }

    private static final class State {
        long frame = -1;
        long started = -1;
        boolean pulse;
        boolean held;
        boolean pressed;
    }
}
