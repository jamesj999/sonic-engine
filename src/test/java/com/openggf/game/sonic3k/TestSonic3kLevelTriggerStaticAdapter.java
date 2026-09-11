package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MGZTriggerPlatformObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kLevelTriggerStaticAdapter {

    private Sonic3kLevelTriggerStaticAdapter adapter;

    @BeforeEach
    void setUp() {
        Sonic3kLevelTriggerManager.reset();
        adapter = new Sonic3kLevelTriggerStaticAdapter();
    }

    @Test
    void keyIsStable() {
        assertEquals("s3k-level-trigger-array", adapter.key());
    }

    @Test
    void snapshotRoundTripRestoresClearedTrigger() {
        Sonic3kLevelTriggerManager.reset();
        Sonic3kLevelTriggerManager.Snapshot clean = adapter.capture();

        Sonic3kLevelTriggerManager.setAll(3);
        assertTrue(Sonic3kLevelTriggerManager.testAny(3));

        adapter.restore(clean);
        assertFalse(Sonic3kLevelTriggerManager.testAny(3),
                "Restoring a pre-trigger snapshot must clear the trigger bit");
    }

    /**
     * Reproduces the reported bug: a spindash arms the MGZ dash trigger,
     * which sets the shared Level_trigger_array bit consumed by
     * MGZTriggerPlatformObjectInstance. If the platform object is unloaded
     * (off-screen) and rewind-recreated from its ObjectSpawn -- the
     * SpawnRewindRecreatable path -- its constructor re-reads
     * Sonic3kLevelTriggerManager.testAny() and fast-forwards to the
     * post-trigger position. Without rewind coverage on the trigger array,
     * rewinding to before the spindash still leaves the array set, so the
     * recreated column snaps back to "moved" instead of reversing.
     */
    @Test
    void rewindRestoreOfTriggerArrayPreventsRecreatedColumnFromStayingTriggered() {
        var spawn = new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_TRIGGER_PLATFORM, 0x10, 0x00, false, 0);

        // Capture the untriggered state (equivalent to a rewind checkpoint
        // taken before the player spindashes the dash trigger).
        Sonic3kLevelTriggerManager.Snapshot beforeSpindash = adapter.capture();

        // Player spindashes; the dash trigger fires and the column moves.
        Sonic3kLevelTriggerManager.setAll(0);
        MGZTriggerPlatformObjectInstance triggered = new MGZTriggerPlatformObjectInstance(spawn);
        for (int frame = 0; frame < 64; frame++) {
            triggered.update(frame, null);
        }
        assertEquals(0x0600 + 0x40, triggered.getY(), "Column should have moved after triggering");

        // Rewind: restore the pre-spindash trigger-array snapshot, then
        // recreate the object from its spawn (as the rewind-recreate path
        // does for an unloaded/off-screen dynamic object).
        adapter.restore(beforeSpindash);
        MGZTriggerPlatformObjectInstance recreated = new MGZTriggerPlatformObjectInstance(spawn);

        assertEquals(0x0600, recreated.getY(),
                "Recreated column must NOT be fast-forwarded once the trigger array is properly rewound");
    }
}
