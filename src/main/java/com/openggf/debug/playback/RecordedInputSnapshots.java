package com.openggf.debug.playback;

import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;

import java.util.Objects;

public final class RecordedInputSnapshots {

    private RecordedInputSnapshots() {
    }

    public static LogicalInputSnapshot fromBk2(Bk2FrameInput current, Bk2FrameInput previous) {
        return fromBk2(current, previous, 0);
    }

    public static LogicalInputSnapshot fromBk2(
            Bk2FrameInput current,
            Bk2FrameInput previous,
            int pendingP1ActionPressMask) {
        Objects.requireNonNull(current, "current");
        Bk2FrameInput previousFrame = previous != null
                ? previous
                : new Bk2FrameInput(current.frameIndex() - 1, 0, 0, false, 0, 0, false, "neutral");
        PlayerInputState p1 = PlayerInputState.of(
                current.p1InputMask(),
                current.p1InputMask() & ~previousFrame.p1InputMask(),
                current.p1ActionMask(),
                (current.p1ActionMask() & ~previousFrame.p1ActionMask())
                        | pendingP1ActionPressMask,
                current.p1StartPressed(),
                current.p1StartPressed() && !previousFrame.p1StartPressed());
        PlayerInputState p2 = PlayerInputState.of(
                current.p2InputMask(),
                current.p2InputMask() & ~previousFrame.p2InputMask(),
                current.p2ActionMask(),
                current.p2ActionMask() & ~previousFrame.p2ActionMask(),
                current.p2StartPressed(),
                current.p2StartPressed() && !previousFrame.p2StartPressed());
        return LogicalInputSnapshot.ofPlayers(p1, p2).withDebugInput(
                current.debugModeTogglePressed(),
                current.debugShiftDown(),
                current.debugControlDown(),
                current.debugAltDown(),
                current.debugSuperDown());
    }
}
