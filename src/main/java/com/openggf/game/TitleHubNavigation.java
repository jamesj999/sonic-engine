package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;

/** Two explicit focus panes; physical/replay input authority remains in InputHandler. */
final class TitleHubNavigation {
    enum Action {
        START("START GAME"), LAUNCH("LAUNCH OPTIONS"), TIME_ATTACK("TIME ATTACK"),
        RECORDINGS("RECORDINGS"), MODS("MODS"), SETTINGS("SETTINGS"), TOOLS("TOOLS");
        final String label;
        Action(String label) { this.label = label; }
    }

    private final boolean[] previous = new boolean[6];
    private final boolean[] pressed = new boolean[6];
    private boolean actions;
    private int selected;

    void capture(InputHandler input) {
        boolean[] current = { MenuInput.left(input), MenuInput.right(input), MenuInput.up(input),
                MenuInput.down(input), MenuInput.accept(input), MenuInput.back(input) };
        for (int i = 0; i < current.length; i++) {
            pressed[i] = current[i] && !previous[i];
            previous[i] = current[i];
        }
    }

    boolean left() { return pressed[0]; }
    boolean right() { return pressed[1]; }
    boolean up() { return pressed[2]; }
    boolean down() { return pressed[3]; }
    boolean accept() { return pressed[4]; }
    boolean back() { return pressed[5]; }
    boolean actions() { return actions; }
    int selected() { return selected; }
    Action action() { return Action.values()[selected]; }
    void enter() { actions = true; selected = 0; }
    void leave() { actions = false; }
    boolean move(int delta) {
        int next = Math.clamp(selected + delta, 0, Action.values().length - 1);
        boolean moved = next != selected;
        selected = next;
        return moved;
    }
}
