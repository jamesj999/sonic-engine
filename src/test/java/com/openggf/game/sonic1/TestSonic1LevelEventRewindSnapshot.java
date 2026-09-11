package com.openggf.game.sonic1;

import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the {@link Sonic1LevelEventManager} extra-state snapshot (C.2).
 *
 * <p>Verifies that S1-specific extra state (per-zone handler eventRoutines and
 * transition guard flags) is preserved by captureExtra/restoreExtra.
 */
class TestSonic1LevelEventRewindSnapshot {

    @Test
    void keyIsLevelEvent() {
        assertEquals("level-event", new Sonic1LevelEventManager().key());
    }

    @Test
    void roundTripGhzEventRoutine() {
        Sonic1LevelEventManager mgr = new Sonic1LevelEventManager();
        mgr.initLevel(0 /* ZONE_GHZ */, 2);
        // Advance eventRoutine on the GHZ handler via initLevel (all start at 0)
        // We'll set it through the public setEventRoutine on the active handler
        mgr.setEventRoutine(4); // 4 = DLE_GHZ3end

        LevelEventSnapshot snap = mgr.capture();

        // Mutate
        mgr.initLevel(0, 2);
        mgr.setEventRoutine(0);

        // Restore
        mgr.restore(snap);

        assertEquals(4, mgr.getEventRoutine(), "GHZ eventRoutine should be restored");
    }

    @Test
    void roundTripSbz3TransitionFlag() throws Exception {
        Sonic1LevelEventManager mgr = new Sonic1LevelEventManager();
        mgr.initLevel(5 /* ZONE_SBZ */, 1);

        java.lang.reflect.Field flag =
                Sonic1LevelEventManager.class.getDeclaredField("sbz3TransitionRequested");
        flag.setAccessible(true);

        // Set the transition flag true and capture that state.
        flag.setBoolean(mgr, true);
        LevelEventSnapshot snapTrue = mgr.capture();
        assertNotNull(snapTrue.extra());

        // Mutate: clear the flag back to false.
        flag.setBoolean(mgr, false);
        assertFalse(flag.getBoolean(mgr), "precondition: flag cleared before restore");

        // Restore the true-snapshot and confirm the flag is brought back to true.
        mgr.restore(snapTrue);
        assertTrue(flag.getBoolean(mgr),
                "sbz3TransitionRequested should be restored to true from the captured snapshot");
    }

    @Test
    void extraBytesNotNullForS1Manager() {
        Sonic1LevelEventManager mgr = new Sonic1LevelEventManager();
        mgr.initLevel(0, 0);
        LevelEventSnapshot snap = mgr.capture();
        assertNotNull(snap.extra(), "S1 manager must produce non-null extra bytes");
        assertTrue(snap.extra().length > 0);
    }

    @Test
    void roundTripDeferredNativePlcWork() throws Exception {
        Sonic1LevelEventManager mgr = new Sonic1LevelEventManager();
        mgr.initLevel(0, 2);
        Object ghz = field(Sonic1LevelEventManager.class, "ghzEvents").get(mgr);
        var pending = ghz.getClass().getSuperclass().getDeclaredField("pendingPlcId");
        pending.setAccessible(true);
        pending.set(ghz, 17);

        LevelEventSnapshot snapshot = mgr.capture();
        pending.set(ghz, null);
        mgr.restore(snapshot);

        assertEquals(17, pending.get(ghz),
                "rewind must retain deferred PLC work instead of replaying the event owner");
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void restoreExtraWithNullIsNoOp() {
        Sonic1LevelEventManager mgr = new Sonic1LevelEventManager();
        mgr.initLevel(0, 2);
        mgr.setEventRoutine(4);
        // Calling restore(null-extra) should not throw or corrupt state
        assertDoesNotThrow(() -> {
            // Construct a snapshot with null extra to exercise the null-guard
            var snap = new LevelEventSnapshot(
                    mgr.getCurrentZone(), mgr.getCurrentAct(),
                    mgr.getEventRoutineFg(), mgr.getEventRoutineBg(),
                    0, 0, false, null, null, null);
            mgr.restore(snap);
        });
    }
}
