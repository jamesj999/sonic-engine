package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;

import java.util.Objects;

/**
 * Stateless bridge between production service boundaries and the replay port.
 * The rewind-owned raw-frame latch remains inside the port.
 */
public final class TraceHardwareTimingBoundaryObserver
        implements HardwareTimingBoundaryObserver {
    private final HardwareTimingReplayPort replayPort;

    public TraceHardwareTimingBoundaryObserver(
            HardwareTimingReplayPort replayPort) {
        this.replayPort = Objects.requireNonNull(replayPort, "replayPort");
    }

    public void beginRawFrame(int rawFrame) {
        replayPort.beginRawFrame(rawFrame);
    }

    public void enterUnrepresentedGap() {
        replayPort.enterUnrepresentedGap();
    }

    /** Applies only a compiled completion on the latched suppressed row. */
    public boolean applySuppressedRowCompletion() {
        return replayPort.applySuppressedRowCompletion();
    }

    @Override
    public void onBoundary(HardwareServiceBoundary boundary) {
        replayPort.apply(boundary);
    }
}
