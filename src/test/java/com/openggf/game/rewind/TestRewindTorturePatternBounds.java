package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level sanity check that {@link RewindTorturePattern} implementations
 * never propose cycles that would push {@code currentFrame} outside
 * {@code [0, frameCount-1]}. This catches off-by-one bounds errors in pattern
 * generation independent of the full integration test.
 */
class TestRewindTorturePatternBounds {

    @Test
    void fixedAdjacentNeverOverruns() {
        assertWithinBounds("fixed-adjacent",
                new RewindTorturePattern.FixedAdjacent(), 5852);
    }

    @Test
    void progressiveLongRewindNeverOverruns() {
        assertWithinBounds("progressive-long",
                new RewindTorturePattern.ProgressiveLongRewind(), 5852);
    }

    @Test
    void randomSeed42NeverOverruns() {
        assertWithinBounds("random-42",
                new RewindTorturePattern.Random_(42L), 5852);
    }

    @Test
    void randomSeed1337NeverOverruns() {
        assertWithinBounds("random-1337",
                new RewindTorturePattern.Random_(1337L), 5852);
    }

    @Test
    void randomSeed8675309NeverOverruns() {
        assertWithinBounds("random-8675309",
                new RewindTorturePattern.Random_(8675309L), 5852);
    }

    private static void assertWithinBounds(String name,
                                             RewindTorturePattern pattern,
                                             int frameCount) {
        int currentFrame = 0;
        int iteration = 0;
        while (true) {
            RewindTorturePattern.Cycle cycle = pattern.next(currentFrame, frameCount);
            if (cycle == null) break;
            int afterForward = currentFrame + cycle.forwardFrames();
            assertTrue(afterForward <= frameCount - 1,
                    "[" + name + "] iteration " + iteration
                            + ": after-forward overrun: " + currentFrame + " + "
                            + cycle.forwardFrames() + " = " + afterForward
                            + " > " + (frameCount - 1));
            int afterRewind = afterForward - cycle.rewindFrames();
            assertTrue(afterRewind >= 0,
                    "[" + name + "] iteration " + iteration
                            + ": after-rewind underrun: " + afterForward + " - "
                            + cycle.rewindFrames() + " = " + afterRewind);
            assertTrue(cycle.rewindFrames() < cycle.forwardFrames(),
                    "[" + name + "] iteration " + iteration
                            + ": rewindFrames must be < forwardFrames; got rewind="
                            + cycle.rewindFrames() + " forward="
                            + cycle.forwardFrames());
            currentFrame = afterRewind;
            iteration++;
        }
        System.out.printf("[bounds] %s: %d iterations, final frame=%d%n",
                name, iteration, currentFrame);
    }
}
