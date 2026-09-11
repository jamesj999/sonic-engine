package com.openggf.game.rewind;

import java.util.Random;

/**
 * Generates the next (forwardFrames, rewindFrames) pair for a rewind torture run.
 * Returns null when the pattern has no more cycles to produce.
 *
 * <p>Each {@link Cycle} enforces the invariant {@code rewindFrames < forwardFrames}
 * so a torture driver never rewinds past the start of the cycle's forward run.
 */
interface RewindTorturePattern {

    Cycle next(int currentFrame, int frameCount);

    record Cycle(int forwardFrames, int rewindFrames) {
        public Cycle {
            if (forwardFrames < 1) {
                throw new IllegalArgumentException("forwardFrames must be >= 1, got " + forwardFrames);
            }
            if (rewindFrames < 0 || rewindFrames >= forwardFrames) {
                throw new IllegalArgumentException(
                        "rewindFrames must be in [0, forwardFrames-1], got " + rewindFrames
                                + " for forwardFrames=" + forwardFrames);
            }
        }
    }

    /** Pattern (a): every 2 frames forward, rewind 1. Net +1/cycle. */
    final class FixedAdjacent implements RewindTorturePattern {
        @Override
        public Cycle next(int currentFrame, int frameCount) {
            // Need room to step 2 forward.
            if (currentFrame + 2 >= frameCount) return null;
            return new Cycle(2, 1);
        }
    }

    /**
     * Pattern (b): forward to end-of-trace, rewind to landing frame N. After each
     * rewind, increment N until N reaches {@code frameCount - 100}.
     */
    final class ProgressiveLongRewind implements RewindTorturePattern {
        private int landingFrame = 1;

        @Override
        public Cycle next(int currentFrame, int frameCount) {
            if (landingFrame >= frameCount - 100) return null;
            int forwardFrames = (frameCount - 1) - currentFrame;
            if (forwardFrames < 1) return null;
            int rewindFrames = (currentFrame + forwardFrames) - landingFrame;
            if (rewindFrames < 1 || rewindFrames >= forwardFrames) return null;
            landingFrame++;
            return new Cycle(forwardFrames, rewindFrames);
        }
    }

    /**
     * Pattern (c): random forwardFrames in [2, min(remaining, 600)], random
     * rewindFrames in [1, forwardFrames-1]. Seeded for reproducibility — the
     * 600-frame cap keeps individual cycles bounded so we don't accidentally
     * generate one giant cycle that consumes the whole trace.
     */
    final class Random_ implements RewindTorturePattern {
        private final Random rng;

        Random_(long seed) { this.rng = new Random(seed); }

        @Override
        public Cycle next(int currentFrame, int frameCount) {
            int remaining = frameCount - 1 - currentFrame;
            if (remaining < 2) return null;
            int forwardCap = Math.min(remaining, 600);
            // forwardFrames in [2, forwardCap]
            int forwardFrames = 2 + rng.nextInt(forwardCap - 1);
            // rewindFrames in [1, forwardFrames-1]
            int rewindFrames = 1 + rng.nextInt(forwardFrames - 1);
            return new Cycle(forwardFrames, rewindFrames);
        }
    }
}
