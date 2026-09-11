package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;

/** Rewind adapter for the ROM {@code f_obj56} runtime byte. */
public final class Sonic1FloatingBlockStateRewindAdapter
        implements RewindSnapshottable<Sonic1FloatingBlockState.Snapshot> {

    @Override
    public String key() {
        return "s1-floating-block-state";
    }

    @Override
    public Sonic1FloatingBlockState.Snapshot capture() {
        Sonic1FloatingBlockState state = resolve();
        return state != null ? state.capture() : null;
    }

    @Override
    public void restore(Sonic1FloatingBlockState.Snapshot snapshot) {
        Sonic1FloatingBlockState state = resolve();
        if (state != null) {
            state.restore(snapshot);
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        Sonic1FloatingBlockState state = resolve();
        if (state != null) {
            state.reset();
        }
    }

    private Sonic1FloatingBlockState resolve() {
        return GameServices.hasRuntime()
                ? GameServices.module().getGameService(Sonic1FloatingBlockState.class)
                : null;
    }
}
