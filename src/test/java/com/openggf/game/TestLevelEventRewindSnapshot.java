package com.openggf.game;

import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the {@link AbstractLevelEventManager} rewind snapshot.
 *
 * <p>Uses a minimal concrete subclass that exposes no extra state, verifying
 * that the base-class fields survive capture → mutate → restore.
 */
class TestLevelEventRewindSnapshot {

    /** Minimal concrete subclass with no extra state. */
    private static class StubLevelEventManager extends AbstractLevelEventManager {
        @Override protected int getRoutineStride()      { return 2; }
        @Override protected int getEventDataFgSize()    { return 6; }
        @Override protected int getEventDataBgSize()    { return 0; }
        @Override protected void onInitLevel(int z, int a) {}
        @Override protected void onUpdate() {}
        @Override public PlayerCharacter getPlayerCharacter() { return PlayerCharacter.SONIC_AND_TAILS; }
    }

    @Test
    void keyIsLevelEvent() {
        assertEquals("level-event", new StubLevelEventManager().key());
    }

    @Test
    void roundTripBaseFields() {
        StubLevelEventManager mgr = new StubLevelEventManager();
        mgr.initLevel(3, 1);
        mgr.update(); // increments frameCounter to 1
        mgr.setFgRoutine(4);
        mgr.setBgRoutine(8);
        mgr.startTimer(60);
        mgr.setBossActive(true);
        // Write some eventDataFg entries directly via reflection-free path
        mgr.eventDataFg[2] = 7;

        LevelEventSnapshot snap = mgr.capture();

        // Mutate
        mgr.initLevel(0, 0);

        // Restore
        mgr.restore(snap);

        assertEquals(3,    mgr.getCurrentZone());
        assertEquals(1,    mgr.getCurrentAct());
        assertEquals(4,    mgr.getEventRoutineFg());
        assertEquals(8,    mgr.getEventRoutineBg());
        assertTrue(mgr.isBossActive());
        assertEquals(7,    mgr.eventDataFg[2]);
    }

    @Test
    void captureIsImmutableAfterMutation() {
        StubLevelEventManager mgr = new StubLevelEventManager();
        mgr.initLevel(1, 0);
        mgr.setFgRoutine(2);

        LevelEventSnapshot snap = mgr.capture();
        // Mutate the manager
        mgr.setFgRoutine(99);

        // Snapshot should still hold the original value
        assertEquals(2, snap.eventRoutineFg());
    }

    @Test
    void nullExtraRoundTrip() {
        StubLevelEventManager mgr = new StubLevelEventManager();
        mgr.initLevel(0, 0);
        LevelEventSnapshot snap = mgr.capture();
        assertNull(snap.extra(), "Stub has no extra state");
        // Restore with null extra should not throw
        assertDoesNotThrow(() -> mgr.restore(snap));
    }
}
