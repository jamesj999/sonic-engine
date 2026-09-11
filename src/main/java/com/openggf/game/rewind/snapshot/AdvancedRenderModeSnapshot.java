package com.openggf.game.rewind.snapshot;

import com.openggf.game.render.AdvancedRenderMode;

import java.util.List;
import java.util.Objects;

/**
 * Snapshot of {@link com.openggf.game.render.AdvancedRenderModeController}
 * registered-contributor list.
 *
 * <p>Contributor object references are captured by identity. Contributors are
 * stateless unless they implement {@link com.openggf.game.rewind.RewindSnapshottable};
 * stateful contributor snapshots are captured alongside the identity list and
 * restored into the same registered contributor instance.
 */
public record AdvancedRenderModeSnapshot(List<AdvancedRenderMode> modes, List<ModeState> modeStates) {
    private static final AdvancedRenderModeSnapshot EMPTY =
            new AdvancedRenderModeSnapshot(List.of(), List.of());

    public record ModeState(int index, String key, Object snapshot) {
    }

    /** Returns the shared immutable snapshot for a controller with no modes. */
    public static AdvancedRenderModeSnapshot empty() {
        return EMPTY;
    }

    public AdvancedRenderModeSnapshot(List<AdvancedRenderMode> modes) {
        this(modes, List.of());
    }

    public AdvancedRenderModeSnapshot {
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(modeStates, "modeStates");
        modes = modes.isEmpty() ? List.of() : List.copyOf(modes);
        modeStates = modeStates.isEmpty() ? List.of() : List.copyOf(modeStates);
    }
}
