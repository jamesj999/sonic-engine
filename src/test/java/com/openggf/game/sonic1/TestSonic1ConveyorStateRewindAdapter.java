package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Sonic1ConveyorState} (ROM: {@code f_conveyrev} / {@code v_obj63}) is a
 * per-module static manager consumed across frames by
 * {@code Sonic1LZConveyorObjectInstance} but, before this adapter, was never
 * registered with the rewind registry -- the same "trigger platform" bug class
 * documented for {@link com.openggf.game.sonic2.ButtonVineTriggerStaticAdapter}
 * and {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}.
 *
 * <p>Without this coverage, a rewind seek to before an LZ conveyor spawner's
 * first visit leaves {@code v_obj63}'s dedup bit set. A rewind-recreated
 * spawner instance re-checks {@link Sonic1ConveyorState#testAndSetSpawned}
 * in its own update and, seeing the stale "already spawned" bit, self-deletes
 * via {@code ObjectLifetimeOps.deleteNoRespawn} without ever recreating the
 * child platforms -- the belt permanently disappears after a rewind past its
 * spawn point.
 */
class TestSonic1ConveyorStateRewindAdapter {

    private Sonic1ConveyorState state;
    private Sonic1ConveyorStateRewindAdapter adapter;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        state = GameServices.module().getGameService(Sonic1ConveyorState.class);
        state.reset();
        adapter = new Sonic1ConveyorStateRewindAdapter();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void keyIsStable() {
        assertEquals("s1-conveyor-state", adapter.key());
    }

    @Test
    void snapshotRoundTripRestoresPreSpawnState() {
        Sonic1ConveyorState.Snapshot clean = adapter.capture();

        state.testAndSetSpawned(2);
        state.setReversed(true);
        assertTrue(state.testAndSetSpawned(2), "sanity: slot 2 should read back as spawned");

        adapter.restore(clean);

        assertFalse(state.testAndSetSpawned(2),
                "Restoring the pre-spawn snapshot must clear the v_obj63 dedup bit");
        state.clearSpawned(2);
        assertFalse(state.isReversed(),
                "Restoring the pre-spawn snapshot must clear the f_conveyrev flag");
    }

    /**
     * Reproduces the reported bug: a rewind past the spawner's first visit
     * must let the spawner spawn its children again, not treat the belt as
     * already-spawned forever.
     */
    @Test
    void rewindRestoreOfSpawnedArrayLetsSpawnerRecreateChildren() {
        // Capture the pre-spawn state (equivalent to a rewind checkpoint taken
        // before the player reaches the LZ conveyor spawner).
        Sonic1ConveyorState.Snapshot beforeSpawn = adapter.capture();

        // Player reaches the spawner; it fires once and marks its slot spawned.
        boolean alreadySpawnedOnFirstVisit = state.testAndSetSpawned(3);
        assertFalse(alreadySpawnedOnFirstVisit, "First visit must not read as already-spawned");

        // Rewind: restore the pre-spawn snapshot (as a backward seek would).
        adapter.restore(beforeSpawn);

        // A rewind-recreated spawner instance re-checks testAndSetSpawned in its
        // own update(); without adapter coverage this would still report
        // "already spawned" and self-delete without recreating the platforms.
        assertFalse(state.testAndSetSpawned(3),
                "Recreated spawner must NOT see a stale 'already spawned' bit after rewind restore");
    }
}
