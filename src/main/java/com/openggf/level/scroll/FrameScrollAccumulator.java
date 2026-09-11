package com.openggf.level.scroll;

/**
 * Rewind-safe replacement for a "{@code x += constant} every {@code update()}
 * call" auto-scroll accumulator.
 *
 * <p>Zone scroll handlers mirror ROM routines that keep a small persistent
 * accumulator advancing a fixed amount per frame (drifting clouds, sky bands,
 * star fields). Implementing that as a field incremented once per
 * {@code update()} call breaks rewind: rewind restores an earlier frame and
 * re-derives parallax by calling the handler again (both while re-simulating a
 * segment and in the post-restore recompute), so a call-count accumulator keeps
 * climbing and the layer drifts — snapping back only at keyframe boundaries.
 *
 * <p>This helper instead derives the value from the {@code frameCounter}
 * argument: {@code (frameCounter - anchorFrame) * incrementPerFrame}. The anchor
 * is captured on the first sample after {@link #reset()} (called on zone/act
 * (re)init), so the value counts frames since the accumulator was last zeroed —
 * exactly like the ROM's clear-at-init + add-every-frame — and {@code valueAt}
 * is idempotent per frame, so re-deriving any past frame reproduces its value.
 *
 * <p>An optional {@code active} gate freezes the value while a mode is inactive
 * (e.g. MGZ2 clouds during the BG-rise cutscene), holding at the last active
 * frame so gated clouds do not teleport on resume. The gate assumes a
 * <em>monotonic</em> (latching) activation — correct for freeze-once effects;
 * a gate that toggles active→inactive→active would over-count the idle span.
 */
public final class FrameScrollAccumulator {

    private final int incrementPerFrame;
    private final int framesAtFirstSample;

    private boolean anchored;
    private int anchorFrame;
    private int lastActiveFrame;

    /**
     * @param incrementPerFrame fixed per-frame increment (16.16 fixed point or raw word)
     */
    public FrameScrollAccumulator(int incrementPerFrame) {
        this(incrementPerFrame, 0);
    }

    /**
     * @param incrementPerFrame    fixed per-frame increment
     * @param framesAtFirstSample  value on the first sampled frame, expressed in
     *                             increments — 0 for read-then-increment ROM
     *                             routines, 1 for increment-then-read routines
     */
    public FrameScrollAccumulator(int incrementPerFrame, int framesAtFirstSample) {
        this.incrementPerFrame = incrementPerFrame;
        this.framesAtFirstSample = framesAtFirstSample;
    }

    /** Re-arm the anchor at the next sample. Call on zone/act (re)initialization. */
    public void reset() {
        anchored = false;
    }

    /** Ungated value at {@code frameCounter}. */
    public int valueAt(int frameCounter) {
        return valueAt(frameCounter, true);
    }

    /**
     * Value at {@code frameCounter}. When {@code active}, the value advances with
     * the frame counter; when inactive it holds at the last active frame.
     */
    public int valueAt(int frameCounter, boolean active) {
        if (!anchored) {
            anchorFrame = frameCounter - framesAtFirstSample;
            lastActiveFrame = anchorFrame;
            anchored = true;
        }
        if (active) {
            lastActiveFrame = frameCounter;
        }
        // int multiply wraps mod 2^32 identically to repeated addition, so the
        // 16.16 sub-pixel accumulation matches the original += behaviour exactly.
        return (lastActiveFrame - anchorFrame) * incrementPerFrame;
    }
}
