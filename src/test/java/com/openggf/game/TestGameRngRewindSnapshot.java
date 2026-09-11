package com.openggf.game;

import com.openggf.game.rewind.snapshot.GameRngSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGameRngRewindSnapshot {

    @Test
    void testGameRngSnapshotRoundTrip() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 12345L);

        // Generate some random values to advance state
        rng.nextWord();
        rng.nextWord();

        // Capture snapshot
        GameRngSnapshot snapshot = rng.capture();
        long capturedSeed = rng.getSeed();

        // Mutate state by generating more random values
        rng.nextWord();
        rng.nextWord();
        rng.nextWord();

        // Seed should have changed
        assertNotEquals(capturedSeed, rng.getSeed());

        // Restore from snapshot
        rng.restore(snapshot);

        // Verify seed matches captured value
        assertEquals(capturedSeed, rng.getSeed());
    }

    @Test
    void testGameRngSnapshotKey() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 0);
        assertEquals("gamerng", rng.key());
    }

    @Test
    void testGameRngS3kFlavour() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0x12345678L);
        GameRngSnapshot snapshot = rng.capture();

        assertEquals(0x12345678L, snapshot.seed());
        assertEquals(GameRng.Flavour.S3K, snapshot.flavour());

        rng.setSeed(0xDEADBEEFL);
        rng.restore(snapshot);

        assertEquals(0x12345678L, rng.getSeed());
    }

    @Test
    void testGameRngDeterministicAfterRestore() {
        GameRng rng1 = new GameRng(GameRng.Flavour.S1_S2, 42L);
        GameRng rng2 = new GameRng(GameRng.Flavour.S1_S2, 42L);

        // Generate some values in rng1
        rng1.nextWord();
        rng1.nextWord();

        // Capture rng1 state
        GameRngSnapshot snapshot = rng1.capture();

        // Advance rng2 differently
        rng2.nextWord();

        // Restore rng2 to rng1's state
        rng2.restore(snapshot);

        // Both should now produce the same sequence
        for (int i = 0; i < 10; i++) {
            assertEquals(rng1.nextWord(), rng2.nextWord(),
                    "RNG sequences should match after restore at iteration " + i);
        }
    }
}
