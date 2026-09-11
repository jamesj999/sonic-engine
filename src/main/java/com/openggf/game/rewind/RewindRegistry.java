package com.openggf.game.rewind;

import com.openggf.debug.SectionProfiler;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the list of {@link RewindSnapshottable} subsystems for the
 * current gameplay session. Owned by {@code GameplayModeContext}.
 *
 * <p>Capture and restore are atomic per frame: no subsystem is mid-step
 * during these operations, so registration order does not affect
 * correctness. Order is preserved for predictable diffing during
 * debugging.
 *
 * <p>Restore is tolerant of unknown keys (a subsystem that was
 * registered when a snapshot was captured may have been deregistered
 * since); such entries are skipped. The reverse — registered subsystems
 * with no entry in the snapshot — explicitly resets them, failing closed when
 * a subsystem does not provide a missing-snapshot reset implementation.
 */
public final class RewindRegistry {
    private static final String GAME_RNG_KEY = "gamerng";

    private final Map<String, RewindSnapshottable<?>> entries = new LinkedHashMap<>();
    private final Map<String, Runnable> postRestoreCallbacks = new LinkedHashMap<>();
    private final SectionProfiler profiler;
    private long layoutVersion;
    private LayoutState layoutState;

    public RewindRegistry() {
        this.profiler = null;
    }

    public RewindRegistry(SectionProfiler profiler) {
        this.profiler = profiler;
    }

    public void register(RewindSnapshottable<?> s) {
        Objects.requireNonNull(s, "s");
        String key = s.key();
        if (entries.putIfAbsent(key, s) != null) {
            throw new IllegalStateException(
                    "RewindSnapshottable already registered: " + key);
        }
        invalidateLayout();
    }

    public void deregister(String key) {
        if (entries.remove(key) != null) {
            invalidateLayout();
        }
    }

    public void registerPostRestoreCallback(String key, Runnable callback) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(callback, "callback");
        if (postRestoreCallbacks.putIfAbsent(key, callback) != null) {
            throw new IllegalStateException(
                    "Post-restore callback already registered: " + key);
        }
    }

    public void deregisterPostRestoreCallback(String key) {
        postRestoreCallbacks.remove(key);
    }

    public CompositeSnapshot capture() {
        if (profiler != null) {
            profiler.beginSection("rewind.capture");
        }
        try {
            LayoutState state = layoutState();
            Object[] values = new Object[state.adapters.length];
            for (int i = 0; i < state.adapters.length; i++) {
                Object value = state.adapters[i].capture();
                if (value == null) {
                    throw new NullPointerException(
                            "Rewind snapshot must not be null for key: "
                                    + state.layout.keyAt(i)
                                    + " (" + state.adapters[i].getClass().getName() + ")");
                }
                values[i] = value;
            }
            return CompositeSnapshot.owned(state.layout, values);
        } finally {
            if (profiler != null) {
                profiler.endSection("rewind.capture");
            }
        }
    }

    public void restore(CompositeSnapshot cs) {
        Objects.requireNonNull(cs, "cs");
        if (profiler != null) {
            profiler.beginSection("rewind.restore");
        }
        try {
            LayoutState state = layoutState();
            if (cs.layout() == state.layout) {
                restoreSameLayout(state, cs);
            } else {
                restoreCrossLayout(state, cs);
            }
            for (Runnable callback : postRestoreCallbacks.values()) {
                callback.run();
            }
        } finally {
            if (profiler != null) {
                profiler.endSection("rewind.restore");
            }
        }
    }

    private static boolean restoresAfterReconstruction(String key) {
        // Object restore may recreate objects whose constructors consume the shared
        // ROM RNG before their captured state blobs are applied. Restore the RNG
        // cursor after those reconstruction side effects so the snapshot's seed is
        // the final post-restore seed.
        return GAME_RNG_KEY.equals(key);
    }

    private void invalidateLayout() {
        layoutVersion++;
        layoutState = null;
    }

    private LayoutState layoutState() {
        LayoutState state = layoutState;
        if (state != null) {
            return state;
        }
        ArrayList<String> keys = new ArrayList<>(entries.size());
        RewindSnapshottable<?>[] adapters = new RewindSnapshottable<?>[entries.size()];
        int index = 0;
        int gameRngIndex = -1;
        for (var entry : entries.entrySet()) {
            String key = entry.getKey();
            keys.add(key);
            adapters[index] = entry.getValue();
            if (restoresAfterReconstruction(key)) {
                gameRngIndex = index;
            }
            index++;
        }
        state = new LayoutState(
                CompositeSnapshotLayout.fromKeys(layoutVersion, keys),
                adapters,
                gameRngIndex);
        layoutState = state;
        return state;
    }

    private static void restoreSameLayout(LayoutState state, CompositeSnapshot snapshot) {
        for (int i = 0; i < state.adapters.length; i++) {
            if (i != state.gameRngIndex) {
                restoreEntry(state.adapters[i], snapshot.valueAt(i));
            }
        }
        if (state.gameRngIndex >= 0) {
            restoreEntry(state.adapters[state.gameRngIndex],
                    snapshot.valueAt(state.gameRngIndex));
        }
    }

    private static void restoreCrossLayout(LayoutState state, CompositeSnapshot snapshot) {
        for (int i = 0; i < state.adapters.length; i++) {
            if (i != state.gameRngIndex) {
                restoreCrossLayoutEntry(state, snapshot, i);
            }
        }
        if (state.gameRngIndex >= 0) {
            restoreCrossLayoutEntry(state, snapshot, state.gameRngIndex);
        }
    }

    private static void restoreCrossLayoutEntry(
            LayoutState state, CompositeSnapshot snapshot, int adapterIndex) {
        int snapshotIndex = snapshot.indexOf(state.layout.keyAt(adapterIndex));
        RewindSnapshottable<?> adapter = state.adapters[adapterIndex];
        if (snapshotIndex < 0) {
            adapter.resetForMissingSnapshot();
            return;
        }
        restoreEntry(adapter, snapshot.valueAt(snapshotIndex));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreEntry(RewindSnapshottable<?> entry, Object snapshot) {
        RewindSnapshottable raw = entry;
        raw.restore(snapshot);
    }

    private record LayoutState(
            CompositeSnapshotLayout layout,
            RewindSnapshottable<?>[] adapters,
            int gameRngIndex) {
    }
}
