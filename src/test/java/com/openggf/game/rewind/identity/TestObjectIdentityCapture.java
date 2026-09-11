package com.openggf.game.rewind.identity;

import com.openggf.game.GameId;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards that every captured object gets a stable, distinct {@link ObjectRefId} after
 * Task 2 wires {@code ObjectManager.rewindCaptureContext()} to register live objects
 * in the {@link RewindIdentityTable}.
 *
 * <h2>What this tests</h2>
 * <ol>
 *   <li>Two dynamic objects spawned in the same harness receive DIFFERENT ids (distinct).</li>
 *   <li>The id is non-null (the object IS registered).</li>
 *   <li>Capturing again returns the SAME id for the same object (stable across captures).</li>
 * </ol>
 *
 * <p>This is a TDD Red→Green test: the test is written FIRST against the desired API and fails
 * until the production registration is wired up.
 */
class TestObjectIdentityCapture {

    /**
     * Two dynamic objects spawned into the same harness must each receive a non-null,
     * mutually-distinct {@link ObjectRefId}; the id must remain stable across two
     * separate captures of the same live scene.
     */
    @Test
    void everyCapturedObjectGetsAStableId() {
        RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S3K);
        ObjectSpawn spawnA = new ObjectSpawn(0x100, 0x100, 1, 0, 0, false, 0, 0);
        ObjectSpawn spawnB = new ObjectSpawn(0x200, 0x100, 1, 0, 0, false, 0, 1);
        var a = h.spawnDynamic(spawnA);
        var b = h.spawnDynamic(spawnB);

        RewindCaptureContext ctx1 = h.captureContext();
        RewindIdentityTable table1 = ctx1.requireIdentityTable();

        ObjectRefId idA = table1.idFor(a);
        ObjectRefId idB = table1.idFor(b);

        assertNotNull(idA, "object A must be registered in the identity table");
        assertNotNull(idB, "object B must be registered in the identity table");
        assertNotEquals(idA, idB, "two distinct objects must receive distinct ids");

        // Re-capture: the same live objects must mint the same ids
        RewindCaptureContext ctx2 = h.captureContext();
        RewindIdentityTable table2 = ctx2.requireIdentityTable();

        assertEquals(idA, table2.idFor(a), "id for object A must be stable across captures");
        assertEquals(idB, table2.idFor(b), "id for object B must be stable across captures");
    }

    /**
     * The harder, spec-critical case: an object's {@link ObjectRefId} must SURVIVE a full
     * capture→restore round-trip. Restore clears {@code rewindObjectIds} then re-populates it
     * from the captured {@code entry.objectId()} — so a placed object reconstructed during
     * restore must resolve to the SAME id it had before, not a freshly-minted one.
     *
     * <p>Uses a placed boss (DEZ Death Egg Robot) because placed/active objects are
     * reconstructed from their spawn during restore (the path that consumes
     * {@code PerSlotEntry.objectId()}); dynamic stub objects without a codec are dropped.
     */
    @Test
    void objectRefIdSurvivesCaptureRestoreRoundTrip() {
        RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S2);
        h.spawnPlacedAndStep(Sonic2ObjectIds.DEATH_EGG_ROBOT, 0);

        // Capture the boss's id BEFORE the round-trip.
        ObjectInstance bossBefore = firstNonChildActive(h);
        assertNotNull(bossBefore, "DEZ boss must be a live active object before round-trip");
        RewindIdentityTable tableBefore = h.captureContext().requireIdentityTable();
        ObjectRefId idBefore = tableBefore.idFor(bossBefore);
        assertNotNull(idBefore, "boss must have a registered id before round-trip");

        // Full capture→restore. The boss is reconstructed from its spawn; its captured
        // objectId is restored into rewindObjectIds.
        h.roundTrip();

        // After restore, the boss (possibly a fresh instance) must resolve to the SAME id.
        ObjectInstance bossAfter = firstNonChildActive(h);
        assertNotNull(bossAfter, "DEZ boss must survive the round-trip as a live active object");
        RewindIdentityTable tableAfter = h.captureContext().requireIdentityTable();
        ObjectRefId idAfter = tableAfter.idFor(bossAfter);
        assertNotNull(idAfter, "boss must still have a registered id after round-trip");
        assertEquals(idBefore, idAfter,
                "an object's ObjectRefId must survive a capture→restore round-trip (restored, not re-minted)");

        // And the restored id must resolve back to the live restored instance.
        assertEquals(bossAfter, tableAfter.resolve(idAfter),
                "the restored id must resolve to the live post-restore instance");
    }

    /**
     * Returns the first live active object whose class name is the DEZ boss itself
     * (not one of its construction-spawned children), or {@code null} if none.
     */
    private static ObjectInstance firstNonChildActive(RewindRoundTripHarness h) {
        for (ObjectInstance o : h.objectManager().getActiveObjects()) {
            if (o.isDestroyed()) {
                continue;
            }
            String name = o.getClass().getName();
            if (name.endsWith("Sonic2DeathEggRobotInstance")) {
                return o;
            }
        }
        return null;
    }
}
