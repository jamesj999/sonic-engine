package com.openggf.game.rewind.snapshot;

import com.openggf.game.OscillationManager;
import com.openggf.game.OscillationSnapshot;
import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state RewindSnapshottable adapter for OscillationManager.
 * Wraps the static snapshot/restore methods to conform to the
 * RewindSnapshottable interface for use in rewind registries.
 */
public final class OscillationStaticAdapter
        implements RewindSnapshottable<OscillationSnapshot> {

    @Override
    public String key() {
        return "oscillation";
    }

    @Override
    public OscillationSnapshot capture() {
        return OscillationManager.snapshot();
    }

    @Override
    public void restore(OscillationSnapshot snapshot) {
        OscillationManager.restore(snapshot);
    }
}
