package com.openggf.game.rewind;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track H.2 — Object parent-child identity rebind test across rewind.
 *
 * <p>Verifies that the {@code ObjectManager}'s {@code reservedChildSlots}
 * mapping (parent-spawn → child slot array) round-trips correctly through a
 * rewind snapshot capture and restore cycle.
 *
 * <p><strong>Scenario:</strong>
 * <ol>
 *   <li>Boot S2 EHZ1 via BK2 trace and advance 60 frames to establish objects.</li>
 *   <li>Inject a synthetic parent-child slot registration directly via
 *       {@link ObjectManager#allocateChildSlots} using a new {@link ObjectSpawn}
 *       instance as the parent key.</li>
 *   <li>Capture a CompositeSnapshot (includes {@code childSpawns} entries).</li>
 *   <li>Advance 30 more frames to diverge state.</li>
 *   <li>Restore the snapshot.</li>
 *   <li>Capture a second snapshot and assert the {@code childSpawns} entries
 *       round-trip: same parent-spawn identity and same slot arrays.</li>
 * </ol>
 *
 * <p>The parent-spawn identity is preserved because {@code ObjectManagerSnapshot}
 * stores the actual {@code ObjectSpawn} reference (not a copy), and
 * {@code reservedChildSlots} is an {@link java.util.IdentityHashMap} — so the
 * correct round-trip is that the same {@code ObjectSpawn} instance reappears as a
 * key after restore.
 *
 * <p>If this test fails with a childSpawns count mismatch, it means the
 * {@code ObjectManager.RewindSnapshottable.restore} path does not correctly
 * restore the {@code reservedChildSlots} map.
 *
 * <p><strong>v1 limitation:</strong> The slot values inside each child-slot
 * array may be {@code -1} for allocations that were consumed and freed during
 * the ADVANCE_AFTER_CAPTURE frames. The test only asserts structural presence
 * (parent-spawn reference, array length) rather than exact slot values for
 * dynamic objects, since live slot numbers can legitimately change during
 * normal gameplay.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestObjectIdentityRebindingAcrossRewind {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");

    private static final int ADVANCE_BEFORE_CAPTURE = 60;
    private static final int ADVANCE_AFTER_CAPTURE  = 30;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void reservedChildSlotsMappingRoundTripsAcrossSnapshotRestore() throws Exception {
        // Skip if trace is absent
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2Path = findBk2(TRACE_DIR);
        Assumptions.assumeTrue(bk2Path != null,
                "No .bk2 file found in " + TRACE_DIR);

        // 1. Boot S2 EHZ1
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withZoneAndAct(0, 0)
                .build();

        // 2. Advance to produce non-trivial object state
        for (int i = 0; i < ADVANCE_BEFORE_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 3. Inject a synthetic parent-child slot pair so we have a known entry
        //    to assert on. Using a dynamically constructed ObjectSpawn (layoutIndex=-1)
        //    to avoid colliding with live ROM-loaded object keys.
        ObjectManager om = GameServices.level().getObjectManager();
        assertNotNull(om, "ObjectManager must be available after level load");

        // A fresh ObjectSpawn with layoutIndex=-1 is the canonical way to represent
        // a dynamically spawned parent object (boss child, projectile parent, etc.)
        ObjectSpawn syntheticParent = new ObjectSpawn(
                0x100, 0x200, 0x01, 0x00, 0, false, 0x0200, -1);

        // Allocate 2 child slots for the synthetic parent
        int[] allocatedSlots = om.allocateChildSlots(syntheticParent, 2);
        assertNotNull(allocatedSlots,
                "allocateChildSlots must return a non-null array");
        assertEquals(2, allocatedSlots.length,
                "allocateChildSlots must return exactly 2 slots");

        // 4. Capture snapshot A — must include our synthetic parent entry
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available");
        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null");

        CompositeSnapshot snapA = registry.capture();
        assertNotNull(snapA, "capture() must return a non-null CompositeSnapshot");

        ObjectManagerSnapshot omSnapA = (ObjectManagerSnapshot) snapA.get("object-manager");
        assertNotNull(omSnapA, "Snapshot must contain 'object-manager' key");

        // Verify the synthetic parent appears in the captured childSpawns
        ObjectManagerSnapshot.ChildSpawnEntry syntheticEntry = findChildSpawnEntry(
                omSnapA.childSpawns(), syntheticParent);
        assertNotNull(syntheticEntry,
                "Captured snapshot must contain the synthetic parent spawn in childSpawns. " +
                "If null, allocateChildSlots did not register into the OM's reservedChildSlots map, " +
                "or the snapshot capture missed the entry.");
        assertArrayEquals(allocatedSlots, syntheticEntry.reservedSlots(),
                "Captured child slot array must match the allocated slots exactly");

        // 5. Diverge: advance M more frames
        for (int i = 0; i < ADVANCE_AFTER_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 6. Restore snapshot A
        registry.restore(snapA);

        // 7. Capture snapshot B after restore
        CompositeSnapshot snapB = registry.capture();
        ObjectManagerSnapshot omSnapB = (ObjectManagerSnapshot) snapB.get("object-manager");
        assertNotNull(omSnapB, "Post-restore snapshot must contain 'object-manager' key");

        // 8. Assert: the synthetic parent entry must be present again with the
        //    same parent-spawn reference (identity) and the same slot array.
        ObjectManagerSnapshot.ChildSpawnEntry restoredEntry = findChildSpawnEntry(
                omSnapB.childSpawns(), syntheticParent);
        assertNotNull(restoredEntry,
                "After restore, the synthetic parent spawn must still be present in childSpawns. " +
                "If null, ObjectManager.RewindSnapshottable.restore does not correctly " +
                "restore the reservedChildSlots map.");

        // The restored entry must reference the same parent spawn instance (identity).
        // ObjectManagerSnapshot stores references, not copies.
        assertSame(syntheticParent, restoredEntry.parentSpawn(),
                "Restored childSpawn must reference the same ObjectSpawn instance as " +
                "was registered before capture (IdentityHashMap key must be preserved).");

        // The restored slot array must match the original allocation.
        assertArrayEquals(allocatedSlots, restoredEntry.reservedSlots(),
                "Restored child slot array must match the original allocated slots. " +
                "Divergence means restore did not copy the slot array back correctly.");
    }

    /**
     * Finds the {@link ObjectManagerSnapshot.ChildSpawnEntry} whose
     * {@code parentSpawn} is the same identity as {@code target}, or {@code null}
     * if not found.
     */
    private static ObjectManagerSnapshot.ChildSpawnEntry findChildSpawnEntry(
            List<ObjectManagerSnapshot.ChildSpawnEntry> entries,
            ObjectSpawn target) {
        for (ObjectManagerSnapshot.ChildSpawnEntry e : entries) {
            if (e.parentSpawn() == target) {
                return e;
            }
        }
        return null;
    }

    private static Path findBk2(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
