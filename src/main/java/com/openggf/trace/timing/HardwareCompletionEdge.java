package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;

/** A recorded hardware-readiness edge. This is scheduling input, not game state. */
public record HardwareCompletionEdge(
        int rawFrame,
        HardwareServiceBoundary boundary,
        HardwareWorkKind kind,
        long ordinal,
        String submissionFingerprint) {
}
