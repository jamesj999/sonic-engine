package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;

import java.util.Objects;

/**
 * Reads inputs frame-by-frame from a {@link TraceData}.
 *
 * <p>Converts each TraceFrame's {@code input} field (P1 input mask) into a
 * Bk2FrameInput record. The trace's input field is treated as the P1 input
 * mask; P2 fields default to zero/false (no sidekick input recorded).
 */
public final class TraceInputSource implements InputSource {

    private final TraceData trace;

    public TraceInputSource(TraceData trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public int frameCount() {
        return trace.frameCount();
    }

    @Override
    public Bk2FrameInput read(int frame) {
        TraceFrame tf = trace.getFrame(frame);
        // TraceFrame.input is the P1 input mask. Convert to Bk2FrameInput with
        // P1 mask only (no P2 input recorded in trace).
        // Action masks and start button default to zero/false (not recorded in physics.csv).
        return new Bk2FrameInput(
                frame,
                tf.input(),
                0,           // p1ActionMask: not recorded in trace CSV
                false,       // p1StartPressed: not recorded in trace CSV
                "trace:" + frame);
    }
}
