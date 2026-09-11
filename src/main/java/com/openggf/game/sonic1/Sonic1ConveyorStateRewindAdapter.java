package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Rewind-registry {@link RewindSnapshottable} adapter for the per-module
 * {@link Sonic1ConveyorState} (ROM: {@code f_conveyrev} / {@code v_obj63}).
 *
 * <p>{@code Sonic1LZConveyorObjectInstance} spawner/platform instances
 * communicate through this shared state rather than direct object
 * references (the spawner's dedup bit and the global reversal flag). Without
 * rewind coverage the state desyncs against the rewound object instance
 * fields on a backward seek -- exactly the failure mode documented for
 * {@link com.openggf.game.sonic2.ButtonVineTriggerStaticAdapter} and
 * {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}: a
 * rewind-recreated spawner re-reads the still-set {@code v_obj63} dedup bit
 * in its own update and self-deletes without ever recreating its child
 * platforms, permanently losing the belt after a rewind past its spawn
 * point.
 *
 * <p>Registered via {@link com.openggf.game.sonic1.events.Sonic1LevelEventManager#extraRewindAdapters()}.
 */
public final class Sonic1ConveyorStateRewindAdapter
        implements RewindSnapshottable<Sonic1ConveyorState.Snapshot> {

    @Override
    public String key() {
        return "s1-conveyor-state";
    }

    @Override
    public Sonic1ConveyorState.Snapshot capture() {
        Sonic1ConveyorState state = resolve();
        return state != null ? state.captureRewindState() : null;
    }

    @Override
    public void restore(Sonic1ConveyorState.Snapshot snapshot) {
        Sonic1ConveyorState state = resolve();
        if (state != null) {
            state.restoreRewindState(snapshot);
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        Sonic1ConveyorState state = resolve();
        if (state != null) {
            state.reset();
        }
    }

    private Sonic1ConveyorState resolve() {
        return GameServices.hasRuntime()
                ? GameServices.module().getGameService(Sonic1ConveyorState.class)
                : null;
    }
}
