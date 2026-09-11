package com.openggf.game.rewind;

import java.util.Objects;
import java.util.function.Consumer;

public final class PlaybackController {

    public enum State { PLAYING, PAUSED, REWINDING }

    private final RewindController rc;
    private final Consumer<State> stateObserver;
    private State state = State.PLAYING;

    public PlaybackController(RewindController rc) {
        this(rc, s -> {});
    }

    public PlaybackController(RewindController rc, Consumer<State> stateObserver) {
        this.rc = Objects.requireNonNull(rc);
        this.stateObserver = Objects.requireNonNull(stateObserver);
    }

    public State state() { return state; }

    public void play()    { setState(State.PLAYING); }
    public void pause()   { setState(State.PAUSED); }
    public void rewind()  { setState(State.REWINDING); }

    private void setState(State s) {
        if (s == state) return;
        state = s;
        stateObserver.accept(s);
    }

    /** Called once per visualiser frame at the configured framerate. */
    public void tick() {
        switch (state) {
            case PLAYING -> rc.step();
            case REWINDING -> {
                if (!rc.stepBackward()) {
                    // Hit buffer start — auto-pause.
                    setState(State.PAUSED);
                }
            }
            case PAUSED -> { /* no-op */ }
        }
    }

    public void stepForwardOnce() {
        State prev = state;
        rc.step();
        if (prev != State.PAUSED) setState(State.PAUSED);
    }

    public void stepBackwardOnce() {
        State prev = state;
        if (!rc.stepBackward()) {
            setState(State.PAUSED);
        } else if (prev != State.PAUSED) {
            setState(State.PAUSED);
        }
    }
}
