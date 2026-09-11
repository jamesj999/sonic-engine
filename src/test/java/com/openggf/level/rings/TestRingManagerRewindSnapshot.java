package com.openggf.level.rings;

import com.openggf.game.session.EngineServices;
import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link RingManager}'s {@link com.openggf.game.rewind.RewindSnapshottable}
 * implementation (Track D).
 *
 * <p>Tests cover the ring-collection BitSet, sparkle timers, lost-ring pool state,
 * and attracted-ring slots without requiring a full level load.
 */
class TestRingManagerRewindSnapshot {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    /** Build a minimal RingManager with N rings; no renderer, no audio. */
    private static RingManager buildManager(int ringCount) {
        List<RingSpawn> spawns = new java.util.ArrayList<>();
        for (int i = 0; i < ringCount; i++) {
            spawns.add(new RingSpawn(i * 16, 256));
        }
        return new RingManager(spawns, null, null, null, null);
    }

    @Test
    void keyIsRings() {
        assertEquals("rings", buildManager(0).key());
    }

    @Test
    void roundTripCollectedBitSet() {
        RingManager mgr = buildManager(8);
        RingSnapshot base = mgr.capture();

        // Craft a snapshot with bits 0, 2, 5 set
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(0); bits.set(2); bits.set(5);
        RingSnapshot modified = new RingSnapshot(
                bits,
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                base.attractedRings());

        mgr.restore(modified);
        RingSnapshot after = mgr.capture();

        assertEquals(bits, after.collected(),
                "Collected BitSet must survive a round-trip");
    }

    @Test
    void captureStoresUncollectedRingsAsEmptyWordArray() {
        RingManager mgr = buildManager(8);

        RingSnapshot snapshot = mgr.capture();

        assertEquals(0, snapshot.collectedWords().length);
    }

    @Test
    void roundTripSparkleTimers() {
        RingManager mgr = buildManager(4);
        RingSnapshot beforeAny = mgr.capture();

        // Build a sparse snapshot with the modified sparkle timer.
        RingSnapshot modified = new RingSnapshot(
                beforeAny.collected(),
                new RingSnapshot.SparkleEntry[] {
                        new RingSnapshot.SparkleEntry(1, 42)
                },
                beforeAny.placementCursorIndex(),
                beforeAny.placementLastCameraX(),
                beforeAny.lostRingActiveCount(),
                beforeAny.spillAnimCounter(),
                beforeAny.spillAnimAccum(),
                beforeAny.spillAnimFrame(),
                beforeAny.lostRingFrameCounter(),
                beforeAny.lostRings(),
                beforeAny.attractedRings());
        mgr.restore(modified);

        RingSnapshot after = mgr.capture();
        assertEquals(1, after.sparkleTimers().length);
        assertEquals(1, after.sparkleTimers()[0].ringIndex());
        assertEquals(42, after.sparkleTimers()[0].startFrame(),
                "Sparkle timer at index 1 must survive restore");
    }

    @Test
    void captureOmitsInactiveSparkleTimers() {
        RingManager mgr = buildManager(4);

        RingSnapshot snapshot = mgr.capture();

        assertEquals(0, snapshot.sparkleTimers().length);
    }

    @Test
    void restoreRebuildsActiveRingWindow() {
        RingManager mgr = buildManager(80);

        mgr.update(0, null, 0);
        RingSnapshot earlyWindow = mgr.capture();

        mgr.update(2048, null, 0);
        RingSnapshot laterWindow = mgr.capture();
        assertNotEquals(java.util.Arrays.toString(earlyWindow.activeSpawnIndices()),
                java.util.Arrays.toString(laterWindow.activeSpawnIndices()),
                "test setup must move the active ring window");

        mgr.restore(earlyWindow);
        RingSnapshot afterRestore = mgr.capture();

        assertArrayEquals(earlyWindow.activeSpawnIndices(), afterRestore.activeSpawnIndices(),
                "Active ring spawns must be restored with the placement cursor");
    }

    @Test
    void restoreSparseSparkleTimersClearsPreviousTimers() {
        RingManager mgr = buildManager(4);
        RingSnapshot base = mgr.capture();

        RingSnapshot withSparkle = new RingSnapshot(
                base.collected(),
                new RingSnapshot.SparkleEntry[] {
                        new RingSnapshot.SparkleEntry(1, 42)
                },
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                base.attractedRings());

        mgr.restore(withSparkle);

        RingSnapshot withoutSparkles = new RingSnapshot(
                base.collected(),
                new RingSnapshot.SparkleEntry[0],
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                base.attractedRings());

        mgr.restore(withoutSparkles);
        RingSnapshot after = mgr.capture();

        assertEquals(0, after.sparkleTimers().length);
    }

    @Test
    void roundTripSharedSpillAnimation() {
        // Per-ring lost-ring state is no longer snapshotted (physics moved to the
        // object exec loop; per-ring round-trip is covered by TestLostRingRewindGenericRestore).
        // Only the small GLOBAL spin (Ring_spill_anim_counter/accum/frame) survives here.
        RingManager mgr = buildManager(2);
        RingSnapshot base = mgr.capture();

        RingSnapshot modified = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                0xAB,   // spillAnimCounter
                0xCD,   // spillAnimAccum
                3,      // spillAnimFrame (0-3)
                base.lostRingFrameCounter(),
                base.lostRings(),
                base.attractedRings());

        mgr.restore(modified);
        RingSnapshot after = mgr.capture();

        assertEquals(0xAB, after.spillAnimCounter());
        assertEquals(0xCD, after.spillAnimAccum());
        assertEquals(3,    after.spillAnimFrame());
        // Per-ring pool fields are retired: capture always reports an empty pool.
        assertEquals(0, after.lostRingActiveCount());
        assertEquals(0, after.lostRings().length);
        assertEquals(0, after.lostRingFrameCounter());
    }

    @Test
    void captureOmitsInactiveLostRingSlots() {
        RingManager mgr = buildManager(0);

        RingSnapshot snapshot = mgr.capture();

        assertEquals(0, snapshot.lostRings().length);
    }

    @Test
    void restoreIgnoresLegacyPerRingEntriesButKeepsSharedSpin() {
        // The legacy per-ring pool restore is retired: a snapshot carrying per-ring
        // LostRingEntry rows must NOT repopulate any pool (the spilled rings are now
        // dynamic objects restored via LostRingObjectInstance generic recreate), while the shared spin
        // still round-trips.
        RingManager mgr = buildManager(0);
        RingSnapshot base = mgr.capture();

        RingSnapshot withStaleEntries = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                4,        // stale lostRingActiveCount — must be ignored
                0x11,     // spillAnimCounter
                0x22,     // spillAnimAccum
                2,        // spillAnimFrame
                99,       // stale lostRingFrameCounter — must be ignored
                new RingSnapshot.LostRingEntry[] {
                        new RingSnapshot.LostRingEntry(
                                true, 0x1234_00, 0x0800_00, 0x300, -0x200,
                                120, false, -1, 0, 5, 3)
                },
                base.attractedRings());

        mgr.restore(withStaleEntries);
        RingSnapshot after = mgr.capture();

        // Per-ring entries are dropped; only the shared spin survives.
        assertEquals(0, after.lostRings().length);
        assertEquals(0, after.lostRingActiveCount());
        assertEquals(0, after.lostRingFrameCounter());
        assertEquals(0x11, after.spillAnimCounter());
        assertEquals(0x22, after.spillAnimAccum());
        assertEquals(2,    after.spillAnimFrame());
    }

    @Test
    void roundTripAttractedRingSlot() {
        RingManager mgr = buildManager(0);
        RingSnapshot base = mgr.capture();

        RingSnapshot.AttractedRingEntry[] atRings = {
                new RingSnapshot.AttractedRingEntry(
                        true, 3, 0x200, 0x180, 0x80, 0x40, 0x100, -0x50, 7)
        };

        RingSnapshot modified = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                atRings);

        mgr.restore(modified);
        RingSnapshot after = mgr.capture();

        assertTrue(after.attractedRings()[0].active());
        assertEquals(3,      after.attractedRings()[0].sourceIndex());
        assertEquals(0x200,  after.attractedRings()[0].x());
        assertEquals(0x180,  after.attractedRings()[0].y());
        assertEquals(0x100,  after.attractedRings()[0].xVel());
        assertEquals(-0x50,  after.attractedRings()[0].yVel());
        assertEquals(7,      after.attractedRings()[0].slotIndex());
    }

    @Test
    void captureOmitsInactiveAttractedRingSlots() {
        RingManager mgr = buildManager(0);

        RingSnapshot snapshot = mgr.capture();

        assertEquals(0, snapshot.attractedRings().length);
    }

    @Test
    void restoreSparseAttractedRingsClearsPreviousActiveSlots() {
        RingManager mgr = buildManager(0);
        RingSnapshot base = mgr.capture();

        RingSnapshot withActiveAttractedRing = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 3, 0x200, 0x180, 0x80, 0x40, 0x100, -0x50)
                });

        mgr.restore(withActiveAttractedRing);

        RingSnapshot withoutAttractedRings = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[0]);

        mgr.restore(withoutAttractedRings);
        RingSnapshot after = mgr.capture();

        assertEquals(0, after.attractedRings().length);
    }
}
