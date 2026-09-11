package com.openggf.level.rings;

import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the iteration order of the ring placement active-index store after its
 * conversion from {@code ArrayList<Integer>} to a primitive array + membership
 * BitSet. Active-ring order feeds collection and draw order (a player
 * overlapping two rings in one frame collects them in iteration order), so
 * insertion order must survive interleaved adds and order-preserving removal —
 * removals must compact, never swap-with-last.
 */
class TestRingManagerActiveIndices {

    private static final int RING_COUNT = 20;
    private static final int RING_SPACING = 32; // ring i sits at x = i * 32, up to x=608

    @BeforeEach
    void setUp() {
        // The window mode decides which indices enter and leave the active
        // store, so pin it rather than inheriting whatever module a fork-mate
        // left behind. S1 keeps rings as objects and windows them with the
        // chunk-aligned placement range this test's arithmetic assumes; S2 and
        // S3K use the ROM's raw camera ring window instead. The ordering
        // machinery under test is shared by both modes.
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_1);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    private static RingManager buildManager() {
        List<RingSpawn> spawns = new ArrayList<>();
        for (int i = 0; i < RING_COUNT; i++) {
            spawns.add(new RingSpawn(i * RING_SPACING, 256));
        }
        return new RingManager(spawns, null, null, null, null);
    }

    private static int[] activeXs(RingManager manager) {
        return manager.getActiveSpawns().stream().mapToInt(RingSpawn::x).toArray();
    }

    /** Builds a snapshot of {@code manager} with the placement window overridden. */
    private static RingSnapshot withWindow(RingManager manager, int[] activeIndices,
                                           int cursorIndex, int lastCameraX) {
        RingSnapshot base = manager.capture();
        return new RingSnapshot(
                base.collectedWords(),
                base.sparkleTimers(),
                cursorIndex,
                lastCameraX,
                activeIndices,
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                base.attractedRings());
    }

    @Test
    void initialWindowLoadActivatesRingsInAscendingInsertionOrder() {
        RingManager manager = buildManager();

        manager.update(0, null, 0);

        int[] expected = new int[RING_COUNT];
        for (int i = 0; i < RING_COUNT; i++) {
            expected[i] = i * RING_SPACING;
        }
        assertArrayEquals(expected, activeXs(manager),
                "All rings sit inside the initial window and must activate in spawn order.");
    }

    @Test
    void restoredArbitraryOrderIsPreservedExactly() {
        RingManager manager = buildManager();
        manager.update(0, null, 0);

        manager.restore(withWindow(manager, new int[] {12, 3, 15}, RING_COUNT, 1024));

        assertArrayEquals(new int[] {12 * RING_SPACING, 3 * RING_SPACING, 15 * RING_SPACING},
                activeXs(manager),
                "Active indices are insertion-ordered, not sorted; a restored order must survive as-is.");
        assertArrayEquals(new int[] {12, 3, 15}, manager.capture().activeSpawnIndices(),
                "Snapshot must round-trip the exact insertion order.");
    }

    @Test
    void interleavedAddsAndRemovesPreserveInsertionOrder() {
        RingManager manager = buildManager();
        manager.update(0, null, 0);

        // Non-monotonic active order [12, 3, 15]; cursor leaves rings 16..19
        // unspawned so the next forward scroll appends them.
        manager.restore(withWindow(manager, new int[] {12, 3, 15}, 16, 1024));

        // Forward delta 64: spawnForward appends 16..19 (window end is far right),
        // then trim drops spawns left of (1088 & 0xFF80) - 0x300 = 256, i.e. ring 3 (x=96).
        manager.update(1088, null, 1);

        assertArrayEquals(new int[] {
                        12 * RING_SPACING, 15 * RING_SPACING,
                        16 * RING_SPACING, 17 * RING_SPACING,
                        18 * RING_SPACING, 19 * RING_SPACING},
                activeXs(manager),
                "Removal must compact in place (order-preserving), and new spawns append at the end.");
    }

    @Test
    void removedIndexCanBeReactivatedAfterTrim() {
        RingManager manager = buildManager();
        manager.update(0, null, 0);

        // Trim everything left of x=256 by scrolling forward, then jump back so
        // refreshWindow rebuilds; the trimmed rings must reactivate.
        manager.restore(withWindow(manager, new int[] {3, 12}, RING_COUNT, 1024));
        manager.update(1088, null, 1);
        assertArrayEquals(new int[] {12 * RING_SPACING}, activeXs(manager));

        manager.update(0, null, 2); // large backward delta forces refreshWindow

        int[] expected = new int[RING_COUNT];
        for (int i = 0; i < RING_COUNT; i++) {
            expected[i] = i * RING_SPACING;
        }
        assertArrayEquals(expected, activeXs(manager),
                "A trimmed index must be re-addable once its membership bit is cleared.");
    }

    @Test
    void duplicateRestoredIndicesAreDeduplicatedKeepingFirstOccurrence() {
        RingManager manager = buildManager();
        manager.update(0, null, 0);

        manager.restore(withWindow(manager, new int[] {5, 5, 9, 5}, RING_COUNT, 1024));

        assertArrayEquals(new int[] {5, 9}, manager.capture().activeSpawnIndices(),
                "Membership dedupe must keep the first occurrence only.");
        assertEquals(2, manager.getActiveSpawns().size());
    }
}
