package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Gumball Machine Y-drift and slot-tracking logic.
 * <p>
 * ROM references (sonic3k.asm):
 * <ul>
 *   <li>Obj_GumballMachine init (line 127399) â€” saves Y, fills slot RAM with 0xFF</li>
 *   <li>sub_6126C (line 127949) â€” recount empty-slot prefix, apply drift</li>
 *   <li>GumballBumper hit (lines 127692-127698) â€” clear slot byte, signal recount</li>
 * </ul>
 * <p>
 * No ROM or OpenGL required â€” pure state-machine tests.
 */
public class TestGumballMachineDrift {

    // The machine internally applies -0x100 offset to spawn.y().
    private static final int SPAWN_Y = 0x200;
    private static final int MACHINE_Y_OFFSET = -0x100;
    private static final int EXPECTED_SAVED_Y = SPAWN_Y + MACHINE_Y_OFFSET; // 0x100
    private static final int DRIFT_PER_EMPTY_SLOT = 0x20;
    private static final int DRIFT_STEP_PER_FRAME = 4;

    private GumballMachineObjectInstance machine;

    @BeforeEach
    public void setUp() {
        ObjectSpawn spawn = new ObjectSpawn(0x100, SPAWN_Y, 0x86, 0, 0, false, 0);
        machine = new GumballMachineObjectInstance(spawn);
        // Drive drift init without routing through update() (which would require
        // injected ObjectServices for child spawning / audio).
        machine.initDrift();
    }

    @Test
    public void initialCurrentY_equalsSavedY_noDrift() {
        assertEquals(EXPECTED_SAVED_Y, machine.getSavedY(), "savedY should be spawn.y() + MACHINE_Y_OFFSET");
        assertEquals(EXPECTED_SAVED_Y, machine.getCurrentY(), "currentY should start equal to savedY");
        assertEquals(EXPECTED_SAVED_Y, machine.getTargetY(), "targetY should start equal to savedY");
    }

    @Test
    public void singleWordPairCleared_driftsBy0x20() {
        // ROM: slot RAM is 14 words; each word consumes 2 bumper subtype bytes.
        // Clearing subtypes 0 and 1 zeroes word 0 â†’ empty-slot prefix is 1 word.
        machine.onBumperHit(0);
        machine.onBumperHit(1);

        // Recount happens inside applyDrift().
        machine.applyDrift();
        assertEquals(EXPECTED_SAVED_Y + DRIFT_PER_EMPTY_SLOT, machine.getTargetY(), "target should drift by 0x20 for one cleared word");

        // currentY should step +4 toward target each frame.
        assertEquals(EXPECTED_SAVED_Y + DRIFT_STEP_PER_FRAME, machine.getCurrentY());

        // Step enough frames to close the gap.
        for (int i = 0; i < 7; i++) {
            machine.applyDrift();
        }
        assertEquals(EXPECTED_SAVED_Y + DRIFT_PER_EMPTY_SLOT, machine.getCurrentY(), "currentY should reach targetY after 0x20/4 = 8 frames");

        // Further frames must not overshoot.
        machine.applyDrift();
        assertEquals(EXPECTED_SAVED_Y + DRIFT_PER_EMPTY_SLOT, machine.getCurrentY(), "currentY must clamp at targetY (no overshoot)");
    }

    @Test
    public void allWordPairsCleared_driftsBy0x1C0_andStops() {
        // Clear every slot byte (0..27) â€” all 14 word pairs empty.
        for (int i = 0; i < 28; i++) {
            machine.onBumperHit(i);
        }

        // Recount is single-shot per frame; subsequent frames just tick currentY.
        machine.applyDrift();
        int expectedTotalDrift = 14 * DRIFT_PER_EMPTY_SLOT; // 0x1C0
        assertEquals(EXPECTED_SAVED_Y + expectedTotalDrift, machine.getTargetY(), "target should drift by 0x1C0 for 14 cleared words");

        // Advance enough frames to reach target: 0x1C0 / 4 = 112 frames total,
        // and we've already consumed one frame in the applyDrift() above.
        int framesNeeded = expectedTotalDrift / DRIFT_STEP_PER_FRAME;
        for (int i = 1; i < framesNeeded; i++) {
            machine.applyDrift();
        }
        assertEquals(EXPECTED_SAVED_Y + expectedTotalDrift, machine.getCurrentY(), "currentY should reach savedY + 0x1C0");

        // Extra frames â€” drift must stop at target.
        for (int i = 0; i < 10; i++) {
            machine.applyDrift();
            assertEquals(EXPECTED_SAVED_Y + expectedTotalDrift, machine.getCurrentY(), "currentY must not overshoot targetY");
        }
    }

    @Test
    public void onBumperHit_outOfRange_isSafe() {
        // Subtype 28 is out of range (slot RAM is 0..27).
        machine.onBumperHit(28);
        machine.onBumperHit(-1);
        machine.onBumperHit(100);
        machine.onBumperHit(Integer.MIN_VALUE);
        machine.onBumperHit(Integer.MAX_VALUE);

        // No slots should have been cleared, so no drift should occur.
        machine.applyDrift();
        assertEquals(EXPECTED_SAVED_Y, machine.getSavedY(), "out-of-range subtype must not affect savedY");
        assertEquals(EXPECTED_SAVED_Y, machine.getTargetY(), "out-of-range subtype must not shift targetY");
        assertEquals(EXPECTED_SAVED_Y, machine.getCurrentY(), "out-of-range subtype must not drift currentY");
    }

    @Test
    public void occupiedPrefix_blocksDrift() {
        // Clear words 1..13 but leave word 0 occupied (subtypes 0+1 still 0xFF).
        // ROM semantics: recount stops at the first non-zero word, so the prefix
        // is 0 even though 13 later words are empty.
        for (int i = 2; i < 28; i++) {
            machine.onBumperHit(i);
        }

        machine.applyDrift();
        assertEquals(EXPECTED_SAVED_Y, machine.getTargetY(), "targetY must stay at savedY when word 0 is occupied");
        assertEquals(EXPECTED_SAVED_Y, machine.getCurrentY(), "currentY must not drift when word 0 is occupied");

        // Even after many frames: no drift.
        for (int i = 0; i < 100; i++) {
            machine.applyDrift();
        }
        assertEquals(EXPECTED_SAVED_Y, machine.getCurrentY(), "currentY must remain at savedY indefinitely");

        // Now clear the blocking word â€” prefix unblocks, full drift applies.
        machine.onBumperHit(0);
        machine.onBumperHit(1);
        machine.applyDrift();
        int expectedTotalDrift = 14 * DRIFT_PER_EMPTY_SLOT;
        assertEquals(EXPECTED_SAVED_Y + expectedTotalDrift, machine.getTargetY(), "after clearing blocker, all 14 words count toward prefix");
        assertTrue(machine.getCurrentY() > EXPECTED_SAVED_Y, "currentY should now be drifting");
    }
}


