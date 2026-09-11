package com.openggf.game.render;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.AdvancedRenderModeSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned registry and resolver for advanced render modes.
 *
 * <p>The controller collects active contributors for the current zone/runtime
 * and resolves them into one {@link AdvancedRenderFrameState} per frame.
 */
public final class AdvancedRenderModeController
        implements RewindSnapshottable<AdvancedRenderModeSnapshot> {

    private final List<AdvancedRenderMode> modes = new ArrayList<>();
    private final AdvancedRenderFrameState.Builder frameBuilder = AdvancedRenderFrameState.builder();
    private final AdvancedRenderFrameState[] frameStates = {
            new AdvancedRenderFrameState(false, false, null),
            new AdvancedRenderFrameState(false, false, null)
    };
    private int nextFrameState;

    /** Registers one render-mode contributor for subsequent frame resolution. */
    public void register(AdvancedRenderMode mode) {
        modes.add(Objects.requireNonNull(mode, "mode"));
    }

    /** Removes all registered contributors. */
    public void clear() {
        modes.clear();
    }

    /** Returns {@code true} when no contributors are registered. */
    public boolean isEmpty() {
        return modes.isEmpty();
    }

    /** Returns the number of registered contributors. */
    public int size() {
        return modes.size();
    }

    /**
     * Resolves the current frame's aggregate render-mode state.
     * Contributors are registered during level/setup; callers must not mutate
     * this controller while resolution is in progress.
     */
    public AdvancedRenderFrameState resolve(AdvancedRenderModeContext context) {
        if (context == null || modes.isEmpty()) {
            return AdvancedRenderFrameState.disabled();
        }
        frameBuilder.reset();
        for (AdvancedRenderMode mode : modes) {
            mode.contribute(context, frameBuilder);
        }
        AdvancedRenderFrameState resolved = frameBuilder.buildInto(frameStates[nextFrameState]);
        if (resolved != AdvancedRenderFrameState.disabled()) {
            nextFrameState ^= 1;
        }
        return resolved;
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "advanced-render-mode";
    }

    @Override
    public AdvancedRenderModeSnapshot capture() {
        if (modes.isEmpty()) {
            return AdvancedRenderModeSnapshot.empty();
        }
        return new AdvancedRenderModeSnapshot(modes, captureModeStates());
    }

    @Override
    public void restore(AdvancedRenderModeSnapshot s) {
        Objects.requireNonNull(s, "s");
        modes.clear();
        modes.addAll(s.modes());
        restoreModeStates(s);
    }

    private List<AdvancedRenderModeSnapshot.ModeState> captureModeStates() {
        List<AdvancedRenderModeSnapshot.ModeState> states = null;
        for (int i = 0; i < modes.size(); i++) {
            AdvancedRenderMode mode = modes.get(i);
            if (mode instanceof RewindSnapshottable<?> snapshottable) {
                if (states == null) {
                    states = new ArrayList<>();
                }
                states.add(new AdvancedRenderModeSnapshot.ModeState(
                        i,
                        snapshottable.key(),
                        Objects.requireNonNull(snapshottable.capture(),
                                "Advanced render mode snapshot must not be null for key: "
                                        + snapshottable.key())));
            }
        }
        return states != null ? states : List.of();
    }

    private void restoreModeStates(AdvancedRenderModeSnapshot snapshot) {
        for (AdvancedRenderModeSnapshot.ModeState state : snapshot.modeStates()) {
            if (state.index() < 0 || state.index() >= modes.size()) {
                throw new IllegalStateException("Missing advanced render mode for snapshot key: " + state.key());
            }
            AdvancedRenderMode mode = modes.get(state.index());
            if (!(mode instanceof RewindSnapshottable<?> snapshottable)
                    || !snapshottable.key().equals(state.key())) {
                throw new IllegalStateException("Cannot restore advanced render mode snapshot for key: "
                        + state.key());
            }
            restoreRaw(snapshottable, state.snapshot());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreRaw(RewindSnapshottable snapshottable, Object snapshot) {
        snapshottable.restore(snapshot);
    }
}
