package com.openggf.trace.replay;

import com.openggf.game.GameMode;

import java.util.Objects;

/** Structural launch state for a visual trace's unobserved title-card prelude. */
public final class VisualTraceLaunchPhase {
    private State state = State.NEW;
    private boolean titleCardControlReleased;

    public void beginTitleCardPresentation() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "visual trace title-card presentation is already started");
        }
        state = State.TITLE_CARD_PRESENTATION;
    }

    /** Claims the real title-card control-release boundary exactly once. */
    public boolean claimTitleCardControlRelease() {
        if (state != State.TITLE_CARD_PRESENTATION
                || titleCardControlReleased) {
            return false;
        }
        titleCardControlReleased = true;
        return true;
    }

    /** Claims the one between-iterations handoff after control is released. */
    public boolean beginReplayBootstrapIfReady(GameMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (state != State.TITLE_CARD_PRESENTATION
                || mode != GameMode.LEVEL
                || !titleCardControlReleased) {
            return false;
        }
        state = State.REPLAY_BOOTSTRAP;
        return true;
    }

    public void markActive() {
        if (state != State.NEW && state != State.REPLAY_BOOTSTRAP) {
            throw new IllegalStateException(
                    "visual trace cannot become active from " + state);
        }
        state = State.ACTIVE;
    }

    public void abort() {
        state = State.ABORTED;
    }

    public boolean ownsEarlyExit() {
        return state == State.TITLE_CARD_PRESENTATION
                || state == State.REPLAY_BOOTSTRAP;
    }

    public boolean isPresentingTitleCard() {
        return state == State.TITLE_CARD_PRESENTATION;
    }

    private enum State {
        NEW,
        TITLE_CARD_PRESENTATION,
        REPLAY_BOOTSTRAP,
        ACTIVE,
        ABORTED
    }
}
