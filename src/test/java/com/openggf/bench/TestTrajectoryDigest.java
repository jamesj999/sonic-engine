package com.openggf.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TestTrajectoryDigest {

    @Test
    void identicalTrajectoriesProduceIdenticalDigests() {
        assertEquals(digestOf(0), digestOf(0));
    }

    @Test
    void aSinglePixelOfDivergenceChangesTheDigest() {
        // The whole point: a runtime that drifts by one pixel at one frame
        // simulated different work, and the timings must not be compared.
        assertNotEquals(digestOf(0), digestOf(1));
    }

    @Test
    void frameOrderMatters() {
        TrajectoryDigest forward = new TrajectoryDigest();
        forward.observe(1, 100, 200, 3, 50, 60);
        forward.observe(2, 110, 200, 3, 55, 60);

        TrajectoryDigest reversed = new TrajectoryDigest();
        reversed.observe(2, 110, 200, 3, 55, 60);
        reversed.observe(1, 100, 200, 3, 50, 60);

        assertNotEquals(forward.hex(), reversed.hex());
    }

    @Test
    void ringCountIsPartOfTheDigest() {
        TrajectoryDigest withRings = new TrajectoryDigest();
        withRings.observe(1, 100, 200, 97, 50, 60);

        TrajectoryDigest withoutRings = new TrajectoryDigest();
        withoutRings.observe(1, 100, 200, 19, 50, 60);

        assertNotEquals(withRings.hex(), withoutRings.hex());
    }

    @Test
    void negativeCoordinatesAreMixedInFully() {
        TrajectoryDigest negative = new TrajectoryDigest();
        negative.observe(1, -1, 0, 0, 0, 0);

        TrajectoryDigest positive = new TrajectoryDigest();
        positive.observe(1, 1, 0, 0, 0, 0);

        assertNotEquals(negative.hex(), positive.hex());
    }

    @Test
    void digestIsFixedWidthHexAndCountsObservations() {
        TrajectoryDigest digest = new TrajectoryDigest();
        for (int frame = 0; frame < 5; frame++) {
            digest.observe(frame, frame, frame, 0, frame, 0);
        }

        assertEquals(5, digest.observations());
        assertEquals(16, digest.hex().length());
        assertEquals(digest.hex().toLowerCase(java.util.Locale.ROOT), digest.hex());
    }

    private static String digestOf(int xOffsetAtFrame3) {
        TrajectoryDigest digest = new TrajectoryDigest();
        for (int frame = 0; frame < 10; frame++) {
            int x = frame * 16 + (frame == 3 ? xOffsetAtFrame3 : 0);
            digest.observe(frame, x, 400, frame / 2, frame * 8, 100);
        }
        return digest.hex();
    }
}
