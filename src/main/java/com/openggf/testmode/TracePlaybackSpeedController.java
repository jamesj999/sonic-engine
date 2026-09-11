package com.openggf.testmode;

import com.openggf.control.InputHandler;

/**
 * Fast-forward speed ladder for visual Trace Test Mode playback.
 * <p>
 * Right steps up the ladder, Left steps down, both only while playback is
 * actually running — paused playback hands Left/Right to
 * {@link TraceCameraFocusController}'s focus cycle instead.
 * <p>
 * A rate above 1.0 is realised as extra gameplay steps pumped into the same
 * rendered outer frame, so fractional rates hand out whole steps through an
 * accumulator: 1.5x alternates one and two extra-step frames and averages out
 * over time. The audio side reads the nominal {@link #rate()} rather than the
 * per-frame step count, so the two stay in sync on average without chasing the
 * accumulator's half-frame jitter.
 */
public final class TracePlaybackSpeedController {

    private static final double[] RATES = {1.0, 1.5, 2.0, 3.0, 5.0};

    private int index;
    private double stepAccumulator;

    /** Highest number of extra steps any ladder entry can ask for in one frame. */
    public static int maxExtraStepsPerFrame() {
        return (int) Math.ceil(RATES[RATES.length - 1] - 1.0);
    }

    /**
     * Applies one frame of ladder input.
     *
     * @param blocked true while the ladder must not move — paused playback, or
     *        a held rewind that owns the frame
     */
    public void handleInput(InputHandler input, boolean blocked, int leftKey, int rightKey) {
        if (input == null || blocked) {
            return;
        }
        if (input.isKeyPressed(rightKey)) {
            index = Math.min(RATES.length - 1, index + 1);
        } else if (input.isKeyPressed(leftKey)) {
            index = Math.max(0, index - 1);
        }
    }

    public double rate() {
        return RATES[index];
    }

    public boolean isFastForwarding() {
        return index > 0;
    }

    /**
     * Whole extra gameplay steps to pump into this outer frame, carrying the
     * fractional remainder. Call exactly once per outer frame.
     */
    public int consumeExtraSteps() {
        stepAccumulator += rate() - 1.0;
        int whole = (int) Math.floor(stepAccumulator);
        stepAccumulator -= whole;
        return whole;
    }

    /** Drops back to real time, discarding any carried fraction. */
    public void reset() {
        index = 0;
        stepAccumulator = 0.0;
    }

    /** HUD label for the current rate, e.g. {@code 1.5x} or {@code 2x}. */
    public String label() {
        double rate = rate();
        return rate == Math.floor(rate)
                ? (long) rate + "x"
                : rate + "x";
    }

    /**
     * HUD label with the Left/Right affordance around it, e.g. {@code < 1x >}.
     * Both arrows are always drawn, including at the ends of the ladder, so the
     * line does not change width as the rate moves.
     */
    public String rateDisplay() {
        return "< " + label() + " >";
    }
}
