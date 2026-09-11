package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Tails follower AI for the S3K Blue Ball special stage.
 * <p>
 * Tails follows Player 1 with a 4-entry delay buffer. When Player 2
 * is not providing input (idle for 600 frames), the AI takes over
 * and mimics P1's actions from the delay buffer.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm sub_937C (line 11711)
 */
public class Sonic3kSpecialStageTailsAI {

    /** Position delay buffer (circular, 256 entries of 4 bytes each). */
    private final int[] posTableInput = new int[256];   // P1 held buttons
    private final int[] posTableJump = new int[256];    // P1 jumping state
    private int posTableIndex;

    /** Tails CPU idle timer. */
    private int cpuIdleTimer;

    /** Last P2 input for idle detection. */
    private int lastP2Input;

    public void initialize() {
        java.util.Arrays.fill(posTableInput, 0);
        java.util.Arrays.fill(posTableJump, 0);
        posTableIndex = 0;
        cpuIdleTimer = 0;
        lastP2Input = 0;
    }

    /**
     * Record P1 state and determine P2 (Tails) input.
     * ROM: sub_937C (sonic3k.asm:11711)
     *
     * @param p1HeldButtons P1 held buttons this frame
     * @param p1Jumping P1 jumping state
     * @param p2HeldButtons P2 held buttons (raw controller input)
     * @return effective input for Tails (buttons bitmask)
     */
    public int update(int p1HeldButtons, int p1Jumping, int p2HeldButtons) {
        // Record P1 state to the position table
        posTableInput[posTableIndex & 0xFF] = p1HeldButtons;
        posTableJump[posTableIndex & 0xFF] = p1Jumping;
        posTableIndex = (posTableIndex + 1) & 0xFF;

        // Check if P2 is actively pressing buttons
        int p2Movement = p2HeldButtons & 0x7F; // Up/Down/Left/Right/A/B/C
        if (p2Movement != 0) {
            cpuIdleTimer = TAILS_CPU_IDLE_TIMEOUT;
        }

        // If P2 is active (timer > 0), use P2 controller directly
        if (cpuIdleTimer > 0) {
            cpuIdleTimer--;
            return p2HeldButtons;
        }

        // AI mode: replay P1 jump state from 4 entries ago.
        // Detect when the delayed P1 state transitions to jumping (0x80 or 0x81)
        // from not-jumping (0 or 1). This triggers Tails to jump 4 frames after Sonic.
        int delayedIndex = (posTableIndex - TAILS_POS_DELAY) & 0xFF;
        int prevIndex = (delayedIndex - 1) & 0xFF;

        int delayedJump = posTableJump[delayedIndex];
        int prevJump = posTableJump[prevIndex];

        int result = 0;
        // If P1 just started jumping at the delayed point (was grounded, now airborne)
        if ((delayedJump & 0x80) != 0 && (prevJump & 0x80) == 0) {
            result = 0x70; // A+B+C pressed — trigger jump
        }

        return result;
    }

    /**
     * Check if Tails AI wants to jump (based on delayed P1 state).
     * ROM: sub_937C reads Pos_table and returns jump button if P1 was jumping.
     *
     * @return true if Tails should auto-jump this frame
     */
    /**
     * Check if the delayed P1 state shows a spring jump transition.
     */
    public boolean shouldAutoSpringJump() {
        int delayedIndex = (posTableIndex - TAILS_POS_DELAY) & 0xFF;
        int prevIndex = (delayedIndex - 1) & 0xFF;

        int currentJump = posTableJump[delayedIndex];
        int prevJump = posTableJump[prevIndex];

        // ROM: cmpi.b #-$7F,d2 (0x81 = spring jump) / tst.b d1
        return currentJump == 0x81 && prevJump >= 0;
    }

    Sonic3kSpecialStageSnapshot.TailsAiSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.TailsAiSnapshot(
                posTableInput, posTableJump, posTableIndex, cpuIdleTimer, lastP2Input);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.TailsAiSnapshot snapshot) {
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.posTableInput(), posTableInput);
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.posTableJump(), posTableJump);
        posTableIndex = snapshot.posTableIndex();
        cpuIdleTimer = snapshot.cpuIdleTimer();
        lastP2Input = snapshot.lastP2Input();
    }
}
