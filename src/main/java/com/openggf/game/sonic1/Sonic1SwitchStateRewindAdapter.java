package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;

/** Rewind adapter for the Sonic 1 ROM {@code f_switch} byte array. */
public final class Sonic1SwitchStateRewindAdapter
        implements RewindSnapshottable<Sonic1SwitchManager.Snapshot> {
    public static final String KEY = "s1-switch-state";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Sonic1SwitchManager.Snapshot capture() {
        Sonic1SwitchManager state = resolve();
        return state != null ? state.captureRewindState() : new Sonic1SwitchManager.Snapshot(null);
    }

    @Override
    public void restore(Sonic1SwitchManager.Snapshot snapshot) {
        Sonic1SwitchManager state = resolve();
        if (state != null) {
            state.restoreRewindState(snapshot);
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        Sonic1SwitchManager state = resolve();
        if (state != null) {
            state.reset();
        }
    }

    private Sonic1SwitchManager resolve() {
        return GameServices.hasRuntime()
                ? GameServices.module().getGameService(Sonic1SwitchManager.class)
                : null;
    }
}
